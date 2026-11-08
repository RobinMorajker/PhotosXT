package com.photoxt.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
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
    // Offline pins feed the Offline filter; they change only when the user pins or unpins.
    @RawQuery(observedEntities = [MediaEntity::class, OfflinePinEntity::class])
    fun timelineRows(query: SupportSQLiteQuery): PagingSource<Int, TimelineRow>

    @RawQuery(observedEntities = [MediaEntity::class, OfflinePinEntity::class])
    fun groupCountRows(query: SupportSQLiteQuery): Flow<List<GroupCount>>

    @RawQuery
    suspend fun stampRows(query: SupportSQLiteQuery): List<MediaStamp>

    /** At most 999 ids per call (SQLite's variable limit on Android 11). */
    @Query("SELECT contentUri, mime FROM media WHERE id IN (:ids) AND contentUri IS NOT NULL")
    suspend fun shareTargets(ids: List<String>): List<ShareTarget>

    @RawQuery(observedEntities = [MediaEntity::class, OfflinePinEntity::class])
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

    /** Where the Changes feed continues after a page applied in the same transaction (§5.5). */
    @Query("UPDATE account SET changesToken = :token, lastSyncAt = :at WHERE id = :id")
    suspend fun saveChangesToken(id: String, token: String, at: Long)

    /** Drops the feed position once no folder of [id] is tracked; a new folder takes a new one. */
    @Query(
        "UPDATE account SET changesToken = NULL WHERE id = :id " +
            "AND NOT EXISTS (SELECT 1 FROM tracked_folder WHERE accountId = :id)"
    )
    suspend fun forgetChangesTokenIfUntracked(id: String)

    @Query("SELECT * FROM account")
    suspend fun accountList(): List<AccountEntity>

    @Query("SELECT * FROM tracked_folder WHERE accountId = :accountId")
    suspend fun trackedFoldersOf(accountId: String): List<TrackedFolderEntity>

    @Query(
        "SELECT * FROM tracked_folder WHERE accountId = :accountId AND driveFolderId = :folderId"
    )
    suspend fun trackedByFolder(accountId: String, folderId: String): TrackedFolderEntity?

    /** At most 999 ids per call. */
    @Query("UPDATE tracked_folder SET state = :state WHERE albumId IN (:ids)")
    suspend fun setStates(ids: List<String>, state: TrackedFolderState)

    /** Points tracked folder [albumId] at another Drive folder (Relink); a scan follows. */
    @Query(
        "UPDATE tracked_folder SET driveFolderId = :folderId, driveId = :driveId, " +
            "state = 'SCANNING' WHERE albumId = :albumId"
    )
    suspend fun relink(albumId: String, folderId: String, driveId: String?)

    @Query("UPDATE tracked_folder SET offline = :offline WHERE albumId = :albumId")
    suspend fun setOffline(albumId: String, offline: Boolean)

    @Query("SELECT * FROM drive_folder WHERE accountId = :accountId")
    suspend fun foldersOfAccount(accountId: String): List<DriveFolderEntity>

    @Query("UPDATE drive_folder SET name = :name WHERE accountId = :accountId AND folderId = :id")
    suspend fun renameFolder(accountId: String, id: String, name: String)

    @Query("SELECT id FROM album WHERE accountId = :accountId")
    suspend fun albumIdsOfAccount(accountId: String): List<String>

    /** The album of every tracked folder, by title. */
    @Query(
        "SELECT a.* FROM album a JOIN tracked_folder t ON t.albumId = a.id " +
            "ORDER BY a.title COLLATE NOCASE"
    )
    suspend fun trackedAlbums(): List<AlbumEntity>

    @Query("UPDATE album SET title = :title, sourceRef = :sourceRef WHERE id = :id")
    suspend fun retitleAlbum(id: String, title: String, sourceRef: String)

    /** At most 999 ids per call. */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun mediaRows(ids: List<String>): List<MediaEntity>

    /** Where [accountId]'s Drive files [fileIds] sit now, those PhotoXT has. At most 999 ids. */
    @Query(
        """
        SELECT m.id, m.remoteId, m.albumId, a.rootId, m.remoteParentId
        FROM media m JOIN album a ON a.id = m.albumId
        WHERE m.accountId = :accountId AND m.remoteId IN (:fileIds)
        """
    )
    suspend fun knownItems(accountId: String, fileIds: List<String>): List<KnownDriveItem>

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

/**
 * The cache lifecycle (spec §4.4): offline pins, cached originals by age and size, and orphans,
 * the files of items Drive no longer has. Pins of [MAX_SQL_VARIABLES] ids at most per call.
 */
@Dao
interface DriveCacheDao {
    @Query(
        "INSERT OR IGNORE INTO offline_pin (mediaId, pinnedAt) " +
            "SELECT id, :at FROM media WHERE id IN (:ids) AND source = 'DRIVE'"
    )
    suspend fun pin(ids: List<String>, at: Long)

    @Query("DELETE FROM offline_pin WHERE mediaId IN (:ids)")
    suspend fun unpin(ids: List<String>)

    /** Pins every item below tracked folder [rootId]. */
    @Query(
        """
        INSERT OR IGNORE INTO offline_pin (mediaId, pinnedAt)
        SELECT id, :at FROM media WHERE albumId IN (SELECT id FROM album WHERE rootId = :rootId)
        """
    )
    suspend fun pinTree(rootId: String, at: Long)

    @Query(
        """
        DELETE FROM offline_pin WHERE mediaId IN (SELECT id FROM media
            WHERE albumId IN (SELECT id FROM album WHERE rootId = :rootId))
        """
    )
    suspend fun unpinTree(rootId: String)

    /** Pins what joined a tracked folder kept offline since its last pass. */
    @Query(
        """
        INSERT OR IGNORE INTO offline_pin (mediaId, pinnedAt)
        SELECT m.id, :at FROM media m JOIN album a ON a.id = m.albumId
            JOIN tracked_folder t ON t.albumId = a.rootId
        WHERE t.offline = 1
        """
    )
    suspend fun pinOfflineTrees(at: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM offline_pin WHERE mediaId = :mediaId)")
    fun pinnedFlow(mediaId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM offline_pin WHERE mediaId = :mediaId)")
    suspend fun isPinned(mediaId: String): Boolean

    /** Pinned items whose current file is not in [offlineDir] yet, oldest pin first. */
    @Query(
        """
        SELECT p.mediaId FROM offline_pin p JOIN media m ON m.id = p.mediaId
            LEFT JOIN drive_cache c ON c.mediaId = p.mediaId
        WHERE c.mediaId IS NULL OR c.version != m.remoteVersion
            OR substr(c.path, 1, length(:offlineDir)) != :offlineDir
        ORDER BY p.pinnedAt
        """
    )
    suspend fun pinsToFetch(offlineDir: String): List<String>

    /** Files in [offlineDir] whose item is no longer pinned: they go back to the cache. */
    @Query(
        """
        SELECT * FROM drive_cache WHERE substr(path, 1, length(:offlineDir)) = :offlineDir
            AND mediaId NOT IN (SELECT mediaId FROM offline_pin)
        """
    )
    suspend fun unpinnedOffline(offlineDir: String): List<DriveCacheEntity>

    /** Cached originals not pinned and last opened before [before]: the retention rule. */
    @Query(
        "SELECT * FROM drive_cache WHERE lastOpenedAt < :before " +
            "AND mediaId NOT IN (SELECT mediaId FROM offline_pin)"
    )
    suspend fun expired(before: Long): List<DriveCacheEntity>

    /** Cached originals not pinned, the most recently opened first: the cap keeps the head. */
    @Query(
        "SELECT * FROM drive_cache WHERE mediaId NOT IN (SELECT mediaId FROM offline_pin) " +
            "ORDER BY lastOpenedAt DESC, mediaId"
    )
    suspend fun unpinnedNewestFirst(): List<DriveCacheEntity>

    /** Cached originals not pinned below tracked folder [rootId], or everywhere when null. */
    @Query(
        """
        SELECT c.* FROM drive_cache c JOIN media m ON m.id = c.mediaId
            JOIN album a ON a.id = m.albumId
        WHERE (:rootId IS NULL OR a.rootId = :rootId)
            AND c.mediaId NOT IN (SELECT mediaId FROM offline_pin)
        """
    )
    suspend fun unpinnedUnder(rootId: String?): List<DriveCacheEntity>

    @Query("DELETE FROM drive_cache WHERE mediaId IN (:ids)")
    suspend fun deleteCache(ids: List<String>)

    /** Moves an original's row along with its file (into or out of the offline folder). */
    @Query("UPDATE drive_cache SET path = :path WHERE mediaId = :mediaId")
    suspend fun moveCache(mediaId: String, path: String)

    /**
     * Records the files of items [ids] as an orphan due after [purgeAfter], from their rows as
     * they are now: call before the rows go or change version.
     */
    @Query(
        """
        INSERT OR REPLACE INTO drive_orphan
            (accountId, fileId, version, md5, bytes, mime, purgeAfter, cachePath, pinned)
        SELECT m.accountId, m.remoteId, COALESCE(m.remoteVersion, 0), m.md5, m.bytes, m.mime,
            :purgeAfter, c.path, p.mediaId IS NOT NULL
        FROM media m LEFT JOIN drive_cache c ON c.mediaId = m.id
            LEFT JOIN offline_pin p ON p.mediaId = m.id
        WHERE m.id IN (:ids) AND m.accountId IS NOT NULL AND m.remoteId IS NOT NULL
        """
    )
    suspend fun orphan(ids: List<String>, purgeAfter: Long)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteMediaRows(ids: List<String>)

    /** Removes Drive items [ids]; their files stay as orphans until [purgeAfter]. */
    @Transaction
    suspend fun removeItems(ids: List<String>, purgeAfter: Long) {
        ids.chunked(MAX_SQL_VARIABLES).forEach {
            orphan(it, purgeAfter)
            deleteMediaRows(it)
        }
    }

    @Query("SELECT * FROM drive_orphan WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun orphansOf(accountId: String, fileIds: List<String>): List<DriveOrphanEntity>

    /** Orphans due by [now], or every orphan with [now] at `Long.MAX_VALUE`. */
    @Query("SELECT * FROM drive_orphan WHERE purgeAfter <= :now")
    suspend fun dueOrphans(now: Long): List<DriveOrphanEntity>

    @Query("SELECT * FROM drive_orphan WHERE accountId = :accountId")
    suspend fun orphansOfAccount(accountId: String): List<DriveOrphanEntity>

    @Delete
    suspend fun deleteOrphans(orphans: List<DriveOrphanEntity>)

    /** Drive items with their files, below tracked folder [rootId] or everywhere when null. */
    @Query(
        """
        SELECT m.id, m.accountId, a.rootId, m.remoteId, COALESCE(m.remoteVersion, 0)
            AS remoteVersion, m.kind, c.path AS cachePath, c.bytes AS cacheBytes,
            p.mediaId IS NOT NULL AS pinned
        FROM media m JOIN album a ON a.id = m.albumId
            LEFT JOIN drive_cache c ON c.mediaId = m.id
            LEFT JOIN offline_pin p ON p.mediaId = m.id
        WHERE m.source = 'DRIVE' AND m.accountId IS NOT NULL AND m.remoteId IS NOT NULL
            AND (:rootId IS NULL OR a.rootId = :rootId)
        """
    )
    suspend fun itemFiles(rootId: String?): List<DriveItemFiles>
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
