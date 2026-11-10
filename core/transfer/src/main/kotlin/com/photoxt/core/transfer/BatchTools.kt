package com.photoxt.core.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import com.photoxt.core.media.ThumbnailStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A batch rename pattern (spec §4.7): `{date}_{n}` turns the third of twelve photos taken on
 * 8 Nov 2026 into `20261108_03.jpg`. Tokens: `{name}` (the old name without its extension),
 * `{date}` (`yyyyMMdd`), `{time}` (`HHmmss`) and `{n}` (the position, zero-padded to the count).
 * The extension stays; characters a file name can't hold become `_`. Two items that end up with
 * one name are told apart by the rename itself (" (1)").
 */
class RenamePattern(private val pattern: String, private val zone: ZoneId) {
    /** The new name of [original], taken at [takenAt]: the [index]th (from 0) of [total]. */
    fun nameFor(original: String, takenAt: Long, index: Int, total: Int): String {
        val (base, extension) = splitName(original)
        val time = Instant.ofEpochMilli(takenAt).atZone(zone)
        val number = (index + 1).toString().padStart(total.toString().length.coerceAtLeast(2), '0')
        val name = pattern
            .replace("{name}", base)
            .replace("{date}", DATE.format(time))
            .replace("{time}", TIME.format(time))
            .replace("{n}", number)
            .replace(UNSAFE, "_")
            .trim()
        return name.ifEmpty { base } + extension
    }

    companion object {
        const val DEFAULT = "{date}_{n}"
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME = DateTimeFormatter.ofPattern("HHmmss")
        private val UNSAFE = Regex("""[\\/:*?"<>|\x00-\x1f]""")
    }
}

/**
 * Converts a photo to JPEG for apps that can't read HEIC, AVIF and the like (spec §4.7). The
 * picture is saved upright, and its capture time, place and camera come along in its EXIF.
 */
internal object JpegConverter {
    suspend fun convert(open: suspend () -> InputStream, out: File) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a photo PhotoXT can read" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth.toLong() * bounds.outHeight, MAX_PIXELS)
        }
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("The photo couldn't be decoded")
        val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
        val upright = bitmap.upright(exif)
        ThumbnailStore.writeAtomically(out) {
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }
        exif?.let { copyTags(it, out) }
    }

    private fun copyTags(from: ExifInterface, out: File) {
        val to = ExifInterface(out)
        TAGS.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }

    private const val QUALITY = 92

    // ponytail: photos over 50 MP are halved until they fit; tiled encoding if full size matters.
    private const val MAX_PIXELS = 50_000_000L

    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH
    )
}

/** This photo turned the way [exif] says it was taken: rotated, and mirrored back if flipped. */
internal fun Bitmap.upright(exif: ExifInterface?): Bitmap {
    val degrees = exif?.rotationDegrees ?: 0
    val flipped = exif?.isFlipped == true
    if (degrees == 0 && !flipped) return this
    val matrix = Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** The smallest power-of-two sample size that brings [pixels] to [max] or fewer. */
internal fun sampleFor(pixels: Long, max: Long): Int {
    var sample = 1
    while (pixels / (sample.toLong() * sample) > max) sample *= 2
    return sample
}

/**
 * The selection as a PDF, one photo a page (spec §4.7, *Export as PDF*, Android `PdfDocument`).
 * Pages are A4, turned to landscape for landscape photos, with each photo fitted inside a margin.
 * Photos are drawn at most [PAGE_PX] on their long edge, which keeps a page under a few MB.
 */
class PdfExport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val device: DeviceStore,
    private val drive: DriveFiles
) {
    /** Writes the photos among [items] to [out]; returns how many pages it wrote. */
    suspend fun export(items: List<FileRef>, out: OutputStream, onPage: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val document = PdfDocument()
            var pages = 0
            try {
                items.filter { it.mime.startsWith("image/") }.forEach { item ->
                    val bitmap = decode(item) ?: return@forEach
                    val landscape = bitmap.width > bitmap.height
                    val width = if (landscape) A4_LONG else A4_SHORT
                    val height = if (landscape) A4_SHORT else A4_LONG
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(width, height, pages + 1).create()
                    )
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        fitInPage(bitmap.width, bitmap.height, width, height, MARGIN),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    document.finishPage(page)
                    bitmap.recycle()
                    pages++
                    onPage(pages)
                }
                document.writeTo(out)
            } finally {
                document.close()
            }
            pages
        }

    private suspend fun decode(item: FileRef): Bitmap? {
        val local = if (item.isDrive) {
            item.mediaId?.let { drive.cachedOriginal(it) } ?: download(item)
        } else {
            null
        }
        return try {
            decodeUpright { local?.inputStream() ?: device.open(checkNotNull(item.uri)) }
        } finally {
            if (local != null && local.parentFile == exportDir) local.delete()
        }
    }

    /** The photo [open] reads, at most [PAGE_PX] on its long edge, upright; null if it isn't. */
    private suspend fun decodeUpright(open: suspend () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(max(1, long / PAGE_PX))
        }
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
        val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
        return bitmap?.upright(exif)
    }

    private val exportDir: File get() = File(context.cacheDir, "pdf-export")

    private suspend fun download(item: FileRef): File {
        val file = File(exportDir, "${item.fileId}.part")
        file.parentFile?.mkdirs()
        drive.download(checkNotNull(item.account), checkNotNull(item.fileId), 0).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    companion object {
        /** A4 in PDF points (1/72 inch). */
        const val A4_SHORT = 595
        const val A4_LONG = 842
        const val MARGIN = 24
        const val PAGE_PX = 1600
    }
}

/** Where a [width] × [height] photo sits on a page: as large as fits inside [margin], centred. */
internal fun fitInPage(
    width: Int,
    height: Int,
    pageWidth: Int,
    pageHeight: Int,
    margin: Int
): RectF {
    val room = RectF(
        margin.toFloat(),
        margin.toFloat(),
        (pageWidth - margin).toFloat(),
        (pageHeight - margin).toFloat()
    )
    val scale = min(room.width() / width, room.height() / height)
    val w = width * scale
    val h = height * scale
    val left = room.left + (room.width() - w) / 2
    val top = room.top + (room.height() - h) / 2
    return RectF(left, top, left + w, top + h)
}
