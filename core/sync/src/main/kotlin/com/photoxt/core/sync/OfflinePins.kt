package com.photoxt.core.sync

import androidx.room.withTransaction
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.ThumbnailStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Offline pins (spec §3 `OFFLINE`, §4.4): items kept on the phone for good, one by one from the
 * viewer or a whole tracked folder with *Make album available offline*, which also pins what
 * joins it later. Pins are recorded at once; [SyncWork] fetches their files into
 * `media-offline/` in the background, and unpinned files go back to the cache.
 */
@Singleton
class OfflinePins @Inject constructor(
    private val database: PxtDatabase,
    private val originals: DriveOriginals,
    private val store: ThumbnailStore,
    private val work: SyncWork,
    private val clock: Clock
) {
    private val cache = database.driveCacheDao()
    private val drive = database.driveDao()

    fun pinned(mediaId: String): Flow<Boolean> = cache.pinnedFlow(mediaId)

    suspend fun setPinned(mediaIds: List<String>, pinned: Boolean) {
        mediaIds.chunked(MAX_SQL_VARIABLES).forEach {
            if (pinned) cache.pin(it, clock.millis()) else cache.unpin(it)
        }
        work.fetchOffline()
    }

    /** Keeps every item below tracked folder [albumId] offline, new ones too, or stops. */
    suspend fun setAlbumOffline(albumId: String, offline: Boolean) {
        database.withTransaction {
            drive.setOffline(albumId, offline)
            if (offline) cache.pinTree(albumId, clock.millis()) else cache.unpinTree(albumId)
        }
        work.fetchOffline()
    }

    /** After a sync: pins what joined albums kept offline, and fetches what pins lack. */
    suspend fun refresh() {
        cache.pinOfflineTrees(clock.millis())
        val prefix = store.offlinePrefix
        if (cache.pinsToFetch(prefix).isNotEmpty() || cache.unpinnedOffline(prefix).isNotEmpty()) {
            work.fetchOffline()
        }
    }

    /**
     * The offline worker's pass: unpinned files leave `media-offline/`, then every pin gets its
     * current file, moved from the cache or downloaded. Returns false when a file could not be
     * fetched, so the worker retries later; pins added meanwhile are fetched in the same pass.
     */
    suspend fun fetchPending(): Boolean {
        val prefix = store.offlinePrefix
        cache.unpinnedOffline(prefix).forEach { originals.releaseOffline(it) }
        val failed = HashSet<String>()
        while (true) {
            val pending = cache.pinsToFetch(prefix).filterNot { it in failed }
            if (pending.isEmpty()) break
            pending.forEach { id ->
                runCatching { originals.keepOffline(id) }.onFailure {
                    if (it is CancellationException) throw it
                    failed += id
                    PxtLog.w(TAG, "An offline file could not be fetched", it)
                }
            }
        }
        return failed.isEmpty()
    }

    private companion object {
        const val TAG = "OfflinePins"
    }
}
