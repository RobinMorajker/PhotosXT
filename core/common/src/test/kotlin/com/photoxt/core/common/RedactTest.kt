package com.photoxt.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test
    fun masksSecretsAndPersonalData() {
        val cases = mapOf(
            "Authorization: Bearer ya29.a0AfH6SMBx-1_z" to "Authorization: Bearer <token>",
            "token ya29.a0AfH6SMBx-1_z expired" to "token <token> expired",
            "jwt eyJhbGciOi.eyJzdWIiOi.SflKxwRJ done" to "jwt <token> done",
            "GET /files?access_token=abc123&alt=media" to
                "GET /files?access_token=<redacted>&alt=media",
            "signed in as jane.doe+test@example.com" to "signed in as <email>",
            "open content://media/external/images/media/42 failed" to "open <uri> failed",
            "wrote /storage/emulated/0/DCIM/Camera/IMG_1.jpg" to "wrote <path>",
            "decoding IMG_20260101_101010.HEIC" to "decoding <file>",
            "upload \"Mum birthday (1).mp4\" queued" to "upload \"<file>\" queued",
            "at 15.49893, 73.82780 in Goa" to "at <location> in Goa"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, redact(input)) }
    }

    @Test
    fun keepsOrdinaryMessages() {
        val message = "Sync finished: 120 items in 1.5 s (page 3 of 4) at MainActivity.kt:12"
        assertEquals(message, redact(message))
    }

    @Test
    fun masksStackTraceMessages() {
        val trace = RuntimeException("cannot read /sdcard/Pictures/a.png").stackTraceToString()
        assertFalse(redact(trace).contains("a.png"))
    }
}
