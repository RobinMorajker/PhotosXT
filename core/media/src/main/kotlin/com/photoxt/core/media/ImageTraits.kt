package com.photoxt.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.net.toUri
import com.photoxt.core.common.PxtLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a photo file holds beyond its pixels (spec §4.2 formats), read from its metadata. */
data class ImageTraits(
    /** The video inside a motion photo, Google or Samsung format, or null. */
    val motionVideo: EmbeddedVideo? = null,
    /** A 360° photo sphere, or null. */
    val panorama: Panorama? = null,
    /** The file carries an HDR gain map (Ultra HDR), which an HDR screen shows brighter. */
    val ultraHdr: Boolean = false
) {
    companion object {
        val NONE = ImageTraits()
    }
}

/** Where the video of a motion photo sits inside the photo's file, in bytes. */
data class EmbeddedVideo(val offset: Long, val length: Long)

/**
 * An equirectangular photo sphere. The image may cover only part of the full sphere; the crop is
 * given as fractions of the full panorama, so a complete sphere is 0, 0, 1, 1.
 */
data class Panorama(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f
)

/**
 * Reads [ImageTraits] from a photo's own bytes: its XMP packet near the start, and for Samsung
 * motion photos the trailer at the end. Only the head and a few trailer blocks are read, never the
 * whole file. A file that cannot be read has no traits.
 */
class ImageTraitsReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun read(contentUri: String, mime: String): ImageTraits = withContext(Dispatchers.IO) {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(contentUri.toUri(), "r")
                ?: return@withContext ImageTraits.NONE
            descriptor.use {
                FileInputStream(it.fileDescriptor).channel.use { channel ->
                    val length = channel.size()
                    val read = { position: Long, count: Int -> channel.readAt(position, count) }
                    val xmp = findXmp(read(0, minOf(length, HEAD_BYTES).toInt()))
                    ImageTraits(
                        motionVideo = xmp?.let { motionVideoFromXmp(it, length) }
                            ?: samsungMotionVideo(length, read),
                        panorama = xmp?.let(::panoramaFromXmp),
                        ultraHdr = mime.mayCarryGainmap() && hasGainmap(contentUri)
                    )
                }
            }
        } catch (e: IOException) {
            PxtLog.w(TAG, "Could not read image metadata", e)
            ImageTraits.NONE
        } catch (e: SecurityException) {
            PxtLog.w(TAG, "No access to image metadata", e)
            ImageTraits.NONE
        }
    }

    /** Ultra HDR: Android 14 and later decode gain maps; a tiny decode is enough to tell. */
    private fun hasGainmap(contentUri: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val options = BitmapFactory.Options().apply { inSampleSize = GAINMAP_PROBE_SAMPLE }
        val bitmap = context.contentResolver.openInputStream(contentUri.toUri())
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false
        return bitmap.hasGainmap().also { bitmap.recycle() }
    }

    private companion object {
        const val TAG = "ImageTraits"
        const val HEAD_BYTES = 256L * 1024
        const val GAINMAP_PROBE_SAMPLE = 32
    }
}

private fun String.mayCarryGainmap() =
    this == "image/jpeg" || this == "image/heic" || this == "image/heif" || this == "image/avif"

private fun FileChannel.readAt(position: Long, count: Int): ByteArray {
    val buffer = ByteBuffer.allocate(count)
    var at = position
    while (buffer.hasRemaining()) {
        val read = read(buffer, at)
        if (read < 0) break
        at += read
    }
    return buffer.array().copyOf(buffer.position())
}
