package com.photoxt.core.organize

import com.photoxt.core.database.ContinueRow
import com.photoxt.core.database.HistoryEntity
import com.photoxt.core.database.HistoryRow
import com.photoxt.core.database.PlaybackPositionEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.resumePosition
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The viewing history (spec §4.9): a private, on-device log of what was viewed, from which album,
 * and how far videos were watched, which resume where they stopped (spec §4.6). Nothing is logged
 * while history is paused (incognito) or for hidden items; vault items never reach it.
 */
@Singleton
class History @Inject constructor(
    database: PxtDatabase,
    private val settings: SettingsStore,
    private val hiding: Hiding,
    private val clock: Clock
) {
    private val views = database.historyDao()
    private val positions = database.playbackDao()

    // Saves go through the app's own scope, so the last one is written even while a screen closes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val recent: Flow<List<HistoryRow>> get() = views.recent(RECENT_LIMIT)

    val continueWatching: Flow<List<ContinueRow>> get() = positions.continueWatching(CONTINUE_LIMIT)

    /** Call once, from `Application.onCreate`: auto-delete runs at each start (spec §4.9). */
    fun start() {
        scope.launch { trim() }
    }

    /** How often [mediaId] was viewed (spec §4.9, "viewed N times"). */
    fun viewCount(mediaId: String): Flow<Int> = views.views(mediaId)

    /**
     * [mediaId] was opened from album [albumId] (null for Pictures and Library lists). Returns
     * the view's id, for the time a video then plays; null when nothing was logged.
     */
    suspend fun viewed(mediaId: String, albumId: String?): Long? {
        if (!mayLog(mediaId)) return null
        return views.insert(
            HistoryEntity(mediaId = mediaId, albumId = albumId, viewedAt = clock.millis())
        )
    }

    /** A video of view [viewId] played [ms] more. */
    fun watched(viewId: Long, ms: Long) {
        if (ms > 0) scope.launch { views.addWatched(viewId, ms) }
    }

    /** Where to start video [mediaId], or null to start from the beginning (spec §4.6). */
    suspend fun resumeAt(mediaId: String): Long? =
        positions.position(mediaId)?.let { resumePosition(it.positionMs, it.durationMs) }

    /** Where video [mediaId] stopped; not saved while paused or hidden. */
    fun savePosition(mediaId: String, positionMs: Long, durationMs: Long) {
        scope.launch {
            if (mayLog(mediaId)) {
                positions.save(
                    PlaybackPositionEntity(mediaId, positionMs, durationMs, clock.millis())
                )
            }
        }
    }

    /** Watched to the end: next time starts from the beginning. */
    fun finished(mediaId: String) {
        scope.launch { positions.clear(mediaId) }
    }

    suspend fun clearAll() {
        views.clearAll()
        positions.clearAll()
    }

    /** Clears the views from day [from] to day [until], both included, in [zone]. */
    suspend fun clearDays(from: java.time.LocalDate, until: java.time.LocalDate, zone: ZoneId) {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        views.clear(start, end)
        positions.clearRange(start, end)
    }

    /** Auto-delete (spec §4.9): views older than the setting's months go. */
    suspend fun trim(zone: ZoneId = ZoneId.systemDefault()): Int {
        val months = settings.current().history.keepMonths
        if (months <= 0) return 0
        val before = ZonedDateTime.ofInstant(clock.instant(), zone).minusMonths(months.toLong())
            .toInstant().toEpochMilli()
        positions.trim(before)
        return views.trim(before)
    }

    private suspend fun mayLog(mediaId: String): Boolean =
        !settings.current().history.paused && !hiding.isHidden(mediaId)

    private companion object {
        const val RECENT_LIMIT = 1_000
        const val CONTINUE_LIMIT = 20
    }
}
