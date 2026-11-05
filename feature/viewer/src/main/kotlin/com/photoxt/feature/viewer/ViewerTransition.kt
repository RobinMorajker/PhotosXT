package com.photoxt.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.photoxt.core.ui.HeroLaunch
import com.photoxt.core.ui.MediaHeroState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The viewer's flight out of its grid tile and back into it (spec §4.5), and the swipe-down and
 * back gestures that end in that flight. While [flying], [HeroLayer] draws the photo at
 * [heroBounds] (viewer coordinates) and the pager stays hidden; [backdropAlpha] below 1 lets the
 * grid show through.
 */
@Stable
internal class ViewerTransition(private val hero: MediaHeroState?) {
    enum class Phase { OPENING, SHOWN, CLOSING }

    /** Opening until [open] runs, so nothing of the viewer shows before its photo flies in. */
    var phase by mutableStateOf(Phase.OPENING)
        private set

    /** What the flying photo shows; stretched photos fly stretched, all others cropped. */
    var model: Any? by mutableStateOf(null)
        private set
    var stretch by mutableStateOf(false)
        private set

    private val flight = Animatable(Rect.Zero, Rect.VectorConverter)
    private val fade = Animatable(0f)
    private val heroFade = Animatable(1f)

    // A gesture moves the photo by [drag] and shrinks it with [progress], starting from [rest].
    private var rest by mutableStateOf(Rect.Zero)
    private var drag by mutableStateOf<Offset?>(null)
    private var progress by mutableFloatStateOf(0f)

    val flying: Boolean get() = phase != Phase.SHOWN || drag != null

    val heroBounds: Rect
        get() = drag?.let { rest.scaledAroundCentre(1f - SHRINK * progress).translate(it) }
            ?: flight.value

    val backdropAlpha: Float get() = if (drag != null) 1f - progress else fade.value

    val heroAlpha: Float get() = heroFade.value

    /** Flies [model] from the tapped tile to [target]; without a tile the viewer fades in. */
    suspend fun open(from: HeroLaunch?, target: Rect, model: Any?, stretch: Boolean) {
        if (from == null || target.isEmpty) {
            phase = Phase.SHOWN
            fade.snapTo(0f)
            fade.animateTo(1f, tween(FADE_MS))
            return
        }
        this.model = model
        this.stretch = stretch
        flight.snapTo(from.bounds)
        fade.snapTo(0f)
        heroFade.snapTo(1f)
        phase = Phase.OPENING
        hero?.hiddenId = from.id
        try {
            coroutineScope {
                launch { fade.animateTo(1f, tween(OPEN_MS)) }
                flight.animateTo(target, tween(OPEN_MS, easing = EmphasizedDecelerate))
            }
        } finally {
            hero?.hiddenId = null
            phase = Phase.SHOWN
        }
    }

    /** A swipe down starts on a photo shown at [from]. */
    fun beginGesture(from: Rect, model: Any?, stretch: Boolean) {
        rest = from
        this.model = model
        this.stretch = stretch
        progress = 0f
        drag = Offset.Zero
    }

    fun dragBy(delta: Offset, height: Float) {
        val moved = (drag ?: return) + delta
        drag = moved
        progress = if (height > 0f) (moved.y / height).coerceIn(0f, 1f) else 0f
    }

    /** Predictive back: the photo shrinks a little and leans away from the edge swiped from. */
    fun backProgress(fraction: Float, fromLeft: Boolean, width: Float) {
        if (drag == null) return
        val shown = fraction.coerceIn(0f, 1f)
        drag = Offset((if (fromLeft) 1f else -1f) * BACK_SHIFT * width * shown, 0f)
        progress = shown * BACK_DEPTH
    }

    /** Whether a swipe down that ended at [velocityY] (px/s) is far or fast enough to close. */
    fun shouldClose(velocityY: Float, height: Float): Boolean {
        val moved = drag?.y ?: return false
        return moved > height * CLOSE_DISTANCE || (velocityY > CLOSE_VELOCITY && moved > 0f)
    }

    /** The gesture let go without closing: the photo springs back. */
    suspend fun cancelGesture() {
        val from = drag ?: return
        val fromProgress = progress
        animate(1f, 0f, animationSpec = tween(SETTLE_MS)) { value, _ ->
            drag = from * value
            progress = fromProgress * value
        }
        drag = null
        progress = 0f
    }

    /**
     * Flies the photo from where it is (shown at [from], or wherever a gesture left it) into the
     * tile [target] finds; with no tile, it shrinks and fades. Ends with the backdrop clear.
     */
    suspend fun close(
        from: Rect,
        model: Any?,
        stretch: Boolean,
        itemId: String?,
        target: suspend () -> Rect?
    ) {
        val start = if (drag != null) heroBounds else from
        val startAlpha = backdropAlpha
        this.model = model
        this.stretch = stretch
        flight.snapTo(start)
        fade.snapTo(startAlpha)
        heroFade.snapTo(1f)
        drag = null
        progress = 0f
        phase = Phase.CLOSING
        hero?.hiddenId = itemId
        try {
            val end = target()
            coroutineScope {
                launch { fade.animateTo(0f, tween(CLOSE_MS)) }
                if (end != null && !start.isEmpty) {
                    flight.animateTo(end, tween(CLOSE_MS, easing = Emphasized))
                } else {
                    launch { heroFade.animateTo(0f, tween(CLOSE_MS)) }
                    flight.animateTo(start.scaledAroundCentre(FADE_SCALE), tween(CLOSE_MS))
                }
            }
        } finally {
            hero?.hiddenId = null
        }
    }

    private companion object {
        const val OPEN_MS = 300
        const val CLOSE_MS = 280
        const val FADE_MS = 200
        const val SETTLE_MS = 220
        const val SHRINK = 0.3f
        const val BACK_SHIFT = 0.04f
        const val BACK_DEPTH = 0.35f
        const val CLOSE_DISTANCE = 0.12f
        const val CLOSE_VELOCITY = 1_500f
        const val FADE_SCALE = 0.85f
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

internal fun Rect.scaledAroundCentre(scale: Float): Rect {
    val c = center
    val w = width * scale
    val h = height * scale
    return Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
}
