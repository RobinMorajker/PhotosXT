package com.photoxt.feature.timeline

import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.DriveDao
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import com.photoxt.core.sync.DriveSync
import com.photoxt.core.sync.OfflinePins
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What a timeline needs from Drive sync (spec §4.2, §4.3, §4.4). */
class DriveAlbums @Inject constructor(
    private val albumDao: AlbumDao,
    private val driveDao: DriveDao,
    private val driveSync: DriveSync,
    private val pins: OfflinePins,
    private val deviceMediaSync: DeviceMediaSync
) {
    /** Album [albumId]'s subfolders, merge and offline switches, and sync state. */
    fun info(albumId: String): Flow<DriveAlbumInfo> = combine(
        albumDao.childAlbums(albumId),
        driveDao.trackedFolders(),
        driveDao.accounts()
    ) { children, tracked, accounts ->
        val root = tracked.firstOrNull { it.albumId == albumId }
        val accountId = accountOf(albumId)
        DriveAlbumInfo(
            accountId = accountId,
            isRoot = root != null,
            merge = root?.mergeSubfolders ?: false,
            offline = root?.offline ?: false,
            syncing = root?.state == TrackedFolderState.SCANNING,
            unavailable = root?.state == TrackedFolderState.UNAVAILABLE,
            needsConsent = accounts.any { it.id == accountId && it.needsConsent },
            children = children.map { it.toChild() }
        )
    }

    /**
     * Pull-to-refresh (spec §4.3): Drive album [albumId]'s account, or for Pictures every account
     * and the phone's own media. False when Drive could not be reached.
     */
    suspend fun refresh(albumId: String?): Boolean {
        if (albumId == null || !isDrive(albumId)) deviceMediaSync.refresh()
        return when {
            albumId == null -> driveSync.refresh()
            isDrive(albumId) -> driveSync.refresh(accountOf(albumId))
            else -> true
        }
    }

    suspend fun setMerge(albumId: String, merge: Boolean) = driveSync.setMerge(albumId, merge)

    suspend fun setOffline(albumId: String, offline: Boolean) =
        pins.setAlbumOffline(albumId, offline)

    suspend fun remove(albumId: String) = driveSync.removeFolder(albumId)

    companion object {
        private const val PREFIX = "drive:"

        fun isDrive(albumId: String) = albumId.startsWith(PREFIX)

        /** Drive album ids are `drive:<account>:<folder>`. */
        fun accountOf(albumId: String): String = albumId.removePrefix(PREFIX).substringBefore(':')
    }
}

private fun AlbumSummary.toChild(): ChildAlbum {
    val cover = coverId?.let { id ->
        thumbnailOf(
            MediaRef(
                id = id,
                source = SourceType.DRIVE,
                contentUri = null,
                modifiedAt = coverModifiedAt,
                accountId = accountId,
                remoteId = id.substringAfterLast(':'),
                remoteVersion = coverVersion
            )
        )
    }
    return ChildAlbum(id, title, count, cover)
}
