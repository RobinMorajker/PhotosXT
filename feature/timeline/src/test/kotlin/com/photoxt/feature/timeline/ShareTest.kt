package com.photoxt.feature.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTest {
    @Test
    fun shareSheetGetsTheNarrowestTypeThatFitsEveryItem() {
        assertEquals("image/jpeg", commonMimeType(listOf("image/jpeg", "image/jpeg")))
        assertEquals("image/*", commonMimeType(listOf("image/jpeg", "image/heic")))
        assertEquals("*/*", commonMimeType(listOf("image/jpeg", "video/mp4")))
    }
}
