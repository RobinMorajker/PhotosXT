package com.photoxt.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.MediaSource

/** An album (spec §5.4). Phase 1 has device folders only; their [id] is `device:<bucket id>`. */
@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val type: AlbumType,
    /** Device folder: `<volume>:<relative path>`, e.g. `external_primary:DCIM/Camera/`. */
    val sourceRef: String,
    val title: String
)

/**
 * One photo or video from any source (spec §5.4). Device items have the id `device:<MediaStore
 * id>` and a [contentUri]. Times are UTC epoch milliseconds; [dayKey] and [monthKey] are the
 * local-calendar keys of [takenAt] (see [com.photoxt.core.model.DateGrouping]), so the timeline
 * can group in SQL.
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
    val source: MediaSource,
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
    val favorite: Boolean
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
    val rotation: Int
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
    val favorite: Boolean
)

/** How many items one date group holds. */
data class GroupCount(val groupKey: Int, val count: Int)

/** An item's id and capture time, for selections. */
data class MediaStamp(val id: String, val takenAt: Long)

/** What sharing needs to hand an item to another app. */
data class ShareTarget(val contentUri: String, val mime: String)

/** One album tile: the album, how many items it has, and its newest item as the cover. */
data class AlbumSummary(
    val id: String,
    val type: AlbumType,
    val sourceRef: String,
    val title: String,
    val count: Int,
    val latestTakenAt: Long,
    val coverUri: String?,
    val coverModifiedAt: Long
)
