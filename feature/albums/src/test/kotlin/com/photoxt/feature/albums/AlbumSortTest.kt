package com.photoxt.feature.albums

import com.photoxt.core.database.AlbumPrefsEntity
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.PinEntity
import com.photoxt.core.database.VirtualAlbumSummary
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.TargetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Albums tab's every sort, both ways (spec §4.2; Phase 6, Done when): names in natural
 * order, empty albums first in Newest item, new albums first in Custom order, and pinned albums
 * always on top in their own order.
 */
class AlbumSortTest {
    private val folders = listOf(
        folder("a", "Album 10", count = 3, latest = 300, created = 100, bytes = 9_000),
        folder("b", "album 2", count = 1, latest = 500, created = 300, bytes = 1_000),
        folder("c", "Trips", count = 5, latest = 100, created = 200, bytes = 5_000),
        folder("e", "New", count = 0, latest = 0, created = 500, bytes = 0)
    )
    private val virtual = listOf(
        VirtualAlbumSummary(
            "v", AlbumType.VIRTUAL, "", "Best", 2, 400, null, null, 0, null, null, null, null,
            null, createdAt = 400, totalBytes = 2_000
        )
    )

    @Test
    fun everySortOrdersTheAlbumsBothWays() {
        val expected = mapOf(
            AlbumSortField.NAME to listOf("b", "a", "v", "e", "c"),
            AlbumSortField.DATE_CREATED to listOf("a", "c", "b", "v", "e"),
            AlbumSortField.ITEM_COUNT to listOf("e", "b", "v", "a", "c"),
            AlbumSortField.TOTAL_SIZE to listOf("e", "b", "v", "c", "a")
        )
        for ((field, ascending) in expected) {
            assertEquals("$field ascending", ascending, ids(AlbumSort(field, descending = false)))
            assertEquals(
                "$field descending",
                ascending.reversed(),
                ids(AlbumSort(field, descending = true))
            )
        }
    }

    @Test
    fun namesSortNaturally() {
        val names = ids(AlbumSort.of(AlbumSortField.NAME))
        assertEquals(true, names.indexOf("b") < names.indexOf("a"))
    }

    @Test
    fun emptyAlbumsLeadNewestItemBothWays() {
        assertEquals(
            listOf("e", "c", "a", "v", "b"),
            ids(AlbumSort(AlbumSortField.NEWEST_ITEM, false))
        )
        assertEquals(
            listOf("e", "b", "v", "a", "c"),
            ids(AlbumSort(AlbumSortField.NEWEST_ITEM, true))
        )
    }

    @Test
    fun customOrderPutsNewAlbumsFirstNewestFirst() {
        val prefs = mapOf(
            "a" to AlbumPrefsEntity("a", position = 2.0),
            "c" to AlbumPrefsEntity("c", position = 1.0)
        )
        assertEquals(
            listOf("b", "v", "e", "c", "a"),
            ids(AlbumSort(AlbumSortField.CUSTOM), prefs = prefs)
        )
    }

    @Test
    fun pinnedAlbumsStayOnTopInEverySortAndDirection() {
        val pins = listOf(
            PinEntity(TargetType.ALBUM, "c", position = 1.0, pinnedAt = 1),
            PinEntity(TargetType.ALBUM, "b", position = 2.0, pinnedAt = 1)
        )
        for (field in AlbumSortField.entries) {
            for (descending in listOf(false, true)) {
                val sort = AlbumSort(field, descending)
                val pinned = ids(sort, pins = pins)
                val plain = ids(sort)
                assertEquals("$sort", listOf("c", "b"), pinned.take(2))
                assertEquals("$sort", plain.filterNot { it == "c" || it == "b" }, pinned.drop(2))
            }
        }
    }

    @Test
    fun hiddenAlbumsAreLeftOut() {
        val prefs = mapOf("a" to AlbumPrefsEntity("a", hidden = true))
        assertEquals(
            listOf("b", "v", "e", "c"),
            ids(AlbumSort.of(AlbumSortField.NAME), prefs = prefs)
        )
    }

    private fun ids(
        sort: AlbumSort,
        prefs: Map<String, AlbumPrefsEntity> = emptyMap(),
        pins: List<PinEntity> = emptyList()
    ): List<String> = arrange(
        AlbumSources(folders, virtual, emptyList(), prefs, pins, emptyList(), sort)
    ).map { it.id }

    private companion object {
        @Suppress("LongParameterList") // One value for each sort.
        fun folder(
            id: String,
            title: String,
            count: Int,
            latest: Long,
            created: Long,
            bytes: Long
        ) = AlbumSummary(
            id = id,
            type = AlbumType.DEVICE_FOLDER,
            sourceRef = "external_primary:$title/",
            title = title,
            count = count,
            latestTakenAt = latest,
            coverId = null,
            coverUri = null,
            coverModifiedAt = 0,
            coverVersion = null,
            accountId = null,
            state = null,
            createdAt = created,
            totalBytes = bytes
        )
    }
}
