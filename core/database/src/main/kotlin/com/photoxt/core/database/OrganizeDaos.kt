package com.photoxt.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import kotlinx.coroutines.flow.Flow

/** Album options (spec §4.2) and album groups (spec §4.8). */
@Dao
interface AlbumPrefsDao {
    @Query("SELECT * FROM album_prefs")
    fun prefs(): Flow<List<AlbumPrefsEntity>>

    @Query("SELECT * FROM album_prefs")
    suspend fun prefsList(): List<AlbumPrefsEntity>

    @Query("SELECT * FROM album_prefs WHERE albumId = :albumId")
    fun prefsFlow(albumId: String): Flow<AlbumPrefsEntity?>

    @Query("SELECT * FROM album_prefs WHERE albumId = :albumId")
    suspend fun prefsOf(albumId: String): AlbumPrefsEntity?

    /** Hidden albums (spec §4.8), for the Hidden list, by name. */
    @Query(
        """
        SELECT a.id, a.title, a.type FROM album a JOIN album_prefs p ON p.albumId = a.id
        WHERE p.hidden = 1 ORDER BY a.title COLLATE NOCASE
        """
    )
    fun hiddenAlbums(): Flow<List<HiddenAlbumRow>>

    @Upsert
    suspend fun savePrefs(prefs: List<AlbumPrefsEntity>)

    /** *Apply to all albums* (spec §4.2, Scope): every album takes the default sort again. */
    @Query("UPDATE album_prefs SET sort = NULL")
    suspend fun clearSorts()

    @Query("SELECT * FROM album_group ORDER BY createdAt")
    fun groups(): Flow<List<AlbumGroupEntity>>

    @Query("SELECT * FROM album_group")
    suspend fun groupList(): List<AlbumGroupEntity>

    @Upsert
    suspend fun saveGroups(groups: List<AlbumGroupEntity>)

    @Query("DELETE FROM album_group WHERE id = :id")
    suspend fun deleteGroupRow(id: String)

    @Query("UPDATE album_prefs SET groupId = NULL WHERE groupId = :id")
    suspend fun ungroup(id: String)

    /** Removes group [id]; its albums return to the Albums tab. */
    @Transaction
    suspend fun deleteGroup(id: String) {
        ungroup(id)
        deleteGroupRow(id)
    }
}

/** Virtual and smart albums (spec §4.8): their rows, members and Custom order. */
@Dao
interface VirtualAlbumDao {
    /** Each virtual album with its item count and newest item as the cover (or its own). */
    @Query(
        """
        SELECT s.*, cv.contentUri AS coverUri, COALESCE(cv.modifiedAt, 0) AS coverModifiedAt,
            cv.remoteVersion AS coverVersion, cv.source AS coverSource,
            cv.accountId AS coverAccountId
        FROM (
            SELECT a.id, a.type, a.sourceRef, a.title, a.accountId, NULL AS state,
                COUNT(m.id) AS count, COALESCE(MAX(m.takenAt), 0) AS latestTakenAt,
                COALESCE(a.createdAt, 0) AS createdAt, COALESCE(SUM(m.bytes), 0) AS totalBytes,
                COALESCE(
                    (SELECT pr.coverId FROM album_prefs pr JOIN album_item ci
                        ON ci.albumId = pr.albumId AND ci.mediaId = pr.coverId
                        WHERE pr.albumId = a.id),
                    (SELECT c.id FROM album_item ai2 JOIN media c ON c.id = ai2.mediaId
                        WHERE ai2.albumId = a.id ORDER BY c.takenAt DESC, c.id DESC LIMIT 1)
                ) AS coverId
            FROM album a LEFT JOIN album_item ai ON ai.albumId = a.id
                LEFT JOIN media m ON m.id = ai.mediaId
            WHERE a.type = 'VIRTUAL'
            GROUP BY a.id
        ) s
        LEFT JOIN media cv ON cv.id = s.coverId
        """
    )
    fun virtualAlbums(): Flow<List<VirtualAlbumSummary>>

    @Query("SELECT * FROM album WHERE type = 'SMART'")
    fun smartAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE type IN ('VIRTUAL', 'SMART')")
    suspend fun ownAlbums(): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAlbums(albums: List<AlbumEntity>)

    @Query("UPDATE album SET title = :title WHERE id = :id AND type IN ('VIRTUAL', 'SMART')")
    suspend fun retitle(id: String, title: String)

    @Query("UPDATE album SET rule = :rule WHERE id = :id AND type = 'SMART'")
    suspend fun setRule(id: String, rule: String)

    /** Deletes a virtual or smart album; its members go with it, the items stay. */
    @Query("DELETE FROM album WHERE id = :id AND type IN ('VIRTUAL', 'SMART')")
    suspend fun deleteOwn(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<AlbumItemEntity>)

    /** At most 900 ids per call. */
    @Query("DELETE FROM album_item WHERE albumId = :albumId AND mediaId IN (:ids)")
    suspend fun removeItems(albumId: String, ids: List<String>)

    @Query("SELECT * FROM album_item")
    suspend fun allItems(): List<AlbumItemEntity>

    @Query("SELECT * FROM item_order WHERE albumId = :albumId")
    suspend fun orderOf(albumId: String): List<ItemOrderEntity>

    @Upsert
    suspend fun saveOrder(order: List<ItemOrderEntity>)

    @Query("SELECT * FROM item_order")
    suspend fun allOrder(): List<ItemOrderEntity>
}

/** Pinned albums and items (spec §4.8), in drag order. */
@Dao
interface PinDao {
    @Query("SELECT * FROM pin WHERE targetType = :type ORDER BY position")
    fun pins(type: TargetType): Flow<List<PinEntity>>

    @Query("SELECT * FROM pin")
    suspend fun allPins(): List<PinEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM pin WHERE targetType = :type AND targetId = :id)")
    fun isPinned(type: TargetType, id: String): Flow<Boolean>

    @Query("SELECT COALESCE(MAX(position), 0) FROM pin WHERE targetType = :type")
    suspend fun lastPosition(type: TargetType): Double

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pins: List<PinEntity>)

    @Upsert
    suspend fun savePins(pins: List<PinEntity>)

    /** At most 900 ids per call. */
    @Query("DELETE FROM pin WHERE targetType = :type AND targetId IN (:ids)")
    suspend fun unpin(type: TargetType, ids: List<String>)

    /** Pinned items of album [albumId] (or virtual album), in drag order. */
    @Query(
        """
        SELECT p.* FROM pin p JOIN media m ON m.id = p.targetId
        WHERE p.targetType = 'ITEM' AND (m.albumId = :albumId OR m.id IN
            (SELECT mediaId FROM album_item WHERE albumId = :albumId))
        ORDER BY p.position
        """
    )
    suspend fun itemPinsIn(albumId: String): List<PinEntity>
}

/** Bookmark collections and their bookmarks (spec §4.8). */
@Dao
interface BookmarkDao {
    @Query(
        """
        SELECT c.*, (SELECT COUNT(*) FROM bookmark b WHERE b.collectionId = c.id) AS count
        FROM bookmark_collection c ORDER BY c.position
        """
    )
    fun collections(): Flow<List<BookmarkCollectionRow>>

    @Query("SELECT * FROM bookmark_collection")
    suspend fun collectionList(): List<BookmarkCollectionEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM bookmark_collection")
    suspend fun lastPosition(): Double

    @Upsert
    suspend fun saveCollections(collections: List<BookmarkCollectionEntity>)

    @Query("DELETE FROM bookmark_collection WHERE id = :id")
    suspend fun deleteCollection(id: String)

    /** The bookmarks of a collection, newest first, with what is left of their targets. */
    @Query(
        """
        SELECT b.*, m.id AS mediaId, m.albumId AS mediaAlbumId, m.contentUri, m.source, m.kind,
            m.modifiedAt, m.remoteVersion, m.accountId, m.durationMs,
            a.id IS NOT NULL AS albumExists
        FROM bookmark b
            LEFT JOIN media m ON b.targetType = 'ITEM' AND m.id = b.targetId
            LEFT JOIN album a ON b.targetType = 'ALBUM' AND a.id = b.targetId
        WHERE b.collectionId = :collectionId
        ORDER BY b.createdAt DESC
        """
    )
    fun bookmarks(collectionId: String): Flow<List<BookmarkRow>>

    @Query("SELECT * FROM bookmark")
    suspend fun bookmarkList(): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmark WHERE targetId = :targetId)")
    fun isBookmarked(targetId: String): Flow<Boolean>

    @Upsert
    suspend fun saveBookmarks(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteBookmark(id: String)
}

/** The viewing history (spec §4.9). */
@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(view: HistoryEntity): Long

    @Insert
    suspend fun insertAll(views: List<HistoryEntity>)

    @Query("UPDATE history SET watchedMs = watchedMs + :ms WHERE id = :id")
    suspend fun addWatched(id: Long, ms: Long)

    /**
     * The items viewed lately, the last view first, each once with how often it was viewed; at
     * most [limit]. Hidden items are never listed (spec §4.9).
     */
    @Query(
        """
        SELECT m.id, m.albumId, m.contentUri, m.source, m.kind, m.modifiedAt, m.remoteVersion,
            m.accountId, m.durationMs, m.name, MAX(h.viewedAt) AS viewedAt, COUNT(*) AS views
        FROM history h JOIN media m ON m.id = h.mediaId
        WHERE m.id NOT IN (SELECT mediaId FROM hidden_item)
        GROUP BY m.id ORDER BY viewedAt DESC LIMIT :limit
        """
    )
    fun recent(limit: Int): Flow<List<HistoryRow>>

    @Query("SELECT COUNT(*) FROM history WHERE mediaId = :mediaId")
    fun views(mediaId: String): Flow<Int>

    @Query("SELECT * FROM history ORDER BY viewedAt, id")
    suspend fun all(): List<HistoryEntity>

    /** Clears views from [from] (included) to [until] (excluded). */
    @Query("DELETE FROM history WHERE viewedAt >= :from AND viewedAt < :until")
    suspend fun clear(from: Long, until: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    /** At most 900 ids per call. */
    @Query("DELETE FROM history WHERE mediaId IN (:ids)")
    suspend fun forget(ids: List<String>)

    /** Forgets every view of album [albumId]'s items (it was hidden). */
    @Query("DELETE FROM history WHERE mediaId IN (SELECT id FROM media WHERE albumId = :albumId)")
    suspend fun forgetAlbum(albumId: String)

    /** Auto-delete (spec §4.9): views before [before] go. */
    @Query("DELETE FROM history WHERE viewedAt < :before")
    suspend fun trim(before: Long): Int
}

/** Hidden items (spec §4.8); hidden albums are [AlbumPrefsEntity.hidden]. */
@Dao
interface HiddenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(items: List<HiddenItemEntity>)

    /** At most 900 ids per call. */
    @Query("DELETE FROM hidden_item WHERE mediaId IN (:ids)")
    suspend fun unhide(ids: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_item WHERE mediaId = :mediaId)")
    suspend fun isHidden(mediaId: String): Boolean

    /** Whether [mediaId] or its album is hidden: then nothing about it is logged. */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM hidden_item WHERE mediaId = :mediaId)
            OR EXISTS(SELECT 1 FROM album_prefs pr JOIN media m ON m.albumId = pr.albumId
                WHERE m.id = :mediaId AND pr.hidden = 1)
        """
    )
    suspend fun isHiddenAnywhere(mediaId: String): Boolean

    @Query("SELECT * FROM hidden_item")
    suspend fun all(): List<HiddenItemEntity>

    @Query("SELECT COUNT(*) FROM hidden_item")
    fun count(): Flow<Int>
}

/** A hidden album as the Hidden list shows it. */
data class HiddenAlbumRow(val id: String, val title: String, val type: AlbumType)

/** A virtual album's tile: an [AlbumSummary] whose cover may come from any source. */
data class VirtualAlbumSummary(
    val id: String,
    val type: com.photoxt.core.model.AlbumType,
    val sourceRef: String,
    val title: String,
    val count: Int,
    val latestTakenAt: Long,
    val coverId: String?,
    val coverUri: String?,
    val coverModifiedAt: Long,
    val coverVersion: Long?,
    val coverSource: SourceType?,
    val coverAccountId: String?,
    val accountId: String?,
    val state: com.photoxt.core.model.TrackedFolderState?,
    val createdAt: Long,
    val totalBytes: Long
)

data class BookmarkCollectionRow(
    val id: String,
    val name: String,
    val note: String?,
    val createdAt: Long,
    val position: Double,
    val count: Int
)

/** A bookmark with what is left of its target: null media fields once the item is gone. */
data class BookmarkRow(
    val id: String,
    val collectionId: String,
    val targetType: TargetType,
    val targetId: String,
    val positionMs: Long?,
    val note: String?,
    val title: String,
    val createdAt: Long,
    val mediaId: String?,
    /** The item's own album, for the viewer to page through. */
    val mediaAlbumId: String?,
    val contentUri: String?,
    val source: SourceType?,
    val kind: MediaKind?,
    val modifiedAt: Long?,
    val remoteVersion: Long?,
    val accountId: String?,
    val durationMs: Long?,
    val albumExists: Boolean
) {
    /** Whether the item or album is still there (spec §4.8: "No longer available"). */
    val available: Boolean
        get() = if (targetType == TargetType.ITEM) mediaId != null else albumExists
}

/** One item of the history list: its last view and how often it was viewed. */
data class HistoryRow(
    val id: String,
    /** The item's own album, for the viewer to page through. */
    val albumId: String,
    val contentUri: String?,
    val source: SourceType,
    val kind: MediaKind,
    val modifiedAt: Long,
    val remoteVersion: Long?,
    val accountId: String?,
    val durationMs: Long?,
    val name: String,
    val viewedAt: Long,
    val views: Int
)

/** A video left part-way (spec §4.9, *Continue watching*). */
data class ContinueRow(
    val id: String,
    val contentUri: String?,
    val source: SourceType,
    val modifiedAt: Long,
    val remoteVersion: Long?,
    val accountId: String?,
    val name: String,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Settings sync's view of the user's own tables (spec §4.8): read whole for the copy in Drive,
 * replaced whole when a reinstall restores it.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM tracked_folder")
    suspend fun trackedFolders(): List<TrackedFolderEntity>

    @Query("SELECT * FROM album WHERE type IN ('VIRTUAL', 'SMART')")
    suspend fun ownAlbums(): List<AlbumEntity>

    @Query("DELETE FROM album WHERE type IN ('VIRTUAL', 'SMART')")
    suspend fun clearOwnAlbums()

    @Query("DELETE FROM album_prefs")
    suspend fun clearPrefs()

    @Query("DELETE FROM album_group")
    suspend fun clearGroups()

    @Query("DELETE FROM item_order")
    suspend fun clearOrder()

    @Query("DELETE FROM pin")
    suspend fun clearPins()

    @Query("DELETE FROM bookmark_collection")
    suspend fun clearCollections()

    @Query("DELETE FROM hidden_item")
    suspend fun clearHidden()

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM playback_position")
    suspend fun clearPositions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHidden(items: List<HiddenItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPositions(items: List<PlaybackPositionEntity>)

    @Query("SELECT * FROM playback_position")
    suspend fun positions(): List<PlaybackPositionEntity>
}
