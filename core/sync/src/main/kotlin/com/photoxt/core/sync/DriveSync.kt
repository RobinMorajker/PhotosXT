package com.photoxt.core.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.photoxt.core.auth.ConsentRequiredException
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.media.DriveCache
import com.photoxt.core.media.DriveThumbnails
import com.photoxt.core.media.ThumbnailPriority
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.TrackedFolderState
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drive albums' lifecycle (spec §4.2, §4.3, §5.5): adding a folder (album at once, scan in the
 * background), keeping every account in step through its Changes feed, relinking or removing a
 * folder, removing a whole account with every file PhotoXT made for it, switching subfolders
 * between nested and merged, and filling grid thumbnails behind the visible ones.
 *
 * Sync runs on app start and return to the foreground (at most every 30 s), on pull-to-refresh,
 * every 6 hours ([SyncWork]) and after PhotoXT's own writes ([syncAccount]). One account's scans,
 * syncs and removals run one at a time, so a scan's sweep never races a change. Scans
 * interrupted by the app being closed, or asked for by a change, resume at the next start.
 */
@Singleton
class DriveSync @Inject constructor(
    private val database: PxtDatabase,
    private val scanner: DriveScanner,
    private val changes: DriveChanges,
    private val thumbnails: DriveThumbnails,
    private val store: ThumbnailStore,
    private val cache: DriveCache,
    private val pins: OfflinePins,
    private val auth: DriveAuth,
    private val conditions: DeviceConditions,
    private val work: SyncWork,
    private val clock: Clock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drive = database.driveDao()
    private val cacheDao = database.driveCacheDao()
    private val scans = ConcurrentHashMap<String, Job>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var lastForegroundSync = Long.MIN_VALUE

    private val fills = ConcurrentHashMap<String, Job>()

    /** Whether the phone has a network; Drive albums show "offline" without one. */
    val online: StateFlow<Boolean> get() = conditions.online

    /** Call once, from `Application.onCreate`. */
    fun start() {
        conditions.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
            }
        )
        scope.launch {
            // WorkManager opens its database on first use: off the main thread.
            work.schedule()
            drive.trackedFoldersIn(TrackedFolderState.SCANNING).forEach { scan(it.albumId) }
            fillThumbnails(rootId = null)
        }
    }

    /** App start or return to the foreground: every account, at most every 30 s (spec §4.3). */
    private fun onForeground() {
        val now = clock.millis()
        if (now - lastForegroundSync < FOREGROUND_DEBOUNCE_MS) return
        lastForegroundSync = now
        scope.launch { syncAll() }
    }

    /**
     * Pull-to-refresh: syncs [accountId], or every account, and returns once their changes are
     * in. Rescans it asks for go on in the background. False when Drive could not be reached.
     */
    suspend fun refresh(accountId: String? = null): Boolean = scope.async {
        val ids = accountId?.let(::listOf) ?: drive.accountList().map { it.id }
        ids.map { syncAccount(it) }.all { it }
    }.await()

    /** Every account's changes: the 6-hour job and the foreground trigger. */
    suspend fun syncAll(): Boolean = drive.accountList().map { syncAccount(it.id) }.all { it }

    /**
     * Applies [accountId]'s Drive changes (spec §5.5), then rescans trees whose shape changed,
     * pins what joined albums kept offline, deletes files that are due and makes missing
     * thumbnails. Call after every write PhotoXT makes to Drive. False when Drive could not be
     * reached or needs the user's consent again.
     */
    suspend fun syncAccount(accountId: String): Boolean {
        val outcome = runCatching { locked(accountId) { changes.sync(accountId) } }
        val error = outcome.exceptionOrNull()
        when {
            error is CancellationException -> throw error

            error is ConsentRequiredException -> PxtLog.w(TAG, "Sync waits for Drive consent")

            error != null -> PxtLog.w(TAG, "Sync failed", error)

            // Null: nothing of this account is tracked.
            else -> outcome.getOrNull()?.let { synced(it) }
        }
        return error == null
    }

    private suspend fun synced(result: ChangesResult) {
        if (result.changes > 0) {
            PxtLog.i(
                TAG,
                "Synced ${result.changes} changes: ${result.written} written, " +
                    "${result.removed} removed, ${result.rescan.size} to rescan, " +
                    "${result.unavailable.size} unavailable"
            )
        }
        result.rescan.forEach { scan(it) }
        settle()
    }

    /**
     * What follows a sync or a scan of [rootId]: offline pins, due orphans, and missing
     * thumbnails, that album's first.
     */
    private suspend fun settle(rootId: String? = null) {
        runCatching {
            pins.refresh()
            cache.purgeDueOrphans()
        }.onFailure {
            if (it is CancellationException) throw it
            PxtLog.w(TAG, "Could not settle the cache after a sync", it)
        }
        if (rootId != null) fillThumbnails(rootId)
        fillThumbnails(rootId = null)
    }

    /**
     * Tracks Drive folder [folderId] ([name], in shared drive [driveId] if any) of [accountId]:
     * its album exists at once and fills as the scan goes. Returns the album id, the existing
     * one if the folder is an album already.
     */
    suspend fun addFolder(
        accountId: String,
        folderId: String,
        name: String,
        driveId: String?,
        merge: Boolean
    ): String {
        drive.trackedByFolder(accountId, folderId)?.let { return it.albumId }
        val albumId = driveAlbumId(accountId, folderId)
        database.withTransaction {
            if (drive.trackedFolder(albumId) == null) {
                database.mediaIndexDao().upsertAlbums(
                    listOf(
                        AlbumEntity(
                            id = albumId,
                            type = AlbumType.DRIVE_FOLDER,
                            sourceRef = "drive:$name/",
                            title = name,
                            accountId = accountId,
                            rootId = albumId
                        )
                    )
                )
                drive.insertTracked(
                    TrackedFolderEntity(
                        albumId,
                        accountId,
                        folderId,
                        driveId,
                        merge,
                        TrackedFolderState.SCANNING,
                        null
                    )
                )
            }
        }
        scan(albumId)
        return albumId
    }

    /**
     * *Relink* (spec §4.3, lost access): album [albumId] follows Drive folder [folderId] from now
     * on, its own or another one. False when that folder is another album already.
     */
    suspend fun relink(albumId: String, folderId: String, driveId: String?): Boolean {
        val tracked = drive.trackedFolder(albumId) ?: return false
        val other = drive.trackedByFolder(tracked.accountId, folderId)
        if (other != null && other.albumId != albumId) return false
        locked(tracked.accountId) { drive.relink(albumId, folderId, driveId) }
        scan(albumId)
        return true
    }

    /** Scans tracked folder [albumId] again, unless a scan of it is running. */
    fun scan(albumId: String) {
        scans[albumId]?.takeIf { it.isActive }?.let { return }
        scans[albumId] = scope.launch {
            val accountId = drive.trackedFolder(albumId)?.accountId ?: return@launch
            val result = runCatching {
                locked(accountId) {
                    val tracked = drive.trackedFolder(albumId) ?: return@locked null
                    drive.setTrackedState(albumId, TrackedFolderState.SCANNING, tracked.scannedAt)
                    scanner.scan(tracked)
                }
            }
            result.onSuccess {
                if (it == null) return@onSuccess
                PxtLog.i(
                    TAG,
                    "Scanned: ${it.folders} folders, ${it.items} items, ${it.removed} gone" +
                        if (it.unavailable) ", folder unavailable" else ""
                )
                settle(albumId)
            }
            result.onFailure { error ->
                if (error is CancellationException) throw error
                // Left in SCANNING: it resumes at the next start or reconnect.
                if (error is ConsentRequiredException) {
                    PxtLog.w(TAG, "Scan waits for Drive consent")
                } else {
                    PxtLog.w(TAG, "Scan failed", error)
                }
            }
        }
    }

    /** After the user reconnected [accountId]: resume what waited for it. */
    fun reconnected(accountId: String) {
        scope.launch {
            drive.trackedFoldersIn(TrackedFolderState.SCANNING)
                .filter { it.accountId == accountId }
                .forEach { scan(it.albumId) }
            syncAccount(accountId)
        }
    }

    /** Shows [albumId]'s subfolders as nested albums, or merges them into it, then rescans. */
    suspend fun setMerge(albumId: String, merge: Boolean) {
        drive.setMerge(albumId, merge)
        scan(albumId)
    }

    /** Stops tracking [albumId]: its albums, items, thumbnails and cached files go. */
    suspend fun removeFolder(albumId: String) {
        scans.remove(albumId)?.cancel()
        val tracked = drive.trackedFolder(albumId) ?: return
        val files = locked(tracked.accountId) {
            val files = cacheDao.itemFiles(albumId)
            drive.removeTracked(albumId)
            drive.forgetChangesTokenIfUntracked(tracked.accountId)
            files
        }
        scope.launch { cache.deleteNow(files) }
    }

    /** Removes [accountId] and everything PhotoXT keeps of its Drive, and revokes its grant. */
    suspend fun removeAccount(accountId: String) {
        val tracked = drive.trackedOf(accountId)
        tracked.forEach { scans.remove(it)?.cancel() }
        val files = locked(accountId) {
            val files = tracked.flatMap { cacheDao.itemFiles(it) }
            auth.revoke(accountId)
            drive.removeAccount(accountId)
            files
        }
        cache.deleteNow(files)
        cache.purgeAccount(accountId)
        store.originals(accountId).forEach { it.deleteRecursively() }
    }

    private suspend fun <T> locked(accountId: String, block: suspend () -> T): T =
        locks.getOrPut(accountId) { Mutex() }.withLock { block() }

    /**
     * Makes missing grid thumbnails behind the visible ones, newest first, [BATCH] at a time:
     * tracked folder [rootId]'s at album priority, or every album's in the background, one pass
     * of each at a time. Pauses on metered networks and in battery saver (spec §4.3); visible
     * tiles never wait.
     */
    private fun fillThumbnails(rootId: String?) {
        val key = rootId ?: ALL
        if (fills[key]?.isActive == true) return
        val priority = if (rootId == null) ThumbnailPriority.BACKGROUND else ThumbnailPriority.ALBUM
        fills[key] = scope.launch {
            while (true) {
                while (conditions.pauseBackgroundWork()) delay(PAUSE_RECHECK_MS)
                val ids = drive.missingThumbnails(rootId, BATCH)
                if (ids.isEmpty()) return@launch
                val results = ids.map { thumbnails.enqueue(it, priority) }
                    .map { runCatching { it.await() } }
                // Nothing worked (offline, or consent needed): stop until the next trigger.
                if (results.all { it.isFailure }) return@launch
            }
        }
    }

    private companion object {
        const val TAG = "DriveSync"
        const val BATCH = 200
        const val PAUSE_RECHECK_MS = 30_000L
        const val FOREGROUND_DEBOUNCE_MS = 30_000L
        const val ALL = "*"
    }
}
