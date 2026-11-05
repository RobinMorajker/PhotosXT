package com.photoxt.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** What a drag on the video is changing, shown over the video while it happens. */
internal sealed interface GestureFeedback {
    data class Seek(val positionMs: Long, val durationMs: Long) : GestureFeedback

    data class Brightness(val level: Float) : GestureFeedback

    data class Volume(val level: Float) : GestureFeedback

    data class Skip(val forward: Boolean) : GestureFeedback
}

/** What the gestures act on: the player's position and the window's and phone's levels. */
internal data class GestureTargets(
    val position: () -> Long,
    val duration: () -> Long,
    val seekTo: (Long, exact: Boolean) -> Unit,
    val brightness: () -> Float,
    val setBrightness: (Float) -> Unit,
    val volume: () -> Float,
    val setVolume: (Float) -> Unit
)

/** The video's pinch zoom and pan, and the feedback of the gesture in progress. */
@Stable
internal class PlayerGestureState {
    var feedback by mutableStateOf<GestureFeedback?>(null)
    var zoom by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    fun zoomBy(factor: Float, move: Offset, size: Size) {
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val limit = Offset(size.width * (zoom - 1f) / 2f, size.height * (zoom - 1f) / 2f)
        pan = Offset(
            (pan.x + move.x).coerceIn(-limit.x, limit.x),
            (pan.y + move.y).coerceIn(-limit.y, limit.y)
        )
    }

    private companion object {
        const val MAX_ZOOM = 4f
    }
}

private enum class DragMode { SEEK, BRIGHTNESS, VOLUME, ZOOM }

/**
 * The player's drags (spec §4.6): horizontal seeks, vertical on the left half sets brightness and
 * on the right half volume, and two fingers zoom into the video. A drag picks its meaning once it
 * passes the touch slop and keeps it until the fingers lift.
 */
internal fun Modifier.playerDrags(state: PlayerGestureState, targets: GestureTargets) =
    pointerInput(state, targets) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val drag = DragSession(down.position.x < size.width / 2f, targets)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2 && drag.mode.let { it == null || it == DragMode.ZOOM }) {
                    drag.mode = DragMode.ZOOM
                    val area = Size(size.width.toFloat(), size.height.toFloat())
                    state.zoomBy(event.calculateZoom(), event.calculatePan(), area)
                    event.changes.forEach { it.consume() }
                } else if (pressed == 1 && drag.mode != DragMode.ZOOM) {
                    val change = event.changes.first { it.pressed }
                    drag.travel += change.positionChange()
                    if (drag.mode == null &&
                        drag.travel.getDistance() > viewConfiguration.touchSlop
                    ) {
                        drag.mode = drag.classify()
                    }
                    drag.mode?.let {
                        state.feedback =
                            drag.apply(Size(size.width.toFloat(), size.height.toFloat()))
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
            drag.finish()
            if (drag.mode != DragMode.ZOOM) state.feedback = null
        }
    }

/** One drag's start values and progress. */
private class DragSession(private val leftHalf: Boolean, private val targets: GestureTargets) {
    var mode: DragMode? = null
    var travel = Offset.Zero
    private val startPosition = targets.position()
    private val startBrightness = targets.brightness()
    private val startVolume = targets.volume()
    private var seekTarget = startPosition

    fun classify(): DragMode = when {
        abs(travel.x) > abs(travel.y) -> DragMode.SEEK
        leftHalf -> DragMode.BRIGHTNESS
        else -> DragMode.VOLUME
    }

    fun apply(size: Size): GestureFeedback? = when (mode) {
        DragMode.SEEK -> {
            val duration = targets.duration().coerceAtLeast(0L)
            val span = minOf(duration, SEEK_SPAN_MS).toFloat()
            seekTarget = (startPosition + (travel.x / size.width * span).toLong())
                .coerceIn(0L, duration)
            targets.seekTo(seekTarget, false)
            GestureFeedback.Seek(seekTarget, duration)
        }

        DragMode.BRIGHTNESS -> {
            val level = (startBrightness - travel.y / (size.height * LEVEL_SPAN)).coerceIn(0f, 1f)
            targets.setBrightness(level)
            GestureFeedback.Brightness(level)
        }

        DragMode.VOLUME -> {
            val level = (startVolume - travel.y / (size.height * LEVEL_SPAN)).coerceIn(0f, 1f)
            targets.setVolume(level)
            GestureFeedback.Volume(level)
        }

        DragMode.ZOOM, null -> null
    }

    fun finish() {
        if (mode == DragMode.SEEK) targets.seekTo(seekTarget, true)
    }

    private companion object {
        /** A drag across the whole width seeks two minutes, or the whole of a shorter video. */
        const val SEEK_SPAN_MS = 120_000L

        /** A drag over 60% of the height goes from no brightness (or volume) to full. */
        const val LEVEL_SPAN = 0.6f
    }
}
