package com.photoxt.core.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountsTest {
    @Test
    fun theStableAccountIdIsReadFromTheIdToken() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                """{"iss":"accounts.google.com","sub":"1098765","email":"a@b.c"}""".toByteArray()
            )
        assertEquals("1098765", subjectOf("header.$payload.signature"))
        assertNull(subjectOf("not-a-token"))
    }

    @Test
    fun fullDriveCoversReadOnlyButNotTheOtherWay() {
        val full = DriveScope.FULL.uri
        val readonly = DriveScope.READONLY.uri
        assertTrue(DriveScope.READONLY.grantedIn("openid $full"))
        assertTrue(DriveScope.READONLY.grantedIn(readonly))
        assertFalse(DriveScope.FULL.grantedIn(readonly))
        assertFalse(DriveScope.READONLY.grantedIn(""))
    }
}
