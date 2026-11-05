package com.photoxt.core.ui

import com.photoxt.core.model.DateGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {
    // Rows: 0 H(30) 1 2 3 | 4 H(20) 5 | 6 H(10) 7 8
    private val layout = TimelineLayout(
        DateGrouping.DAY,
        keys = intArrayOf(30, 20, 10),
        counts = intArrayOf(3, 1, 2)
    )

    @Test
    fun knowsWhereEveryHeaderAndItemIs() {
        assertEquals(9, layout.rowCount)
        assertEquals(6, layout.itemCount)
        assertEquals(listOf(0, 4, 6), (0 until 9).filter(layout::isHeader))
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2, 2, 2), (0 until 9).map(layout::groupOf))
        assertEquals(-1, layout.groupOf(9))
        assertEquals(1, layout.countOfKey(20))
        assertEquals(0, layout.countOfKey(99))
    }

    @Test
    fun mapsItemsToRowsAndBack() {
        val itemRows = listOf(1, 2, 3, 5, 7, 8)
        itemRows.forEachIndexed { ordinal, row ->
            assertEquals(ordinal, layout.itemOrdinalAt(row))
            assertEquals(row, layout.rowOfItem(ordinal))
        }
        assertEquals("a header stands for its first item", 3, layout.itemOrdinalAt(4))
    }

    @Test
    fun keepsTheSameItemAcrossGroupings() {
        // The same six items grouped by month: rows 0 H 1 2 3 4 | 5 H 6 7.
        val months = TimelineLayout(DateGrouping.MONTH, intArrayOf(2, 1), intArrayOf(4, 2))
        val ordinal = layout.itemOrdinalAt(5)
        assertEquals(4, months.rowOfItem(ordinal))
        assertTrue(months.isHeader(5))
        assertFalse(TimelineLayout.EMPTY.isHeader(0))
        assertEquals(0, TimelineLayout.EMPTY.rowOfItem(3))
    }

    @Test
    fun dragSelectAddsOrRemovesTheWholeRange() {
        val cells = listOf(
            GridCell.Header(30),
            item("a"),
            item("b"),
            GridCell.Header(20),
            item("c")
        )
        val added = rangeSelection(emptyMap(), from = 4, to = 1, adding = true) { cells[it] }
        assertEquals(setOf("a", "b", "c"), added.keys)
        val removed = rangeSelection(added, from = 2, to = 4, adding = false) { cells[it] }
        assertEquals(setOf("a"), removed.keys)
    }

    private fun item(id: String) = GridCell.Item(id, null, false, null, false, takenAt = 0)
}
