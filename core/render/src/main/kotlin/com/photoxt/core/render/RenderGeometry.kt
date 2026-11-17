package com.photoxt.core.render

import com.photoxt.core.model.Frame
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.Rect01
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A 3×3 matrix, row-major, for affine and projective maps of 2D points. */
@Suppress("MagicNumber") // Its cells, by index.
class Mat3(val m: DoubleArray) {
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(SIZE)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                r[i * 3 + j] =
                    m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
            }
        }
        return Mat3(r)
    }

    /** [x], [y] through this map, divided by its w. */
    fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > EPSILON) { "The map can't be inverted" }
        val inv = 1.0 / det
        return Mat3(
            doubleArrayOf(
                c00 * inv,
                (a[2] * a[7] - a[1] * a[8]) * inv,
                (a[1] * a[5] - a[2] * a[4]) * inv,
                c01 * inv,
                (a[0] * a[8] - a[2] * a[6]) * inv,
                (a[2] * a[3] - a[0] * a[5]) * inv,
                c02 * inv,
                (a[1] * a[6] - a[0] * a[7]) * inv,
                (a[0] * a[4] - a[1] * a[3]) * inv
            )
        )
    }

    /** Column-major floats, as `glUniformMatrix3fv` takes them. */
    fun toGl(): FloatArray = FloatArray(SIZE) { i -> m[(i % 3) * 3 + i / 3].toFloat() }

    companion object {
        private const val SIZE = 9
        private const val EPSILON = 1e-12

        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** The affine map with first row [a], [b], [c] and second row [d], [e], [f]. */
        @Suppress("LongParameterList")
        fun affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) =
            Mat3(doubleArrayOf(a, b, c, d, e, f, 0.0, 0.0, 1.0))

        fun scale(sx: Double, sy: Double) = affine(sx, 0.0, 0.0, 0.0, sy, 0.0)

        fun translate(tx: Double, ty: Double) = affine(1.0, 0.0, tx, 0.0, 1.0, ty)

        /**
         * The projective map of the unit square onto the quad [q] (x, y of its top-left, top-right,
         * bottom-right and bottom-left corners), after Heckbert's square-to-quad.
         */
        fun squareToQuad(q: DoubleArray): Mat3 {
            val (x0, y0) = q[0] to q[1]
            val (x1, y1) = q[2] to q[3]
            val (x2, y2) = q[4] to q[5]
            val (x3, y3) = q[6] to q[7]
            val sx = x0 - x1 + x2 - x3
            val sy = y0 - y1 + y2 - y3
            if (abs(sx) < EPSILON && abs(sy) < EPSILON) {
                return affine(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0)
            }
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            val g = (sx * dy2 - dx2 * sy) / den
            val h = (dx1 * sy - sx * dy1) / den
            return Mat3(
                doubleArrayOf(
                    x1 - x0 + g * x1,
                    x3 - x0 + h * x3,
                    x0,
                    y1 - y0 + g * y1,
                    y3 - y0 + h * y3,
                    y0,
                    g,
                    h,
                    1.0
                )
            )
        }
    }
}

/** An integer size in pixels. */
data class Size(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val shortEdge: Int get() = min(width, height)
    val pixels: Long get() = width.toLong() * height
}

/** A box of source fractions, by its edges. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    companion object {
        /** The whole raster. */
        val WHOLE = Box(0.0, 0.0, 1.0, 1.0)
    }
}

/** Pixels a frame adds around the picture. */
data class Border(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/**
 * Where every output pixel of an edit comes from (spec §4.10, Transform): the source raster as
 * stored, its EXIF [orientation], then [geometry], at [scale] output pixels per pixel of the
 * cropped picture, with the frame's border around it.
 *
 * [outToSource] maps output pixels to source fractions (x, y of the raster as stored), which is
 * what the shader samples, masks included.
 */
class RenderGeometry(
    val raster: Size,
    val orientation: Int,
    val geometry: Geometry,
    val scale: Double,
    framing: Frame = Frame()
) {
    /** The photo upright (EXIF applied), then turned: the frame the crop is a part of. */
    val frame: Size

    /** The cropped picture in output pixels, before the frame's border. */
    val content: Size

    val border: Border

    /** The output: content plus border. */
    val output: Size

    /** Corner radius of the content in output pixels (rounded frames). */
    val cornerRadius: Float

    val outToSource: Mat3

    init {
        val upright = if (orientation in TRANSPOSED) Size(raster.height, raster.width) else raster
        frame = if (geometry.quarterTurns % 2 == 1) Size(upright.height, upright.width) else upright
        val crop = geometry.crop
        val cropW = crop.width.toDouble() * frame.width
        val cropH = crop.height.toDouble() * frame.height
        content = Size(
            max(1, (cropW * scale).roundToInt()),
            max(1, (cropH * scale).roundToInt())
        )
        val b = (framing.width * content.shortEdge).roundToInt().coerceAtLeast(0)
        border = when (framing.style) {
            FrameStyle.NONE -> Border()
            FrameStyle.SOLID, FrameStyle.ROUNDED -> Border(b, b, b, b)
            FrameStyle.POLAROID -> Border(b, b, b, b * POLAROID_BOTTOM)
        }
        output = Size(
            content.width + border.left + border.right,
            content.height + border.top + border.bottom
        )
        val rounded = framing.style == FrameStyle.ROUNDED
        cornerRadius = if (rounded) framing.radius * content.shortEdge else 0f
        outToSource = sourceFromUpright(upright) * uprightFromTurned() *
            turnedFromStraight() * straightFromOutput(cropW, cropH)
    }

    /** Output pixels to fractions of the cropped picture (layers' coordinates). */
    val outToContent: Mat3
        get() = Mat3.scale(1.0 / content.width, 1.0 / content.height) *
            Mat3.translate(-border.left.toDouble(), -border.top.toDouble())

    val contentToOut: Mat3 get() = outToContent.inverse()

    val sourceToOut: Mat3 get() = outToSource.inverse()

    /** Source fractions to fractions of the cropped picture. */
    val sourceToContent: Mat3 get() = outToContent * sourceToOut

    val contentToSource: Mat3 get() = outToSource * contentToOut

    /** The source pixels the output rectangle [x], [y], [w], [h] reads, as a fraction box. */
    fun sourceBounds(x: Double, y: Double, w: Double, h: Double): Box {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for ((px, py) in listOf(x to y, x + w to y, x + w to y + h, x to y + h)) {
            val (sx, sy) = outToSource.map(px, py)
            minX = min(minX, sx)
            minY = min(minY, sy)
            maxX = max(maxX, sx)
            maxY = max(maxY, sy)
        }
        return Box(minX, minY, maxX, maxY)
    }

    /** Source pixels per output pixel near output point [x], [y]: the smaller of both axes. */
    fun density(x: Double, y: Double): Double {
        val (x0, y0) = outToSource.map(x, y)
        val (x1, y1) = outToSource.map(x + 1, y)
        val (x2, y2) = outToSource.map(x, y + 1)
        val dx = kotlin.math.hypot((x1 - x0) * raster.width, (y1 - y0) * raster.height)
        val dy = kotlin.math.hypot((x2 - x0) * raster.width, (y2 - y0) * raster.height)
        return min(dx, dy)
    }

    // Output pixels → fractions of the straightened frame (before the crop).
    private fun straightFromOutput(cropW: Double, cropH: Double): Mat3 {
        val crop = geometry.crop
        // Output pixel → content fraction → frame fraction → frame pixels.
        val toContent = outToContent
        val toFrame = Mat3.affine(
            crop.width.toDouble(),
            0.0,
            crop.left.toDouble(),
            0.0,
            crop.height.toDouble(),
            crop.top.toDouble()
        )
        check(cropW > 0 && cropH > 0) { "An empty crop" }
        return Mat3.scale(frame.width.toDouble(), frame.height.toDouble()) * toFrame * toContent
    }

    // Straightened frame pixels → turned-frame fractions: undo the straightening and the quad.
    private fun turnedFromStraight(): Mat3 {
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        val theta = Math.toRadians(geometry.straighten.toDouble())
        val c = cos(theta)
        val s = sin(theta)
        val cover = coverScale(w, h, abs(theta))
        // Content turned clockwise by theta and scaled by cover: the inverse turns back.
        val unrotate = Mat3.translate(w / 2, h / 2) *
            Mat3.affine(c / cover, s / cover, 0.0, -s / cover, c / cover, 0.0) *
            Mat3.translate(-w / 2, -h / 2)
        val toFraction = Mat3.scale(1 / w, 1 / h)
        return Mat3.squareToQuad(quad()) * toFraction * unrotate
    }

    /** The perspective quad in turned-frame fractions: the four corners, or the keystones'. */
    fun quad(): DoubleArray {
        geometry.corners?.takeIf { it.size == QUAD }?.let { corners ->
            return DoubleArray(QUAD) { corners[it].toDouble() }
        }
        val v = geometry.keystoneV.toDouble() * KEYSTONE
        val hz = geometry.keystoneH.toDouble() * KEYSTONE
        val top = max(v, 0.0)
        val bottom = max(-v, 0.0)
        val right = max(hz, 0.0)
        val left = max(-hz, 0.0)
        return doubleArrayOf(
            top,
            left,
            1 - top,
            right,
            1 - bottom,
            1 - right,
            bottom,
            1 - left
        )
    }

    // Turned-frame fractions → upright fractions: undo the flips, then the quarter turns.
    @Suppress("MagicNumber") // One, two or three quarter turns.
    private fun uprightFromTurned(): Mat3 {
        val flip = Mat3.affine(
            if (geometry.flipH) -1.0 else 1.0,
            0.0,
            if (geometry.flipH) 1.0 else 0.0,
            0.0,
            if (geometry.flipV) -1.0 else 1.0,
            if (geometry.flipV) 1.0 else 0.0
        )
        val unturn = when (geometry.quarterTurns.mod(TURNS)) {
            1 -> Mat3.affine(0.0, 1.0, 0.0, -1.0, 0.0, 1.0)
            2 -> Mat3.affine(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0)
            3 -> Mat3.affine(0.0, -1.0, 1.0, 1.0, 0.0, 0.0)
            else -> Mat3.IDENTITY
        }
        return unturn * flip
    }

    // Upright fractions → source fractions: undo the EXIF orientation.
    @Suppress("MagicNumber") // EXIF's orientation values, 2 to 8.
    private fun sourceFromUpright(upright: Size): Mat3 {
        check(upright.pixels > 0)
        return when (orientation) {
            2 -> Mat3.affine(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0)
            3 -> Mat3.affine(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0)
            4 -> Mat3.affine(1.0, 0.0, 0.0, 0.0, -1.0, 1.0)
            5 -> Mat3.affine(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            6 -> Mat3.affine(0.0, 1.0, 0.0, -1.0, 0.0, 1.0)
            7 -> Mat3.affine(0.0, -1.0, 1.0, -1.0, 0.0, 1.0)
            8 -> Mat3.affine(0.0, -1.0, 1.0, 1.0, 0.0, 0.0)
            else -> Mat3.IDENTITY
        }
    }

    companion object {
        /** EXIF orientations that swap width and height. */
        val TRANSPOSED = setOf(5, 6, 7, 8)
        private const val TURNS = 4
        private const val QUAD = 8
        private const val POLAROID_BOTTOM = 4

        /** How far a keystone slider at its end moves the narrowed edge's corners inward. */
        private const val KEYSTONE = 0.25

        /** How much a w×h picture turned by [theta] must grow to cover the w×h frame. */
        fun coverScale(w: Double, h: Double, theta: Double): Double {
            val c = cos(theta)
            val s = sin(theta)
            return max((w * c + h * s) / w, (w * s + h * c) / h)
        }

        /** The largest crop of aspect [ratio] (width / height, in pixels) centred in [frame]. */
        fun centredCrop(frame: Size, ratio: Float): Rect01 {
            val frameRatio = frame.width.toFloat() / frame.height
            return if (ratio > frameRatio) {
                val h = frameRatio / ratio
                Rect01(0f, (1 - h) / 2, 1f, (1 + h) / 2)
            } else {
                val w = ratio / frameRatio
                Rect01((1 - w) / 2, 0f, (1 + w) / 2, 1f)
            }
        }
    }
}
