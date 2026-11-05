package com.photoxt.core.media

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.MediaStoreCursorEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.MediaSource
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies MediaStore into Room incrementally (spec §4.3, "Device").
 *
 * Per volume it keeps MediaStore's version and generation. A pass reads only items whose
 * generation moved, then diffs ids: ids that disappeared are deleted, and ids it has never seen
 * are fetched directly (photos picked later under Android 14's limited access keep their old
 * generation, and an item can land between the scan and the diff). It rescans the whole volume
 * only when MediaStore's version changes (its database was rebuilt) or the time zone changes
 * (stored day keys moved). Re-running a pass is harmless.
 */
class MediaStoreIndexer @Inject constructor(
    private val store: MediaStoreReader,
    private val database: PxtDatabase
) {
    private val dao = database.mediaIndexDao()

    /**
     * One pass. [diff] also compares every id, which finds deletions and items without a new
     * generation; a pass after an insert or edit can skip it. Callers run one pass at a time.
     */
    suspend fun index(zone: ZoneId = ZoneId.systemDefault(), diff: Boolean = true): IndexResult =
        withContext(Dispatchers.IO) {
            val volumes = store.volumes()
            val cursors = dao.cursors().associateBy { it.volume }
            var result = IndexResult(changed = 0, removed = 0)
            for (volume in volumes) {
                result += indexVolume(volume, cursors[volume], zone, diff)
            }
            database.withTransaction {
                dao.deleteDeviceMediaOutside(volumes.toList())
                dao.deleteCursorsOutside(volumes.toList())
                dao.deleteEmptyDeviceAlbums()
            }
            result
        }

    private suspend fun indexVolume(
        volume: String,
        cursor: MediaStoreCursorEntity?,
        zone: ZoneId,
        diff: Boolean
    ): IndexResult {
        val version = store.version(volume)
        // Read before scanning, so a change made during the scan is picked up by the next pass.
        val generation = store.generation(volume)
        val full = cursor == null ||
            cursor.version != version ||
            cursor.zone != zone.id ||
            generation < cursor.generation
        var changed = 0
        if (full || generation > cursor.generation) {
            store.scan(volume, if (full) null else cursor.generation) { batch ->
                changed += write(batch, zone)
            }
        }
        val gone = if (diff || full) diffIds(volume, zone) { changed += it } else emptyList()
        database.withTransaction {
            gone.chunked(MAX_SQL_VARIABLES).forEach { dao.deleteMedia(it) }
            dao.saveCursor(MediaStoreCursorEntity(volume, version, generation, zone.id))
        }
        return IndexResult(changed, gone.size)
    }

    /** Fetches ids MediaStore shows but Room lacks, and returns the Room ids MediaStore lost. */
    private suspend fun diffIds(
        volume: String,
        zone: ZoneId,
        onWritten: (Int) -> Unit
    ): List<String> {
        val present = store.ids(volume)
        val known = dao.deviceMediaIds(volume).toHashSet()
        val unseen = present.filterNot { deviceMediaId(it) in known }
        if (unseen.isNotEmpty()) {
            store.fetch(volume, unseen).chunked(WRITE_BATCH).forEach { onWritten(write(it, zone)) }
        }
        val presentIds = present.mapTo(HashSet(), ::deviceMediaId)
        return known.filterNot(presentIds::contains)
    }

    private suspend fun write(items: List<DeviceMedia>, zone: ZoneId): Int {
        database.withTransaction {
            dao.upsertAlbums(items.distinctBy { it.bucketId }.map { it.toAlbum() })
            dao.upsertMedia(items.map { it.toEntity(zone) })
        }
        return items.size
    }

    private companion object {
        const val MAX_SQL_VARIABLES = 900
        const val WRITE_BATCH = 500
    }
}

/** Items added or changed, and items removed, by one indexing pass. */
data class IndexResult(val changed: Int, val removed: Int) {
    operator fun plus(other: IndexResult) =
        IndexResult(changed + other.changed, removed + other.removed)
}

fun deviceMediaId(mediaStoreId: Long): String = "device:$mediaStoreId"

fun deviceAlbumId(bucketId: Long): String = "device:$bucketId"

private fun DeviceMedia.toAlbum() = AlbumEntity(
    id = deviceAlbumId(bucketId),
    type = AlbumType.DEVICE_FOLDER,
    sourceRef = "$volume:${relativePath.orEmpty()}",
    title = bucketName.orEmpty()
)

private fun DeviceMedia.toEntity(zone: ZoneId): MediaEntity {
    // Files without a capture date fall back to their modification, then their addition time.
    val takenAt = dateTakenMs?.takeIf { it > 0 }
        ?: (dateModifiedSec.takeIf { it > 0 } ?: dateAddedSec) * MILLIS_PER_SECOND
    return MediaEntity(
        id = deviceMediaId(id),
        source = MediaSource.DEVICE,
        volume = volume,
        contentUri = contentUri,
        albumId = deviceAlbumId(bucketId),
        name = name,
        mime = mime,
        kind = kind,
        bytes = bytes,
        width = width,
        height = height,
        rotation = orientation,
        durationMs = durationMs,
        takenAt = takenAt,
        modifiedAt = dateModifiedSec * MILLIS_PER_SECOND,
        dayKey = DateGrouping.DAY.keyOf(takenAt, zone),
        monthKey = DateGrouping.MONTH.keyOf(takenAt, zone),
        favorite = favorite
    )
}

private const val MILLIS_PER_SECOND = 1000L
