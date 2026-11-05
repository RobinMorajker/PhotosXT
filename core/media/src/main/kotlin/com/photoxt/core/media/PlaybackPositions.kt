package com.photoxt.core.media

import com.photoxt.core.database.PlaybackDao
import com.photoxt.core.database.PlaybackPositionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where each video was left (spec §4.6, continuity). Saves go through the app's own scope, so the
 * last one is written even while the player is closing.
 */
@Singleton
class PlaybackPositions @Inject constructor(private val dao: PlaybackDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where to start [mediaId], or null to start from the beginning. */
    suspend fun resumeAt(mediaId: String): Long? =
        dao.position(mediaId)?.let { resumePosition(it.positionMs, it.durationMs) }

    fun save(mediaId: String, positionMs: Long, durationMs: Long) {
        scope.launch {
            dao.save(
                PlaybackPositionEntity(mediaId, positionMs, durationMs, System.currentTimeMillis())
            )
        }
    }

    /** Watched to the end: next time starts from the beginning. */
    fun clear(mediaId: String) {
        scope.launch { dao.clear(mediaId) }
    }
}

/**
 * Where a video stopped at [positionMs] of [durationMs] resumes: there, unless it had barely
 * started or was nearly over, in which case it starts again.
 */
fun resumePosition(positionMs: Long, durationMs: Long): Long? {
    if (positionMs < MIN_RESUME_MS) return null
    if (durationMs <= 0) return positionMs
    val nearlyOver =
        positionMs > durationMs - END_MARGIN_MS || positionMs > durationMs * NEARLY_DONE
    return if (nearlyOver) null else positionMs
}

private const val MIN_RESUME_MS = 3_000L
private const val END_MARGIN_MS = 3_000L
private const val NEARLY_DONE = 0.95
