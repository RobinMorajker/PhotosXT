package com.photoxt.core.model

import kotlinx.serialization.Serializable

/** What an item list is ordered by (spec §4.2, Sorting). */
enum class ItemSortField {
    DATE_TAKEN,
    DATE_ADDED,
    DATE_MODIFIED,
    NAME,
    SIZE,

    /** The user's drag order (`item_order`); items without a place yet come first. */
    CUSTOM;

    /** Date sorts keep date headers for their date; the rest show a plain grid. */
    val dated: Boolean get() = this == DATE_TAKEN || this == DATE_ADDED || this == DATE_MODIFIED

    /** The direction a newly picked sort starts in: newest, largest first; names A to Z. */
    val naturalDescending: Boolean get() = this != NAME && this != CUSTOM
}

/** An item sort and its direction. Custom order has no direction. */
@Serializable
data class ItemSort(
    val field: ItemSortField = ItemSortField.DATE_TAKEN,
    val descending: Boolean = true
) {
    companion object {
        /** [field] in its natural direction (spec §4.2, Direction). */
        fun of(field: ItemSortField) = ItemSort(field, field.naturalDescending)
    }
}

/** What the Albums tab is ordered by (spec §4.2, Sorting); nested albums and groups follow it. */
enum class AlbumSortField {
    CUSTOM,
    NAME,
    NEWEST_ITEM,
    DATE_CREATED,
    ITEM_COUNT,
    TOTAL_SIZE;

    val naturalDescending: Boolean get() = this != NAME && this != CUSTOM
}

@Serializable
data class AlbumSort(
    val field: AlbumSortField = AlbumSortField.NEWEST_ITEM,
    val descending: Boolean = true
) {
    companion object {
        fun of(field: AlbumSortField) = AlbumSort(field, field.naturalDescending)
    }
}

/**
 * Item lists that are not albums (spec §4.1, Library): each remembers its own sort. [HIDDEN] is the
 * Hidden list, behind the unlock (spec §4.8).
 */
enum class MediaList { FAVORITES, VIDEOS, SCREENSHOTS, OFFLINE, HIDDEN }

/**
 * A smart album's rule (spec §4.8): every set condition must hold. Times are capture times in
 * epoch milliseconds, [from] included and [until] excluded. Place and label rules wait for
 * Places and labels (Phase 13); [hasLocation] stands in for places until then.
 */
@Serializable
data class SmartRule(
    val kind: MediaKind? = null,
    val from: Long? = null,
    val until: Long? = null,
    val source: SourceType? = null,
    val albumIds: List<String> = emptyList(),
    /** Part of the camera's make and model, any case. */
    val camera: String? = null,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val minDurationMs: Long? = null,
    val favorite: Boolean = false,
    val hasLocation: Boolean = false
)

/** The group time of an album's pinned items: they sort at its top, whatever the sort. */
const val PINNED_TIME = Long.MIN_VALUE

/** The group key of an album's pinned items, above every date group. */
const val PINNED_KEY = Int.MAX_VALUE

/** What a pin or bookmark points at (spec §4.8). */
enum class TargetType { ALBUM, ITEM }

/**
 * A name as a key that sorts in natural order (spec §4.2, Name): case ignored, and every run of
 * digits compared as a number, so `IMG_2` comes before `IMG_10`. Leading zeros are dropped, so
 * `IMG_02` and `IMG_2` tie (and break the tie by capture time).
 */
fun naturalKey(name: String): String {
    val lower = name.lowercase()
    val out = StringBuilder(lower.length + DIGIT_PAD)
    var i = 0
    while (i < lower.length) {
        val c = lower[i]
        if (c in '0'..'9') {
            var end = i
            while (end < lower.length && lower[end] in '0'..'9') end++
            val digits = lower.substring(i, end).trimStart('0')
            // A longer number is a bigger one: its length leads, then the digits themselves.
            out.append(digits.length.toString().padStart(LENGTH_WIDTH, '0')).append(digits)
            i = end
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private const val LENGTH_WIDTH = 3
private const val DIGIT_PAD = 8
