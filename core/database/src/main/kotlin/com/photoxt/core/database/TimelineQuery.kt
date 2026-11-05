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

    private val conditions = listOfNotNull(
        when (filter) {
            MediaFilter.ALL -> null
            MediaFilter.PHOTOS -> "kind = 'IMAGE'"
            MediaFilter.VIDEOS -> "kind = 'VIDEO'"
            MediaFilter.FAVORITES -> "favorite = 1"
        },
        albumId?.let { "albumId = ?" }
    )

    private val args: List<Any> = listOfNotNull(albumId)

    private fun where(vararg extra: String): String {
        val all = conditions + extra
        return if (all.isEmpty()) "" else "WHERE " + all.joinToString(" AND ")
    }

    /** Headers and items, newest group first, each header right before its items. */
    internal fun rows(): SupportSQLiteQuery = SimpleSQLiteQuery(
        """
        SELECT 1 AS isHeader, $groupKey AS groupKey, NULL AS id, NULL AS contentUri, NULL AS kind,
            NULL AS durationMs, 0 AS favorite, 0 AS modifiedAt, MAX(takenAt) AS takenAt
        FROM media ${where()} GROUP BY $groupKey
        UNION ALL
        SELECT 0, $groupKey, id, contentUri, kind, durationMs, favorite, modifiedAt, takenAt
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
