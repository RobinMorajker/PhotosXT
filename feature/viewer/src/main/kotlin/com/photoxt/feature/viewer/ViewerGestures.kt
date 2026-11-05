package com.photoxt.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a vertical swipe on an unzoomed page does: down closes the viewer, up shows details. */
internal class VerticalSwipes(
    val enabled: () -> Boolean,
    val onStart: () -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: (velocityY: Float) -> Unit,
    val onSwipeUp: () -> Unit
)

/**
 * Swipes down and up on a page. They only start once the finger has moved vertically past the
 * touch slop, and only when the photo inside did not take the drag to pan, so the pager keeps
 * horizontal swipes and a zoomed photo keeps its panning.
 */
internal fun Modifier.verticalSwipes(swipes: VerticalSwipes) = pointerInput(swipes) {
    val swipeUp = SWIPE_UP.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!swipes.enabled()) return@awaitEachGesture
        var overSlop = 0f
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            overSlop = over
            change.consume()
        } ?: return@awaitEachGesture
        if (overSlop < 0f) {
            var travelled = overSlop
            val ended = verticalDrag(start.id) {
                travelled += it.positionChange().y
                it.consume()
            }
            if (ended && travelled < -swipeUp) swipes.onSwipeUp()
        } else {
            val velocity = VelocityTracker()
            swipes.onStart()
            swipes.onDrag(Offset(0f, overSlop))
            val ended = drag(start.id) { change ->
                velocity.addPointerInputChange(change)
                swipes.onDrag(change.positionChange())
                change.consume()
            }
            swipes.onEnd(if (ended) velocity.calculateVelocity().y else 0f)
        }
    }
}

/**
 * Two-finger rotation of one page (spec §4.5): view only, and gone once the page leaves. The
 * rotation follows the fingers once they have turned past [ROTATE_SLOP] degrees, and on release
 * settles on the nearest quarter turn, scaled so the turned photo still fits.
 */
@Stable
internal class PageRotation(private val scope: CoroutineScope) {
    private val settled = Animatable(0f)
    private var gesture by mutableFloatStateOf(0f)

    /** A rotation gesture is in progress; the photo's own gestures pause meanwhile. */
    var active by mutableStateOf(false)
        private set

    val degrees: Float get() = if (active) settled.value + gesture else settled.value

    val turned: Boolean get() = degrees.roundToInt() % FULL_TURN != 0

    fun start() {
        gesture = 0f
        active = true
    }

    fun rotateBy(delta: Float) {
        gesture += delta
    }

    fun end() {
        val now = degrees
        val target = (now / QUARTER).roundToInt() * QUARTER
        active = false
        gesture = 0f
        scope.launch {
            settled.snapTo(now)
            settled.animateTo(target, tween(SETTLE_MS))
        }
    }

    private companion object {
        const val FULL_TURN = 360
        const val QUARTER = 90f
        const val SETTLE_MS = 200
    }
}

@Composable
internal fun rememberPageRotation(): PageRotation {
    val scope = rememberCoroutineScope()
    return remember(scope) { PageRotation(scope) }
}

/**
 * Turns this page with [rotation]. [fitted] is the photo's size as laid out unturned; a sideways
 * photo is scaled so its turned bounds still fit the page.
 */
internal fun Modifier.rotatable(rotation: PageRotation, fitted: Size) = this
    .pointerInput(rotation) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var turned = 0f
            do {
                // The initial pass sees the fingers before the photo's own pinch-zoom takes them.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val delta = event.calculateRotation()
                    turned += delta
                    if (!rotation.active && abs(turned) > ROTATE_SLOP) rotation.start()
                    if (rotation.active) {
                        rotation.rotateBy(delta)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (rotation.active) rotation.end()
        }
    }
    .graphicsLayer {
        val degrees = rotation.degrees
        rotationZ = degrees
        if (degrees != 0f && !fitted.isEmpty()) {
            val sideways = minOf(size.width / fitted.height, size.height / fitted.width)
            val upright = minOf(size.width / fitted.width, size.height / fitted.height)
            // ponytail: past 1 the photo is enlarged from its fitted decode, so it looks soft until
            // zoomed; scaling the zoom itself would keep it sharp.
            val target = sideways / upright
            val weight = abs(sin(degrees * PI.toFloat() / HALF_TURN))
            val scale = 1f + (target - 1f) * weight
            scaleX = scale
            scaleY = scale
        }
    }

private val SWIPE_UP = 48.dp
private const val ROTATE_SLOP = 20f
private const val HALF_TURN = 180f
