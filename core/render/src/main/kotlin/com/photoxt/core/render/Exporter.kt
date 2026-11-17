package com.photoxt.core.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Geometry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The chosen format can't hold the picture at this size (WebP stops at 16,383 px a side). */
class ExportTooLargeException(message: String) : IOException(message)

/** What an export wrote. */
data class ExportResult(val mime: String, val width: Int, val height: Int, val bytes: Long)

/** One export: [recipe] over the photo in [input], of type [inputMime], into [output]. */
class ExportRequest(
    val input: File,
    val inputMime: String,
    val recipe: EditRecipe,
    val options: ExportOptions,
    val output: File
)

/** Renders an edit at full size into a file (spec §4.10, Output). */
interface PhotoExporter {
    /** Writes [request]'s picture as its options say; [progress] hears the share done, 0 to 1. */
    suspend fun export(request: ExportRequest, progress: (Float) -> Unit = {}): ExportResult
}

/**
 * The full-size export (spec §4.10): the photo is rendered through the GPU in tiles of 2,048 by
 * 256 pixels, a strip of tiles at a time, and each strip goes straight into a JPEG or PNG
 * encoder, so a 200 MP photo never sits whole in memory. WebP, HEIC and AVIF need the whole
 * picture, as do size targets, which re-encode it: those assemble one bitmap, when it fits.
 */
class GlPhotoExporter(private val context: Context, private val assets: EditAssets) :
    PhotoExporter {
    override suspend fun export(request: ExportRequest, progress: (Float) -> Unit): ExportResult {
        val thread = Executors.newSingleThreadExecutor { Thread(it, "photoxt-export") }
        try {
            return withContext(thread.asCoroutineDispatcher()) {
                SourceImage(request.input).use { source -> render(request, source, progress) }
            }
        } finally {
            thread.shutdown()
        }
    }

    /** An export under way: what the strips and the writers need of it. */
    private class Job(
        val source: SourceImage,
        val recipe: EditRecipe,
        val geometry: RenderGeometry,
        val painter: OverlayPainter,
        val progress: (Float) -> Unit
    ) {
        val out: Size get() = geometry.output
    }

    private suspend fun render(
        request: ExportRequest,
        source: SourceImage,
        progress: (Float) -> Unit
    ): ExportResult {
        val recipe = request.recipe
        val options = request.options
        val format = ExportFormat.written(options.format, request.inputMime)
        // WebP, HEIC and AVIF take one whole bitmap, and a size target re-encodes one.
        val whole = format !in STREAMED || options.maxBytes != null
        val geometry = sized(source, recipe, options, format.takeIf { whole })
        val inputs = PhotoInputs.build(source, recipe, assets)
        val tables = RecipeTables.build(recipe, source.size, assets)
        val job = Job(source, recipe, geometry, OverlayPainter(recipe, assets), progress)
        request.output.parentFile?.mkdirs()
        val written = GlSession().use { gl ->
            gl.setPhoto(inputs)
            gl.setRecipe(recipe, geometry, tables)
            if (whole) assembled(gl, job, format, request) else streamed(gl, job, format, request)
        }
        if (options.keepMetadata) Metadata.copy(request.input, request.output, format, written)
        val mime = format.mime ?: JPEG_MIME
        return ExportResult(mime, written.width, written.height, request.output.length())
    }

    /**
     * The export's geometry at the size [options] ask for. A [whole] bitmap must also fit its
     * format and the memory there is: a size target lets it shrink to fit; without one the
     * export is refused, as the user asked for that size.
     */
    private fun sized(
        source: SourceImage,
        recipe: EditRecipe,
        options: ExportOptions,
        whole: ExportFormat?
    ): RenderGeometry {
        val geometry = geometryFor(source, recipe, scaleFor(source, recipe, options))
        val size = geometry.output
        val fit = if (whole == null) 1.0 else min(fitFormat(whole, size), fitMemory(size))
        return when {
            fit >= 1.0 -> geometry

            options.maxBytes != null -> geometryFor(source, recipe, geometry.scale * fit)

            else -> throw ExportTooLargeException(
                "${whole?.name} can't hold ${size.width}×${size.height}"
            )
        }
    }

    /** JPEG and PNG: each strip goes straight into the encoder. */
    private suspend fun streamed(
        gl: GlSession,
        job: Job,
        format: ExportFormat,
        request: ExportRequest
    ): Size {
        val out = job.out
        request.output.outputStream().use { stream ->
            val sink: RowSink = if (format == ExportFormat.PNG) {
                val alpha = job.recipe.hasAlpha || job.source.mayHaveAlpha
                PngWriter(stream, out.width, out.height, alpha)
            } else {
                JpegWriter(stream, out.width, out.height, request.options.quality)
            }
            sink.use {
                strips(gl, job) { bytes, rows -> sink.write(bytes, rows) }
                sink.finish()
            }
        }
        return out
    }

    /** The other formats, and size targets: the strips are laid into one bitmap first. */
    private suspend fun assembled(
        gl: GlSession,
        job: Job,
        format: ExportFormat,
        request: ExportRequest
    ): Size {
        val out = job.out
        val options = request.options
        val full = createBitmap(out.width, out.height)
        val canvas = Canvas(full)
        val paint = Paint()
        // JPEG has no alpha, so its picture goes over white; the others keep the strips' own.
        if (format == ExportFormat.JPEG) {
            canvas.drawColor(WHITE)
        } else {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        var y = 0
        strips(gl, job) { bytes, rows ->
            val strip = createBitmap(out.width, rows)
            strip.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, out.width * rows * PIXEL))
            canvas.drawBitmap(strip, 0f, y.toFloat(), paint)
            strip.recycle()
            y += rows
        }
        // HEIC and AVIF take their metadata as a block while they are written.
        val heif = format == ExportFormat.HEIC || format == ExportFormat.AVIF
        val work = checkNotNull(request.output.parentFile)
        return Encoders.writeWhole(full, format, options, request.output) { size ->
            if (options.keepMetadata && heif) Metadata.block(request.input, size, work) else null
        }
    }

    /** Renders the output a strip at a time, overlays included, into [emit]. */
    private suspend fun strips(gl: GlSession, job: Job, emit: (ByteArray, Int) -> Unit) {
        val out = job.out
        val tileW = min(TILE_WIDTH, gl.maxTextureSize)
        val stripRows = min(STRIP_ROWS, gl.maxTextureSize)
        val strip = ByteArray(out.width * stripRows * PIXEL)
        val tile = ByteBuffer.allocateDirect(tileW * stripRows * PIXEL)
        var y = 0
        while (y < out.height) {
            coroutineContext.ensureActive()
            val rows = min(stripRows, out.height - y)
            var x = 0
            while (x < out.width) {
                val w = min(tileW, out.width - x)
                // A tile that reads no source pixels shows only the frame.
                loadRegion(gl, job, Rect(x, y, x + w, y + rows))
                tile.clear()
                gl.draw(x, y, w, rows, tile)
                for (r in 0 until rows) {
                    tile.position(r * w * PIXEL)
                    tile.get(strip, (r * out.width + x) * PIXEL, w * PIXEL)
                }
                x += w
            }
            if (!job.painter.isEmpty) paintOverlays(job, strip, y, rows)
            emit(strip, rows)
            y += rows
            job.progress(y.toFloat() / out.height)
        }
    }

    /** The text, drawings, stickers and watermark that fall on rows [y] to [y] + [rows]. */
    private fun paintOverlays(job: Job, strip: ByteArray, y: Int, rows: Int) {
        val out = job.out
        val border = job.geometry.border
        val content = RectF(
            border.left.toFloat(),
            border.top.toFloat(),
            (border.left + job.geometry.content.width).toFloat(),
            (border.top + job.geometry.content.height).toFloat()
        )
        val bytes = out.width * rows * PIXEL
        val bitmap = createBitmap(out.width, rows)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(strip, 0, bytes))
        val canvas = Canvas(bitmap)
        canvas.translate(0f, -y.toFloat())
        job.painter.draw(canvas, content)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(strip, 0, bytes))
        bitmap.recycle()
    }

    /**
     * Decodes the part of the source that the output rectangle [tile] reads, at the resolution
     * it needs, with erasures laid over it; false when the rectangle reads none.
     */
    private fun loadRegion(gl: GlSession, job: Job, tile: Rect): Boolean {
        val raster = job.source.size
        val read = job.geometry.sourceBounds(
            tile.left.toDouble(),
            tile.top.toDouble(),
            tile.width().toDouble(),
            tile.height().toDouble()
        )
        val density = job.geometry.density(
            tile.left + tile.width() / 2.0,
            tile.top + tile.height() / 2.0
        )
        var sample = 1
        while (sample * 2 <= density) sample *= 2
        var rect: Rect
        while (true) {
            val apron = APRON * sample
            rect = Rect(
                max(0, floor(read.left * raster.width).toInt() - apron),
                max(0, floor(read.top * raster.height).toInt() - apron),
                min(raster.width, ceil(read.right * raster.width).toInt() + apron),
                min(raster.height, ceil(read.bottom * raster.height).toInt() + apron)
            )
            if (rect.width() <= 0 || rect.height() <= 0) return false
            if (max(rect.width(), rect.height()) / sample <= gl.maxTextureSize) break
            sample *= 2
        }
        val bitmap = job.source.decodeRegion(rect, sample).mutableCopy()
        val region = Box(
            rect.left.toDouble() / raster.width,
            rect.top.toDouble() / raster.height,
            rect.right.toDouble() / raster.width,
            rect.bottom.toDouble() / raster.height
        )
        PhotoInputs.bakeErasures(bitmap, job.recipe.erasures, assets, region)
        gl.setSource(bitmap, region)
        return true
    }

    private fun scaleFor(source: SourceImage, recipe: EditRecipe, options: ExportOptions): Double {
        val full = geometryFor(source, recipe, 1.0).content
        val percent = options.resize.percent?.coerceIn(1, PERCENT)?.div(PERCENT.toDouble()) ?: 1.0
        val edge = options.resize.longEdge?.takeIf { it > 0 }
            ?.let { min(1.0, it.toDouble() / full.longEdge) }
            ?: 1.0
        return min(percent, edge)
    }

    private fun geometryFor(source: SourceImage, recipe: EditRecipe, scale: Double) =
        RenderGeometry(source.size, source.orientation, recipe.geometry, scale, recipe.frame)

    /** How much [size] must shrink for [format]'s own limits (1 when it fits). */
    private fun fitFormat(format: ExportFormat, size: Size): Double {
        val tooLong = format == ExportFormat.WEBP && size.longEdge > WEBP_MAX
        return if (tooLong) WEBP_MAX.toDouble() / size.longEdge else 1.0
    }

    /** How much [size] must shrink for one bitmap of it to fit in a third of free memory. */
    private fun fitMemory(size: Size): Double {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val budget = min(info.availMem / MEMORY_SHARE, MAX_WHOLE_BYTES)
        val need = size.pixels * PIXEL
        return if (need <= budget) 1.0 else sqrt(budget.toDouble() / need) * SAFETY
    }

    private companion object {
        const val TILE_WIDTH = 2048
        const val STRIP_ROWS = 256
        const val APRON = 4
        const val PIXEL = 4
        const val PERCENT = 100
        const val WEBP_MAX = 16_383
        const val MEMORY_SHARE = 3L
        const val MAX_WHOLE_BYTES = 1_200_000_000L
        const val SAFETY = 0.98
        const val WHITE = -1
        const val JPEG_MIME = "image/jpeg"
        val STREAMED = setOf(ExportFormat.JPEG, ExportFormat.PNG)
    }
}

/** Formats that need the whole bitmap, and file-size targets (spec §4.10: "under 500 KB"). */
internal object Encoders {
    /**
     * Writes [bitmap] as [format]. With a size target, the best quality that fits is found by
     * halving; when even the lowest doesn't fit, the picture is made smaller and tried again,
     * a few times. [exif] gives the metadata block for a picture of a size (HEIC and AVIF take
     * it as they are written). Returns the size written.
     */
    fun writeWhole(
        bitmap: Bitmap,
        format: ExportFormat,
        options: ExportOptions,
        out: File,
        exif: (Size) -> ByteArray?
    ): Size {
        val target = options.maxBytes
        var picture = bitmap
        var size = Size(picture.width, picture.height)
        if (target == null) {
            write(picture, format, options.quality, out, exif(size))
            return size
        }
        var shrinks = 0
        while (!fitted(picture, format, options, out, exif(size)) && shrinks++ < SHRINKS) {
            val shrink = sqrt(target.toDouble() / out.length()) * SHRINK_MARGIN
            size = Size(
                max(1, (picture.width * shrink).roundToInt()),
                max(1, (picture.height * shrink).roundToInt())
            )
            picture = picture.scale(size.width, size.height)
        }
        return size
    }

    /**
     * Writes [picture] at the best quality, up to the one asked for, that keeps the file within
     * the size target; false when none does, and the file is then the lowest quality's.
     */
    private fun fitted(
        picture: Bitmap,
        format: ExportFormat,
        options: ExportOptions,
        out: File,
        exif: ByteArray?
    ): Boolean {
        val target = checkNotNull(options.maxBytes)
        if (format == ExportFormat.PNG) {
            write(picture, format, options.quality, out, exif)
            return out.length() <= target
        }
        var lo = MIN_QUALITY
        var hi = options.quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            write(picture, format, mid, out, exif)
            if (out.length() <= target) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best >= 0) write(picture, format, best, out, exif)
        return best >= 0
    }

    fun write(
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        out: File,
        exif: ByteArray? = null
    ) {
        if (format == ExportFormat.HEIC || format == ExportFormat.AVIF) {
            heif(bitmap, quality, out, avif = format == ExportFormat.AVIF, exif)
            return
        }
        val codec = when {
            format == ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            format != ExportFormat.WEBP -> Bitmap.CompressFormat.JPEG
            quality >= MAX_QUALITY -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> Bitmap.CompressFormat.WEBP_LOSSY
        }
        val written = out.outputStream().use { bitmap.compress(codec, quality, it) }
        if (!written) throw IOException("The encoder refused the picture")
    }

    private fun heif(bitmap: Bitmap, quality: Int, out: File, avif: Boolean, exif: ByteArray?) {
        out.delete()
        if (avif) {
            AvifWriter.Builder(out.path, bitmap.width, bitmap.height, AvifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(0, MAX_QUALITY))
                .setGridEnabled(true)
                .setMaxImages(1)
                .build()
                .use { writer ->
                    writer.start()
                    writer.addBitmap(bitmap)
                    exif?.let { writer.addExifData(0, it, 0, it.size) }
                    writer.stop(HEIF_TIMEOUT_MS)
                }
        } else {
            HeifWriter.Builder(out.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(0, MAX_QUALITY))
                .setGridEnabled(true)
                .setMaxImages(1)
                .build()
                .use { writer ->
                    writer.start()
                    writer.addBitmap(bitmap)
                    exif?.let { writer.addExifData(0, it, 0, it.size) }
                    writer.stop(HEIF_TIMEOUT_MS)
                }
        }
    }

    private const val MIN_QUALITY = 10
    private const val MAX_QUALITY = 100
    private const val SHRINKS = 4
    private const val SHRINK_MARGIN = 0.92
    private const val HEIF_TIMEOUT_MS = 60_000L
}

/** The geometry an edit's preview uses, fitting its output inside [maxEdge] pixels. */
fun previewGeometry(
    raster: Size,
    orientation: Int,
    recipe: EditRecipe,
    maxEdge: Int,
    geometry: Geometry = recipe.geometry
): RenderGeometry {
    val full = RenderGeometry(raster, orientation, geometry, 1.0, recipe.frame)
    val scale = min(1.0, maxEdge.toDouble() / full.output.longEdge)
    return RenderGeometry(raster, orientation, geometry, scale, recipe.frame)
}
