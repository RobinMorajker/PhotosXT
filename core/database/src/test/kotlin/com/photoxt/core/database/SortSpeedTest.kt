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
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.measureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6, Done when: a 20,000-item album switches between any two sorts in under 1 s. What a
 * switch waits for, here on the JVM: the grid's group layout, its first pages of rows, and the
 * viewer's position of an item. The phone check measures the same on the Galaxy A15.
 */
@RunWith(AndroidJUnit4::class)
class SortSpeedTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PxtDatabase::class.java
    ).allowMainThreadQueries().build()
    private val media = database.mediaDao()

    @After
    fun close() = database.close()

    @Test
    fun everySortOfA20000ItemAlbumLoadsInUnderASecond() = runTest {
        fill()
        val sorts = ItemSortField.entries.flatMap {
            listOf(ItemSort(it, false), ItemSort(it, true))
        }
        var shown = ItemSort()
        val slowest = sorts.maxOf { sort ->
            val took = measureTime { switchTo(shown, sort) }.inWholeMilliseconds
            println("$sort: $took ms")
            shown = sort
            took
        }
        assertTrue("slowest sort switch took $slowest ms", slowest < LIMIT_MS)
    }

    /** What the grid and the viewer load after [sort] is picked over [shown]. */
    private suspend fun switchTo(shown: ItemSort, sort: ItemSort) {
        if (sort.field == ItemSortField.CUSTOM && shown.field != ItemSortField.CUSTOM) {
            database.seedCustomOrder(ALBUM, MediaListQuery(albumId = ALBUM, sort = shown))
        }
        val grid = TimelineQuery(albumId = ALBUM, sort = sort)
        media.groupCounts(grid).first()
        media.timeline(grid).load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = FIRST_PAGES,
                placeholdersEnabled = true
            )
        )
        val item = checkNotNull(media.viewerItem("m${COUNT / 2}"))
        media.positionOf(MediaListQuery(albumId = ALBUM, sort = sort), item)
    }

    private suspend fun fill() {
        val index = database.mediaIndexDao()
        index.upsertAlbums(
            listOf(AlbumEntity(ALBUM, AlbumType.DEVICE_FOLDER, "external_primary:big/", "big"))
        )
        val random = Random(SEED)
        val start = 1_600_000_000_000L
        (0 until COUNT).chunked(CHUNK).forEach { chunk ->
            index.upsertMedia(
                chunk.map { n ->
                    val taken = start + random.nextLong(YEARS_MS)
                    val added = start + random.nextLong(YEARS_MS)
                    val modified = start + random.nextLong(YEARS_MS)
                    val name = "IMG_${random.nextInt(1_000_000)}.jpg"
                    MediaEntity(
                        id = "m$n",
                        source = SourceType.DEVICE,
                        volume = "external_primary",
                        contentUri = "content://media/external/images/media/$n",
                        albumId = ALBUM,
                        name = name,
                        mime = "image/jpeg",
                        kind = MediaKind.IMAGE,
                        bytes = random.nextLong(10_000_000),
                        width = 4_000,
                        height = 3_000,
                        rotation = 0,
                        durationMs = null,
                        takenAt = taken,
                        modifiedAt = modified,
                        dayKey = DateGrouping.DAY.keyOf(taken, ZoneOffset.UTC),
                        monthKey = DateGrouping.MONTH.keyOf(taken, ZoneOffset.UTC),
                        favorite = false,
                        addedAt = added,
                        addedDayKey = DateGrouping.DAY.keyOf(added, ZoneOffset.UTC),
                        addedMonthKey = DateGrouping.MONTH.keyOf(added, ZoneOffset.UTC),
                        modifiedDayKey = DateGrouping.DAY.keyOf(modified, ZoneOffset.UTC),
                        modifiedMonthKey = DateGrouping.MONTH.keyOf(modified, ZoneOffset.UTC),
                        nameKey = naturalKey(name)
                    )
                }
            )
        }
        // Some pinned items, and a Custom order for half the album.
        database.pinDao().savePins(
            (0 until PINS).map {
                PinEntity(TargetType.ITEM, "m$it", it.toDouble(), 1)
            }
        )
        (0 until COUNT / 2).chunked(CHUNK).forEach { chunk ->
            database.virtualAlbumDao().saveOrder(
                chunk.map {
                    ItemOrderEntity(ALBUM, "m$it", it.toDouble())
                }
            )
        }
    }

    private companion object {
        const val ALBUM = "device:big"
        const val COUNT = 20_000
        const val CHUNK = 500
        const val PINS = 12
        const val SEED = 6
        const val FIRST_PAGES = 450
        const val LIMIT_MS = 1_000L
        const val YEARS_MS = 5L * 365 * 24 * 60 * 60 * 1000
    }
}
