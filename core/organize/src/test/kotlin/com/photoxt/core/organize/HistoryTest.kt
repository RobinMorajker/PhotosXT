package com.photoxt.core.organize

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.database.PlaybackPositionEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.TargetType
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The history (spec §4.9; Phase 6, Done when: history never contains Hidden items): hidden items
 * and hidden albums are never logged, hiding forgets what was, incognito logs nothing, and views
 * older than the setting go.
 */
@RunWith(AndroidJUnit4::class)
class HistoryTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PxtDatabase::class.java
    ).allowMainThreadQueries().build()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestClock()
    private val settings = SettingsStore(MemorySettings())
    private val hiding = Hiding(database, clock)
    private val history = History(database, settings, hiding, clock)

    @Before
    fun fill() = runTest {
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

    @After
    fun close() = database.close()

    @Test
    fun viewsAreLoggedAndCounted() = runTest {
        assertNotNull(history.viewed("a", ALBUM))
        history.viewed("a", null)
        history.viewed("b", ALBUM)

        assertEquals(setOf("a", "b"), history.recent.first().map { it.id }.toSet())
        assertEquals(2, history.viewCount("a").first())
    }

    @Test
    fun hiddenItemsAreNeverLogged() = runTest {
        hiding.hide(listOf("a"))

        assertNull(history.viewed("a", ALBUM))
        assertEquals(0, history.viewCount("a").first())
        assertTrue(history.recent.first().isEmpty())
    }

    @Test
    fun bookmarksOfHiddenItemsStayOutOfTheListUntilUnhidden() = runTest {
        val thumbnails = ThumbnailStore(File(context.cacheDir, "thumbs"))
        val bookmarks = Bookmarks(context, database, thumbnails, clock)
        val collection = bookmarks.newCollection("Trip", null)
        bookmarks.add(collection, TargetType.ITEM, "a", null)
        val dao = database.bookmarkDao()

        hiding.hide(listOf("a"))
        assertTrue(dao.bookmarks(collection).first().isEmpty())
        assertEquals(0, dao.collections().first().single().count)

        hiding.unhide(listOf("a"))
        assertEquals(listOf("a"), dao.bookmarks(collection).first().map { it.targetId })
    }

    @Test
    fun hidingForgetsViewsAndVideoPositions() = runTest {
        history.viewed("a", ALBUM)
        history.viewed("v", ALBUM)
        database.playbackDao().save(PlaybackPositionEntity("v", 20_000, 60_000, 1))

        hiding.hide(listOf("a", "v"))

        assertTrue(database.historyDao().all().isEmpty())
        assertNull(database.playbackDao().position("v"))
        assertTrue(history.continueWatching.first().isEmpty())
    }

    @Test
    fun aHiddenAlbumIsForgottenAndNotLogged() = runTest {
        history.viewed("a", ALBUM)
        history.viewed("o", "device:other")

        hiding.setAlbumHidden(ALBUM, true)

        assertEquals(listOf("o"), database.historyDao().all().map { it.mediaId })
        assertNull(history.viewed("b", ALBUM))
        hiding.setAlbumHidden(ALBUM, false)
        assertNotNull(history.viewed("b", ALBUM))
    }

    @Test
    fun pausedHistoryLogsNothing() = runTest {
        settings.updateHistory { it.copy(paused = true) }

        assertNull(history.viewed("a", ALBUM))
        assertTrue(database.historyDao().all().isEmpty())
    }

    @Test
    fun autoDeleteDropsViewsOlderThanTheSetting() = runTest {
        history.viewed("a", ALBUM)
        clock.advanceDays(100)
        history.viewed("b", ALBUM)

        settings.updateHistory { it.copy(keepMonths = 3) }
        history.trim()
        assertEquals(listOf("b"), database.historyDao().all().map { it.mediaId })

        // Never: nothing goes, however old.
        settings.updateHistory { it.copy(keepMonths = 0) }
        clock.advanceDays(1_000)
        history.trim()
        assertEquals(listOf("b"), database.historyDao().all().map { it.mediaId })
    }

    @Test
    fun clearingADayRangeKeepsTheRest() = runTest {
        history.viewed("a", ALBUM)
        clock.advanceDays(2)
        history.viewed("b", ALBUM)

        val today = java.time.LocalDate.parse("2026-11-14")
        history.clearDays(today, today, java.time.ZoneOffset.UTC)

        assertEquals(listOf("a"), database.historyDao().all().map { it.mediaId })
    }
}
