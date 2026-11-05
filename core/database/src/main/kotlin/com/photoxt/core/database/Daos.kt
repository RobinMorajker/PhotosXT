package com.photoxt.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
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
    /** Albums with at least one item, newest first, each with its newest item as the cover. */
    @Query(
        """
        SELECT a.id, a.type, a.sourceRef, a.title, COUNT(*) AS count,
            MAX(m.takenAt) AS latestTakenAt,
            (SELECT c.contentUri FROM media c WHERE c.albumId = a.id
                ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverUri,
            (SELECT c.modifiedAt FROM media c WHERE c.albumId = a.id
                ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverModifiedAt
        FROM album a JOIN media m ON m.albumId = a.id
        GROUP BY a.id
        ORDER BY latestTakenAt DESC
        """
    )
    fun albums(): Flow<List<AlbumSummary>>
}
