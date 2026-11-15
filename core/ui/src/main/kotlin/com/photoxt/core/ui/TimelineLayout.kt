package com.photoxt.core.ui

import androidx.compose.runtime.Immutable
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.PINNED_KEY

/**
 * Where every group sits in a timeline whose rows are each group's header followed by its items,
 * in display order. It is built from per-group item counts, so it knows every row's place before
 * the rows themselves are loaded: spans for placeholders, fast-scroller labels, and keeping the
 * same photo in view when the grouping changes.
 *
 * Date sorts ([headed]) give every group a header. Name, Size and Custom order show one group
 * without one (spec §4.2, Layout); an album's pinned items ([PINNED_KEY]) always have theirs.
 */
@Immutable
class TimelineLayout(
    val grouping: DateGrouping,
    val keys: IntArray,
    counts: IntArray,
    val headed: Boolean = true
) {
    /** The row of each group's header, or of its first item when it has none. */
    private val startRows = IntArray(keys.size)
    private val hasHeader = BooleanArray(keys.size)
    private val headerRows: IntArray
    private val itemsBefore = IntArray(keys.size)
    private val groupByKey = HashMap<Int, Int>(keys.size * 2)

    /** Headers plus items. */
    val rowCount: Int

    val itemCount: Int

    init {
        require(keys.size == counts.size) { "One count per group" }
        var row = 0
        var items = 0
        val headers = ArrayList<Int>(keys.size)
        for (group in keys.indices) {
            groupByKey[keys[group]] = group
            startRows[group] = row
            hasHeader[group] = headed || keys[group] == PINNED_KEY
            if (hasHeader[group]) headers += row
            itemsBefore[group] = items
            row += counts[group] + if (hasHeader[group]) 1 else 0
            items += counts[group]
        }
        headerRows = headers.toIntArray()
        rowCount = row
        itemCount = items
    }

    fun isHeader(row: Int): Boolean = headerRows.binarySearch(row) >= 0

    /** The group that [row] belongs to, as an index into [keys], or -1 if it is out of range. */
    fun groupOf(row: Int): Int {
        if (row !in 0 until rowCount) return -1
        val found = startRows.binarySearch(row)
        return if (found >= 0) found else -found - 2
    }

    /** Items in group [group] (an index into [keys]). */
    fun countOf(group: Int): Int {
        val next = if (group + 1 < keys.size) startRows[group + 1] else rowCount
        return next - startRows[group] - if (hasHeader[group]) 1 else 0
    }

    /** Items in the group with [key], or 0 if there is none. */
    fun countOfKey(key: Int): Int = groupByKey[key]?.let(::countOf) ?: 0

    /**
     * The position among items only (0 = first) of the item at [row], or of the group's first
     * item when [row] is a header.
     */
    fun itemOrdinalAt(row: Int): Int {
        val group = groupOf(row)
        if (group < 0) return 0
        val first = startRows[group] + if (hasHeader[group]) 1 else 0
        return itemsBefore[group] + (row - first).coerceAtLeast(0)
    }

    /** The row of the item at [ordinal] among items only. */
    fun rowOfItem(ordinal: Int): Int {
        if (itemCount == 0) return 0
        val item = ordinal.coerceIn(0, itemCount - 1)
        val found = itemsBefore.binarySearch(item)
        // Several groups can start at one item index only when some are empty; take the last.
        var group = if (found >= 0) found else -found - 2
        while (group + 1 < keys.size && itemsBefore[group + 1] == item) group++
        return startRows[group] + (if (hasHeader[group]) 1 else 0) + (item - itemsBefore[group])
    }

    companion object {
        val EMPTY = TimelineLayout(DateGrouping.DAY, IntArray(0), IntArray(0))
    }
}
