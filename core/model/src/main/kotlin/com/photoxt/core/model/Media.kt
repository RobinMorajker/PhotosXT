package com.photoxt.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where an item's bytes live (spec §3). Drive and remote sources arrive in Phases 3 and 12. */
enum class MediaSource { DEVICE }

enum class MediaKind { IMAGE, VIDEO }

/** Album types (spec §3). Drive, virtual and smart albums arrive in later phases. */
enum class AlbumType { DEVICE_FOLDER }

/** How much of the phone's media PhotoXT may read (spec §4.3, permissions UX). */
enum class MediaAccess {
    /** Every photo and video on the phone. */
    FULL,

    /** Android 14+: only the photos and videos the user picked. */
    LIMITED,

    /** Not granted yet, or refused. */
    DENIED
}

/**
 * Timeline filter chips (spec §4.2). Device, Drive and Offline join when Drive albums exist
 * (Phase 3); until then every item is a device item.
 */
enum class MediaFilter { ALL, PHOTOS, VIDEOS, FAVORITES }

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
