package com.photoxt.core.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import com.photoxt.core.common.PxtLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.gpu.GpuDelegateFactory

/**
 * The object eraser's inpainting models (spec §4.10, Retouch: "LiteRT, downloaded on demand"),
 * each pinned to one file: a Hugging Face commit, its size and SHA-256. Robin chose both, with a
 * switch in Settings (2026-11-16).
 */
enum class EraserModel(val url: String, val bytes: Long, val sha256: String) {
    /** Picsart's MI-GAN, 512 px, MIT licence, from Google's LiteRT community page. */
    MIGAN(
        "https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/" +
            "179a35d8e8a518f6253f9c42fc9b412c6438cf7c/migan_fp16.tflite",
        16_312_640L,
        "ef53f8dca69e5ce29629441128322c4d9a0a527b2a043703e0ab497f2463c25f"
    ),

    /** Samsung Research's LaMa (dilated, Apache-2.0), compiled by Qualcomm AI Hub, 512 px. */
    LAMA(
        "https://huggingface.co/mlboydaisuke/LaMa-Dilated-LiteRT/resolve/" +
            "4c8d164fc5405b55fc0d45f3151a91e0db5349f8/lama_dilated.tflite",
        182_648_392L,
        "a2ac8ed174be1233e4e518c8ef57fb943ea97162696dcd2d428c056280efe583"
    );

    companion object {
        /** Both models take and give 512×512 pictures. */
        const val SIDE = 512
    }
}

/** The file arrived but isn't the one pinned: deleted, nothing run. */
class ModelChecksumException : IOException("The model file didn't match its checksum")

/** Downloads, checks and keeps the eraser models in app storage (not backed up). */
@Singleton
class EraserModels @Inject constructor(@ApplicationContext context: Context) {
    // Lazy: Hilt builds this on the main thread, where both touch the disk (StrictMode).
    private val dir by lazy { File(context.noBackupFilesDir, "models") }
    private val client by lazy { OkHttpClient() }
    private val mutex = Mutex()

    fun file(model: EraserModel) = File(dir, model.name.lowercase() + ".tflite")

    /** Whether [model] is on the phone, checked. */
    fun ready(model: EraserModel): Boolean = file(model).length() == model.bytes

    /** Bytes the downloaded models take. */
    fun sizeOnDisk(): Long = EraserModel.entries.sumOf { file(it).length() }

    fun delete(model: EraserModel) {
        file(model).delete()
        File(dir, file(model).name + ".part").delete()
    }

    /**
     * Fetches [model] unless it is here, resuming a part file, then checks its SHA-256;
     * [progress] hears the share done.
     */
    suspend fun download(model: EraserModel, progress: (Float) -> Unit = {}): File =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val target = file(model)
                if (ready(model)) return@withContext target
                dir.mkdirs()
                val part = File(dir, target.name + ".part")
                val have = if (part.isFile) part.length() else 0L
                if (have < model.bytes) fetch(model, part, have, progress)
                if (part.length() != model.bytes || sha256(part) != model.sha256) {
                    part.delete()
                    throw ModelChecksumException()
                }
                if (!part.renameTo(target)) throw IOException("Couldn't keep the model")
                PxtLog.i(TAG, "Eraser model ${model.name} ready")
                target
            }
        }

    private suspend fun fetch(
        model: EraserModel,
        part: File,
        have: Long,
        progress: (Float) -> Unit
    ) {
        val request = Request.Builder().url(model.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // A server that ignored the range sends everything again.
            val start = if (response.code == HTTP_PARTIAL) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(start)
                out.seek(start)
                append(response.body.byteStream(), out, start) {
                    progress(it.toFloat() / model.bytes)
                }
            }
        }
    }

    /**
     * [input] onto the end of [out], which holds [start] bytes; [onBytes] hears how many it
     * holds as they come. Leaving the editor stops the download; the part file resumes it.
     */
    private suspend fun append(
        input: InputStream,
        out: RandomAccessFile,
        start: Long,
        onBytes: (Long) -> Unit
    ) {
        val buffer = ByteArray(BUFFER)
        var done = start
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            onBytes(done)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Eraser"
        const val BUFFER = 1 shl 16
        const val HTTP_PARTIAL = 206
    }
}

/** Fills the masked part of a 512×512 picture (spec §4.10, object eraser). */
interface Inpainter {
    /** [picture] with [hole] (one byte a pixel, 255 = erase) filled in, by [model]. */
    suspend fun inpaint(model: EraserModel, picture: Bitmap, hole: ByteArray): Bitmap
}

/**
 * Runs the eraser models through LiteRT in Google Play services (the runtime stays out of the
 * APK), on the GPU where Play services offers it, else on the CPU.
 */
@Singleton
class LiteRtInpainter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: EraserModels
) : Inpainter {
    private val mutex = Mutex()
    private var gpu: Boolean? = null
    private var loaded: Pair<EraserModel, InterpreterApi>? = null

    override suspend fun inpaint(model: EraserModel, picture: Bitmap, hole: ByteArray): Bitmap =
        mutex.withLock {
            val side = EraserModel.SIDE
            require(picture.width == side && picture.height == side && hole.size == side * side)
            val interpreter = interpreter(model)
            withContext(Dispatchers.Default) {
                val pixels = IntArray(side * side)
                picture.getPixels(pixels, 0, side, 0, 0, side, side)
                val out = when (model) {
                    EraserModel.MIGAN -> runMigan(interpreter, pixels, hole)
                    EraserModel.LAMA -> runLama(interpreter, pixels, hole)
                }
                Bitmap.createBitmap(out, side, side, Bitmap.Config.ARGB_8888)
            }
        }

    // MI-GAN: one NCHW input, concat(keep − 0.5, rgb·keep) with rgb in [−1, 1]; out in [−1, 1].
    private fun runMigan(interpreter: InterpreterApi, pixels: IntArray, hole: ByteArray): IntArray {
        val n = pixels.size
        // Four planes: what to keep, then red, green and blue.
        val input = floats(n * (1 + RGB))
        val keep = FloatArray(n) { if ((hole[it].toInt() and MASK) >= HALF) 0f else 1f }
        for (i in 0 until n) input.put(keep[i] - KEEP_CENTRE)
        for (shift in intArrayOf(RED, GREEN, BLUE)) {
            for (i in 0 until n) {
                input.put((((pixels[i] shr shift) and MASK) / HALF_RANGE - 1f) * keep[i])
            }
        }
        input.rewind()
        val output = floats(n * RGB)
        interpreter.run(input, output)
        output.rewind()
        val planes = Array(RGB) { FloatArray(n) { output.get() } }
        fun byte(plane: Int, i: Int) =
            ((planes[plane][i] + 1f) * HALF_RANGE).toInt().coerceIn(0, MASK)
        return IntArray(n) { i ->
            OPAQUE or (byte(0, i) shl RED) or (byte(1, i) shl GREEN) or byte(2, i)
        }
    }

    // LaMa: NHWC image and mask in [0, 1], mask 1 = hole; out NHWC in [0, 1].
    private fun runLama(interpreter: InterpreterApi, pixels: IntArray, hole: ByteArray): IntArray {
        val n = pixels.size
        val image = floats(n * RGB)
        val mask = floats(n)
        for (i in 0 until n) {
            image.put(((pixels[i] shr RED) and MASK) / FULL_RANGE)
            image.put(((pixels[i] shr GREEN) and MASK) / FULL_RANGE)
            image.put((pixels[i] and MASK) / FULL_RANGE)
            mask.put(if ((hole[i].toInt() and MASK) >= HALF) 1f else 0f)
        }
        image.rewind()
        mask.rewind()
        // The image input is the one whose last dimension is its three channels.
        val imageFirst = interpreter.getInputTensor(0).shape().last() == RGB
        val inputs: Array<Any> = if (imageFirst) arrayOf(image, mask) else arrayOf(mask, image)
        val output = floats(n * RGB)
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to output))
        output.rewind()
        return IntArray(n) {
            val r = (output.get() * FULL_RANGE).toInt().coerceIn(0, MASK)
            val g = (output.get() * FULL_RANGE).toInt().coerceIn(0, MASK)
            val b = (output.get() * FULL_RANGE).toInt().coerceIn(0, MASK)
            OPAQUE or (r shl RED) or (g shl GREEN) or b
        }
    }

    private suspend fun interpreter(model: EraserModel): InterpreterApi {
        loaded?.takeIf { it.first == model }?.let { return it.second }
        loaded?.second?.close()
        loaded = null
        val file = models.download(model)
        val useGpu = gpu ?: runCatching { TfLiteGpu.isGpuDelegateAvailable(context).await() }
            .getOrDefault(false)
            .also { gpu = it }
        TfLite.initialize(
            context,
            TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(useGpu).build()
        ).await()
        val interpreter = withContext(Dispatchers.Default) {
            val runtime = InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY
            val onGpu = if (useGpu) {
                val options = InterpreterApi.Options()
                    .setRuntime(runtime)
                    .addDelegateFactory(GpuDelegateFactory())
                runCatching { InterpreterApi.create(file, options) }
                    .onFailure {
                        PxtLog.w(TAG, "The GPU couldn't run ${model.name}; using the CPU", it)
                    }
                    .getOrNull()
            } else {
                null
            }
            val onCpu = InterpreterApi.Options().setRuntime(runtime).setNumThreads(THREADS)
            onGpu ?: InterpreterApi.create(file, onCpu)
        }
        loaded = model to interpreter
        return interpreter
    }

    private fun floats(count: Int) = ByteBuffer
        .allocateDirect(count * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private companion object {
        const val TAG = "Eraser"
        const val RGB = 3
        const val KEEP_CENTRE = 0.5f
        const val MASK = 0xFF
        const val HALF = 128
        const val HALF_RANGE = 127.5f
        const val FULL_RANGE = 255f
        const val RED = 16
        const val GREEN = 8
        const val BLUE = 0
        const val OPAQUE = -0x1000000
        const val THREADS = 4
    }
}
