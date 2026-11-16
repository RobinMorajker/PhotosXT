package com.photoxt.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.naturalKey
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every item sort, both ways, over a sample album (spec §4.2; Phase 6, Done when): names in
 * natural order, Date added independent of Date taken, and pinned items always on top. The grid's
 * rows, the viewer's items and each item's position agree on one order.
 */
@RunWith(AndroidJUnit4::class)
class SortTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PxtDatabase::class.java
    ).allowMainThreadQueries().build()
    private val media = database.mediaDao()

    @Before
    fun fill() = runTest {
        val index = database.mediaIndexDao()
        index.upsertAlbums(
            listOf(AlbumEntity(ALBUM, AlbumType.DEVICE_FOLDER, "external_primary:c/", "c"))
        )
        // Each date puts the five items in a different order, and none matches the names'.
        index.upsertMedia(
            listOf(
                item("IMG_2.jpg", taken = 1, added = 5, modified = 2, bytes = 300),
                item("IMG_10.jpg", taken = 3, added = 1, modified = 5, bytes = 100),
                item("img_3.JPG", taken = 2, added = 4, modified = 1, bytes = 500),
                item("IMG_1.jpg", taken = 5, added = 2, modified = 3, bytes = 200),
                item("Holiday.mp4", taken = 4, added = 3, modified = 4, bytes = 400)
            )
        )
    }

    @After
    fun close() = database.close()

    @Test
    fun everySortOrdersTheAlbumBothWays() = runTest {
        for ((field, ascending) in EXPECTED) {
            assertEquals("$field ascending", ascending, ids(ItemSort(field, descending = false)))
            assertEquals(
                "$field descending",
                ascending.reversed(),
                ids(ItemSort(field, descending = true))
            )
        }
    }

    @Test
    fun namesSortInNaturalOrderIgnoringCase() = runTest {
        val names = ids(ItemSort.of(ItemSortField.NAME))
        assertEquals(listOf(HOLIDAY, IMG_1, IMG_2, IMG_3, IMG_10), names)
        assertEquals(true, names.indexOf("IMG_2.jpg") < names.indexOf("IMG_10.jpg"))
    }

    @Test
    fun dateAddedIsItsOwnOrderWithItsOwnDays() = runTest {
        val added = ids(ItemSort(ItemSortField.DATE_ADDED))
        assertNotEquals(ids(ItemSort(ItemSortField.DATE_TAKEN)), added)
        // The grid's headers are the days each item was added, not taken.
        val rows = rows(ItemSort(ItemSortField.DATE_ADDED))
        assertEquals(
            listOf(
                "h:5", "IMG_2.jpg", "h:4", "img_3.JPG", "h:3", "Holiday.mp4",
                "h:2", "IMG_1.jpg", "h:1", "IMG_10.jpg"
            ),
            rows
        )
    }

    @Test
    fun pinnedItemsStayOnTopInEverySortAndDirection() = runTest {
        database.pinDao().savePins(
            listOf(
                PinEntity(TargetType.ITEM, "IMG_2.jpg", position = 2.0, pinnedAt = 1),
                PinEntity(TargetType.ITEM, "Holiday.mp4", position = 1.0, pinnedAt = 1)
            )
        )
        for (field in ItemSortField.entries) {
            for (descending in listOf(false, true)) {
                val sort = ItemSort(field, descending)
                val order = ids(sort)
                assertEquals("$sort", listOf("Holiday.mp4", "IMG_2.jpg"), order.take(2))
                // The rest keep the sort's order; the grid shows the pins under their own header.
                val rest = EXPECTED[field]?.let { if (descending) it.reversed() else it }
                    ?.filterNot { it == "Holiday.mp4" || it == "IMG_2.jpg" }
                if (rest != null) assertEquals("$sort", rest, order.drop(2))
                assertEquals("$sort", listOf("pins", HOLIDAY, IMG_2), rows(sort).take(3))
            }
        }
    }

    @Test
    fun customOrderPutsItemsWithoutAPlaceFirstNewestFirst() = runTest {
        database.virtualAlbumDao().saveOrder(
            listOf(
                ItemOrderEntity(ALBUM, "IMG_10.jpg", 1.0),
                ItemOrderEntity(ALBUM, "IMG_2.jpg", 2.0),
                ItemOrderEntity(ALBUM, "img_3.JPG", 3.0)
            )
        )
        // Holiday (taken 4) and IMG_1 (taken 5) have no place yet: they lead, newest first.
        assertEquals(
            listOf("IMG_1.jpg", "Holiday.mp4", "IMG_10.jpg", "IMG_2.jpg", "img_3.JPG"),
            ids(ItemSort(ItemSortField.CUSTOM))
        )
    }

    @Test
    fun customOrderStartsFromTheOrderOnScreenAndKeepsItsPlaces() = runTest {
        val name = ItemSort(ItemSortField.NAME, descending = true)
        database.seedCustomOrder(ALBUM, query(name))
        assertEquals(ids(name), ids(ItemSort(ItemSortField.CUSTOM)))
        // Started again from another sort, the items keep the places they have.
        database.seedCustomOrder(ALBUM, query(ItemSort(ItemSortField.DATE_ADDED)))
        assertEquals(ids(name), ids(ItemSort(ItemSortField.CUSTOM)))
    }

    @Test
    fun theGridTheViewerAndPositionsAgree() = runTest {
        for (field in ItemSortField.entries) {
            for (descending in listOf(false, true)) {
                val sort = ItemSort(field, descending)
                val viewer = viewerIds(sort)
                assertEquals("$sort", ids(sort), viewer)
                val gridItems = rows(sort).filterNot { it.startsWith("h:") || it == "pins" }
                assertEquals("$sort", viewer, gridItems)
                viewer.forEachIndexed { at, id ->
                    val item = checkNotNull(media.viewerItem(id))
                    assertEquals("$sort $id", at, media.positionOf(query(sort), item))
                }
            }
        }
    }

    @Test
    fun picturesSortsTooButHasNoPins() = runTest {
        database.pinDao().savePins(listOf(PinEntity(TargetType.ITEM, "IMG_2.jpg", 1.0, 1)))
        for ((field, ascending) in EXPECTED) {
            val pictures = media.idsOf(MediaListQuery(sort = ItemSort(field, descending = false)))
            assertEquals("$field", ascending, pictures)
        }
    }

    private fun query(sort: ItemSort) = MediaListQuery(albumId = ALBUM, sort = sort)

    private suspend fun ids(sort: ItemSort): List<String> = media.idsOf(query(sort))

    private suspend fun viewerIds(sort: ItemSort): List<String> {
        val page = media.viewerItems(query(sort)).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    /** The grid's rows: "pins" for the pinned header, "h:<day>" for a date header, else the id. */
    private suspend fun rows(sort: ItemSort): List<String> {
        val query = TimelineQuery(albumId = ALBUM, sort = sort)
        val page = media.timeline(query).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        return page.data.map { row ->
            when {
                !row.isHeader -> row.id.orEmpty()
                row.groupKey == com.photoxt.core.model.PINNED_KEY -> "pins"
                else -> "h:" + (row.groupKey - BASE_DAY)
            }
        }
    }

    private companion object {
        const val ALBUM = "camera"
        const val IMG_1 = "IMG_1.jpg"
        const val IMG_2 = "IMG_2.jpg"
        const val IMG_3 = "img_3.JPG"
        const val IMG_10 = "IMG_10.jpg"
        const val HOLIDAY = "Holiday.mp4"
        val BASE_DAY = LocalDate.parse("2026-01-01").toEpochDay().toInt()

        /** Each sort's ascending order of the sample album. */
        val EXPECTED = mapOf(
            ItemSortField.DATE_TAKEN to listOf(IMG_2, IMG_3, IMG_10, HOLIDAY, IMG_1),
            ItemSortField.DATE_ADDED to listOf(IMG_10, IMG_1, HOLIDAY, IMG_3, IMG_2),
            ItemSortField.DATE_MODIFIED to listOf(IMG_3, IMG_2, IMG_1, HOLIDAY, IMG_10),
            ItemSortField.NAME to listOf(HOLIDAY, IMG_1, IMG_2, IMG_3, IMG_10),
            ItemSortField.SIZE to listOf(IMG_10, IMG_1, IMG_2, HOLIDAY, IMG_3)
        )

        /** Day [n] after the base day, at noon UTC. */
        fun at(day: Int): Long = LocalDate.parse("2026-01-01").plusDays(day.toLong()).atTime(12, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()

        fun item(name: String, taken: Int, added: Int, modified: Int, bytes: Long): MediaEntity {
            val video = name.endsWith(".mp4")
            return MediaEntity(
                id = name,
                source = SourceType.DEVICE,
                volume = "external_primary",
                contentUri = "content://$name",
                albumId = ALBUM,
                name = name,
                mime = if (video) "video/mp4" else "image/jpeg",
                kind = if (video) MediaKind.VIDEO else MediaKind.IMAGE,
                bytes = bytes,
                width = 1,
                height = 1,
                rotation = 0,
                durationMs = if (video) 5_000 else null,
                takenAt = at(taken),
                modifiedAt = at(modified),
                dayKey = DateGrouping.DAY.keyOf(at(taken), ZoneOffset.UTC),
                monthKey = DateGrouping.MONTH.keyOf(at(taken), ZoneOffset.UTC),
                favorite = false,
                addedAt = at(added),
                addedDayKey = DateGrouping.DAY.keyOf(at(added), ZoneOffset.UTC),
                addedMonthKey = DateGrouping.MONTH.keyOf(at(added), ZoneOffset.UTC),
                modifiedDayKey = DateGrouping.DAY.keyOf(at(modified), ZoneOffset.UTC),
                modifiedMonthKey = DateGrouping.MONTH.keyOf(at(modified), ZoneOffset.UTC),
                nameKey = naturalKey(name)
            )
        }
    }
}
