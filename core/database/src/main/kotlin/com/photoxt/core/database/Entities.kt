package com.photoxt.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState

/**
 * An album (spec §5.4). A device folder's [id] is `device:<bucket id>`; a Drive folder's is
 * `drive:<account id>:<folder id>` (see `driveAlbumId`). Drive albums go with their tracked
 * folder (`DriveDao.removeTracked`), not through a foreign key: adding one to this table would
 * make Room rebuild it, and its items with it, on upgrade.
 */
@Entity(tableName = "album", indices = [Index("parentId"), Index("rootId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val type: AlbumType,
    /**
     * Device folder: `<volume>:<relative path>`, e.g. `external_primary:DCIM/Camera/`. Drive
     * folder: `drive:<folder path from the tracked folder>`, e.g. `drive:Holidays/2025/`.
     */
    val sourceRef: String,
    val title: String,
    /** The Google account of a Drive album. */
    val accountId: String? = null,
    /** A Drive subfolder shown as a nested album: the album of its parent folder. */
    val parentId: String? = null,
    /** A Drive album: the album of the tracked folder it belongs to (itself for that one). */
    val rootId: String? = null
)

/**
 * A Google account added to PhotoXT (spec §5.4). [id] is the account's stable Google id (`sub`).
 * Tokens never land here: Play services keeps them (spec §5.7).
 */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    /** Drive scopes granted so far, space-separated. */
    val scopes: String,
    /** Drive refused the grant (it expired or was revoked): show "Reconnect Google Drive". */
    val needsConsent: Boolean,
    /** The Changes API position, taken before the first scan (spec §5.5). */
    val changesToken: String?,
    val lastSyncAt: Long?,
    val addedAt: Long
)

/**
 * A Drive folder the user added (spec §5.4): it and everything below it become albums. Its root
 * album has the id [albumId]; [mergeSubfolders] puts every item below it into that one album.
 */
@Entity(
    tableName = "tracked_folder",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId", "driveFolderId", unique = true)]
)
data class TrackedFolderEntity(
    @PrimaryKey val albumId: String,
    val accountId: String,
    val driveFolderId: String,
    /** The shared drive it lives in, if any. */
    val driveId: String?,
    val mergeSubfolders: Boolean,
    val state: TrackedFolderState,
    val scannedAt: Long?,
    /** "Make album available offline" (spec §4.4): every item below it is pinned, new ones too. */
    @ColumnInfo(defaultValue = "0") val offline: Boolean = false
)

/**
 * One folder of a tracked Drive tree, so items can be placed (nested or merged) and later
 * changes recognised without asking Drive again. The tracked folder itself has no parent.
 */
@Entity(
    tableName = "drive_folder",
    primaryKeys = ["accountId", "folderId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackedFolderEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["trackedAlbumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackedAlbumId")]
)
data class DriveFolderEntity(
    val accountId: String,
    val folderId: String,
    val parentFolderId: String?,
    val trackedAlbumId: String,
    val name: String
)

/**
 * One photo or video from any source (spec §5.4). Device items have the id `device:<MediaStore
 * id>` and a [contentUri]; Drive items `drive:<account id>:<file id>` and the Drive fields. Times
 * are UTC epoch milliseconds; [dayKey] and [monthKey] are the local-calendar keys of [takenAt]
 * (see [com.photoxt.core.model.DateGrouping]), so the timeline can group in SQL.
 *
 * Only sync writes this table: screens page over it, and every write reloads them. State that
 * changes while browsing (thumbnails, cached originals, resume points) lives in side tables.
 */
@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("albumId", "takenAt"),
        Index("dayKey", "takenAt"),
        Index("monthKey", "takenAt")
    ]
)
data class MediaEntity(
    @PrimaryKey val id: String,
    val source: SourceType,
    /** MediaStore volume of a device item, e.g. `external_primary`. */
    val volume: String?,
    val contentUri: String?,
    val albumId: String,
    val name: String,
    val mime: String,
    val kind: MediaKind,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long?,
    val takenAt: Long,
    val modifiedAt: Long,
    val dayKey: Int,
    val monthKey: Int,
    val favorite: Boolean,
    val accountId: String? = null,
    /** Drive: the file id. */
    val remoteId: String? = null,
    /** Drive: the folder the file sits in, which places it in a nested album. */
    val remoteParentId: String? = null,
    /** Drive: the file's version, which rises with every change; part of thumbnail keys. */
    val remoteVersion: Long? = null,
    val md5: String? = null,
    /** Drive: a short-lived link to Drive's own thumbnail, refreshed when it fails. */
    val thumbnailLink: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val camera: String? = null
)

/**
 * A Drive item's grid thumbnail (spec §4.3): made once its WebP is on disk (or [failed] when no
 * source gave one), with a BlurHash for instant placeholders. Removing the item removes it.
 */
@Entity(
    tableName = "drive_thumbnail",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DriveThumbnailEntity(
    @PrimaryKey val mediaId: String,
    val version: Long,
    val blurhash: String?,
    @ColumnInfo(defaultValue = "0") val failed: Boolean,
    val updatedAt: Long
)

/**
 * A Drive item's full file on the phone (spec §4.4): where it is and when it was last opened, for
 * the retention rule. A pinned item's file sits in `media-offline/` and never expires; whether an
 * item is pinned is [OfflinePinEntity]'s business. Removing the item removes this row.
 */
@Entity(
    tableName = "drive_cache",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DriveCacheEntity(
    @PrimaryKey val mediaId: String,
    val path: String,
    val version: Long,
    val bytes: Long,
    val cachedAt: Long,
    val lastOpenedAt: Long
)

/**
 * A Drive item the user keeps offline (spec §3, `OFFLINE`): its full file is fetched into
 * `media-offline/` and never expires. Written only when pins change, so the Offline filter can
 * observe it without every file opening reloading the grids.
 */
@Entity(
    tableName = "offline_pin",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class OfflinePinEntity(@PrimaryKey val mediaId: String, val pinnedAt: Long)

/**
 * Files of one version of a Drive file that no item uses any more (spec §4.3, cleanup): its grid
 * thumbnail and preview, its cached original at [cachePath] and its video spans. An item removed
 * in Drive leaves one with a 7-day grace, so restoring it in Drive brings it back with its files;
 * a replaced version leaves one due at once. The cache cleanup deletes the files after
 * [purgeAfter].
 */
@Entity(tableName = "drive_orphan", primaryKeys = ["accountId", "fileId", "version"])
data class DriveOrphanEntity(
    val accountId: String,
    val fileId: String,
    val version: Long,
    val md5: String?,
    val bytes: Long,
    val mime: String,
    val purgeAfter: Long,
    val cachePath: String?,
    /** It was pinned offline, so a restore pins it again. */
    val pinned: Boolean
)

/**
 * Where the MediaStore indexer stopped on one volume. A changed [version] (MediaStore rebuilt its
 * database) or [zone] (the timeline's day keys moved) forces a full rescan of that volume.
 */
@Entity(tableName = "media_store_cursor")
data class MediaStoreCursorEntity(
    @PrimaryKey val volume: String,
    val version: String,
    val generation: Long,
    val zone: String
)

/**
 * Where playback of a video stopped (spec §4.6, continuity), so it resumes there. It goes with its
 * item: removing the item removes it. Phase 6 folds it into the viewing history.
 */
@Entity(
    tableName = "playback_position",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaybackPositionEntity(
    @PrimaryKey val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/**
 * A row of the paged timeline: a date header ([isHeader], no [id]) or an item. [width] and
 * [height] are the stored pixel size before [rotation] is applied.
 */
data class TimelineRow(
    val isHeader: Boolean,
    val groupKey: Int,
    val id: String?,
    val contentUri: String?,
    val kind: MediaKind?,
    val durationMs: Long?,
    val favorite: Boolean,
    val modifiedAt: Long,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val source: SourceType?,
    val remoteVersion: Long?
)

/** One photo or video as the viewer shows it, in the order of the grid it was opened from. */
data class ViewerItem(
    val id: String,
    val contentUri: String?,
    val albumId: String,
    val name: String,
    val mime: String,
    val kind: MediaKind,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long?,
    val takenAt: Long,
    val modifiedAt: Long,
    val favorite: Boolean,
    val source: SourceType,
    val accountId: String?,
    val remoteId: String?,
    val remoteVersion: Long?,
    val lat: Double?,
    val lng: Double?,
    val camera: String?
)

/** How many items one date group holds. */
data class GroupCount(val groupKey: Int, val count: Int)

/** An item's id and capture time, for selections. */
data class MediaStamp(val id: String, val takenAt: Long)

/** What sharing needs to hand an item to another app. */
data class ShareTarget(val contentUri: String, val mime: String)

/**
 * One album tile: the album, how many items it has, and its newest item as the cover. A tracked
 * Drive folder counts every item below it; [state] is its tracked folder's.
 */
data class AlbumSummary(
    val id: String,
    val type: AlbumType,
    val sourceRef: String,
    val title: String,
    val count: Int,
    val latestTakenAt: Long,
    val coverId: String?,
    val coverUri: String?,
    val coverModifiedAt: Long,
    val coverVersion: Long?,
    val accountId: String?,
    val state: TrackedFolderState?
)

/** A Drive item as sync sees it: where it is placed and which content it has. */
data class KnownDriveItem(
    val id: String,
    val remoteId: String,
    val albumId: String,
    /** The tracked folder's album. */
    val rootId: String?,
    val remoteParentId: String?
)

/** One Drive item's files, for the Storage screen and the cache cleanup (spec §4.4). */
data class DriveItemFiles(
    val id: String,
    val accountId: String,
    val rootId: String?,
    val remoteId: String,
    val remoteVersion: Long,
    val kind: MediaKind,
    val cachePath: String?,
    val cacheBytes: Long?,
    val pinned: Boolean
)
