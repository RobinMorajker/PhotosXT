package com.photoxt.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.MediaFilter

/**
 * One timeline (all media, or one album) under a filter and a date grouping, as the SQL behind
 * [MediaDao]'s timeline queries.
 *
 * The rows query returns each group's header followed by its items, so the headers sit at exact
 * positions: the paged grid can jump anywhere without headers shifting as pages load.
 */
data class TimelineQuery(
    val filter: MediaFilter = MediaFilter.ALL,
    val grouping: DateGrouping = DateGrouping.DAY,
    val albumId: String? = null
) {
    private val groupKey = when (grouping) {
        DateGrouping.DAY -> "dayKey"
        DateGrouping.MONTH -> "monthKey"
        DateGrouping.YEAR -> "(monthKey / 12)"
    }

    private val list = MediaListQuery(filter, albumId)
    private val args: List<Any> get() = list.args

    private fun where(vararg extra: String): String = list.where(*extra)

    /** Headers and items, newest group first, each header right before its items. */
    internal fun rows(): SupportSQLiteQuery = SimpleSQLiteQuery(
        """
        SELECT 1 AS isHeader, $groupKey AS groupKey, NULL AS id, NULL AS contentUri, NULL AS kind,
            NULL AS durationMs, 0 AS favorite, 0 AS modifiedAt, MAX(takenAt) AS takenAt,
            0 AS width, 0 AS height, 0 AS rotation, NULL AS source, NULL AS remoteVersion
        FROM media ${where()} GROUP BY $groupKey
        UNION ALL
        SELECT 0, $groupKey, id, contentUri, kind, durationMs, favorite, modifiedAt, takenAt,
            width, height, rotation, source, remoteVersion
        FROM media ${where()}
        ORDER BY groupKey DESC, isHeader DESC, takenAt DESC, id DESC
        """.trimIndent(),
        (args + args).toTypedArray()
    )

    /** Item count per group, newest group first: the layout of [rows] without loading it. */
    internal fun groupCounts(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT $groupKey AS groupKey, COUNT(*) AS count FROM media ${where()} " +
            "GROUP BY $groupKey ORDER BY groupKey DESC",
        args.toTypedArray()
    )

    /** Every item of one group, for its select-all control. */
    internal fun stampsInGroup(key: Int): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT id, takenAt FROM media ${where("$groupKey = ?")}",
        (args + key).toTypedArray()
    )
}

/**
 * The items of one timeline (all media, or one album) under a filter, without date headers: what
 * the viewer pages through. Items come newest first, in exactly the order of the grid's items,
 * because every grouping key rises with the capture time it is computed from.
 */
data class MediaListQuery(val filter: MediaFilter = MediaFilter.ALL, val albumId: String? = null) {
    private val conditions = listOfNotNull(
        when (filter) {
            MediaFilter.ALL -> null
            MediaFilter.PHOTOS -> "kind = 'IMAGE'"
            MediaFilter.VIDEOS -> "kind = 'VIDEO'"
            MediaFilter.FAVORITES -> "favorite = 1"
            MediaFilter.DEVICE -> "source = 'DEVICE'"
            MediaFilter.DRIVE -> "source = 'DRIVE'"
            MediaFilter.OFFLINE -> "id IN (SELECT mediaId FROM offline_pin)"
        },
        albumId?.let { "albumId = ?" }
    )

    internal val args: List<Any> = listOfNotNull(albumId)

    internal fun where(vararg extra: String): String {
        val all = conditions + extra
        return if (all.isEmpty()) "" else "WHERE " + all.joinToString(" AND ")
    }

    /** Every item, newest first. */
    internal fun items(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT $VIEWER_COLUMNS FROM media ${where()} ORDER BY takenAt DESC, id DESC",
        args.toTypedArray()
    )

    /** How many items come before the item captured at [takenAt] with [id]: its position. */
    internal fun countBefore(takenAt: Long, id: String): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT COUNT(*) FROM media ${where("(takenAt > ? OR (takenAt = ? AND id > ?))")}",
        (args + takenAt + takenAt + id).toTypedArray()
    )
}

internal const val VIEWER_COLUMNS = "id, contentUri, albumId, name, mime, kind, bytes, width, " +
    "height, rotation, durationMs, takenAt, modifiedAt, favorite, source, accountId, remoteId, " +
    "remoteVersion, lat, lng, camera"
