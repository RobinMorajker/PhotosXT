package com.photoxt.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.DriveCacheDao
import com.photoxt.core.database.DriveCacheEntity
import com.photoxt.core.database.DriveDao
import com.photoxt.core.database.DriveItem
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/** Where a Drive item's full file stands for the viewer. */
sealed interface Original {
    /** Downloading; [fraction] is 0–1. */
    data class Loading(val fraction: Float) : Original

    data class Ready(val file: File) : Original

    data class Failed(val error: Throwable) : Original
}

/**
 * Drive items' full files on the phone (spec §4.4): opened from the cache when there, otherwise
 * streamed into it, resuming a partial download with a Range request and checked by size and MD5
 * before use (spec §0 rule 4). Opening records the time for the retention rule, and the first
 * open of a photo also leaves a ~1600 px preview for next time. A pinned item's file lives in
 * `media-offline/` instead, where the cache cleanup never looks.
 */
@Singleton
class DriveOriginals @Inject constructor(
    private val dao: DriveDao,
    private val cacheDao: DriveCacheDao,
    private val sources: MediaSources,
    private val store: ThumbnailStore,
    private val clock: Clock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val prefetching = ConcurrentHashMap.newKeySet<String>()

    /** A Drive item's BlurHash, the first placeholder of its page (spec §4.4). */
    suspend fun blurhash(mediaId: String): String? = dao.blurhash(mediaId)

    /**
     * The preview of [fileId] at [version], if one was made. Reads the disk, off the main thread,
     * and marks it used for the preview LRU.
     */
    suspend fun preview(fileId: String, version: Long): File? = withContext(Dispatchers.IO) {
        store.preview(fileId, version).takeIf { it.exists() }
            ?.also { it.setLastModified(clock.millis()) }
    }

    /** [mediaId]'s full file if it is on the phone already, current and complete. */
    suspend fun localFile(mediaId: String): File? = withContext(Dispatchers.IO) {
        val item = dao.driveItem(mediaId) ?: return@withContext null
        dao.cached(mediaId)?.takeIf { it.version == item.remoteVersion }
            ?.let { File(it.path) }
            ?.takeIf { complete(it, item) }
    }

    /** The full file of [mediaId], from the phone or downloaded now. */
    fun original(mediaId: String): Flow<Original> = flow {
        val item = dao.driveItem(mediaId) ?: throw IOException("The item is gone")
        // Under the item's lock, so pinning cannot move the file between download and record.
        val target = locks.getOrPut(mediaId) { Mutex() }.withLock {
            val known = dao.cached(mediaId)
            val target = known?.takeIf { it.version == item.remoteVersion }?.let { File(it.path) }
                ?: store.original(
                    item.accountId,
                    item.remoteId,
                    item.remoteVersion,
                    item.mime,
                    offline = cacheDao.isPinned(mediaId)
                )
            if (!complete(target, item)) download(item, target) { emit(Original.Loading(it)) }
            val now = clock.millis()
            dao.saveCache(
                DriveCacheEntity(
                    mediaId = mediaId,
                    path = target.path,
                    version = item.remoteVersion,
                    bytes = target.length(),
                    cachedAt = known?.takeIf { it.path == target.path }?.cachedAt ?: now,
                    lastOpenedAt = now
                )
            )
            target
        }
        emit(Original.Ready(target))
        if (item.kind == MediaKind.IMAGE) scope.launch { makePreview(item, target) }
    }.catch {
        if (it is CancellationException) throw it
        PxtLog.w(TAG, "Could not open a Drive original", it)
        emit(Original.Failed(it))
    }.flowOn(Dispatchers.IO)

    /**
     * Fetches the originals of [mediaIds] in the background (spec §4.4, viewer prefetch), each at
     * most once at a time; a download already running for the page itself is shared.
     */
    fun prefetch(mediaIds: List<String>) {
        mediaIds.filter { prefetching.add(it) }.forEach { id ->
            scope.launch {
                try {
                    original(id).collect()
                } finally {
                    prefetching.remove(id)
                }
            }
        }
    }

    /**
     * Puts [mediaId]'s current file into `media-offline/` (spec §4.4, offline pins): moved there
     * when it is cached already, downloaded otherwise. Throws when the download fails.
     */
    suspend fun keepOffline(mediaId: String) = withContext(Dispatchers.IO) {
        val item = dao.driveItem(mediaId) ?: return@withContext
        val target =
            store.original(item.accountId, item.remoteId, item.remoteVersion, item.mime, true)
        locks.getOrPut(mediaId) { Mutex() }.withLock {
            val known = dao.cached(mediaId)
            val current = known?.takeIf { it.version == item.remoteVersion }?.let { File(it.path) }
            when {
                current == target && complete(target, item) -> Unit
                current != null && complete(current, item) -> moveInto(current, target)
                else -> download(item, target) {}
            }
            val now = clock.millis()
            dao.saveCache(
                DriveCacheEntity(
                    mediaId = mediaId,
                    path = target.path,
                    version = item.remoteVersion,
                    bytes = target.length(),
                    cachedAt = known?.cachedAt ?: now,
                    lastOpenedAt = known?.lastOpenedAt ?: now
                )
            )
        }
    }

    /** Moves an unpinned item's file from `media-offline/` back into the cache. */
    suspend fun releaseOffline(cached: DriveCacheEntity) = withContext(Dispatchers.IO) {
        locks.getOrPut(cached.mediaId) { Mutex() }.withLock {
            val item = dao.driveItem(cached.mediaId) ?: return@withLock
            val from = File(cached.path)
            val to = store.original(item.accountId, item.remoteId, cached.version, item.mime)
            if (from.exists()) {
                moveInto(from, to)
                cacheDao.moveCache(cached.mediaId, to.path)
            } else {
                cacheDao.deleteCache(listOf(cached.mediaId))
            }
        }
    }

    private fun moveInto(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun complete(target: File, item: DriveItem) =
        target.exists() && (item.bytes <= 0 || target.length() == item.bytes)

    private suspend fun download(
        item: DriveItem,
        target: File,
        onProgress: suspend (Float) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var done = if (part.exists()) part.length() else 0L
        if (item.bytes in 1..done) {
            part.delete()
            done = 0
        }
        val ref = MediaRef(
            item.id,
            SourceType.DRIVE,
            null,
            item.modifiedAt,
            item.accountId,
            item.remoteId,
            item.remoteVersion
        )
        sources[SourceType.DRIVE].openStream(ref, done).use { input ->
            FileOutputStream(part, done > 0).use { output ->
                copy(input, output, done, item.bytes, onProgress)
            }
        }
        verify(part, item)
        if (!part.renameTo(target)) throw IOException("Could not move the download into place")
    }

    /** Copies [input] to [output], reporting progress from [done] of [total] bytes. */
    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        done: Long,
        total: Long,
        onProgress: suspend (Float) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = done
        var reported = -1f
        var read = input.read(buffer)
        while (read >= 0) {
            output.write(buffer, 0, read)
            copied += read
            val fraction = if (total > 0) copied.toFloat() / total else 0f
            if (fraction - reported >= PROGRESS_STEP) {
                reported = fraction
                onProgress(fraction)
            }
            read = input.read(buffer)
        }
    }

    /** Checks a finished download against Drive's size and MD5; a bad one is discarded. */
    private fun verify(part: File, item: DriveItem) {
        if (item.bytes > 0 && part.length() != item.bytes) {
            throw IOException("Downloaded ${part.length()} of ${item.bytes} bytes")
        }
        val md5 = item.md5
        if (md5 != null && !md5.equals(md5Of(part), ignoreCase = true)) {
            part.delete()
            throw IOException("The download does not match Drive's checksum")
        }
    }

    private fun makePreview(item: DriveItem, original: File) {
        val preview = store.preview(item.remoteId, item.remoteVersion)
        if (preview.exists()) return
        runCatching { previewOf(original)?.let { ThumbnailStore.writeWebp(preview, it) } }
            .onFailure { PxtLog.w(TAG, "No preview made", it) }
    }

    /** [original] decoded to about [PREVIEW_PX] on its long edge, upright. */
    private fun previewOf(original: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(max(1, long / PREVIEW_PX))
        }
        val decoded = if (long > 0) BitmapFactory.decodeFile(original.path, options) else null
        return decoded?.let {
            val factor = PREVIEW_PX.toFloat() / max(it.width, it.height)
            val sized = if (factor < 1f) {
                it.scale((it.width * factor).roundToInt(), (it.height * factor).roundToInt())
            } else {
                it
            }
            sized.oriented(ExifInterface(original))
        }
    }

    private companion object {
        const val TAG = "DriveOriginals"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP = 0.02f
        const val PREVIEW_PX = 1600

        fun md5Of(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** [this] turned and flipped as [exif] says, since a WebP preview carries no orientation. */
internal fun Bitmap.oriented(exif: ExifInterface): Bitmap {
    val degrees = exif.rotationDegrees
    if (degrees == 0 && !exif.isFlipped) return this
    val matrix = Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * A Drive item's full file as a Coil model: downloaded into the cache first if needed. For the
 * slideshow and Compare; the viewer page uses the file itself, which allows tiled decoding.
 */
data class DriveOriginalRef(val mediaId: String, val fileId: String, val version: Long)

/** The memory-cache key of a Drive item's full photo. */
fun driveFullKey(fileId: String, version: Long): String = "drive:$fileId@$version#full"

class DriveOriginalFetcher(
    private val data: DriveOriginalRef,
    private val originals: DriveOriginals
) : coil3.fetch.Fetcher {
    override suspend fun fetch(): coil3.fetch.FetchResult {
        val result = originals.original(data.mediaId)
            .first { it is Original.Ready || it is Original.Failed }
        if (result is Original.Failed) throw result.error
        val file = (result as Original.Ready).file
        return coil3.fetch.SourceFetchResult(
            source = coil3.decode.ImageSource(file.toOkioPath(), okio.FileSystem.SYSTEM),
            mimeType = null,
            dataSource = coil3.decode.DataSource.DISK
        )
    }

    /** Register on the app's image loader, with [DriveOriginalKeyer]. */
    class Factory(private val originals: DriveOriginals) :
        coil3.fetch.Fetcher.Factory<DriveOriginalRef> {
        override fun create(
            data: DriveOriginalRef,
            options: coil3.request.Options,
            imageLoader: coil3.ImageLoader
        ) = DriveOriginalFetcher(data, originals)
    }
}

class DriveOriginalKeyer : coil3.key.Keyer<DriveOriginalRef> {
    override fun key(data: DriveOriginalRef, options: coil3.request.Options) =
        driveFullKey(data.fileId, data.version)
}
