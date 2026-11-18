package com.photoxt.core.render

import androidx.exifinterface.media.ExifInterface
import com.photoxt.core.model.ExportFormat
import java.io.File

/**
 * *Keep metadata* (spec §4.10, Output): the original's EXIF (dates, camera, exposure, location,
 * author) copied into the export, upright now and with its new size. Without it the export
 * carries no metadata at all. XMP is left behind: it describes the old file (a motion photo's
 * video, a gain map) and would be wrong for the new one.
 */
object Metadata {
    fun copy(from: File, to: File, format: ExportFormat, size: Size) {
        if (format == ExportFormat.HEIC || format == ExportFormat.AVIF) return
        val source = runCatching { ExifInterface(from) }.getOrNull() ?: return
        val target = ExifInterface(to)
        write(source, target, size)
        target.saveAttributes()
    }

    /**
     * The EXIF block ("Exif\0\0" and a TIFF header) for an export of [size] from [from], for
     * HEIC and AVIF, whose writers take it as bytes; null when [from] has none.
     */
    fun block(from: File, size: Size, work: File): ByteArray? {
        val source = runCatching { ExifInterface(from) }.getOrNull() ?: return null
        val carrier = File(work, "exif-${System.nanoTime()}.jpg")
        try {
            carrier.parentFile?.mkdirs()
            carrier.writeBytes(TINY_JPEG)
            val target = ExifInterface(carrier)
            write(source, target, size)
            // The carrier's own 1×1 would pass for the export's size: MediaStore reads it.
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, size.width.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, size.height.toString())
            target.saveAttributes()
            return app1(carrier.readBytes())
        } finally {
            carrier.delete()
        }
    }

    private fun write(source: ExifInterface, target: ExifInterface, size: Size) {
        TAGS.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        target.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        target.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
    }

    /** The payload of a JPEG's EXIF APP1 segment. */
    @Suppress("MagicNumber") // A segment: FF, its type, two bytes of length, then the payload.
    private fun app1(jpeg: ByteArray): ByteArray? {
        var found: ByteArray? = null
        var i = 2
        while (found == null && i + 4 <= jpeg.size && jpeg[i] == MARKER) {
            val type = jpeg[i + 1].toInt() and BYTE
            // The picture's own data starts: no EXIF before it.
            if (type == SOS) break
            val length = ((jpeg[i + 2].toInt() and BYTE) shl 8) or (jpeg[i + 3].toInt() and BYTE)
            if (type == APP1) found = jpeg.copyOfRange(i + 4, i + 2 + length)
            i += 2 + length
        }
        return found
    }

    private const val SOFTWARE = "PhotoXT"
    private const val MARKER: Byte = -1
    private const val BYTE = 0xFF
    private const val APP1 = 0xE1
    private const val SOS = 0xDA

    /** A 1×1 baseline JPEG to carry EXIF while it is assembled. */
    private val TINY_JPEG: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        JpegWriter(out, 1, 1, 90).use { writer ->
            writer.write(byteArrayOf(-1, -1, -1, -1), 1)
            writer.finish()
        }
        out.toByteArray()
    }

    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF
    )
}
