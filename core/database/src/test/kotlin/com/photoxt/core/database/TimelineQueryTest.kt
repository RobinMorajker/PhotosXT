package com.photoxt.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The timeline's rows: every header right before its items, in the order the grid shows them. */
@RunWith(AndroidJUnit4::class)
class TimelineQueryTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PxtDatabase::class.java
    ).allowMainThreadQueries().build()
    private val media = database.mediaDao()

    @Before
    fun fill() = runTest {
        val index = database.mediaIndexDao()
        index.upsertAlbums(listOf(album("camera"), album("downloads")))
        index.upsertMedia(
            listOf(
                item("a", "2026-11-04T09:00"),
                item("b", "2026-11-04T18:00", kind = MediaKind.VIDEO),
                item("c", "2026-11-02T12:00", favorite = true),
                item("d", "2026-10-30T08:00", album = "downloads"),
                item("e", "2025-12-31T23:00")
            )
        )
    }

    @After
    fun close() = database.close()

    @Test
    fun dayRowsPutEachHeaderBeforeItsItemsNewestFirst() = runTest {
        assertEquals(
            listOf("2026-11-04", "b", "a", "2026-11-02", "c", "2026-10-30", "d", "2025-12-31", "e"),
            rows(TimelineQuery())
        )
    }

    @Test
    fun monthAndYearGroupingsShareTheItemOrder() = runTest {
        assertEquals(
            listOf("2026-11", "b", "a", "c", "2026-10", "d", "2025-12", "e"),
            rows(TimelineQuery(grouping = DateGrouping.MONTH))
        )
        assertEquals(
            listOf("2026", "b", "a", "c", "d", "2025", "e"),
            rows(TimelineQuery(grouping = DateGrouping.YEAR))
        )
    }

    @Test
    fun filtersAndAlbumsNarrowTheRows() = runTest {
        assertEquals(listOf("2026-11-04", "b"), rows(TimelineQuery(MediaFilter.VIDEOS)))
        assertEquals(listOf("2026-11-02", "c"), rows(TimelineQuery(MediaFilter.FAVORITES)))
        assertEquals(
            listOf("2026-10-30", "d"),
            rows(TimelineQuery(albumId = "downloads"))
        )
        assertEquals(
            listOf("2026-11-04", "a", "2026-11-02", "c", "2025-12-31", "e"),
            rows(TimelineQuery(MediaFilter.PHOTOS, albumId = "camera"))
        )
    }

    @Test
    fun groupCountsDescribeTheRowsWithoutLoadingThem() = runTest {
        val counts = media.groupCounts(TimelineQuery(grouping = DateGrouping.MONTH)).first()
        assertEquals(
            listOf(month("2026-11") to 3, month("2026-10") to 1, month("2025-12") to 1),
            counts.map { it.groupKey to it.count }
        )
        val stamps = media.stampsInGroup(TimelineQuery(), day("2026-11-04"))
        assertEquals(setOf("a", "b"), stamps.map { it.id }.toSet())
    }

    @Test
    fun albumSummaryCountsItemsAndUsesTheNewestAsCover() = runTest {
        val albums = database.albumDao().albums().first()
        assertEquals(listOf("camera", "downloads"), albums.map { it.id })
        assertEquals(4, albums.first().count)
        assertEquals("content://b", albums.first().coverUri)
    }

    /** An album just made has no items yet: it leads the list, where it can be found. */
    @Test
    fun aNewEmptyAlbumComesFirst() = runTest {
        database.mediaIndexDao().upsertAlbums(listOf(album("new").copy(pending = true)))
        val albums = database.albumDao().albums().first()
        assertEquals(listOf("new", "camera", "downloads"), albums.map { it.id })
    }

    @Test
    fun viewerItemsKeepTheGridOrderAndKnowTheirPosition() = runTest {
        val all = MediaListQuery()
        val page = media.viewerItems(all).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        assertEquals(listOf("b", "a", "c", "d", "e"), page.data.map { it.id })

        val c = checkNotNull(media.viewerItem("c"))
        assertEquals(2, media.positionOf(all, c))
        assertEquals(1, media.positionOf(MediaListQuery(MediaFilter.PHOTOS), c))
        assertEquals(2, media.positionOf(MediaListQuery(albumId = "camera"), c))
        assertEquals("external_primary:camera/", media.folderOf("c"))
    }

    @Test
    fun aResumePointGoesWithItsItem() = runTest {
        val playback = database.playbackDao()
        playback.save(PlaybackPositionEntity("b", positionMs = 1_500, durationMs = 5_000, 1))
        assertEquals(1_500L, playback.position("b")?.positionMs)
        database.mediaIndexDao().deleteMedia(listOf("b"))
        assertEquals(null, playback.position("b"))
    }

    /** Header rows as their date ("2026-11-04", "2026-11", "2026"), item rows as their id. */
    private suspend fun rows(query: TimelineQuery): List<String> {
        val page = media.timeline(query).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        return page.data.map { row ->
            if (!row.isHeader) {
                row.id.orEmpty()
            } else {
                val start = query.grouping.startOf(row.groupKey).toString()
                when (query.grouping) {
                    DateGrouping.DAY -> start
                    DateGrouping.MONTH -> start.take(7)
                    DateGrouping.YEAR -> start.take(4)
                }
            }
        }
    }

    private companion object {
        fun album(id: String) =
            AlbumEntity(id, AlbumType.DEVICE_FOLDER, "external_primary:$id/", id)

        fun day(date: String) = LocalDate.parse(date).toEpochDay().toInt()

        fun month(yearMonth: String) = DateGrouping.monthKey(LocalDate.parse("$yearMonth-01"))

        fun item(
            id: String,
            dateTime: String,
            kind: MediaKind = MediaKind.IMAGE,
            favorite: Boolean = false,
            album: String = "camera"
        ): MediaEntity {
            val takenAt = LocalDateTime.parse(dateTime).toInstant(ZoneOffset.UTC).toEpochMilli()
            return MediaEntity(
                id = id,
                source = SourceType.DEVICE,
                volume = "external_primary",
                contentUri = "content://$id",
                albumId = album,
                name = "$id.jpg",
                mime = if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
                kind = kind,
                bytes = 1,
                width = 1,
                height = 1,
                rotation = 0,
                durationMs = if (kind == MediaKind.VIDEO) 5_000 else null,
                takenAt = takenAt,
                modifiedAt = takenAt,
                dayKey = DateGrouping.DAY.keyOf(takenAt, ZoneOffset.UTC),
                monthKey = DateGrouping.MONTH.keyOf(takenAt, ZoneOffset.UTC),
                favorite = favorite
            )
        }
    }
}
