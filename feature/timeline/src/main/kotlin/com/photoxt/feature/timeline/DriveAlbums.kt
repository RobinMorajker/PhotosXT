package com.photoxt.feature.timeline

import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.DriveDao
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import com.photoxt.core.sync.DriveSync
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What a Drive album's grid needs from Drive sync (spec §4.2, §4.3). */
class DriveAlbums @Inject constructor(
    private val albumDao: AlbumDao,
    private val driveDao: DriveDao,
    private val driveSync: DriveSync
) {
    /** Album [albumId]'s subfolders, merge switch and sync state. */
    fun info(albumId: String): Flow<DriveAlbumInfo> = combine(
        albumDao.childAlbums(albumId),
        driveDao.trackedFolders(),
        driveDao.accounts()
    ) { children, tracked, accounts ->
        val root = tracked.firstOrNull { it.albumId == albumId }
        // Album ids are `drive:<account>:<folder>`.
        val accountId = albumId.removePrefix("drive:").substringBefore(':')
        DriveAlbumInfo(
            isRoot = root != null,
            merge = root?.mergeSubfolders ?: false,
            syncing = root?.state == TrackedFolderState.SCANNING,
            needsConsent = accounts.any { it.id == accountId && it.needsConsent },
            children = children.map { it.toChild() }
        )
    }

    suspend fun setMerge(albumId: String, merge: Boolean) = driveSync.setMerge(albumId, merge)

    suspend fun remove(albumId: String) = driveSync.removeFolder(albumId)
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
