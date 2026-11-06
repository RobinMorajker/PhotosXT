package com.photoxt.core.media

import android.content.Context
import androidx.core.net.toUri
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.model.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response

/** One photo or video wherever it lives: what a [MediaSource] needs to reach its bytes. */
data class MediaRef(
    val id: String,
    val source: SourceType,
    val contentUri: String?,
    val modifiedAt: Long,
    val accountId: String? = null,
    val remoteId: String? = null,
    val remoteVersion: Long? = null
)

/**
 * Where an item's bytes come from (spec §5.3). Feature code asks this, never MediaStore or Drive
 * directly, so more sources (OneDrive, NAS, WebDAV) can come later without touching it.
 * Listing changes stays with each source's sync (MediaStore indexer, Drive scanner) until the
 * Changes API sync in Phase 4; copy, move and delete arrive with file operations in Phase 5.
 */
interface MediaSource {
    val type: SourceType

    /** Whether PhotoXT may change the item where it lives (used from Phase 5). */
    val canWrite: Boolean

    /** Whether the item can be shared as it is, without a PhotoXT link (Phase 11). */
    val canShare: Boolean

    /** A Coil model of the item's grid thumbnail. */
    fun thumbnail(item: MediaRef): Any? = thumbnailOf(item)

    /** The item's bytes from [offset] on. The caller closes the stream. */
    suspend fun openStream(item: MediaRef, offset: Long = 0): InputStream
}

/** The grid thumbnail model of [item], for any source. */
fun thumbnailOf(item: MediaRef): Any? = when (item.source) {
    SourceType.DEVICE -> item.contentUri?.let { DeviceThumbnail(it, item.modifiedAt) }

    SourceType.DRIVE -> {
        val fileId = item.remoteId
        val version = item.remoteVersion
        if (fileId != null && version != null) DriveThumbnail(item.id, fileId, version) else null
    }
}

@Singleton
class MediaSources @Inject constructor(device: DeviceSource, drive: DriveSource) {
    private val sources = mapOf(SourceType.DEVICE to device, SourceType.DRIVE to drive)

    operator fun get(type: SourceType): MediaSource = sources.getValue(type)
}

/** The phone's own media, through MediaStore. */
class DeviceSource @Inject constructor(@param:ApplicationContext private val context: Context) :
    MediaSource {
    override val type = SourceType.DEVICE
    override val canWrite = true
    override val canShare = true

    override suspend fun openStream(item: MediaRef, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val uri = checkNotNull(item.contentUri) { "A device item without a content URI" }
            val stream = context.contentResolver.openInputStream(uri.toUri())
                ?: throw IOException("MediaStore could not open the item")
            stream.apply { skipFully(offset) }
        }
}

/** One account's Drive, over authorised HTTP with Range requests (spec §4.4). */
class DriveSource @Inject constructor(private val http: DriveHttp) : MediaSource {
    override val type = SourceType.DRIVE

    // ponytail: read-only until the `drive` scope upgrade with file operations (Phase 5).
    override val canWrite = false
    override val canShare = false

    override suspend fun openStream(item: MediaRef, offset: Long): InputStream {
        val accountId = checkNotNull(item.accountId) { "A Drive item without an account" }
        val fileId = checkNotNull(item.remoteId) { "A Drive item without a file id" }
        val response = http.open(accountId, http.mediaUrl(fileId), offset)
        val stream = ResponseStream(response)
        // A server that ignores the range answers 200 with the whole file.
        if (response.code != DriveHttp.HTTP_PARTIAL) {
            withContext(Dispatchers.IO) { stream.skipFully(offset) }
        }
        return stream
    }

    private class ResponseStream(private val response: Response) :
        FilterInputStream(response.body.byteStream()) {
        override fun close() = response.close()
    }
}

private fun InputStream.skipFully(count: Long) {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped <= 0) {
            if (read() < 0) throw IOException("The stream ended before the requested offset")
            left--
        } else {
            left -= skipped
        }
    }
}
