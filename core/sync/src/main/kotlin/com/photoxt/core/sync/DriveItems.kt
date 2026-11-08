package com.photoxt.core.sync

import com.photoxt.core.database.DriveCacheEntity
import com.photoxt.core.database.DriveOrphanEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import java.io.File
import java.time.Clock
import javax.inject.Inject

/**
 * Writes Drive items found by a scan or a change (spec §4.3), inside the caller's transaction.
 *
 * Drive raises a file's `version` on every change, a rename or a star included, so a new version
 * alone does not mean new pixels. An item keeps its stored version, which keys its thumbnails and
 * cached files, while its MD5 and size stay the same. New content leaves the old version's files
 * as an orphan that is due at once, so the next cleanup deletes them and the thumbnail is made
 * again. An item back from Drive's bin takes its orphan's version again and with it its files,
 * cached original and offline pin.
 */
class DriveItems @Inject constructor(private val database: PxtDatabase, private val clock: Clock) {
    private val drive = database.driveDao()
    private val cache = database.driveCacheDao()
    private val index = database.mediaIndexDao()

    /** Upserts [incoming], all of [accountId]. Returns how many have new content. */
    suspend fun write(accountId: String, incoming: List<MediaEntity>): Int {
        if (incoming.isEmpty()) return 0
        val now = clock.millis()
        val existing = incoming.map { it.id }.chunked(MAX_SQL_VARIABLES)
            .flatMap { drive.mediaRows(it) }
            .associateBy { it.id }
        val orphans = incoming.mapNotNull { it.remoteId }.chunked(MAX_SQL_VARIABLES)
            .flatMap { cache.orphansOf(accountId, it) }
            .groupBy { it.fileId }
        val changed = ArrayList<String>()
        val restored = ArrayList<Pair<String, DriveOrphanEntity>>()
        val rows = incoming.map { item ->
            val old = existing[item.id]
            val orphan = orphans[item.remoteId]?.firstOrNull { sameContent(it, item) }
            when {
                old != null && sameContent(old, item) ->
                    item.copy(remoteVersion = old.remoteVersion)

                old != null -> item.also { changed += it.id }

                orphan != null -> item.copy(remoteVersion = orphan.version)
                    .also { restored += it.id to orphan }

                else -> item
            }
        }
        changed.chunked(MAX_SQL_VARIABLES).forEach {
            cache.orphan(it, purgeAfter = now)
            cache.deleteCache(it)
        }
        index.upsertMedia(rows)
        restored.forEach { (id, orphan) -> restore(id, orphan, now) }
        return changed.size
    }

    /** Gives item [id] back what it had before Drive removed it. */
    private suspend fun restore(id: String, orphan: DriveOrphanEntity, now: Long) {
        val path = orphan.cachePath
        if (path != null && File(path).length() == orphan.bytes) {
            drive.saveCache(DriveCacheEntity(id, path, orphan.version, orphan.bytes, now, now))
        }
        if (orphan.pinned) cache.pin(listOf(id), now)
        cache.deleteOrphans(listOf(orphan))
    }
}

/** Whether [old] and [new] hold the same bytes: same MD5 and size, else the same version. */
internal fun sameContent(old: MediaEntity, new: MediaEntity): Boolean =
    sameContent(old.md5, old.bytes, old.remoteVersion, new)

internal fun sameContent(orphan: DriveOrphanEntity, new: MediaEntity): Boolean =
    sameContent(orphan.md5, orphan.bytes, orphan.version, new)

private fun sameContent(md5: String?, bytes: Long, version: Long?, new: MediaEntity): Boolean {
    val newMd5 = new.md5
    return if (md5 != null && newMd5 != null) {
        md5.equals(newMd5, ignoreCase = true) && bytes == new.bytes
    } else {
        version == new.remoteVersion
    }
}
