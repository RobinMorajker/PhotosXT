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
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.last
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

/**
 * The cache lifecycle on a fake clock (spec §0 rule 6, §4.4): the retention rule, offline pins,
 * the cap, the preview LRU and the orphans' grace.
 */
@RunWith(AndroidJUnit4::class)
class DriveCacheTest {
    private val server = MockWebServer()
    private val clock = TestClock(Instant.parse("2026-11-01T10:00:00Z"))
    private lateinit var database: PxtDatabase
    private lateinit var store: ThumbnailStore
    private lateinit var originals: DriveOriginals
    private lateinit var cache: DriveCache

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val fileId = request.url.encodedPath.substringAfterLast('/')
                return MockResponse.Builder().body(Buffer().write(contentOf(fileId))).build()
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
        store = ThumbnailStore(File(context.cacheDir, "cache-test").apply { deleteRecursively() })
        originals = DriveOriginals(
            database.driveDao(),
            database.driveCacheDao(),
            MediaSources(DeviceSource(context), DriveSource(http)),
            store,
            clock
        )
        cache = DriveCache(database, store, DriveVideo(context, http), clock)
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun anItemOpened31DaysAgoReturnsToThumbnailOnlyUnlessPinned() = runTest {
        insert("old", "pinned", "recent")
        listOf("old", "pinned").forEach { open(it) }
        originals.keepOffline(id("pinned"))
        database.driveCacheDao().pin(listOf(id("pinned")), clock.millis())
        val grid = store.grid("old", VERSION).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        clock.advance(Duration.ofDays(2))
        open("recent")
        clock.advance(Duration.ofDays(29))

        cache.cleanUp(RETENTION, BIG, BIG)

        // Opened 31 days ago: thumbnail only, its grid thumbnail kept.
        assertNull(database.driveDao().cached(id("old")))
        assertFalse(store.original("a", "old", VERSION, MIME).exists())
        assertTrue(grid.exists())
        // Pinned: still offline, in media-offline/.
        val pinned = database.driveDao().cached(id("pinned"))
        assertNotNull(pinned)
        assertTrue(pinned!!.path.startsWith(store.offlinePrefix))
        assertTrue(File(pinned.path).exists())
        // Opened 29 days ago: still cached.
        assertTrue(File(database.driveDao().cached(id("recent"))!!.path).exists())
    }

    @Test
    fun theCapEvictsTheLeastRecentlyOpenedFirst() = runTest {
        insert("a1", "a2", "a3")
        listOf("a1", "a2", "a3").forEach {
            open(it)
            clock.advance(Duration.ofMinutes(1))
        }

        cache.cleanUp(RETENTION, originalsCap = BYTES * 2, previewsCap = BIG)

        assertNull(database.driveDao().cached(id("a1")))
        assertNotNull(database.driveDao().cached(id("a2")))
        assertNotNull(database.driveDao().cached(id("a3")))
    }

    @Test
    fun previewsStayUnderTheirCapLeastRecentlyUsedFirst() = runTest {
        val previews = (1..3).map { n ->
            store.preview("p$n", 1).apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(100))
                setLastModified(clock.millis() + n * MINUTE_MS)
            }
        }

        cache.cleanUp(RETENTION, BIG, previewsCap = 250)

        assertEquals(listOf(false, true, true), previews.map { it.exists() })
    }

    @Test
    fun filesOfItemsRemovedInDriveStayForTheGracePeriod() = runTest {
        insert("gone")
        open("gone")
        val file = store.original("a", "gone", VERSION, MIME)
        database.driveCacheDao().removeItems(
            listOf(id("gone")),
            purgeAfter = clock.millis() + Duration.ofDays(7).toMillis()
        )
        clock.advance(Duration.ofDays(6))
        cache.cleanUp(null, BIG, BIG)
        assertTrue(file.exists())

        clock.advance(Duration.ofDays(2))
        cache.cleanUp(null, BIG, BIG)

        assertFalse(file.exists())
        assertEquals(emptyList<Any>(), database.driveCacheDao().dueOrphans(Long.MAX_VALUE))
    }

    private suspend fun open(fileId: String) {
        assertTrue(originals.original(id(fileId)).last() is Original.Ready)
    }

    private suspend fun insert(vararg fileIds: String) {
        database.driveDao().upsertAccount(
            AccountEntity("a", "a@example.com", null, null, "", false, null, null, 1)
        )
        database.mediaIndexDao().upsertAlbums(
            listOf(AlbumEntity(ROOT, AlbumType.DRIVE_FOLDER, "drive:R/", "R", "a", null, ROOT))
        )
        database.driveDao().insertTracked(
            TrackedFolderEntity(ROOT, "a", "r", null, false, TrackedFolderState.READY, 1)
        )
        database.mediaIndexDao().upsertMedia(
            fileIds.map { fileId ->
                MediaEntity(
                    id = id(fileId), source = SourceType.DRIVE, volume = null, contentUri = null,
                    albumId = ROOT, name = "$fileId.jpg", mime = MIME, kind = MediaKind.IMAGE,
                    bytes = BYTES, width = 1, height = 1, rotation = 0, durationMs = null,
                    takenAt = 1, modifiedAt = 1, dayKey = 0, monthKey = 0, favorite = false,
                    accountId = "a", remoteId = fileId, remoteVersion = VERSION
                )
            }
        )
    }

    private companion object {
        const val ROOT = "drive:a:r"
        const val MIME = "image/jpeg"
        const val VERSION = 5L
        const val BYTES = 1_000L
        const val BIG = Long.MAX_VALUE
        const val MINUTE_MS = 60_000L
        val RETENTION = Duration.ofDays(30).toMillis()

        fun id(fileId: String) = "drive:a:$fileId"

        /** Each fake file's bytes, different per file. */
        fun contentOf(fileId: String) =
            ByteArray(BYTES.toInt()) { (it + fileId.hashCode()).toByte() }
    }
}

/** A clock the test moves by hand. */
class TestClock(private var now: Instant) : Clock() {
    fun advance(by: Duration) {
        now += by
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
