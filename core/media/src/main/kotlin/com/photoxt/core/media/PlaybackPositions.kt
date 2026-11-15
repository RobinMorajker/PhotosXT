package com.photoxt.core.media

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
