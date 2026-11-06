package com.photoxt.core.sync

import androidx.room.withTransaction
import com.photoxt.core.auth.ConsentRequiredException
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.media.DriveThumbnails
import com.photoxt.core.media.ThumbnailPriority
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.TrackedFolderState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drive albums' lifecycle (spec §4.2, §4.3): adding a folder (album at once, scan in the
 * background), removing it or a whole account with every file PhotoXT made for it, switching
 * subfolders between nested and merged, and filling grid thumbnails behind the visible ones.
 * Scans interrupted by the app being closed resume at the next start. Incremental sync through
 * the Changes API, its triggers and the 6-hour job come in Phase 4.
 */
@Singleton
class DriveSync @Inject constructor(
    private val database: PxtDatabase,
    private val scanner: DriveScanner,
    private val thumbnails: DriveThumbnails,
    private val store: ThumbnailStore,
    private val auth: DriveAuth,
    private val conditions: DeviceConditions
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drive = database.driveDao()
    private val scans = ConcurrentHashMap<String, Job>()

    /** Whether the phone has a network; Drive albums show "offline" without one. */
    val online: StateFlow<Boolean> get() = conditions.online

    /** Call once, from `Application.onCreate`. */
    fun start() {
        conditions.start()
        scope.launch {
            drive.trackedFoldersIn(TrackedFolderState.SCANNING).forEach { scan(it.albumId) }
            fillThumbnails(rootId = null, priority = ThumbnailPriority.BACKGROUND)
        }
    }

    /**
     * Tracks Drive folder [folderId] ([name], in shared drive [driveId] if any) of [accountId]:
     * its album exists at once and fills as the scan goes. Returns the album id.
     */
    suspend fun addFolder(
        accountId: String,
        folderId: String,
        name: String,
        driveId: String?,
        merge: Boolean
    ): String {
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

    /** Scans tracked folder [albumId] again, unless a scan of it is running. */
    fun scan(albumId: String) {
        scans[albumId]?.takeIf { it.isActive }?.let { return }
        scans[albumId] = scope.launch {
            val tracked = drive.trackedFolder(albumId) ?: return@launch
            drive.setTrackedState(albumId, TrackedFolderState.SCANNING, tracked.scannedAt)
            val result = runCatching { scanner.scan(tracked) }
            result.onSuccess {
                PxtLog.i(
                    TAG,
                    "Scanned: ${it.folders} folders, ${it.items} items, ${it.removed} gone"
                )
                fillThumbnails(albumId, ThumbnailPriority.ALBUM)
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
            fillThumbnails(rootId = null, priority = ThumbnailPriority.BACKGROUND)
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
        val files = filesOf(albumId)
        drive.removeTracked(albumId)
        deleteFiles(files)
    }

    /** Removes [accountId] and everything PhotoXT keeps of its Drive, and revokes its grant. */
    suspend fun removeAccount(accountId: String) {
        val tracked = drive.trackedOf(accountId)
        tracked.forEach { scans.remove(it)?.cancel() }
        val files = tracked.flatMap { filesOf(it) }
        auth.revoke(accountId)
        drive.removeAccount(accountId)
        deleteFiles(files)
        store.originals(accountId).deleteRecursively()
    }

    private suspend fun filesOf(rootId: String): List<File> {
        val versions = drive.filesUnder(rootId).mapNotNull { file ->
            file.version?.let {
                listOf(store.grid(file.fileId, it), store.preview(file.fileId, it))
            }
        }.flatten()
        val cached = drive.mediaIdsUnder(rootId).chunked(MAX_SQL_VARIABLES)
            .flatMap { drive.cachePaths(it) }
            .map(::File)
        return versions + cached
    }

    private fun deleteFiles(files: List<File>) = scope.launch {
        files.forEach { it.delete() }
    }

    /**
     * Makes missing grid thumbnails behind the visible ones, newest first, [BATCH] at a time.
     * Pauses on metered networks and in battery saver (spec §4.3); visible tiles never wait.
     */
    private suspend fun fillThumbnails(rootId: String?, priority: ThumbnailPriority) {
        while (true) {
            while (conditions.pauseBackgroundWork()) delay(PAUSE_RECHECK_MS)
            val ids = drive.missingThumbnails(rootId, BATCH)
            if (ids.isEmpty()) return
            val results = ids.map { thumbnails.enqueue(it, priority) }
                .map { runCatching { it.await() } }
            // Nothing worked (offline, or consent needed): stop until the next trigger.
            if (results.all { it.isFailure }) return
        }
    }

    private companion object {
        const val TAG = "DriveSync"
        const val BATCH = 200
        const val PAUSE_RECHECK_MS = 30_000L
    }
}
