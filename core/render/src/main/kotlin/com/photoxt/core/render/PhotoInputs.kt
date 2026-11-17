package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Erasure
import com.photoxt.core.model.MaskKind
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A one-channel mask, [width]×[height] bytes, row by row. */
class MaskRaster(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * A photo file as the editor reads it: its raster as stored and its EXIF orientation, decoded
 * whole at a reduced size or a region at a time. Formats Android can't decode by region are
 * decoded once, at most [MAX_WHOLE] pixels, and cut from memory.
 */
class SourceImage(private val file: File) : AutoCloseable {
    /** The raster as stored, before its EXIF rotation. */
    val size: Size

    /** EXIF orientation, 1 to 8; 1 when the decoder already turned the pixels. */
    val orientation: Int

    /** Whether the file can hold see-through pixels (PNG, WebP, HEIF with alpha). */
    val mayHaveAlpha: Boolean

    private var regionDecoder: BitmapRegionDecoder? = null
    private var whole: Bitmap? = null

    init {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val mime = bounds.outMimeType
        regionDecoder = runCatching { newRegionDecoder(file) }.getOrNull()
        if (bounds.outWidth > 0 && regionDecoder != null) {
            size = Size(bounds.outWidth, bounds.outHeight)
            orientation = runCatching {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            }.getOrDefault(1).takeIf { it in 1..MAX_ORIENTATION } ?: 1
        } else {
            // ImageDecoder turns the picture upright itself.
            val decoded = decodeWhole(file)
            whole = decoded
            size = Size(decoded.width, decoded.height)
            orientation = 1
        }
        mayHaveAlpha = mime == null || mime != "image/jpeg"
    }

    /** The whole raster, its long edge at most [maxLongEdge], exactly [target] when given. */
    fun decodeScaled(maxLongEdge: Int, target: Size? = null): Bitmap {
        val want = target ?: fit(size, maxLongEdge)
        val sample = sampleFor(size.width, want.width)
        val decoded = regionDecoder?.let { decoder ->
            synchronized(decoder) {
                decoder.decodeRegion(Rect(0, 0, size.width, size.height), options(sample))
            }
        } ?: checkNotNull(whole)
        return decoded.scaledTo(want)
    }

    /**
     * Raster pixels [rect] at 1/[sample] of their size; the result also reports the rectangle
     * actually decoded (decoders round to whole samples).
     */
    fun decodeRegion(rect: Rect, sample: Int): Bitmap {
        val decoder = regionDecoder
        if (decoder != null) {
            synchronized(decoder) {
                return checkNotNull(decoder.decodeRegion(rect, options(sample)))
            }
        }
        val all = checkNotNull(whole)
        val part = Bitmap.createBitmap(all, rect.left, rect.top, rect.width(), rect.height())
        return if (sample ==
            1
        ) {
            part
        } else {
            part.scaledTo(
                Size(
                    max(1, rect.width() / sample),
                    max(
                        1,
                        rect.height() / sample
                    )
                )
            )
        }
    }

    override fun close() {
        regionDecoder?.recycle()
        regionDecoder = null
        whole = null
    }

    private fun options(sample: Int) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    companion object {
        const val MAX_WHOLE = 64_000_000L
        private const val MAX_ORIENTATION = 8

        /** The largest power of two that keeps [from] pixels at least [to] after sampling. */
        fun sampleFor(from: Int, to: Int): Int {
            var sample = 1
            while (from / (sample * 2) >= to) sample *= 2
            return sample
        }

        fun fit(size: Size, maxLongEdge: Int): Size {
            if (size.longEdge <= maxLongEdge) return size
            val scale = maxLongEdge.toDouble() / size.longEdge
            return Size(
                max(1, (size.width * scale).roundToInt()),
                max(1, (size.height * scale).roundToInt())
            )
        }

        @Suppress("DEPRECATION")
        private fun newRegionDecoder(file: File): BitmapRegionDecoder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                BitmapRegionDecoder.newInstance(file.path, false)
            } ?: throw IOException("No region decoder")

        private fun decodeWhole(file: File): Bitmap {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val pixels = info.size.width.toLong() * info.size.height
                if (pixels > MAX_WHOLE) {
                    val scale = kotlin.math.sqrt(MAX_WHOLE.toDouble() / pixels)
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt(),
                        (info.size.height * scale).toInt()
                    )
                }
            }
        }
    }
}

/** [this] at exactly [size], filtered; itself when it already is. */
internal fun Bitmap.scaledTo(size: Size): Bitmap {
    val soft = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    return if (soft.width == size.width &&
        soft.height == size.height
    ) {
        soft
    } else {
        soft.scale(size.width, size.height)
    }
}

/**
 * What every render of one photo shares (spec §4.10): low-resolution guides of the whole raster
 * (sharp, lightly blurred, heavily blurred, each with its erasures), the haze colour dehaze
 * removes, the subject mask, and a replacement background picture.
 */
class PhotoInputs(
    val guide: Bitmap,
    val guideSoft: Bitmap,
    val guideBlur: Bitmap,
    val haze: FloatArray,
    val subject: MaskRaster?,
    val backgroundImage: Bitmap?
) {
    companion object {
        /** Long edge of the guides: fixed, so the preview and the export read the same ones. */
        const val GUIDE_EDGE = 1024
        private const val SOFT_RADIUS = 0.004
        private const val BLUR_RADIUS = 0.03

        fun build(source: SourceImage, recipe: EditRecipe, assets: EditAssets): PhotoInputs {
            val size = SourceImage.fit(source.size, GUIDE_EDGE)
            val guide = source.decodeScaled(GUIDE_EDGE, size).mutableCopy()
            bakeErasures(guide, recipe.erasures, assets, Box.WHOLE)
            val soft = Blur.box(guide, max(1, (size.longEdge * SOFT_RADIUS).roundToInt()))
            val blurred = Blur.box(guide, max(2, (size.longEdge * BLUR_RADIUS).roundToInt()))
            return PhotoInputs(
                guide = guide,
                guideSoft = soft,
                guideBlur = blurred,
                haze = Blur.hazeLight(blurred),
                subject = recipe.subjectMask?.let { assets.mask(it) },
                backgroundImage = recipe.background.image?.let { assets.bitmap(it, GUIDE_EDGE * 2) }
            )
        }

        /**
         * Draws [erasures] over [bitmap], which shows [region] of the raster, so erased objects
         * are gone before any other step reads the photo.
         */
        fun bakeErasures(bitmap: Bitmap, erasures: List<Erasure>, assets: EditAssets, region: Box) {
            if (erasures.isEmpty()) return
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val shown = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            val sx = bitmap.width / region.width
            val sy = bitmap.height / region.height
            for (erasure in erasures) {
                val r = erasure.rect
                val dst = RectF(
                    ((r.left - region.left) * sx).toFloat(),
                    ((r.top - region.top) * sy).toFloat(),
                    ((r.right - region.left) * sx).toFloat(),
                    ((r.bottom - region.top) * sy).toFloat()
                )
                // Only the patches that fall on this part of the photo are read.
                val patch = if (RectF.intersects(dst, shown)) {
                    assets.bitmap(erasure.patch, MAX_PATCH)
                } else {
                    null
                }
                patch?.let { canvas.drawBitmap(it, null, dst, paint) }
            }
        }

        private const val MAX_PATCH = 4096
    }
}

/** What a recipe adds to the GPU's inputs: curve tables, the LUT, brush masks. */
class RecipeTables(
    val curves: ByteArray?,
    val lut: Lut3d?,
    val brush: MaskRaster?,
    val brushChannel: Map<String, Int>
) {
    companion object {
        /** Long edge of the brush-mask raster. */
        const val MASK_EDGE = 2048
        private const val SOFT_EDGE = 0.35f

        fun build(
            recipe: EditRecipe,
            raster: Size,
            assets: EditAssets,
            previous: RecipeTables? = null
        ): RecipeTables {
            val curves = if (ToneCurves.isIdentity(
                    recipe.curves
                )
            ) {
                null
            } else {
                ToneCurves.texture(recipe.curves)
            }
            val filter = recipe.filter
            val lut = filter?.lut?.let(assets::lut) ?: filter?.look?.let(Looks::lut)
            val brushMasks = recipe.masks.take(EditShader.MAX_MASKS).filter {
                it.kind ==
                    MaskKind.BRUSH
            }
            val channels = brushMasks.mapIndexed { i, mask -> mask.id to i }.toMap()
            val strokes = brushMasks.map { it.strokes }
            val brush = if (brushMasks.isEmpty()) {
                null
            } else if (previous?.brushKey == strokes) {
                previous.brush
            } else {
                rasterize(brushMasks.map { it.strokes }, raster)
            }
            return RecipeTables(curves, lut, brush, channels).also { it.brushKey = strokes }
        }

        /** Up to four masks' strokes as one RGBA raster, a mask per channel, in source space. */
        fun rasterize(
            masks: List<List<com.photoxt.core.model.MaskStroke>>,
            raster: Size
        ): MaskRaster {
            val size = SourceImage.fit(raster, MASK_EDGE)
            val bytes = ByteArray(size.width * size.height * CHANNELS)
            masks.forEachIndexed { channel, strokes ->
                val alpha = createBitmap(size.width, size.height, Bitmap.Config.ALPHA_8)
                drawStrokes(Canvas(alpha), strokes, size)
                val row = ByteArray(size.width * size.height)
                alpha.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(row))
                for (i in row.indices) bytes[i * CHANNELS + channel] = row[i]
            }
            return MaskRaster(size.width, size.height, bytes)
        }

        /** Mask [strokes] (source fractions) onto [canvas], which covers the raster at [size]. */
        fun drawStrokes(
            canvas: Canvas,
            strokes: List<com.photoxt.core.model.MaskStroke>,
            size: Size
        ) {
            val edge = min(size.width, size.height).toFloat()
            for (stroke in strokes) {
                val width = max(1f, stroke.size * edge)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = width
                    color = -1
                    maskFilter = BlurMaskFilter(width * SOFT_EDGE, BlurMaskFilter.Blur.NORMAL)
                    if (stroke.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                val p = stroke.points
                if (p.size < 2) continue
                val path = Path().apply {
                    moveTo(p[0] * size.width, p[1] * size.height)
                    var i = 2
                    while (i + 1 < p.size) {
                        lineTo(p[i] * size.width, p[i + 1] * size.height)
                        i += 2
                    }
                    // A tap is one point: a line too short to see still leaves its dot.
                    if (p.size == 2) lineTo(p[0] * size.width + DOT, p[1] * size.height)
                }
                canvas.drawPath(path, paint)
            }
        }

        private const val CHANNELS = 4
        private const val DOT = 0.1f
    }

    private var brushKey: List<List<com.photoxt.core.model.MaskStroke>>? = null
}

/** CPU blurs for the guides, and the haze estimate. */
internal object Blur {
    /** A near-Gaussian blur of [src] by [radius] pixels: three box passes each way. */
    fun box(src: Bitmap, radius: Int): Bitmap {
        val size = Size(src.width, src.height)
        val pixels = IntArray(size.width * size.height)
        src.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
        val tmp = IntArray(pixels.size)
        val r = max(1, radius / 2)
        repeat(PASSES) {
            boxPass(pixels, tmp, size, r, horizontal = true)
            boxPass(tmp, pixels, size, r, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, size.width, size.height, Bitmap.Config.ARGB_8888)
    }

    /** One pass: every pixel becomes the mean of the 2r + 1 along its row, or its column. */
    private fun boxPass(from: IntArray, to: IntArray, size: Size, r: Int, horizontal: Boolean) {
        val w = size.width
        val lines = if (horizontal) size.height else w
        val length = if (horizontal) w else size.height
        val window = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            fun at(i: Int): Int {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) from[line * w + k] else from[k * w + line]
            }
            for (i in -r..r) {
                val p = at(i)
                sa += Argb.alpha(p)
                sr += Argb.red(p)
                sg += Argb.green(p)
                sb += Argb.blue(p)
            }
            for (i in 0 until length) {
                val out = Argb.of(sa / window, sr / window, sg / window, sb / window)
                if (horizontal) to[line * w + i] = out else to[i * w + line] = out
                // The window slides on: one pixel comes in, one drops out.
                val add = at(i + r + 1)
                val drop = at(i - r)
                sa += Argb.alpha(add) - Argb.alpha(drop)
                sr += Argb.red(add) - Argb.red(drop)
                sg += Argb.green(add) - Argb.green(drop)
                sb += Argb.blue(add) - Argb.blue(drop)
            }
        }
    }

    /**
     * The haze ("atmospheric light") colour of a blurred photo: the mean of its brightest 0.1%
     * by dark channel, as in He et al.'s dark-channel prior.
     */
    fun hazeLight(blurred: Bitmap): FloatArray {
        val pixels = IntArray(blurred.width * blurred.height)
        blurred.getPixels(pixels, 0, blurred.width, 0, 0, blurred.width, blurred.height)
        val dark = IntArray(pixels.size) {
            val p = pixels[it]
            min(Argb.red(p), min(Argb.green(p), Argb.blue(p)))
        }
        val byDarkness = dark.sortedArray()
        val threshold = byDarkness[byDarkness.size - 1 - pixels.size / HAZE_SHARE]
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        pixels.forEachIndexed { i, p ->
            if (dark[i] >= threshold) {
                r += Argb.red(p)
                g += Argb.green(p)
                b += Argb.blue(p)
                n++
            }
        }
        val count = max(1, n) * Argb.MAX.toFloat()
        return floatArrayOf(r / count, g / count, b / count)
    }

    private const val PASSES = 3
    private const val HAZE_SHARE = 1000
}

internal fun Bitmap.mutableCopy(): Bitmap = copy(Bitmap.Config.ARGB_8888, true)
