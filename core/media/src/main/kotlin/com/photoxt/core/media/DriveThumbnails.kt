package com.photoxt.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.DriveDao
import com.photoxt.core.database.DriveItem
import com.photoxt.core.database.DriveThumbnailEntity
import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.DriveHttpException
import com.photoxt.core.model.MediaKind
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Path.Companion.toOkioPath

/** A Drive item's grid thumbnail as a Coil model; [fileId] and [version] key its file. */
data class DriveThumbnail(val mediaId: String, val fileId: String, val version: Long)

/** The memory-cache key of a Drive grid thumbnail, so other requests can show it meanwhile. */
fun driveThumbnailKey(fileId: String, version: Long): String = "drive:$fileId@$version"

/**
 * Makes Drive items' grid thumbnails on the phone (spec §4.3): Drive's own thumbnail at about
 * 512 px (a tiny authorised download; the link is short-lived, so it is refreshed once when it
 * fails), else the JPEG's embedded EXIF thumbnail from its first 128 KB, a video frame read over
 * the authorised stream, or the downscaled file. Each is saved as a WebP about 384 px on its
 * short edge, with a BlurHash in the database. Visible tiles go first (see [ThumbnailQueue]).
 */
@Singleton
class DriveThumbnails @Inject constructor(
    private val dao: DriveDao,
    private val http: DriveHttp,
    private val tokens: DriveTokens,
    private val store: ThumbnailStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ponytail: a fixed number of downloads at once; tune it, or follow the network type, if
    // thumbnails trail a fast scroll on slow connections.
    private val queue = ThumbnailQueue(scope, WORKERS) { make(it) }

    /** The grid thumbnail file of [thumbnail], made first (at visible priority) if needed. */
    suspend fun gridFile(thumbnail: DriveThumbnail): File {
        val file = store.grid(thumbnail.fileId, thumbnail.version)
        if (!file.exists()) queue.enqueue(thumbnail.mediaId, ThumbnailPriority.VISIBLE).await()
        if (!file.exists()) throw IOException("Drive has no thumbnail for this item")
        return file
    }

    /** Queues the thumbnail of [mediaId]; the result completes when it is made or failed. */
    fun enqueue(mediaId: String, priority: ThumbnailPriority): Deferred<Unit> =
        queue.enqueue(mediaId, priority)

    private suspend fun make(mediaId: String) {
        val item = dao.driveItem(mediaId) ?: return
        val file = store.grid(item.remoteId, item.remoteVersion)
        val bitmap = if (file.exists()) {
            BitmapFactory.decodeFile(file.path)
        } else {
            val fetched = fetch(item)
            if (fetched == null) {
                dao.saveThumbnail(
                    DriveThumbnailEntity(mediaId, item.remoteVersion, null, true, now())
                )
                throw IOException("No source gave a thumbnail")
            }
            fetched.toGridSize().also { ThumbnailStore.writeWebp(file, it) }
        } ?: throw IOException("Unreadable thumbnail")
        dao.saveThumbnail(
            DriveThumbnailEntity(mediaId, item.remoteVersion, blurHashOf(bitmap), false, now())
        )
    }

    private suspend fun fetch(item: DriveItem): Bitmap? {
        val link = item.thumbnailLink
        if (link != null) fromLink(item, link)?.let { return it }
        return when {
            item.kind == MediaKind.VIDEO -> videoFrame(item)
            item.mime.equals("image/jpeg", ignoreCase = true) -> exifThumbnail(item) ?: whole(item)
            else -> whole(item)
        }
    }

    private suspend fun fromLink(item: DriveItem, link: String): Bitmap? {
        val bytes = try {
            download(item.accountId, sized(link))
        } catch (e: DriveHttpException) {
            if (e.code !in STALE_LINK_CODES) throw e
            // The link expired: ask Drive for a new one, once.
            val fresh = http.api.get(DriveAccount(item.accountId), item.remoteId, LINK_FIELDS)
            val renewed = fresh.thumbnailLink?.takeIf { fresh.hasThumbnail }
            dao.setThumbnailLink(item.id, renewed)
            renewed?.let { download(item.accountId, sized(it)) } ?: return null
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun exifThumbnail(item: DriveItem): Bitmap? = runCatching {
        val head = http.open(item.accountId, http.mediaUrl(item.remoteId), 0, EXIF_BYTES - 1)
            .use { it.body.bytes() }
        ExifInterface(head.inputStream()).thumbnailBitmap
    }.onFailure { PxtLog.d(TAG, "No EXIF thumbnail: ${it.javaClass.simpleName}") }.getOrNull()

    private suspend fun videoFrame(item: DriveItem): Bitmap? = withContext(Dispatchers.IO) {
        val token = tokens.token(item.accountId)
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(
                    http.mediaUrl(item.remoteId).toString(),
                    mapOf("Authorization" to "Bearer $token")
                )
                retriever.getScaledFrameAtTime(
                    FRAME_AT_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    LINK_SIZE_PX,
                    LINK_SIZE_PX
                )
            }
        }.onFailure { PxtLog.w(TAG, "No video frame", it) }.getOrNull()
    }

    private suspend fun whole(item: DriveItem): Bitmap? {
        if (item.kind != MediaKind.IMAGE || item.bytes > MAX_WHOLE_BYTES) return null
        val bytes = download(item.accountId, http.mediaUrl(item.remoteId).toString())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = Integer.highestOneBit(
            max(1, min(bounds.outWidth, bounds.outHeight) / GRID_SHORT_EDGE_PX)
        )
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private suspend fun download(accountId: String, url: String): ByteArray =
        http.open(accountId, url.toHttpUrl()).use { response ->
            withContext(Dispatchers.IO) { response.body.bytes() }
        }

    class Fetcher(private val data: DriveThumbnail, private val thumbnails: DriveThumbnails) :
        coil3.fetch.Fetcher {
        override suspend fun fetch(): FetchResult {
            val file = thumbnails.gridFile(data)
            return SourceFetchResult(
                source = ImageSource(file.toOkioPath(), okio.FileSystem.SYSTEM),
                mimeType = "image/webp",
                dataSource = DataSource.DISK
            )
        }

        /** Register on the app's image loader, with [DriveThumbnailKeyer]. */
        class Factory(private val thumbnails: DriveThumbnails) :
            coil3.fetch.Fetcher.Factory<DriveThumbnail> {
            override fun create(data: DriveThumbnail, options: Options, imageLoader: ImageLoader) =
                Fetcher(data, thumbnails)
        }
    }

    private companion object {
        const val TAG = "DriveThumbnails"
        const val WORKERS = 6
        const val LINK_SIZE_PX = 512
        const val GRID_SHORT_EDGE_PX = 384
        const val BLURHASH_PX = 32
        const val EXIF_BYTES = 128 * 1024L
        const val FRAME_AT_US = 1_000_000L
        const val MAX_WHOLE_BYTES = 30L * 1024 * 1024
        const val LINK_FIELDS = "id,thumbnailLink,hasThumbnail"
        val STALE_LINK_CODES = setOf(401, 403, 404, 410)
        val SIZE_SUFFIX = Regex("=s\\d+(-[\\w-]*)?$")

        fun now() = System.currentTimeMillis()

        /** Drive's thumbnail link at about [LINK_SIZE_PX] on the long edge. */
        fun sized(link: String): String = if (SIZE_SUFFIX.containsMatchIn(link)) {
            link.replace(SIZE_SUFFIX, "=s$LINK_SIZE_PX")
        } else {
            "$link=s$LINK_SIZE_PX"
        }

        fun Bitmap.toGridSize(): Bitmap {
            val short = min(width, height)
            if (short <= GRID_SHORT_EDGE_PX) return this
            val factor = GRID_SHORT_EDGE_PX.toFloat() / short
            return scale((width * factor).roundToInt(), (height * factor).roundToInt())
        }

        fun blurHashOf(bitmap: Bitmap): String {
            val factor = BLURHASH_PX.toFloat() / max(bitmap.width, bitmap.height)
            val w = max(1, (bitmap.width * factor).roundToInt())
            val h = max(1, (bitmap.height * factor).roundToInt())
            val small = bitmap.scale(w, h)
            val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
            return BlurHash.encode(pixels, w, h)
        }
    }
}

class DriveThumbnailKeyer : Keyer<DriveThumbnail> {
    override fun key(data: DriveThumbnail, options: Options) =
        driveThumbnailKey(data.fileId, data.version)
}
