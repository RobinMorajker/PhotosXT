package com.photoxt.core.media

import com.photoxt.core.database.DriveCacheEntity
import com.photoxt.core.database.DriveItemFiles
import com.photoxt.core.database.DriveOrphanEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.MediaKind
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bytes PhotoXT keeps on the phone for Drive items, by kind (spec §4.4, Storage screen). */
data class StorageBreakdown(
    val thumbnails: Long = 0,
    val previews: Long = 0,
    val originals: Long = 0,
    val offline: Long = 0,
    val video: Long = 0
) {
    val total: Long get() = thumbnails + previews + originals + offline + video

    operator fun plus(other: StorageBreakdown) = StorageBreakdown(
        thumbnails + other.thumbnails,
        previews + other.previews,
        originals + other.originals,
        offline + other.offline,
        video + other.video
    )
}

/** One tracked Drive folder's share, nested albums included. */
data class AlbumStorage(
    val albumId: String,
    val title: String,
    val items: Int,
    val usage: StorageBreakdown
)

data class AccountStorage(
    val accountId: String,
    val email: String,
    val albums: List<AlbumStorage>
) {
    val usage: StorageBreakdown
        get() = albums.fold(StorageBreakdown()) { sum, album -> sum + album.usage }
}

/** Every account's share, and [removed]: files of items gone from Drive, kept 7 days. */
data class StorageUsage(val accounts: List<AccountStorage>, val removed: Long) {
    val usage: StorageBreakdown
        get() = accounts.fold(StorageBreakdown()) { sum, account -> sum + account.usage }

    val total: Long get() = usage.total + removed
}

/**
 * The Drive cache's lifecycle (spec §4.4, §4.3 cleanup): the daily cleanup, *Free up now*, *Clear
 * cache for this album*, and what the Storage screen shows. Grid thumbnails stay while their item
 * exists; originals pinned offline are never touched here.
 */
@Singleton
class DriveCache @Inject constructor(
    database: PxtDatabase,
    private val store: ThumbnailStore,
    private val video: DriveVideo,
    private val clock: Clock
) {
    private val cacheDao = database.driveCacheDao()
    private val driveDao = database.driveDao()

    /**
     * The daily pass. Orphans past their grace go. Originals not opened for [retentionMs] (null:
     * kept for good) are deleted unless pinned, so their items return to thumbnail-only, and so
     * are video spans not played for that long. Then the remaining originals stay under
     * [originalsCap] and previews under [previewsCap] bytes, least recently used evicted first.
     * Returns the bytes freed.
     */
    suspend fun cleanUp(retentionMs: Long?, originalsCap: Long, previewsCap: Long): Long =
        withContext(Dispatchers.IO) {
            val now = clock.millis()
            var freed = purge(cacheDao.dueOrphans(now))
            if (retentionMs != null) {
                freed += evict(cacheDao.expired(now - retentionMs))
                freed += video.trim(now - retentionMs)
            }
            freed + capOriginals(originalsCap) + capPreviews(previewsCap)
        }

    /** Deletes the files of orphans that are due: sync calls it after applying changes. */
    suspend fun purgeDueOrphans(): Long =
        withContext(Dispatchers.IO) { purge(cacheDao.dueOrphans(clock.millis())) }

    /**
     * *Free up now*: every original that is not pinned, every preview, the video cache, and the
     * files of items removed in Drive. Grid thumbnails and offline pins stay.
     */
    suspend fun freeUpNow(): Long = withContext(Dispatchers.IO) {
        purge(cacheDao.dueOrphans(Long.MAX_VALUE)) +
            evict(cacheDao.unpinnedUnder(null)) +
            delete(store.previews.files()) +
            video.clear()
    }

    /** *Clear cache for this album*: [rootId]'s unpinned originals, previews and video spans. */
    suspend fun clearAlbum(rootId: String): Long = withContext(Dispatchers.IO) {
        val items = cacheDao.itemFiles(rootId)
        evict(cacheDao.unpinnedUnder(rootId)) +
            delete(items.map { store.preview(it.remoteId, it.remoteVersion) }) +
            video.remove(items.videoKeys())
    }

    /** Deletes every file of [items] now: the user removed their album or account. */
    suspend fun deleteNow(items: List<DriveItemFiles>): Long = withContext(Dispatchers.IO) {
        val files = items.flatMap {
            listOfNotNull(
                store.grid(it.remoteId, it.remoteVersion),
                store.preview(it.remoteId, it.remoteVersion),
                it.cachePath?.let(::File)
            )
        }
        delete(files) + video.remove(items.videoKeys())
    }

    /** Deletes the files of [accountId]'s orphans now: the account was removed. */
    suspend fun purgeAccount(accountId: String): Long =
        withContext(Dispatchers.IO) { purge(cacheDao.orphansOfAccount(accountId)) }

    /** What each account and tracked folder keeps on the phone. Reads every file's size. */
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val videoBytes = video.cachedBytes()
        val items = cacheDao.itemFiles(null).groupBy { it.rootId }
        val albums = driveDao.trackedAlbums().groupBy { it.accountId }
        val accounts = driveDao.accountList().map { account ->
            val shares = albums[account.id].orEmpty().map { album ->
                val files = items[album.id].orEmpty()
                val usage = files.fold(StorageBreakdown()) { sum, file ->
                    sum +
                        file.usage(videoBytes)
                }
                AlbumStorage(album.id, album.title, files.size, usage)
            }
            AccountStorage(account.id, account.email, shares)
        }
        val removed = cacheDao.dueOrphans(Long.MAX_VALUE).sumOf { orphan ->
            orphan.files().sumOf { it.length() } +
                (videoBytes[video.cacheKey(orphan.fileId, orphan.version)] ?: 0L)
        }
        StorageUsage(accounts, removed)
    }

    private fun DriveItemFiles.usage(videoBytes: Map<String, Long>): StorageBreakdown {
        val cached = cacheBytes ?: 0L
        return StorageBreakdown(
            thumbnails = store.grid(remoteId, remoteVersion).length(),
            previews = store.preview(remoteId, remoteVersion).length(),
            originals = if (pinned) 0L else cached,
            offline = if (pinned) cached else 0L,
            video = if (kind == MediaKind.VIDEO) {
                videoBytes[video.cacheKey(remoteId, remoteVersion)] ?: 0L
            } else {
                0L
            }
        )
    }

    private fun DriveOrphanEntity.files(): List<File> = listOfNotNull(
        store.grid(fileId, version),
        store.preview(fileId, version),
        cachePath?.let(::File)
    )

    private fun List<DriveItemFiles>.videoKeys(): List<String> =
        filter { it.kind == MediaKind.VIDEO }.map { video.cacheKey(it.remoteId, it.remoteVersion) }

    private suspend fun purge(orphans: List<DriveOrphanEntity>): Long {
        if (orphans.isEmpty()) return 0L
        val freed = delete(orphans.flatMap { it.files() }) +
            video.remove(orphans.map { video.cacheKey(it.fileId, it.version) })
        orphans.chunked(MAX_SQL_VARIABLES).forEach { cacheDao.deleteOrphans(it) }
        return freed
    }

    /** Drops cached originals [rows]; their items return to thumbnail-only. */
    private suspend fun evict(rows: List<DriveCacheEntity>): Long {
        if (rows.isEmpty()) return 0L
        rows.map { it.mediaId }.chunked(MAX_SQL_VARIABLES).forEach { cacheDao.deleteCache(it) }
        return delete(rows.map { File(it.path) })
    }

    /** Evicts the least recently opened originals until the rest fit in [cap] bytes. */
    private suspend fun capOriginals(cap: Long): Long =
        evict(overCap(cacheDao.unpinnedNewestFirst(), cap) { it.bytes })

    /** Deletes the least recently used previews until the rest fit in [cap] bytes. */
    private fun capPreviews(cap: Long): Long {
        val newestFirst = store.previews.files().sortedByDescending { it.lastModified() }
        return delete(overCap(newestFirst, cap) { it.length() })
    }

    /** What is in [cap] no longer: [newestFirst] from the first entry that does not fit. */
    private fun <T> overCap(newestFirst: List<T>, cap: Long, bytes: (T) -> Long): List<T> {
        var kept = 0L
        val fit = newestFirst.indexOfFirst { entry ->
            kept += bytes(entry)
            kept > cap
        }
        return if (fit < 0) emptyList() else newestFirst.subList(fit, newestFirst.size)
    }

    /** Deletes [files]; returns the bytes of those that were there. */
    private fun delete(files: List<File>): Long = files.sumOf { file ->
        val bytes = file.length()
        if (file.delete()) bytes else 0L
    }

    private fun File.files(): List<File> = walkTopDown().filter { it.isFile }.toList()
}
