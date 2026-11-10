package com.photoxt.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where an item's bytes live (spec §3): [DEVICE] is MediaStore, [DRIVE] one Google account's
 * Drive. Remote (someone else's public album) arrives in Phase 12.
 */
enum class SourceType { DEVICE, DRIVE }

enum class MediaKind { IMAGE, VIDEO }

/** Album types (spec §3). Virtual and smart albums arrive in later phases. */
enum class AlbumType { DEVICE_FOLDER, DRIVE_FOLDER }

/**
 * Where a cached Drive original stands (spec §3): only its thumbnail is on the phone, the full
 * file is cached and expires, or it is pinned offline and never expires.
 */
enum class CacheState { THUMB_ONLY, CACHED, OFFLINE }

/**
 * A tracked Drive folder: a scan is running or due, it is in step, or it was binned, deleted or
 * unshared in Drive (spec §4.3, lost access): then its items stay until the user removes it.
 */
enum class TrackedFolderState { SCANNING, READY, UNAVAILABLE }

/** How much of the phone's media PhotoXT may read (spec §4.3, permissions UX). */
enum class MediaAccess {
    /** Every photo and video on the phone. */
    FULL,

    /** Android 14+: only the photos and videos the user picked. */
    LIMITED,

    /** Not granted yet, or refused. */
    DENIED
}

/** Timeline filter chips (spec §4.2). [OFFLINE] is the Drive items pinned offline. */
enum class MediaFilter { ALL, PHOTOS, VIDEOS, FAVORITES, DEVICE, DRIVE, OFFLINE }

/**
 * The calendar unit a timeline's date headers group by. Keys are local-calendar integers stored
 * with every item, so the database can group and sort by them:
 * [DAY] is the epoch day, [MONTH] is `year * 12 + month - 1`, and [YEAR] is the year.
 */
enum class DateGrouping {
    DAY,
    MONTH,
    YEAR;

    /** The key of [epochMillis] in [zone] for this unit. */
    fun keyOf(epochMillis: Long, zone: ZoneId): Int {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return when (this) {
            DAY -> date.toEpochDay().toInt()
            MONTH -> monthKey(date)
            YEAR -> date.year
        }
    }

    /** The first day of the period a key stands for, for labels. */
    fun startOf(key: Int): LocalDate = when (this) {
        DAY -> LocalDate.ofEpochDay(key.toLong())
        MONTH -> LocalDate.of(Math.floorDiv(key, MONTHS), Math.floorMod(key, MONTHS) + 1, 1)
        YEAR -> LocalDate.of(key, 1, 1)
    }

    companion object {
        private const val MONTHS = 12

        fun monthKey(date: LocalDate): Int = date.year * MONTHS + date.monthValue - 1
    }
}

/**
 * How the viewer fits a photo to the screen (spec §4.5). [STRETCH] fills the screen and ignores
 * the aspect ratio on purpose; [ONE_TO_ONE] shows one image pixel per screen pixel. The viewer
 * keeps the chosen mode for the session, and the slideshow uses it too.
 */
enum class DisplayMode { FIT, FILL, STRETCH, FIT_WIDTH, FIT_HEIGHT, ONE_TO_ONE }

/**
 * Where the viewer starts: the item [id] at [index] (0 = newest) among the items of one album, or
 * of everything when [albumId] is null, under [filter]. The viewer pages through the same items
 * in the same order as the grid it was opened from.
 */
data class ViewerStart(
    val albumId: String?,
    val filter: MediaFilter,
    val index: Int,
    val id: String
)

/**
 * What one file operation does (spec §4.7). A batch is one user action; each of its jobs does the
 * batch's operation to one item, except folder creation, which a recursive paste needs first.
 */
enum class TransferOp {
    COPY,
    MOVE,
    TRASH,
    RESTORE,
    DELETE,
    RENAME,
    CONVERT,
    MKDIR,

    /** A Drive file the user can't bin leaves the folder instead (spec §4.7, Trash). */
    REMOVE_FROM_ALBUM
}

/**
 * Where a transfer job stands. [WAITING] needs the user: a name conflict, a system permission
 * screen, or a choice for a Drive file they don't own (see the job's `waitFor`).
 */
enum class TransferState { QUEUED, RUNNING, WAITING, DONE, FAILED, SKIPPED, CANCELLED }

/** What a [TransferState.WAITING] job waits for. */
enum class WaitFor {
    /** An item of that name is already there: Keep both, Replace or Skip. */
    CONFLICT,

    /** A Drive file shared with the user can't go to the bin: offer Remove from this album. */
    NOT_OWNED,

    /** The Google account must grant full Drive access first (spec §2, scope upgrade). */
    DRIVE_SCOPE,

    /** MediaStore asks the user before PhotoXT changes another app's item (write request). */
    DEVICE_WRITE,

    /** MediaStore's own confirmation to move items to or from its trash, or delete them. */
    DEVICE_TRASH,
    DEVICE_RESTORE,
    DEVICE_DELETE
}

/** What happens when an item of the same name is already in the target (spec §4.7). */
enum class ConflictPolicy {
    ASK,

    /** Keep both: the new one gets a " (1)" suffix. */
    KEEP_BOTH,
    REPLACE,
    SKIP
}

/** The in-app clipboard's mode (spec §4.7): a paste copies, or moves (cut). */
enum class ClipboardMode { COPY, CUT }
