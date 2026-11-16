package com.photoxt.core.organize

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.database.ItemOrderEntity
import com.photoxt.core.database.PlaybackPositionEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.DeviceState
import com.photoxt.core.datastore.PxtSettings
import com.photoxt.core.datastore.SettingsJson
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.MediaList
import com.photoxt.core.model.SmartRule
import com.photoxt.core.model.TargetType
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings sync's copy (spec §4.8; Phase 6, Done when: pins, bookmarks, sort choices and settings
 * come back after an uninstall and reinstall). The copy an install sends, restored into a fresh
 * install, gives back the same state, and that state reads back as the same copy, so nothing is
 * sent again until something changes.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val old = database()
    private val fresh = database()
    private val clock = TestClock()
    private val settings = SettingsStore(MemorySettings())

    @After
    fun close() {
        old.close()
        fresh.close()
    }

    @Test
    fun aReinstallGetsEverythingBack() = runTest {
        seed(old)
        organise()
        val sent = SnapshotTables(old).read(settings.current())
        val text = Snapshots.encode(sent)

        // The reinstall: the phone's items are indexed again, then the copy comes back.
        seed(fresh)
        val copy = checkNotNull(Snapshots.decode(text))
        SnapshotTables(fresh).replace(copy)
        val restored = checkNotNull(SettingsJson.decode(copy.settings, PxtSettings()))

        assertEquals(settings.current().copy(device = DeviceState()), restored)
        assertEquals(
            ItemSort(ItemSortField.NAME, descending = false),
            restored.sort.perAlbum[ALBUM]
        )
        assertEquals(
            listOf(ALBUM),
            fresh.pinDao().allPins().filter {
                it.targetType ==
                    TargetType.ALBUM
            }.map { it.targetId }
        )
        assertEquals("the jump", fresh.bookmarkDao().bookmarkList().single().note)
        assertEquals(42_000L, fresh.bookmarkDao().bookmarkList().single().positionMs)
        assertEquals(sent.copy(savedAt = 0), SnapshotTables(fresh).read(restored).copy(savedAt = 0))
        assertEquals(Snapshots.hash(sent), Snapshots.hash(SnapshotTables(fresh).read(restored)))
    }

    @Test
    fun aChangeChangesTheCopyButThisPhonesOwnStateDoesNot() = runTest {
        seed(old)
        val before = Snapshots.hash(SnapshotTables(old).read(settings.current()))

        settings.updateDevice { it.copy(syncedAt = 5, syncedHash = "abc", restoreChecked = true) }
        assertEquals(before, Snapshots.hash(SnapshotTables(old).read(settings.current())))

        Pins(old, settings, clock).setPinned(TargetType.ITEM, listOf("a"), true)
        assertNotEquals(before, Snapshots.hash(SnapshotTables(old).read(settings.current())))
    }

    /** A smart album of a whole phone in Custom order: the copy stays small, and old ones read. */
    @Test
    fun theCopyIsGzippedAndAPlainOneStillReads() {
        val album = "smart:ea45b890-96e4-4ba1-960a-cdc498eb9079"
        val places = (1..ORDER_SIZE).shuffled(Random(1))
        val order = places.mapIndexed { i, place ->
            ItemOrderEntity(album, "device:${1_000_000_000 + i}", place.toDouble())
        }
        val copy = Snapshot(settings = "{}", order = order)

        val plain = Snapshots.encode(copy).encodeToByteArray()
        val packed = Snapshots.pack(copy)
        println("$ORDER_SIZE places: ${plain.size / KIB} KiB, gzipped ${packed.size / KIB} KiB")
        assertTrue("packed ${packed.size} bytes", packed.size < PACKED_LIMIT)
        assertEquals(copy, Snapshots.unpack(packed))
        // What the first Phase 6 builds wrote: plain JSON under the same name.
        assertEquals(copy, Snapshots.unpack(plain))
    }

    @Test
    fun aCopyFromANewerVersionStillReads() {
        val text = """{"version":2,"settings":"{}","pins":[],"future":{"x":1}}"""
        assertNotNull(Snapshots.decode(text))
    }

    /** Pins, sorts, a group, a bookmark moment, own albums, a hidden item, history, settings. */
    private suspend fun organise() {
        val pins = Pins(old, settings, clock)
        pins.setPinned(TargetType.ALBUM, listOf(ALBUM), true)
        pins.setPinned(TargetType.ITEM, listOf("a"), true)
        val options = AlbumOptions(old, settings, clock)
        options.setSort(ALBUM, ItemSort(ItemSortField.NAME, descending = false))
        options.setAlbumsSort(AlbumSort(AlbumSortField.TOTAL_SIZE, descending = false))
        options.setPicturesSort(ItemSort(ItemSortField.DATE_ADDED))
        options.setListSort(MediaList.FAVORITES, ItemSort(ItemSortField.SIZE))
        options.newGroup("Trips", listOf("device:other"))
        val bookmarks =
            Bookmarks(context, old, ThumbnailStore(File(context.cacheDir, "thumbs")), clock)
        val collection = bookmarks.newCollection("To print", "A4, matte")
        bookmarks.add(collection, TargetType.ITEM, "v", "the jump", positionMs = 42_000)
        val own = OwnAlbums(old, old.mediaDao(), clock)
        own.newVirtual("Best", listOf("a", "b"))
        own.newSmart("Videos", SmartRule(kind = MediaKind.VIDEO))
        val hiding = Hiding(old, clock)
        hiding.hide(listOf("b"))
        History(old, settings, hiding, clock).viewed("a", ALBUM)
        old.playbackDao().save(PlaybackPositionEntity("v", 20_000, 60_000, clock.millis()))
        settings.updateView { it.copy(zoomLock = true, slideshowSeconds = 5) }
        settings.updateHistory { it.copy(keepMonths = 3) }
        settings.updateSync { it.copy(driveHours = 12, driveOnMobile = false) }
        settings.updateDevice { it.copy(restoreChecked = true, syncedHash = "old") }
    }

    private suspend fun seed(database: PxtDatabase) {
        database.mediaIndexDao().upsertAlbums(listOf(album(), album("device:other", "Other")))
        database.mediaIndexDao().upsertMedia(
            listOf(
                item("a"),
                item("b"),
                item("v", kind = MediaKind.VIDEO),
                item("o", "device:other")
            )
        )
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, PxtDatabase::class.java)
        .allowMainThreadQueries()
        .build()
}

private const val ORDER_SIZE = 24_000
private const val PACKED_LIMIT = 300 * 1024
private const val KIB = 1024
