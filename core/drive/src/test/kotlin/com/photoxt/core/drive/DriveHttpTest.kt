package com.photoxt.core.drive

import com.photoxt.core.auth.DriveTokens
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Signing Drive requests per account (spec §2, §5.7). */
class DriveHttpTest {
    private val server = MockWebServer()
    private val invalidated = mutableListOf<String>()
    private var nextToken = 1

    private val tokens = object : DriveTokens {
        override suspend fun token(accountId: String) = "$accountId-token-$nextToken"

        override suspend fun invalidate(accountId: String, token: String) {
            invalidated += token
            nextToken++
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun aRefusedTokenIsDroppedAndTheRequestRetriedOnceWithANewOne() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().body("""{"startPageToken":"9"}""").build())
        val http = DriveHttp(tokens, server.url("/"))

        val token = http.api.startPageToken(DriveAccount("me")).startPageToken

        assertEquals("9", token)
        assertEquals(listOf("me-token-1"), invalidated)
        assertEquals("Bearer me-token-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer me-token-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun theAccountHeaderNeverLeavesThePhone() = runTest {
        server.enqueue(MockResponse.Builder().code(206).body("abc").build())
        val http = DriveHttp(tokens, server.url("/"))
        val request = okhttp3.Request.Builder()
            .url(http.mediaUrl("f1"))
            .header(DriveHttp.ACCOUNT_HEADER, "me")
            .header("Range", "bytes=10-")
            .build()

        http.client.newCall(request).await().close()

        val sent = server.takeRequest()
        assertNull(sent.headers[DriveHttp.ACCOUNT_HEADER])
        assertEquals("Bearer me-token-1", sent.headers["Authorization"])
        assertEquals("/drive/v3/files/f1?alt=media&supportsAllDrives=true", sent.target)
    }

    @Test
    fun queryLiteralsAreEscaped() {
        assertEquals("""'Robin\'s \\ photos'""", driveLiteral("""Robin's \ photos"""))
    }
}
