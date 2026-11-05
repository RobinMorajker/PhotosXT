package com.photoxt.core.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.MediaKind
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The incremental MediaStore indexer (spec §4.3, Phase 1 "Done when"). */
@RunWith(AndroidJUnit4::class)
class MediaStoreIndexerTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PxtDatabase::class.java
    ).allowMainThreadQueries().build()
    private val store = FakeMediaStore()
    private val indexer = MediaStoreIndexer(store, database)

    @After
    fun close() = database.close()

    @Test
    fun firstPassReadsEverythingThenOnlyWhatChanged() = runTest {
        store.put(photo(1), photo(2), photo(3))
        assertEquals(IndexResult(changed = 3, removed = 0), indexer.index(UTC))
        assertEquals(listOf<Long?>(null), store.scans)

        assertEquals(IndexResult(changed = 0, removed = 0), indexer.index(UTC))
        assertEquals("nothing changed, so nothing is scanned", 1, store.scans.size)

        store.put(photo(4))
        assertEquals(IndexResult(changed = 1, removed = 0), indexer.index(UTC))
        assertEquals("only items after the saved generation", 3L, store.scans.last())
        assertEquals(setOf(1L, 2L, 3L, 4L), indexedIds())
    }

    @Test
    fun quickPassAfterAnInsertSkipsTheIdDiff() = runTest {
        store.put(photo(1))
        indexer.index(UTC)
        val diffs = store.idQueries
        store.put(photo(2))
        assertEquals(IndexResult(changed = 1, removed = 0), indexer.index(UTC, diff = false))
        assertEquals(diffs, store.idQueries)
        assertEquals(setOf(1L, 2L), indexedIds())
    }

    @Test
    fun changedItemIsUpdated() = runTest {
        store.put(photo(1))
        indexer.index(UTC)
        store.put(photo(1).copy(favorite = true))
        indexer.index(UTC)
        assertEquals(1, intColumn("favorite", 1))
    }

    @Test
    fun deletedItemIsRemovedOnTheNextPass() = runTest {
        store.put(photo(1), photo(2))
        indexer.index(UTC)
        store.delete(2)
        assertEquals(IndexResult(changed = 0, removed = 1), indexer.index(UTC))
        assertEquals(setOf(1L), indexedIds())
    }

    @Test
    fun newMediaStoreVersionForcesAFullRescan() = runTest {
        store.put(photo(1), photo(2))
        indexer.index(UTC)
        store.version = "rebuilt"
        assertEquals(IndexResult(changed = 2, removed = 0), indexer.index(UTC))
        assertEquals(listOf<Long?>(null, null), store.scans)
    }

    @Test
    fun newTimeZoneMovesDayKeys() = runTest {
        // 2026-11-03 20:00 UTC is already 4 November in India.
        store.put(photo(1, takenAt = 1_793_736_000_000L))
        indexer.index(UTC)
        val utcDay = intColumn("dayKey", 1)
        indexer.index(ZoneId.of("Asia/Kolkata"))
        assertEquals(utcDay + 1, intColumn("dayKey", 1))
    }

    @Test
    fun itemsThatBecomeVisibleWithOldGenerationsAreFound() = runTest {
        // Android 14 limited access: the user picks one more old photo later.
        store.put(photo(1), photo(2))
        store.hidden += 2L
        indexer.index(UTC)
        assertEquals(setOf(1L), indexedIds())

        store.hidden -= 2L
        assertEquals(IndexResult(changed = 1, removed = 0), indexer.index(UTC))
        assertEquals(setOf(1L, 2L), indexedIds())
        assertEquals("fetched by id, not by rescanning", listOf<Long?>(null), store.scans)
        assertEquals(listOf(listOf(2L)), store.fetches)
    }

    @Test
    fun emptiedAlbumAndUnmountedVolumeAreDropped() = runTest {
        store.put(photo(1, bucket = 10), photo(2, bucket = 20))
        store.put(photo(3, bucket = 30, volume = SD_CARD))
        indexer.index(UTC)
        assertEquals(3, database.albumDao().albums().first().size)

        store.delete(2)
        store.unmount(SD_CARD)
        indexer.index(UTC)
        assertEquals(setOf(1L), indexedIds())
        assertEquals(listOf("device:10"), database.albumDao().albums().first().map { it.id })
    }

    @Test
    fun failedQueryDeletesNothing() = runTest {
        store.put(photo(1))
        indexer.index(UTC)
        store.failIds = true
        val failure = runCatching { indexer.index(UTC) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(setOf(1L), indexedIds())
    }

    private suspend fun indexedIds(): Set<Long> =
        (store.volumesSeen).flatMap { database.mediaIndexDao().deviceMediaIds(it) }
            .map { it.removePrefix("device:").toLong() }
            .toSet()

    private fun intColumn(column: String, id: Long): Int = database.openHelper.readableDatabase
        .query("SELECT $column FROM media WHERE id = ?", arrayOf(deviceMediaId(id)))
        .use { cursor ->
            check(cursor.moveToFirst()) { "No row for $id" }
            cursor.getInt(0)
        }

    private companion object {
        val UTC: ZoneId = ZoneOffset.UTC
        const val PRIMARY = "external_primary"
        const val SD_CARD = "1234-5678"

        fun photo(
            id: Long,
            bucket: Long = 10,
            volume: String = PRIMARY,
            takenAt: Long = 1_790_000_000_000L + id
        ) = DeviceMedia(
            id = id,
            volume = volume,
            contentUri = "content://media/$volume/images/media/$id",
            kind = MediaKind.IMAGE,
            bucketId = bucket,
            bucketName = "Bucket $bucket",
            relativePath = "DCIM/Bucket$bucket/",
            name = "IMG_$id.jpg",
            mime = "image/jpeg",
            bytes = 1_000,
            width = 4000,
            height = 3000,
            orientation = 0,
            durationMs = null,
            dateTakenMs = takenAt,
            dateModifiedSec = takenAt / 1000,
            dateAddedSec = takenAt / 1000,
            favorite = false
        )
    }
}

/** MediaStore in memory: per-volume generations, a version, and items hidden from the app. */
private class FakeMediaStore : MediaStoreReader {
    private val items = LinkedHashMap<Long, DeviceMedia>()
    private val itemGeneration = HashMap<Long, Long>()
    private val generations = HashMap<String, Long>()
    private val mounted = LinkedHashSet<String>()
    val volumesSeen = LinkedHashSet<String>()
    val hidden = HashSet<Long>()
    val scans = ArrayList<Long?>()
    var idQueries = 0
    val fetches = ArrayList<List<Long>>()
    var version = "1"
    var failIds = false

    fun put(vararg media: DeviceMedia) = media.forEach {
        mounted += it.volume
        volumesSeen += it.volume
        val generation = generations.getOrDefault(it.volume, 0L) + 1
        generations[it.volume] = generation
        itemGeneration[it.id] = generation
        items[it.id] = it
    }

    fun delete(id: Long) {
        val removed = items.remove(id) ?: return
        generations[removed.volume] = generations.getOrDefault(removed.volume, 0L) + 1
    }

    fun unmount(volume: String) {
        mounted -= volume
    }

    private fun visible(volume: String) =
        items.values.filter { it.volume == volume && it.volume in mounted && it.id !in hidden }

    override fun volumes(): Set<String> = mounted.toSet()

    override fun version(volume: String) = version

    override fun generation(volume: String) = generations.getOrDefault(volume, 0L)

    override suspend fun scan(
        volume: String,
        sinceGeneration: Long?,
        onBatch: suspend (List<DeviceMedia>) -> Unit
    ) {
        scans += sinceGeneration
        visible(volume)
            .filter { sinceGeneration == null || itemGeneration.getValue(it.id) > sinceGeneration }
            .chunked(2)
            .forEach { onBatch(it) }
    }

    override fun ids(volume: String): Set<Long> {
        check(!failIds) { "MediaStore query returned no cursor" }
        idQueries++
        return visible(volume).mapTo(HashSet()) { it.id }
    }

    override fun fetch(volume: String, ids: Collection<Long>): List<DeviceMedia> {
        fetches += ids.toList()
        return visible(volume).filter { it.id in ids }
    }
}
