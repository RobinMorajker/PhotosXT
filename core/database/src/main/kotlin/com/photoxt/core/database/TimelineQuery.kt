package com.photoxt.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaList
import com.photoxt.core.model.PINNED_KEY
import com.photoxt.core.model.PINNED_TIME
import com.photoxt.core.model.SmartRule

/**
 * One timeline (Pictures, an album or a Library list) under a filter, a date grouping and a sort
 * (spec §4.2), as the SQL behind [MediaDao]'s timeline queries.
 *
 * The rows query returns each group's header followed by its items, so the headers sit at exact
 * positions: the paged grid can jump anywhere without headers shifting as pages load. Date sorts
 * group by their own date; Name, Size and Custom order show one group without a header. An
 * album's pinned items come first, under their own header, whatever the sort.
 */
data class TimelineQuery(
    val filter: MediaFilter = MediaFilter.ALL,
    val grouping: DateGrouping = DateGrouping.DAY,
    val albumId: String? = null,
    val sort: ItemSort = ItemSort(),
    val list: MediaList? = null,
    val rule: SmartRule? = null
) {
    private val items = MediaListQuery(filter, albumId, sort, list, rule)
    private val sql = MediaSql(items, grouping)

    /** Whether the non-pinned items have date headers (date sorts) or form one plain group. */
    val dated: Boolean get() = sort.field.dated

    /** Headers and items in display order, each header right before its items. */
    internal fun rows(): SupportSQLiteQuery = SimpleSQLiteQuery(
        """
        WITH s AS (${sql.inner})
        SELECT 1 AS isHeader, section, groupKey, NULL AS id, NULL AS contentUri, NULL AS kind,
            NULL AS durationMs, 0 AS favorite, 0 AS modifiedAt, MAX(takenAt) AS takenAt,
            0 AS width, 0 AS height, 0 AS rotation, NULL AS source, NULL AS remoteVersion,
            NULL AS name, 0 AS bytes, 0 AS groupTime, NULL AS pinPos, NULL AS sortKey
        FROM s ${if (dated) "" else "WHERE section = 0"} GROUP BY section, groupKey
        UNION ALL
        SELECT 0, section, groupKey, id, contentUri, kind, durationMs, favorite, modifiedAt,
            takenAt, width, height, rotation, source, remoteVersion, name, bytes, groupTime,
            pinPos, sortKey
        FROM s
        ORDER BY section, groupKey ${sql.dir}, isHeader DESC, ${sql.itemOrder}
        """.trimIndent(),
        sql.args.toTypedArray()
    )

    /** Item count per group in display order: the layout of [rows] without loading it. */
    internal fun groupCounts(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "WITH s AS (${sql.inner}) SELECT groupKey, COUNT(*) AS count FROM s " +
            "GROUP BY section, groupKey ORDER BY section, groupKey ${sql.dir}",
        sql.args.toTypedArray()
    )

    /** Every item of one group, for its select-all control. */
    internal fun stampsInGroup(key: Int): SupportSQLiteQuery = SimpleSQLiteQuery(
        "WITH s AS (${sql.inner}) SELECT id, groupTime AS takenAt FROM s WHERE groupKey = ?",
        (sql.args + key).toTypedArray()
    )
}

/**
 * The items of one timeline without date headers: what the viewer pages through, in exactly the
 * order of the grid's items (pinned first, then the sort, ties broken by capture time and id).
 */
data class MediaListQuery(
    val filter: MediaFilter = MediaFilter.ALL,
    val albumId: String? = null,
    val sort: ItemSort = ItemSort(),
    val list: MediaList? = null,
    /** A smart album's rule; a smart album without one shows nothing. */
    val rule: SmartRule? = null
) {
    private val sql = MediaSql(this, DateGrouping.DAY)

    /** Every item, in display order. */
    internal fun items(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "WITH s AS (${sql.inner}) SELECT $VIEWER_COLUMNS FROM s " +
            "ORDER BY section, groupKey ${sql.dir}, ${sql.itemOrder}",
        sql.args.toTypedArray()
    )

    /** The ids of [items], in the same order. */
    internal fun ids(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "WITH s AS (${sql.inner}) SELECT id FROM s " +
            "ORDER BY section, groupKey ${sql.dir}, ${sql.itemOrder}",
        sql.args.toTypedArray()
    )

    /**
     * Custom order's start (spec §4.2): a place in album [albumId] for every item that has none,
     * in the order of [items], as one statement and its bound values. Rows go in by item id, the
     * order of `item_order`'s key, so its index fills in order.
     */
    internal fun seedOrder(albumId: String): Pair<String, Array<Any>> =
        """
        WITH s AS (${sql.inner})
        INSERT OR IGNORE INTO item_order (albumId, mediaId, position)
        SELECT ?, id, n FROM (SELECT id, ROW_NUMBER() OVER
            (ORDER BY section, groupKey ${sql.dir}, ${sql.itemOrder}) AS n FROM s)
        ORDER BY id
        """.trimIndent() to (sql.args + albumId).toTypedArray()

    /** Where item [id] sits among [items], 0 being the first; no row when it isn't there. */
    internal fun positionOf(id: String): SupportSQLiteQuery = SimpleSQLiteQuery(
        """
        WITH s AS (${sql.inner})
        SELECT n FROM (SELECT id, ROW_NUMBER() OVER
            (ORDER BY section, groupKey ${sql.dir}, ${sql.itemOrder}) - 1 AS n FROM s)
        WHERE id = ?
        """.trimIndent(),
        (sql.args + id).toTypedArray()
    )

    /** How many items there are. */
    internal fun count(): SupportSQLiteQuery = SimpleSQLiteQuery(
        "WITH s AS (${sql.inner}) SELECT COUNT(*) FROM s",
        sql.args.toTypedArray()
    )

    /** Count, newest item and size of the items, for a smart album's tile. */
    internal fun summary(): SupportSQLiteQuery = SimpleSQLiteQuery(
        """
        WITH s AS (${sql.inner})
        SELECT COUNT(s.id) AS count, COALESCE(MAX(s.takenAt), 0) AS latestTakenAt,
            COALESCE(SUM(s.bytes), 0) AS totalBytes, c.id AS coverId, c.contentUri AS coverUri,
            COALESCE(c.modifiedAt, 0) AS coverModifiedAt, c.remoteVersion AS coverVersion,
            c.source AS coverSource, c.accountId AS coverAccountId
        FROM s LEFT JOIN (SELECT * FROM s ORDER BY takenAt DESC, id DESC LIMIT 1) c ON 1
        """.trimIndent(),
        sql.args.toTypedArray()
    )

    /** Whether this lists one album, whose pinned items come first (spec §4.8). */
    internal val inAlbum: Boolean get() = albumId != null
}

/** What a list's tile shows: how many items, the newest one as its cover, and their size. */
data class ListSummary(
    val count: Int,
    val latestTakenAt: Long,
    val totalBytes: Long,
    val coverId: String?,
    val coverUri: String?,
    val coverModifiedAt: Long,
    val coverVersion: Long?,
    val coverSource: com.photoxt.core.model.SourceType?,
    val coverAccountId: String?
)

/** The SQL of a [MediaListQuery]: one inner select (`s`) every query reads from. */
private class MediaSql(query: MediaListQuery, grouping: DateGrouping) {
    private val sort = query.sort
    private val inAlbum = query.inAlbum
    private val argList = mutableListOf<Any>()

    /** The bound values of [inner], in order. */
    val args: List<Any> get() = argList

    /** The direction of the sort; Custom order runs its one way. */
    val dir = if (sort.descending && sort.field != ItemSortField.CUSTOM) "DESC" else "ASC"

    /** Items without a place in Custom order come first, newest first (spec §4.2). */
    private val tieDir = if (sort.field == ItemSortField.CUSTOM) "DESC" else dir

    val itemOrder = "pinPos, sortKey $dir, takenAt $tieDir, id $tieDir"

    val inner: String

    init {
        val joins = buildList {
            if (inAlbum) add("LEFT JOIN pin p ON p.targetType = 'ITEM' AND p.targetId = m.id")
            if (sort.field == ItemSortField.CUSTOM) {
                add("LEFT JOIN item_order io ON io.albumId = ? AND io.mediaId = m.id")
                argList += query.albumId.orEmpty()
            }
        }
        val conditions = conditionsOf(query)
        val pinned = if (inAlbum) "p.targetId IS NOT NULL" else "0"
        val dateKey = dateKey(sort.field, grouping)
        inner = """
            SELECT m.*, CASE WHEN $pinned THEN 0 ELSE 1 END AS section,
                CASE WHEN $pinned THEN $PINNED_KEY ELSE $dateKey END AS groupKey,
                ${if (inAlbum) "p.position" else "NULL"} AS pinPos,
                ${sortKey(sort.field)} AS sortKey,
                CASE WHEN $pinned THEN $PINNED_TIME ELSE ${groupTime(sort.field)} END AS groupTime
            FROM media m ${joins.joinToString(" ")}
            ${if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")}
        """.trimIndent()
    }

    private fun conditionsOf(query: MediaListQuery): List<String> = buildList {
        filterCondition(query.filter)?.let(::add)
        val album = query.albumId
        when {
            album != null && album.startsWith(VIRTUAL_PREFIX) -> {
                add("m.id IN (SELECT mediaId FROM album_item WHERE albumId = ?)")
                argList += album
                add(NOT_IN_HIDDEN_ALBUM)
            }

            album != null && album.startsWith(SMART_PREFIX) -> {
                val rule = query.rule
                if (rule == null) add("0") else addAll(ruleConditions(rule))
                add(NOT_IN_HIDDEN_ALBUM)
            }

            album != null -> {
                add("m.albumId = ?")
                argList += album
            }

            query.list != null -> {
                add(listCondition(query.list))
                if (query.list != MediaList.HIDDEN) add(NOT_IN_HIDDEN_ALBUM)
            }

            // Pictures: everything but hidden albums and albums kept out of it (spec §4.2).
            else -> add(
                "m.albumId NOT IN (SELECT albumId FROM album_prefs " +
                    "WHERE hidden = 1 OR hideFromPictures = 1)"
            )
        }
        if (query.list != MediaList.HIDDEN) {
            add("m.id NOT IN (SELECT mediaId FROM hidden_item)")
        }
    }

    private fun ruleConditions(rule: SmartRule): List<String> = buildList {
        rule.kind?.let {
            add("m.kind = ?")
            argList += it.name
        }
        rule.from?.let {
            add("m.takenAt >= ?")
            argList += it
        }
        rule.until?.let {
            add("m.takenAt < ?")
            argList += it
        }
        rule.source?.let {
            add("m.source = ?")
            argList += it.name
        }
        if (rule.albumIds.isNotEmpty()) {
            add("m.albumId IN (${rule.albumIds.joinToString(",") { "?" }})")
            argList.addAll(rule.albumIds)
        }
        rule.camera?.takeIf { it.isNotBlank() }?.let {
            add("m.camera LIKE ? ESCAPE '\\'")
            argList += "%" + it.trim().replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%"
        }
        rule.minBytes?.let {
            add("m.bytes >= ?")
            argList += it
        }
        rule.maxBytes?.let {
            add("m.bytes <= ?")
            argList += it
        }
        rule.minDurationMs?.let {
            add("m.durationMs >= ?")
            argList += it
        }
        if (rule.favorite) add("m.favorite = 1")
        if (rule.hasLocation) add("m.lat IS NOT NULL")
    }

    private companion object {
        const val NOT_IN_HIDDEN_ALBUM =
            "m.albumId NOT IN (SELECT albumId FROM album_prefs WHERE hidden = 1)"

        fun filterCondition(filter: MediaFilter): String? = when (filter) {
            MediaFilter.ALL -> null
            MediaFilter.PHOTOS -> "m.kind = 'IMAGE'"
            MediaFilter.VIDEOS -> "m.kind = 'VIDEO'"
            MediaFilter.FAVORITES -> "m.favorite = 1"
            MediaFilter.DEVICE -> "m.source = 'DEVICE'"
            MediaFilter.DRIVE -> "m.source = 'DRIVE'"
            MediaFilter.OFFLINE -> "m.id IN (SELECT mediaId FROM offline_pin)"
        }

        fun listCondition(list: MediaList): String = when (list) {
            MediaList.FAVORITES -> "m.favorite = 1"

            MediaList.VIDEOS -> "m.kind = 'VIDEO'"

            // Recognised as recogniseFolder does: a phone folder named Screenshots.
            MediaList.SCREENSHOTS ->
                "m.albumId IN (SELECT id FROM album WHERE type = 'DEVICE_FOLDER' AND " +
                    "(lower(sourceRef) LIKE '%/screenshots/' OR " +
                    "lower(sourceRef) LIKE '%:screenshots/'))"

            MediaList.OFFLINE -> "m.id IN (SELECT mediaId FROM offline_pin)"

            MediaList.HIDDEN -> "m.id IN (SELECT mediaId FROM hidden_item)"
        }

        fun dateKey(field: ItemSortField, grouping: DateGrouping): String {
            val prefix = when (field) {
                ItemSortField.DATE_ADDED -> "added"
                ItemSortField.DATE_MODIFIED -> "modified"
                ItemSortField.DATE_TAKEN -> ""
                else -> return "0"
            }
            fun column(name: String) = if (prefix.isEmpty()) {
                "m.$name"
            } else {
                "m.$prefix${name.replaceFirstChar(
                    Char::uppercase
                )}"
            }
            return when (grouping) {
                DateGrouping.DAY -> column("dayKey")
                DateGrouping.MONTH -> column("monthKey")
                DateGrouping.YEAR -> "(${column("monthKey")} / 12)"
            }
        }

        fun sortKey(field: ItemSortField): String = when (field) {
            ItemSortField.DATE_TAKEN -> "m.takenAt"
            ItemSortField.DATE_ADDED -> "m.addedAt"
            ItemSortField.DATE_MODIFIED -> "m.modifiedAt"
            ItemSortField.NAME -> "m.nameKey"
            ItemSortField.SIZE -> "m.bytes"
            ItemSortField.CUSTOM -> "io.position"
        }

        fun groupTime(field: ItemSortField): String = when (field) {
            ItemSortField.DATE_ADDED -> "m.addedAt"
            ItemSortField.DATE_MODIFIED -> "m.modifiedAt"
            else -> "m.takenAt"
        }
    }
}

/** Album ids of PhotoXT's own albums (spec §3): virtual and smart. */
const val VIRTUAL_PREFIX = "virtual:"
const val SMART_PREFIX = "smart:"

internal const val VIEWER_COLUMNS = "id, contentUri, albumId, name, mime, kind, bytes, width, " +
    "height, rotation, durationMs, takenAt, modifiedAt, favorite, source, accountId, remoteId, " +
    "remoteVersion, lat, lng, camera"
