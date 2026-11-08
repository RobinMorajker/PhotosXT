package com.photoxt.core.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opening a Drive original: resumed downloads and checksums (spec §0 rule 4, §4.4). */
@RunWith(AndroidJUnit4::class)
class DriveOriginalsTest {
    private val server = MockWebServer()
    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val ranges = mutableListOf<String?>()
    private lateinit var database: PxtDatabase
    private lateinit var store: ThumbnailStore
    private lateinit var originals: DriveOriginals

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                ranges += range
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val body = Buffer().write(content, from, content.size - from)
                return MockResponse.Builder()
                    .code(if (range != null) 206 else 200)
                    .body(body)
                    .build()
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PxtDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tokens = object : DriveTokens {
            override suspend fun token(accountId: String) = "test-token"

            override suspend fun invalidate(accountId: String, token: String) = Unit
        }
        val http = DriveHttp(tokens, server.url("/"))
        store =
            ThumbnailStore(File(context.cacheDir, "originals-test").apply { deleteRecursively() })
        originals = DriveOriginals(
            database.driveDao(),
            database.driveCacheDao(),
            MediaSources(DeviceSource(context), DriveSource(http)),
            store,
            Clock.systemUTC()
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun resumesAPartialDownloadWithARangeRequestAndChecksIt() = runTest {
        insert(md5 = md5(content))
        val target = store.original("a", "f1", 5, "image/jpeg")
        target.parentFile!!.mkdirs()
        File(target.path + ".part").writeBytes(content.copyOf(PARTIAL))

        val result = originals.original("drive:a:f1").last()

        assertTrue(result is Original.Ready)
        assertEquals(listOf("bytes=$PARTIAL-"), ranges)
        assertArrayEquals(content, (result as Original.Ready).file.readBytes())
        assertEquals(target.path, database.driveDao().cached("drive:a:f1")?.path)
    }

    @Test
    fun aChecksumMismatchFailsAndDiscardsTheDownload() = runTest {
        insert(md5 = "0".repeat(32))

        val result = originals.original("drive:a:f1").last()

        assertTrue(result is Original.Failed)
        val target = store.original("a", "f1", 5, "image/jpeg")
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    private suspend fun insert(md5: String) {
        database.driveDao().upsertAccount(
            AccountEntity("a", "a@example.com", null, null, "", false, null, null, 1)
        )
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    "drive:a:r",
                    AlbumType.DRIVE_FOLDER,
                    "drive:R/",
                    "R",
                    "a",
                    null,
                    "drive:a:r"
                )
            )
        )
        database.mediaIndexDao().upsertMedia(
            listOf(
                MediaEntity(
                    id = "drive:a:f1", source = SourceType.DRIVE, volume = null, contentUri = null,
                    albumId = "drive:a:r", name = "f1.jpg", mime = "image/jpeg",
                    kind = MediaKind.IMAGE, bytes = content.size.toLong(), width = 1, height = 1,
                    rotation = 0, durationMs = null, takenAt = 1, modifiedAt = 1, dayKey = 0,
                    monthKey = 0, favorite = false, accountId = "a", remoteId = "f1",
                    remoteVersion = 5, md5 = md5
                )
            )
        )
    }

    private companion object {
        const val PARTIAL = 50_000

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
