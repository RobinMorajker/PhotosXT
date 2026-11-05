package com.photoxt.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPositionsTest {
    @Test
    fun aVideoResumesWhereItWasLeftUnlessBarelyStartedOrNearlyOver() {
        assertEquals(65_000L, resumePosition(65_000, 300_000))
        assertNull(resumePosition(2_000, 300_000))
        assertNull(resumePosition(298_000, 300_000))
        assertNull(resumePosition(290_000, 300_000))
        assertEquals(12_000L, resumePosition(12_000, 0))
    }
}
