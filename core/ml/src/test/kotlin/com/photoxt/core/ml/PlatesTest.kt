package com.photoxt.core.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** *Find faces, text and number plates* hides plates, not every word the text reader finds. */
class PlatesTest {
    @Test
    fun platesAreFound() {
        val indian = listOf("GA 07 AB 1234", "MH12AB1234", "ga-07-ab-1234", "22 BH 1234 AA")
        val others = listOf("AB12 CDE", "ABC 123")
        (indian + others).forEach { assertTrue(it, Plates.looksLikePlate(it)) }
    }

    @Test
    fun otherTextIsNot() {
        listOf("HELLO", "2026", "Total 12.50", "OPEN 24 HOURS", "Room 101", "NO 1", "12:45 PM")
            .forEach { assertFalse(it, Plates.looksLikePlate(it)) }
    }
}
