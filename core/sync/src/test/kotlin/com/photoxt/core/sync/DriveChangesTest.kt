package com.photoxt.core.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.DriveCacheEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.media.DriveCache
import com.photoxt.core.media.DriveThumbnail
import com.photoxt.core.media.DriveThumbnails
import com.photoxt.core.media.DriveVideo
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.TrackedFolderState
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Incremental sync against a fake Drive's Changes feed (spec §0 rule 6, §4.3, §5.5). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveChangesTest {
    private val server = MockWebServer()
    private val clock = FixedClock(Instant.parse("2026-11-01T10:00:00Z"))
    private lateinit var database: PxtDatabase
    private lateinit var store: ThumbnailStore
    private lateinit var scanner: DriveScanner
    private lateinit var changes: DriveChanges
    private lateinit var thumbnails: DriveThumbnails
    private lateinit var cache: DriveCache

    /** The fake Drive: folder listings, the Changes feed by position, and the tracked folder. */
    private lateinit var folders: MutableMap<String, List<String>>
    private val feed = mutableMapOf<String, String>()
    private var startToken = "42"
    private var root = """{"id":"root","name":"Holidays","trashed":false}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        // Thumbnail links point at the fake server, so the tree is made once it runs.
        folders = mutableMapOf(
            "root" to listOf(photo("p1", "root", md5 = "aa"), folder("sub", "Trip", "root")),
            "sub" to listOf(photo("p2", "sub", md5 = "bb"))
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PxtDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tokens = object : DriveTokens {
            override suspend fun token(accountId: String) = "test-token"

            override suspend fun invalidate(accountId: String, token: String) = Unit
        }
        val http = DriveHttp(tokens, server.url("/"))
        val items = DriveItems(database, clock)
        store = ThumbnailStore(File(context.cacheDir, "changes-test").apply { deleteRecursively() })
        scanner = DriveScanner(http, database, items, clock)
        changes = DriveChanges(http, database, items, clock)
        thumbnails = DriveThumbnails(database.driveDao(), http, tokens, store)
        cache = DriveCache(database, store, DriveVideo(context, http), clock)
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun addsRenamesMovesAndRemovesItemsAsDriveReportsThem() = runTest {
        scanTree()
        changesAt(
            "42",
            next = null,
            start = "43",
            fileChange(photo("p3", "root", md5 = "cc")),
            fileChange(photo("p1", "root", md5 = "aa", name = "Renamed.jpg", version = 9)),
            fileChange(photo("p2", "root", md5 = "bb")),
            fileChange(folder("sub", "Trip 2025", "root")),
            removed("v9"),
            fileChange(photo("x1", "elsewhere", md5 = "dd"))
        )
        folders["root"] = folders.getValue("root") + photo("p3", "root", md5 = "cc")

        val result = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(6, result.changes)
        assertEquals("43", database.driveDao().account("a")?.changesToken)
        assertEquals(ROOT, albumOf("p3"))
        assertEquals("Renamed.jpg", nameOf("p1"))
        // A rename is no new content: the version that keys its thumbnail stays.
        assertEquals(7L, database.driveDao().driveItem(id("p1"))?.remoteVersion)
        assertEquals(ROOT, albumOf("p2"))
        assertEquals("Trip 2025", titleOf(SUB))
        assertNull(database.driveDao().driveItem(id("x1")))
        assertEquals(emptySet<String>(), result.rescan)

        changesAt("43", next = null, start = "44", trashed(photo("p3", "root", md5 = "cc")))
        changes.sync("a", ZoneOffset.UTC)

        assertNull(database.driveDao().driveItem(id("p3")))
        assertEquals(1, database.driveCacheDao().orphansOf("a", listOf("p3")).size)
    }

    @Test
    fun aChangedFileRegeneratesItsThumbnail() = runTest {
        scanTree()
        val first = thumbnails.gridFile(DriveThumbnail(id("p1"), "p1", 7))
        assertTrue(isRed(colorOf(first)))
        changesAt(
            "42",
            next = null,
            start = "43",
            fileChange(photo("p1", "root", md5 = "new", version = 8))
        )

        changes.sync("a", ZoneOffset.UTC)
        cache.purgeDueOrphans()

        assertFalse(first.exists())
        assertTrue(id("p1") in database.driveDao().missingThumbnails(null, LIMIT))
        val remade = thumbnails.gridFile(DriveThumbnail(id("p1"), "p1", 8))
        assertTrue(isBlue(colorOf(remade)))
        assertFalse(id("p1") in database.driveDao().missingThumbnails(null, LIMIT))
    }

    @Test
    fun anItemBackFromTheBinGetsItsFilesAndPinBack() = runTest {
        scanTree()
        val original = store.original("a", "p1", 7, "image/jpeg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(SIZE.toInt()))
        }
        database.driveDao().saveCache(DriveCacheEntity(id("p1"), original.path, 7, SIZE, 1, 1))
        database.driveCacheDao().pin(listOf(id("p1")), 1)
        changesAt("42", next = "42b", start = null, trashed(photo("p1", "root", md5 = "aa")))
        changesAt(
            "42b",
            next = null,
            start = "43",
            fileChange(photo("p1", "root", md5 = "aa", version = 12))
        )

        changes.sync("a", ZoneOffset.UTC)

        assertEquals(7L, database.driveDao().driveItem(id("p1"))?.remoteVersion)
        assertEquals(original.path, database.driveDao().cached(id("p1"))?.path)
        assertTrue(database.driveCacheDao().isPinned(id("p1")))
        assertEquals(emptyList<Any>(), database.driveCacheDao().dueOrphans(Long.MAX_VALUE))
    }

    @Test
    fun aTrackedFolderBinnedInDriveKeepsItsItemsUntilItComesBack() = runTest {
        scanTree()
        changesAt(
            "42",
            next = null,
            start = "43",
            trashed(folder("root", "Holidays", "my-drive")),
            trashed(photo("p1", "root", md5 = "aa"))
        )

        val lost = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(setOf(ROOT), lost.unavailable)
        assertEquals(TrackedFolderState.UNAVAILABLE, database.driveDao().trackedFolder(ROOT)?.state)
        assertNotNull(database.driveDao().driveItem(id("p1")))

        changesAt(
            "43",
            next = null,
            start = "44",
            fileChange(folder("root", "Holidays", "my-drive"))
        )
        val back = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(setOf(ROOT), back.rescan)
        assertEquals(TrackedFolderState.SCANNING, database.driveDao().trackedFolder(ROOT)?.state)
    }

    @Test
    fun aRemovalWhoseTrackedFolderIsGoneKeepsTheItem() = runTest {
        scanTree()
        root = "404"
        changesAt("42", next = null, start = "43", removed("p1"))

        val result = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(setOf(ROOT), result.unavailable)
        assertNotNull(database.driveDao().driveItem(id("p1")))
    }

    @Test
    fun aNewFolderOrAnItemMovedOutMarksItsTreeForARescan() = runTest {
        scanTree()
        changesAt(
            "42",
            next = null,
            start = "43",
            fileChange(folder("new", "New", "sub")),
            fileChange(photo("p2", "elsewhere", md5 = "bb"))
        )

        val result = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(setOf(ROOT), result.rescan)
        assertEquals(TrackedFolderState.SCANNING, database.driveDao().trackedFolder(ROOT)?.state)
        // The rescan settles it: p2 is no longer in the tree.
        folders["sub"] = listOf(folder("new", "New", "sub"))
        folders["new"] = emptyList()
        scanner.scan(database.driveDao().trackedFolder(ROOT)!!, ZoneOffset.UTC)
        assertNull(database.driveDao().driveItem(id("p2")))
    }

    @Test
    fun anExpiredPositionStartsOverWithARescan() = runTest {
        scanTree()
        startToken = "99"

        val result = changes.sync("a", ZoneOffset.UTC)!!

        assertEquals(setOf(ROOT), result.rescan)
        assertEquals("99", database.driveDao().account("a")?.changesToken)
    }

    private suspend fun scanTree() {
        val drive = database.driveDao()
        drive.upsertAccount(
            AccountEntity("a", "a@example.com", null, null, "", false, null, null, 1)
        )
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(ROOT, AlbumType.DRIVE_FOLDER, "drive:H/", "Holidays", "a", null, ROOT)
            )
        )
        val tracked =
            TrackedFolderEntity(ROOT, "a", "root", null, false, TrackedFolderState.SCANNING, null)
        drive.insertTracked(tracked)
        scanner.scan(tracked, ZoneOffset.UTC)
        assertEquals("42", drive.account("a")?.changesToken)
    }

    private fun changesAt(token: String, next: String?, start: String?, vararg entries: String) {
        val tail = listOfNotNull(
            next?.let { """"nextPageToken":"$it"""" },
            start?.let { """"newStartPageToken":"$it"""" }
        )
        feed[token] = """{"changes":[${entries.joinToString(",")}],${tail.joinToString(",")}}"""
    }

    private fun albumOf(fileId: String): String = stringOf("albumId", fileId)

    private fun titleOf(albumId: String): String =
        database.query("SELECT title FROM album WHERE id = ?", arrayOf(albumId)).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun nameOf(fileId: String): String = stringOf("name", fileId)

    private fun stringOf(column: String, fileId: String): String =
        database.query("SELECT $column FROM media WHERE id = ?", arrayOf(id(fileId))).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun colorOf(file: File): Int = BitmapFactory.decodeFile(file.path).getPixel(2, 2)

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val body: Any? = when {
            path.endsWith("/changes/startPageToken") -> """{"startPageToken":"$startToken"}"""

            // An unknown position answers 410 Gone, as Drive does.
            path == "/drive/v3/changes" -> feed[url.queryParameter("pageToken")]

            path == "/drive/v3/files" -> {
                val parent = Regex("'([^']+)' in parents").find(url.queryParameter("q")!!)!!
                    .groupValues[1]
                """{"files":[${folders[parent].orEmpty().joinToString(",")}]}"""
            }

            path == "/drive/v3/files/root" -> root.takeIf { it != "404" }

            path.startsWith("/thumb/") ->
                Buffer().write(png(if ("blue" in path) Color.BLUE else Color.RED))

            else -> null
        }
        val response = MockResponse.Builder()
        return when (body) {
            is String -> response.body(body)
            is Buffer -> response.body(body)
            else -> response.code(if (path == "/drive/v3/changes") HTTP_GONE else HTTP_NOT_FOUND)
        }.build()
    }

    /** A photo in [parent]; new content (MD5 "new") comes with a blue thumbnail, else red. */
    private fun photo(
        fileId: String,
        parent: String,
        md5: String,
        name: String = "$fileId.jpg",
        version: Int = 7
    ): String {
        val thumb = if (md5 == "new") "blue" else "red"
        return """
            {"id":"$fileId","name":"$name","mimeType":"image/jpeg","size":"$SIZE",
             "version":"$version","md5Checksum":"$md5","parents":["$parent"],
             "modifiedTime":"2025-01-01T00:00:00.000Z","hasThumbnail":true,
             "thumbnailLink":"${server.url("/thumb/$fileId-$thumb=s220")}",
             "imageMediaMetadata":{"width":4000,"height":3000,"time":"2024:05:01 10:00:00"}}
        """.trimIndent()
    }

    private companion object {
        const val ROOT = "drive:a:root"
        const val SUB = "drive:a:sub"
        const val SIZE = 1000L
        const val LIMIT = 10
        const val HTTP_GONE = 410
        const val HTTP_NOT_FOUND = 404

        fun id(fileId: String) = "drive:a:$fileId"

        fun folder(id: String, name: String, parent: String) =
            """{"id":"$id","name":"$name","mimeType":"application/vnd.google-apps.folder",""" +
                """"parents":["$parent"]}"""

        fun fileChange(file: String) =
            """{"changeType":"file","fileId":${idOf(file)},"file":$file}"""

        fun removed(fileId: String) = """{"changeType":"file","fileId":"$fileId","removed":true}"""

        fun trashed(file: String) = fileChange(file.replaceFirst("{", """{"trashed":true,"""))

        fun idOf(file: String) = Regex(""""id":("[^"]+")""").find(file)!!.groupValues[1]

        fun isRed(color: Int) = Color.red(color) > 200 && Color.blue(color) < 60

        fun isBlue(color: Int) = Color.blue(color) > 200 && Color.red(color) < 60

        fun png(color: Int): ByteArray = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
    }
}

/** A clock that stands still. */
class FixedClock(private val now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
