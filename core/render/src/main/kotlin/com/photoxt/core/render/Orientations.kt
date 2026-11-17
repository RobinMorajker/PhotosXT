package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.Matrix
import com.photoxt.core.model.Ellipse01
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.Point01
import java.io.File

/**
 * EXIF orientation between the raster as stored (where recipes place masks and spots) and the
 * photo upright (what detectors see and people look at).
 */
object Orientations {
    /** [raster] turned upright for [orientation]. */
    fun upright(raster: Bitmap, orientation: Int): Bitmap {
        if (orientation <= 1) return raster
        val matrix = Matrix()
        when (orientation) {
            FLIP_H -> matrix.setScale(-1f, 1f)

            ROTATE_180 -> matrix.setRotate(HALF_TURN)

            FLIP_V -> matrix.setScale(1f, -1f)

            TRANSPOSE -> {
                matrix.setRotate(QUARTER)
                matrix.postScale(-1f, 1f)
            }

            ROTATE_90 -> matrix.setRotate(QUARTER)

            TRANSVERSE -> {
                matrix.setRotate(-QUARTER)
                matrix.postScale(-1f, 1f)
            }

            ROTATE_270 -> matrix.setRotate(-QUARTER)
        }
        return Bitmap.createBitmap(raster, 0, 0, raster.width, raster.height, matrix, true)
    }

    /** A point of the upright photo (fractions) as a point of the raster. */
    fun toRaster(orientation: Int, x: Float, y: Float): Point01 {
        val m = RenderGeometry(Size(2, 2), orientation, Geometry(), 1.0)
        val (sx, sy) = m.outToSource.map(
            x.toDouble() * m.output.width,
            y.toDouble() * m.output.height
        )
        return Point01(sx.toFloat(), sy.toFloat())
    }

    /** A mask of the upright photo, [width]×[height] bytes, as a mask of the raster. */
    fun maskToRaster(bytes: ByteArray, width: Int, height: Int, orientation: Int): MaskRaster {
        if (orientation <= 1) return MaskRaster(width, height, bytes)
        val transposed = orientation in RenderGeometry.TRANSPOSED
        val rw = if (transposed) height else width
        val rh = if (transposed) width else height
        val out = ByteArray(rw * rh)
        val geometry = RenderGeometry(Size(rw, rh), orientation, Geometry(), 1.0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (sx, sy) = geometry.outToSource.map(x + CENTRE, y + CENTRE)
                val rx = (sx * rw).toInt().coerceIn(0, rw - 1)
                val ry = (sy * rh).toInt().coerceIn(0, rh - 1)
                out[ry * rw + rx] = bytes[y * width + x]
            }
        }
        return MaskRaster(rw, rh, out)
    }

    private const val FLIP_H = 2
    private const val ROTATE_180 = 3
    private const val FLIP_V = 4
    private const val TRANSPOSE = 5
    private const val ROTATE_90 = 6
    private const val TRANSVERSE = 7
    private const val ROTATE_270 = 8
    private const val QUARTER = 90f
    private const val HALF_TURN = 180f

    /** The middle of a pixel. */
    private const val CENTRE = 0.5
}

/**
 * What a pasted edit must find again in each photo it lands on (spec §4.10, batch apply): the
 * subject, for a new background, background blur and subject masks, and the faces, for skin
 * smoothing.
 */
interface PhotoFinder {
    /** The asset name of the subject mask of the photo in [photo]; null when none is found. */
    suspend fun subjectMask(photo: File): String?

    /** The faces in the photo in [photo]; empty when none are found. */
    suspend fun faces(photo: File): List<Ellipse01>
}
