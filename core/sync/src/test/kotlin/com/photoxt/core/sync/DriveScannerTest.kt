package com.photoxt.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.TrackedFolderState
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The initial scan against a fake Drive (spec §0 rule 6, §4.3, §5.5). */
@RunWith(AndroidJUnit4::class)
class DriveScannerTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var database: PxtDatabase
    private lateinit var scanner: DriveScanner

    /** What each fake folder holds, as JSON file objects. */
    private val folders = mutableMapOf(
        // Two pages, to follow nextPageToken.
        "root" to listOf(
            listOf(photo("p1", "2024:05:01 10:00:00"), folder("sub", "Trip"), doc("d1")),
            listOf(video("v1"), shortcut("s1", "px"))
        ),
        "sub" to listOf(listOf(photo("p2", "2023:12:31 23:30:00")))
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
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
        val clock = Clock.systemUTC()
        val http = DriveHttp(tokens, server.url("/"))
        scanner = DriveScanner(http, database, DriveItems(database, clock), clock)
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun scansTheTreeIntoNestedAlbumsAfterTakingTheChangesPosition() = runTest {
        val tracked = track(merge = false)

        val result = scanner.scan(tracked, ZoneOffset.UTC)

        assertEquals("startPageToken", requests.first())
        assertEquals(ScanResult(folders = 2, items = 4, removed = 0), result)
        val drive = database.driveDao()
        assertEquals("42", drive.account("a")?.changesToken)
        assertEquals(TrackedFolderState.READY, drive.trackedFolder(ROOT)?.state)
        assertEquals(listOf("drive:a:sub"), drive.albumIdsUnder(ROOT) - ROOT)
        val p1 = drive.driveItem("drive:a:p1")!!
        assertEquals(MediaKind.IMAGE, p1.kind)
        assertEquals(7L, p1.remoteVersion)
        assertEquals(albumOf("drive:a:p1"), ROOT)
        // The shortcut counts as its target, in the folder that holds the shortcut.
        assertEquals(albumOf("drive:a:px"), ROOT)
        assertEquals(albumOf("drive:a:p2"), "drive:a:sub")
        assertNull(drive.driveItem("drive:a:d1"))
        val takenAt = LocalDateTime.of(2024, 5, 1, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(takenAt, takenAtOf("drive:a:p1"))
        assertEquals(DURATION_MS, durationOf("drive:a:v1"))
    }

    @Test
    fun aRescanMergesSubfoldersAndDropsWhatDriveNoLongerHas() = runTest {
        scanner.scan(track(merge = false), ZoneOffset.UTC)
        folders["sub"] = listOf(emptyList())
        database.driveDao().setMerge(ROOT, true)
        val tracked = database.driveDao().trackedFolder(ROOT)!!

        val result = scanner.scan(tracked, ZoneOffset.UTC)

        assertEquals(1, result.removed)
        assertNull(database.driveDao().driveItem("drive:a:p2"))
        assertEquals(emptyList<String>(), database.driveDao().albumIdsUnder(ROOT) - ROOT)
        assertEquals(3, database.driveDao().mediaIdsUnder(ROOT).size)
    }

    private suspend fun track(merge: Boolean): TrackedFolderEntity {
        val drive = database.driveDao()
        drive.upsertAccount(
            AccountEntity("a", "a@example.com", null, null, "", false, null, null, 1)
        )
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    ROOT,
                    AlbumType.DRIVE_FOLDER,
                    "drive:Holidays/",
                    "Holidays",
                    "a",
                    null,
                    ROOT
                )
            )
        )
        val tracked =
            TrackedFolderEntity(ROOT, "a", "root", null, merge, TrackedFolderState.SCANNING, null)
        drive.insertTracked(tracked)
        return tracked
    }

    private fun albumOf(mediaId: String): String = database.query(
        "SELECT albumId FROM media WHERE id = ?",
        arrayOf(mediaId)
    ).use {
        it.moveToFirst()
        it.getString(0)
    }

    private fun takenAtOf(mediaId: String): Long = longOf("takenAt", mediaId)

    private fun durationOf(mediaId: String): Long = longOf("durationMs", mediaId)

    private fun longOf(column: String, mediaId: String): Long =
        database.query("SELECT $column FROM media WHERE id = ?", arrayOf(mediaId)).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val body = when {
            path.endsWith("/changes/startPageToken") -> {
                requests += "startPageToken"
                """{"startPageToken":"42"}"""
            }

            path == "/drive/v3/files" -> {
                val parent = Regex("'([^']+)' in parents").find(url.queryParameter("q")!!)!!
                    .groupValues[1]
                requests += "list:$parent"
                val pages = folders.getValue(parent)
                val page = url.queryParameter("pageToken")?.toInt() ?: 0
                val next = if (page + 1 < pages.size) ""","nextPageToken":"${page + 1}"""" else ""
                """{"files":[${pages[page].joinToString(",")}]$next}"""
            }

            path == "/drive/v3/files/root" -> """{"id":"root","name":"Holidays"}"""

            path == "/drive/v3/files/px" -> photo("px", null)

            else -> return MockResponse.Builder().code(404).build()
        }
        return MockResponse.Builder().body(body).build()
    }

    private companion object {
        const val ROOT = "drive:a:root"
        const val DURATION_MS = 61_000L

        fun photo(id: String, time: String?) = """
            {"id":"$id","name":"$id.jpg","mimeType":"image/jpeg","size":"1000","version":"7",
             "modifiedTime":"2025-01-01T00:00:00.000Z","hasThumbnail":true,
             "thumbnailLink":"https://lh3.example/$id=s220",
             "imageMediaMetadata":{"width":4000,"height":3000,"rotation":1
             ${if (time != null) ""","time":"$time"""" else ""}}}
        """.trimIndent()

        fun video(id: String) = """
            {"id":"$id","name":"$id.mp4","mimeType":"video/mp4","size":"5000","version":"3",
             "modifiedTime":"2025-02-01T00:00:00.000Z",
             "videoMediaMetadata":{"width":1920,"height":1080,"durationMillis":"$DURATION_MS"}}
        """.trimIndent()

        fun folder(id: String, name: String) =
            """{"id":"$id","name":"$name","mimeType":"application/vnd.google-apps.folder"}"""

        fun doc(id: String) =
            """{"id":"$id","name":"Notes","mimeType":"application/vnd.google-apps.document"}"""

        fun shortcut(id: String, target: String) = """
            {"id":"$id","name":"link","mimeType":"application/vnd.google-apps.shortcut",
             "shortcutDetails":{"targetId":"$target","targetMimeType":"image/jpeg"}}
        """.trimIndent()
    }
}
