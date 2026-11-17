package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Erasure
import com.photoxt.core.model.Frame
import com.photoxt.core.model.Rect01
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** What a preview shows (spec §4.10). */
enum class PreviewMode {
    /** The finished edit. */
    EDIT,

    /** The whole frame before the crop, for the crop tool, without frame or overlays. */
    FRAME,

    /** The original, upright and uncropped, for *Compare*. */
    ORIGINAL
}

/** One rendered preview and the geometry it was drawn with. */
class Preview(val bitmap: Bitmap, val geometry: RenderGeometry, val mode: PreviewMode)

/**
 * The editor's live preview (spec §4.10): the photo decoded once at a screen-sized proxy and
 * rendered through the same shader as the export, on a thread of its own. Two bitmaps take
 * turns, so the screen keeps showing one while the next is drawn into the other.
 */
class PreviewSession(val assets: EditAssets) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "photoxt-editor-gl") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var gl: GlSession? = null
    private var source: SourceImage? = null
    private var proxyBase: Bitmap? = null
    private var proxy: Bitmap? = null
    private var proxyErasures: List<Erasure>? = null
    private var inputs: PhotoInputs? = null
    private var inputsKey: Any? = null
    private var tables: RecipeTables? = null
    private var buffer: ByteBuffer? = null
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private var sourceUploaded: Bitmap? = null

    /** The raster as stored and its EXIF orientation, once [open] ran. */
    var raster: Size = Size(1, 1)
        private set
    var orientation: Int = 1
        private set

    /** Opens [file]: decodes the proxy and starts the GPU. */
    suspend fun open(file: File) = withContext(dispatcher) {
        val image = SourceImage(file)
        source = image
        raster = image.size
        orientation = image.orientation
        proxyBase = image.decodeScaled(PROXY_EDGE)
        gl = GlSession()
    }

    /** The proxy as decoded (no edits), for detectors and auto-enhance. */
    suspend fun proxy(): Bitmap = withContext(dispatcher) { checkNotNull(proxyBase) }

    /** [recipe] rendered as [mode] asks, its long edge at most [maxEdge]. */
    suspend fun render(recipe: EditRecipe, mode: PreviewMode, maxEdge: Int = PROXY_EDGE): Preview =
        withContext(dispatcher) {
            val session = checkNotNull(gl) { "open first" }
            val shown = when (mode) {
                PreviewMode.EDIT -> recipe

                PreviewMode.FRAME -> recipe.copy(
                    geometry = recipe.geometry.copy(crop = Rect01.FULL),
                    frame = Frame(),
                    layers = emptyList(),
                    strokes = emptyList(),
                    watermark = null
                )

                PreviewMode.ORIGINAL -> EditRecipe()
            }
            val photo = inputsFor(shown)
            val base = proxyFor(shown.erasures)
            val geometry = previewGeometry(raster, orientation, shown, maxEdge)
            tables = RecipeTables.build(shown, raster, assets, tables)
            session.setPhoto(photo)
            if (sourceUploaded !== base) {
                session.setSource(base, Box.WHOLE)
                sourceUploaded = base
            }
            session.setRecipe(shown, geometry, checkNotNull(tables))
            val out = geometry.output
            val bytes = out.width * out.height * PIXEL
            val target = buffer?.takeIf { it.capacity() >= bytes }
                ?: ByteBuffer.allocateDirect(bytes).also { buffer = it }
            target.clear()
            session.draw(0, 0, out.width, out.height, target)
            target.position(0)
            target.limit(bytes)
            val bitmap = bitmapFor(out)
            bitmap.copyPixelsFromBuffer(target)
            val painter = OverlayPainter(shown, assets)
            if (!painter.isEmpty) {
                val content = RectF(
                    geometry.border.left.toFloat(),
                    geometry.border.top.toFloat(),
                    (geometry.border.left + geometry.content.width).toFloat(),
                    (geometry.border.top + geometry.content.height).toFloat()
                )
                painter.draw(Canvas(bitmap), content)
            }
            Preview(bitmap, geometry, mode)
        }

    /** Raster pixels [rect] (fractions) at full resolution, its long edge at most [maxEdge]. */
    suspend fun region(rect: Rect01, maxEdge: Int): Bitmap = withContext(dispatcher) {
        val image = checkNotNull(source)
        val r = android.graphics.Rect(
            (rect.left * image.size.width).toInt(),
            (rect.top * image.size.height).toInt(),
            (rect.right * image.size.width).toInt().coerceAtMost(image.size.width),
            (rect.bottom * image.size.height).toInt().coerceAtMost(image.size.height)
        )
        val sample = SourceImage.sampleFor(max(r.width(), r.height()), maxEdge)
        image.decodeRegion(r, sample)
    }

    override fun close() {
        executor.execute {
            gl?.close()
            gl = null
            source?.close()
            source = null
        }
        executor.shutdown()
    }

    private fun inputsFor(recipe: EditRecipe): PhotoInputs {
        val key = Triple(recipe.erasures, recipe.subjectMask, recipe.background.image)
        inputs?.takeIf { inputsKey == key }?.let { return it }
        return PhotoInputs.build(checkNotNull(source), recipe, assets).also {
            inputs = it
            inputsKey = key
        }
    }

    /** The proxy with [erasures] laid over it; the last one made is kept for the next render. */
    private fun proxyFor(erasures: List<Erasure>): Bitmap {
        val base = checkNotNull(proxyBase)
        val made = proxy?.takeIf { proxyErasures == erasures }
        return when {
            erasures.isEmpty() -> base

            made != null -> made

            else -> base.mutableCopy().also {
                PhotoInputs.bakeErasures(it, erasures, assets, Box.WHOLE)
                proxy = it
                proxyErasures = erasures
            }
        }
    }

    private fun bitmapFor(size: Size): Bitmap {
        val slot = next
        next = 1 - next
        val old = bitmaps[slot]
        if (old != null && old.width == size.width && old.height == size.height) return old
        return createBitmap(size.width, size.height).also { bitmaps[slot] = it }
    }

    companion object {
        /** Long edge of the proxy and of previews: about a phone screen, with room to zoom. */
        const val PROXY_EDGE = 2048
        private const val PIXEL = 4
    }
}

/**
 * *Auto-enhance* (spec §4.10): exposure, contrast, highlights, shadows and vibrance set from the
 * photo's luminance histogram, as slider values the user can still change.
 */
object AutoEnhance {
    fun of(proxy: Bitmap, recipe: EditRecipe): EditRecipe {
        val w = proxy.width
        val h = proxy.height
        val step = max(1, (w * h / SAMPLES))
        val pixels = IntArray(w * h)
        proxy.getPixels(pixels, 0, w, 0, 0, w, h)
        val histogram = IntArray(BINS)
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val l = (LR * Argb.red(p) + LG * Argb.green(p) + LB * Argb.blue(p)).toInt()
            histogram[l.coerceIn(0, BINS - 1)]++
            count++
            i += step
        }
        fun percentile(p: Double): Float {
            var acc = 0
            val target = (count * p).toInt()
            for (k in 0 until BINS) {
                acc += histogram[k]
                if (acc >= target) return k / (BINS - 1f)
            }
            return 1f
        }
        val low = percentile(LOW)
        val median = percentile(MEDIAN)
        val high = percentile(HIGH)
        val exposure = (ln(MID_GREY / max(median, MIN_MEDIAN)) / ln(2.0) * EXPOSURE_SHARE).toFloat()
            .coerceIn(-MAX_EXPOSURE, MAX_EXPOSURE)
        val spread = high - low
        val contrast = ((TARGET_SPREAD - spread) * CONTRAST_GAIN).coerceIn(-MAX_TONE, MAX_TONE)
        val shadows = if (low < DARK) ((DARK - low) / DARK * MAX_TONE) else 0f
        val highlights = if (high > BRIGHT) -((high - BRIGHT) / (1 - BRIGHT) * MAX_TONE) else 0f
        return recipe.copy(
            light = recipe.light.copy(
                exposure = exposure,
                contrast = contrast,
                shadows = shadows,
                highlights = highlights
            ),
            color = recipe.color.copy(vibrance = AUTO_VIBRANCE)
        )
    }

    private const val SAMPLES = 200_000
    private const val BINS = 256
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f
    private const val LOW = 0.01
    private const val MEDIAN = 0.5
    private const val HIGH = 0.99
    private const val MID_GREY = 0.46
    private const val MIN_MEDIAN = 0.05f
    private const val EXPOSURE_SHARE = 0.6
    private const val MAX_EXPOSURE = 1f
    private const val TARGET_SPREAD = 0.9f
    private const val CONTRAST_GAIN = 0.8f
    private const val MAX_TONE = 0.4f
    private const val DARK = 0.04f
    private const val BRIGHT = 0.96f
    private const val AUTO_VIBRANCE = 0.15f
}
