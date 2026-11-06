package com.photoxt.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.photoxt.core.model.TrackedFolderState
import kotlinx.coroutines.flow.Flow

/** What screens read about media. Call the raw queries through the [TimelineQuery] extensions. */
@Dao
interface MediaDao {
    @RawQuery(observedEntities = [MediaEntity::class])
    fun timelineRows(query: SupportSQLiteQuery): PagingSource<Int, TimelineRow>

    @RawQuery(observedEntities = [MediaEntity::class])
    fun groupCountRows(query: SupportSQLiteQuery): Flow<List<GroupCount>>

    @RawQuery
    suspend fun stampRows(query: SupportSQLiteQuery): List<MediaStamp>

    /** At most 999 ids per call (SQLite's variable limit on Android 11). */
    @Query("SELECT contentUri, mime FROM media WHERE id IN (:ids) AND contentUri IS NOT NULL")
    suspend fun shareTargets(ids: List<String>): List<ShareTarget>

    @RawQuery(observedEntities = [MediaEntity::class])
    fun viewerItemRows(query: SupportSQLiteQuery): PagingSource<Int, ViewerItem>

    @RawQuery
    suspend fun viewerItemList(query: SupportSQLiteQuery): List<ViewerItem>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    @Query("SELECT $VIEWER_COLUMNS FROM media WHERE id = :id")
    suspend fun viewerItem(id: String): ViewerItem?

    /** The folder an item is in, as `<volume>:<relative path>` (see [AlbumEntity.sourceRef]). */
    @Query("SELECT a.sourceRef FROM album a JOIN media m ON m.albumId = a.id WHERE m.id = :id")
    suspend fun folderOf(id: String): String?
}

fun MediaDao.viewerItems(query: MediaListQuery): PagingSource<Int, ViewerItem> =
    viewerItemRows(query.items())

/** Every item of [query] at once, for the slideshow's shuffle. */
suspend fun MediaDao.allViewerItems(query: MediaListQuery): List<ViewerItem> =
    viewerItemList(query.items())

/** Where [item] sits among the items of [query], 0 being the newest. */
suspend fun MediaDao.positionOf(query: MediaListQuery, item: ViewerItem): Int =
    count(query.countBefore(item.takenAt, item.id))

fun MediaDao.timeline(query: TimelineQuery): PagingSource<Int, TimelineRow> =
    timelineRows(query.rows())

fun MediaDao.groupCounts(query: TimelineQuery): Flow<List<GroupCount>> =
    groupCountRows(query.groupCounts())

suspend fun MediaDao.stampsInGroup(query: TimelineQuery, groupKey: Int): List<MediaStamp> =
    stampRows(query.stampsInGroup(groupKey))

/** The MediaStore indexer's writes. Albums go in before the media that reference them. */
@Dao
interface MediaIndexDao {
    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Upsert
    suspend fun upsertMedia(items: List<MediaEntity>)

    @Query("SELECT id FROM media WHERE source = 'DEVICE' AND volume = :volume")
    suspend fun deviceMediaIds(volume: String): List<String>

    /** At most 999 ids per call (SQLite's variable limit on Android 11). */
    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteMedia(ids: List<String>)

    @Query("DELETE FROM media WHERE source = 'DEVICE' AND volume NOT IN (:volumes)")
    suspend fun deleteDeviceMediaOutside(volumes: List<String>)

    @Query(
        "DELETE FROM album WHERE type = 'DEVICE_FOLDER' AND id NOT IN (SELECT albumId FROM media)"
    )
    suspend fun deleteEmptyDeviceAlbums()

    @Query("SELECT * FROM media_store_cursor")
    suspend fun cursors(): List<MediaStoreCursorEntity>

    @Upsert
    suspend fun saveCursor(cursor: MediaStoreCursorEntity)

    @Query("DELETE FROM media_store_cursor WHERE volume NOT IN (:volumes)")
    suspend fun deleteCursorsOutside(volumes: List<String>)
}

/** Resume points of videos (spec §4.6). */
@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_position WHERE mediaId = :mediaId")
    suspend fun position(mediaId: String): PlaybackPositionEntity?

    @Upsert
    suspend fun save(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_position WHERE mediaId = :mediaId")
    suspend fun clear(mediaId: String)
}

@Dao
interface AlbumDao {
    /**
     * The Albums tab, newest first: every device folder with items, and every tracked Drive
     * folder (empty or not) counting all items below it. Each has its newest item as the cover.
     */
    @Query(
        """
        SELECT s.*, cv.contentUri AS coverUri, COALESCE(cv.modifiedAt, 0) AS coverModifiedAt,
            cv.remoteVersion AS coverVersion
        FROM (
            SELECT a.id, a.type, a.sourceRef, a.title, a.accountId, t.state,
                COUNT(m.id) AS count, COALESCE(MAX(m.takenAt), 0) AS latestTakenAt,
                CASE WHEN a.rootId IS NULL THEN
                    (SELECT c.id FROM media c WHERE c.albumId = a.id
                        ORDER BY c.takenAt DESC, c.id DESC LIMIT 1)
                ELSE
                    (SELECT c.id FROM media c
                        WHERE c.albumId IN (SELECT y.id FROM album y WHERE y.rootId = a.id)
                        ORDER BY c.takenAt DESC, c.id DESC LIMIT 1)
                END AS coverId
            FROM album a
            LEFT JOIN tracked_folder t ON t.albumId = a.id
            LEFT JOIN album x ON (a.rootId IS NULL AND x.id = a.id) OR x.rootId = a.id
            LEFT JOIN media m ON m.albumId = x.id
            WHERE a.parentId IS NULL
            GROUP BY a.id
            HAVING count > 0 OR a.type = 'DRIVE_FOLDER'
        ) s
        LEFT JOIN media cv ON cv.id = s.coverId
        ORDER BY s.latestTakenAt DESC
        """
    )
    fun albums(): Flow<List<AlbumSummary>>

    /** A Drive album's subfolders shown as nested albums, by name, each with its own items. */
    @Query(
        """
        SELECT s.*, cv.contentUri AS coverUri, COALESCE(cv.modifiedAt, 0) AS coverModifiedAt,
            cv.remoteVersion AS coverVersion
        FROM (
            SELECT a.id, a.type, a.sourceRef, a.title, a.accountId, NULL AS state,
                COUNT(m.id) AS count, COALESCE(MAX(m.takenAt), 0) AS latestTakenAt,
                (SELECT c.id FROM media c WHERE c.albumId = a.id
                    ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverId
            FROM album a LEFT JOIN media m ON m.albumId = a.id
            WHERE a.parentId = :parentId
            GROUP BY a.id
        ) s
        LEFT JOIN media cv ON cv.id = s.coverId
        ORDER BY s.title COLLATE NOCASE
        """
    )
    fun childAlbums(parentId: String): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<AlbumEntity?>
}

/** Google accounts, tracked Drive folders and Drive items' side tables (spec §5.4). */
@Dao
interface DriveDao {
    @Query("SELECT * FROM account ORDER BY addedAt")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Upsert
    suspend fun upsertAccount(account: AccountEntity)

    @Query("UPDATE account SET needsConsent = :needsConsent WHERE id = :id")
    suspend fun setNeedsConsent(id: String, needsConsent: Boolean)

    @Query("UPDATE account SET scopes = :scopes, needsConsent = 0 WHERE id = :id")
    suspend fun setScopes(id: String, scopes: String)

    /** Keeps the oldest position: a later folder's changes are covered from there too. */
    @Query("UPDATE account SET changesToken = :token WHERE id = :id AND changesToken IS NULL")
    suspend fun saveFirstChangesToken(id: String, token: String)

    @Query("SELECT * FROM tracked_folder")
    fun trackedFolders(): Flow<List<TrackedFolderEntity>>

    @Query("SELECT * FROM tracked_folder WHERE albumId = :albumId")
    suspend fun trackedFolder(albumId: String): TrackedFolderEntity?

    @Query("SELECT * FROM tracked_folder WHERE state = :state")
    suspend fun trackedFoldersIn(state: TrackedFolderState): List<TrackedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracked(folder: TrackedFolderEntity): Long

    @Query("UPDATE tracked_folder SET state = :state, scannedAt = :scannedAt WHERE albumId = :id")
    suspend fun setTrackedState(id: String, state: TrackedFolderState, scannedAt: Long?)

    @Query("UPDATE tracked_folder SET mergeSubfolders = :merge WHERE albumId = :id")
    suspend fun setMerge(id: String, merge: Boolean)

    @Upsert
    suspend fun upsertFolders(folders: List<DriveFolderEntity>)

    @Query("SELECT * FROM drive_folder WHERE trackedAlbumId = :trackedAlbumId")
    suspend fun foldersOf(trackedAlbumId: String): List<DriveFolderEntity>

    /** At most 999 ids per call (SQLite's variable limit on Android 11). */
    @Query("DELETE FROM drive_folder WHERE trackedAlbumId = :trackedAlbumId AND folderId IN (:ids)")
    suspend fun deleteFolders(trackedAlbumId: String, ids: List<String>)

    @Query("SELECT id FROM album WHERE rootId = :rootId")
    suspend fun albumIdsUnder(rootId: String): List<String>

    /** At most 999 ids per call. Their items go with them. */
    @Query("DELETE FROM album WHERE id IN (:ids)")
    suspend fun deleteAlbums(ids: List<String>)

    @Query(
        "SELECT id FROM media WHERE albumId IN (SELECT id FROM album WHERE rootId = :rootId)"
    )
    suspend fun mediaIdsUnder(rootId: String): List<String>

    @Query(
        """
        SELECT remoteId AS fileId, remoteVersion AS version FROM media
        WHERE albumId IN (SELECT id FROM album WHERE rootId = :rootId) AND remoteId IS NOT NULL
        """
    )
    suspend fun filesUnder(rootId: String): List<DriveFileVersion>

    @Query("SELECT path FROM drive_cache WHERE mediaId IN (:ids)")
    suspend fun cachePaths(ids: List<String>): List<String>

    /** Removes a tracked folder with its albums and items (their side tables follow). */
    @Transaction
    suspend fun removeTracked(rootId: String) {
        albumIdsUnder(rootId).chunked(MAX_SQL_VARIABLES).forEach { deleteAlbums(it) }
        deleteTracked(rootId)
    }

    @Query("DELETE FROM tracked_folder WHERE albumId = :rootId")
    suspend fun deleteTracked(rootId: String)

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun deleteAccountRow(id: String)

    /** Removes an account with everything PhotoXT keeps of its Drive. */
    @Transaction
    suspend fun removeAccount(id: String) {
        trackedOf(id).forEach { removeTracked(it) }
        deleteAccountRow(id)
    }

    @Query("SELECT albumId FROM tracked_folder WHERE accountId = :accountId")
    suspend fun trackedOf(accountId: String): List<String>

    @Query(
        """
        SELECT id, accountId, remoteId, remoteVersion, mime, bytes, thumbnailLink, kind, md5,
            modifiedAt
        FROM media WHERE id = :mediaId AND source = 'DRIVE'
        """
    )
    suspend fun driveItem(mediaId: String): DriveItem?

    @Query("UPDATE media SET thumbnailLink = :link WHERE id = :mediaId")
    suspend fun setThumbnailLink(mediaId: String, link: String?)

    /** Drive items still without a current grid thumbnail, newest first. */
    @Query(
        """
        SELECT m.id FROM media m LEFT JOIN drive_thumbnail t ON t.mediaId = m.id
        WHERE m.source = 'DRIVE' AND (:rootId IS NULL OR m.albumId IN
            (SELECT id FROM album WHERE rootId = :rootId))
            AND (t.mediaId IS NULL OR t.version != m.remoteVersion)
        ORDER BY m.takenAt DESC LIMIT :limit
        """
    )
    suspend fun missingThumbnails(rootId: String?, limit: Int): List<String>

    @Upsert
    suspend fun saveThumbnail(thumbnail: DriveThumbnailEntity)

    @Query("SELECT blurhash FROM drive_thumbnail WHERE mediaId = :mediaId")
    suspend fun blurhash(mediaId: String): String?

    @Query("SELECT * FROM drive_cache WHERE mediaId = :mediaId")
    suspend fun cached(mediaId: String): DriveCacheEntity?

    @Upsert
    suspend fun saveCache(cache: DriveCacheEntity)

    @Query("UPDATE drive_cache SET lastOpenedAt = :at WHERE mediaId = :mediaId")
    suspend fun touchCache(mediaId: String, at: Long)
}

/** A Drive file and its version, to find its thumbnails on disk. */
data class DriveFileVersion(val fileId: String, val version: Long?)

/** What fetching a Drive item's thumbnail or full file needs. */
data class DriveItem(
    val id: String,
    val accountId: String,
    val remoteId: String,
    val remoteVersion: Long,
    val mime: String,
    val bytes: Long,
    val thumbnailLink: String?,
    val kind: com.photoxt.core.model.MediaKind,
    val md5: String?,
    val modifiedAt: Long
)

/** SQLite's variable limit on Android 11 is 999; stay under it. */
const val MAX_SQL_VARIABLES = 900
