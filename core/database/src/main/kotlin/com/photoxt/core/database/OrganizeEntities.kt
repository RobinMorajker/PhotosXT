package com.photoxt.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.TargetType
import kotlinx.serialization.Serializable

// The user's own organisation (spec §4.8, §4.9, §5.4). Sync never writes these tables, and none
// has a foreign key to media or albums that sync owns: settings sync restores them before the
// albums they name are scanned again, and a row whose item is gone simply stays unseen.

/**
 * What the user chose for one album (spec §4.2, Album options): its cover, its sort, its place in
 * Custom order and in a group, and whether it is hidden. One row per album the user touched.
 */
@Entity(tableName = "album_prefs", indices = [Index("groupId")])
@Serializable
data class AlbumPrefsEntity(
    @PrimaryKey val albumId: String,
    /** An item of the album as its cover; the newest item when null or gone. */
    val coverId: String? = null,
    /** Its own sort; the default of every album (settings) when null. */
    val sort: ItemSortField? = null,
    val sortDescending: Boolean = true,
    /** Its place in the Albums tab's Custom order; new albums (null) join at the top. */
    val position: Double? = null,
    val groupId: String? = null,
    /** Hidden entirely: off the Albums tab and Pictures, in the Hidden list. */
    val hidden: Boolean = false,
    /** Kept in Albums, but its items stay out of Pictures. */
    val hideFromPictures: Boolean = false
)

/** An album group (spec §4.8): a tile in the Albums tab that holds albums. */
@Entity(tableName = "album_group")
@Serializable
data class AlbumGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val position: Double? = null
)

/** One item of a virtual album (spec §4.8): nothing is moved. */
@Entity(
    tableName = "album_item",
    primaryKeys = ["albumId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mediaId")]
)
@Serializable
data class AlbumItemEntity(val albumId: String, val mediaId: String, val addedAt: Long)

/** An item's place in its album's Custom order (spec §4.2, §5.4 `item_order`). */
@Entity(tableName = "item_order", primaryKeys = ["albumId", "mediaId"])
@Serializable
data class ItemOrderEntity(val albumId: String, val mediaId: String, val position: Double)

/**
 * A pin (spec §4.8): an album at the top of Albums, or an item at the top of its album, in the
 * user's drag order ([position], smallest first).
 */
@Entity(tableName = "pin", primaryKeys = ["targetType", "targetId"])
@Serializable
data class PinEntity(
    val targetType: TargetType,
    val targetId: String,
    val position: Double,
    val pinnedAt: Long
)

/** A named bookmark collection with an optional note (spec §4.8). */
@Entity(tableName = "bookmark_collection")
@Serializable
data class BookmarkCollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val note: String? = null,
    val createdAt: Long,
    val position: Double
)

/**
 * A bookmark (spec §4.8): an item, an album, or a moment ([positionMs]) of a video, saved in a
 * collection, with a note. [title] is the name it had, for when it is no longer available.
 */
@Entity(
    tableName = "bookmark",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId"), Index("targetId")]
)
@Serializable
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val targetType: TargetType,
    val targetId: String,
    val positionMs: Long? = null,
    val note: String? = null,
    val title: String,
    val createdAt: Long
)

/**
 * One view of an item (spec §4.9): when, from which album, and how long a video played. Never
 * written for hidden or vault items, nor while history is paused.
 */
@Entity(tableName = "history", indices = [Index("mediaId", "viewedAt"), Index("viewedAt")])
@Serializable
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val albumId: String?,
    val viewedAt: Long,
    val watchedMs: Long = 0
)

/** An item hidden from Pictures, albums and search (spec §4.8); shown in the Hidden list. */
@Entity(tableName = "hidden_item")
@Serializable
data class HiddenItemEntity(@PrimaryKey val mediaId: String, val hiddenAt: Long)
