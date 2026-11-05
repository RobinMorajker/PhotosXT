package com.photoxt.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTest {
    @Test
    fun timesReadAsMinutesOrHours() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_400))
        assertEquals("1:02:05", formatTime(3_725_000))
    }
}
