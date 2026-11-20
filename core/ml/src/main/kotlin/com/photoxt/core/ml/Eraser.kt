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
import java.nio.FloatBuffer
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
 * each pinned to one file: an immutable commit, its size and SHA-256. Robin asked for LaMa, MI-GAN
 * and DeepFill v2 with a switch (2026-11-19). All three are trained on Places2 (scenes): the LaMa
 * dropped on 2026-11-18, Qualcomm AI Hub's LaMa-Dilated, was a face model.
 */
enum class EraserModel(val url: String, val bytes: Long, val sha256: String) {
    /** Samsung Research's big-lama (Apache-2.0), its FFTs as matrix multiplies; image and mask. */
    LAMA(
        "https://huggingface.co/Dineshsoudagar/big-lama-512-LiteRT/resolve/" +
            "32e8ffaa7149aa2d679e0f5bb821b41ece9b5a04/lama_512_fp16.tflite",
        104_462_512L,
        "522092191a7edb8abbd80589ce74385d579b0c97d6978da7a4f257da39e6d885"
    ),

    /** Picsart's MI-GAN (MIT), from Google's LiteRT community page. */
    MIGAN(
        "https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/" +
            "179a35d8e8a518f6253f9c42fc9b412c6438cf7c/migan_fp16.tflite",
        16_312_640L,
        "ef53f8dca69e5ce29629441128322c4d9a0a527b2a043703e0ab497f2463c25f"
    ),

    /**
     * DeepFill v2 (CC BY-NC 4.0: non-commercial only), built for LiteRT in this repository's
     * `models/`, with LaMa's image-and-mask interface.
     */
    DEEPFILL(
        "https://raw.githubusercontent.com/RobinMorajker/PhotosXT/" +
            "89c3daff9a8e656abc4c86550025997b3812a642/models/deepfill_v2_512_fp16.tflite",
        8_698_084L,
        "5561b5aede282dc06762d4da569c98587c82b5622e8b94612ca28d855bedef28"
    );

    companion object {
        /** Every model takes and gives 512×512 pictures. */
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

    /** Named by its checksum, so a file pinned before (LaMa-Dilated's) is a stranger, pruned. */
    fun file(model: EraserModel) =
        File(dir, "${model.name.lowercase()}-${model.sha256.take(NAME_HASH)}.tflite")

    /** Whether [model] is on the phone, checked. */
    fun ready(model: EraserModel): Boolean = file(model).length() == model.bytes

    /**
     * Left once this phone's GPU couldn't build [model] (the A15's Mali can't build LaMa, and
     * trying costs 28 s), so later loads go straight to the CPU; deleting the models clears it.
     */
    fun cpuOnly(model: EraserModel) = File(dir, file(model).name + CPU_ONLY)

    /** Bytes the downloaded models take, any left by older pins included. */
    fun sizeOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every downloaded model; the eraser fetches its own again when needed. */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Files no current pin owns, such as LaMa-Dilated's 183 MB or a half-fetched old pin. */
    private fun prune() {
        val ours = EraserModel.entries.flatMap { m ->
            listOf("", ".part", CPU_ONLY).map { file(m).name + it }
        }
        dir.listFiles()?.filter { it.name !in ours }?.forEach { it.delete() }
    }

    /**
     * Fetches [model] unless it is here, resuming a part file, then checks its SHA-256;
     * [progress] hears the share done.
     */
    suspend fun download(model: EraserModel, progress: (Float) -> Unit = {}): File =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                prune()
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
        const val NAME_HASH = 12
        const val CPU_ONLY = ".cpu"
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
                val output = Planes.floats(pixels.size * Planes.RGB)
                when (model) {
                    EraserModel.MIGAN -> interpreter.run(Planes.migan(pixels, hole), output)

                    EraserModel.LAMA, EraserModel.DEEPFILL -> {
                        val (image, mask) = Planes.imageAndMask(pixels, hole)
                        // The image is the input with three channels: LaMa's comes first,
                        // DeepFill's second.
                        val imageFirst = interpreter.getInputTensor(0).shape()[1] == Planes.RGB
                        val inputs: Array<Any> =
                            if (imageFirst) arrayOf(image, mask) else arrayOf(mask, image)
                        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to output))
                    }
                }
                output.rewind()
                val out = Planes.pixels(output, pixels.size, signed = model == EraserModel.MIGAN)
                Bitmap.createBitmap(out, side, side, Bitmap.Config.ARGB_8888)
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
        val interpreter = withContext(Dispatchers.IO) {
            val runtime = InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY
            val cpuOnly = models.cpuOnly(model)
            val onGpu = if (useGpu && !cpuOnly.exists()) {
                val options = InterpreterApi.Options()
                    .setRuntime(runtime)
                    .addDelegateFactory(GpuDelegateFactory())
                runCatching { InterpreterApi.create(file, options) }
                    .onFailure {
                        PxtLog.w(TAG, "The GPU couldn't run ${model.name}; using the CPU", it)
                        cpuOnly.createNewFile()
                    }
                    .getOrNull()
            } else {
                null
            }
            val onCpu = InterpreterApi.Options().setRuntime(runtime).setNumThreads(THREADS)
            PxtLog.i(TAG, "${model.name} runs on the ${if (onGpu != null) "GPU" else "CPU"}")
            onGpu ?: InterpreterApi.create(file, onCpu)
        }
        loaded = model to interpreter
        return interpreter
    }

    private companion object {
        const val TAG = "Eraser"
        const val THREADS = 4
    }
}

/** Pixels to and from the models' NCHW float planes; hole bytes of 128 and up are filled. */
internal object Planes {
    const val RGB = 3
    private const val KEEP_CENTRE = 0.5f
    private const val MASK = 0xFF
    private const val HALF = 128
    private const val HALF_RANGE = 127.5f
    private const val FULL_RANGE = 255f
    private val SHIFTS = intArrayOf(16, 8, 0)
    private const val OPAQUE = -0x1000000

    /** MI-GAN's one input: what to keep − 0.5, then RGB in [−1, 1] times what to keep. */
    fun migan(pixels: IntArray, hole: ByteArray): FloatBuffer {
        val n = pixels.size
        val keep = FloatArray(n) { if (filled(hole, it)) 0f else 1f }
        val input = floats(n * (1 + RGB))
        for (i in 0 until n) input.put(keep[i] - KEEP_CENTRE)
        for (shift in SHIFTS) {
            for (i in 0 until n) {
                input.put((((pixels[i] shr shift) and MASK) / HALF_RANGE - 1f) * keep[i])
            }
        }
        input.rewind()
        return input
    }

    /** LaMa's and DeepFill's inputs: RGB in [0, 1] with the hole blanked, and the hole as 1. */
    fun imageAndMask(pixels: IntArray, hole: ByteArray): Pair<FloatBuffer, FloatBuffer> {
        val n = pixels.size
        val image = floats(n * RGB)
        for (shift in SHIFTS) {
            for (i in 0 until n) {
                val value = ((pixels[i] shr shift) and MASK) / FULL_RANGE
                image.put(if (filled(hole, i)) 0f else value)
            }
        }
        val mask = floats(n)
        for (i in 0 until n) mask.put(if (filled(hole, i)) 1f else 0f)
        image.rewind()
        mask.rewind()
        return image to mask
    }

    /** Red, green and blue planes as opaque pixels, from [−1, 1] when [signed], else [0, 1]. */
    fun pixels(planes: FloatBuffer, n: Int, signed: Boolean): IntArray {
        val rgb = Array(RGB) { FloatArray(n) { planes.get() } }
        fun byte(plane: Int, i: Int): Int {
            val v = rgb[plane][i]
            return (if (signed) (v + 1f) * HALF_RANGE else v * FULL_RANGE).toInt().coerceIn(0, MASK)
        }
        return IntArray(n) { i ->
            OPAQUE or (byte(0, i) shl SHIFTS[0]) or (byte(1, i) shl SHIFTS[1]) or byte(2, i)
        }
    }

    fun floats(count: Int): FloatBuffer = ByteBuffer
        .allocateDirect(count * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun filled(hole: ByteArray, i: Int) = (hole[i].toInt() and MASK) >= HALF
}
