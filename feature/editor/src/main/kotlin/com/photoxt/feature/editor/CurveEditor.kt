package com.photoxt.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.Curves
import com.photoxt.core.model.Point01
import com.photoxt.core.render.ToneCurves
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The curve of the channel picked: drag a point to move it, drag elsewhere or tap to add one,
 * double-tap a point to take it away. TalkBack gets ready-made curves as actions instead.
 */
@Composable
internal fun CurveEditor(state: EditorState, tools: EditorTools) {
    val channel = state.curve
    val points = state.recipe.curves.of(channel).ifEmpty { STRAIGHT }
    val current by rememberUpdatedState(points)
    val reach = with(LocalDensity.current) { TOUCH_DP.dp.toPx() }
    val step = R.string.editor_step_curves
    val touch = remember(channel, tools) {
        CurveTouch(
            reach = reach,
            points = { current },
            set = { label, p ->
                tools.history.edit(label) { r -> r.copy(curves = r.curves.with(channel, p)) }
            },
            commit = { tools.history.commit(step) }
        )
    }
    val name = stringResource(R.string.editor_curve_named, stringResource(channel.label))
    val count = pluralStringResource(R.plurals.editor_curve_points, points.size, points.size)
    val actions = listOf(
        R.string.editor_curve_lighten to LIGHTEN,
        R.string.editor_curve_darken to DARKEN,
        R.string.editor_curve_contrast to CONTRAST,
        R.string.editor_curve_straight to STRAIGHT
    ).map { (label, shape) ->
        CustomAccessibilityAction(stringResource(label)) {
            touch.set(step, shape)
            true
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .widthIn(max = MAX_DP.dp)
            .aspectRatio(1f)
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = name
                stateDescription = count
                customActions = actions
            }
            .pointerInput(touch) { curveTaps(touch) }
            .pointerInput(touch) { curveDrags(touch) }
    ) {
        drawCurve(points, Color(channel.ink))
    }
}

/** What the curve's gestures read and write: its points now, and the recipe. */
private class CurveTouch(
    val reach: Float,
    val points: () -> List<Point01>,
    val set: (label: Int?, List<Point01>) -> Unit,
    val commit: () -> Unit
)

/** A tap adds a point; a double tap on a point, the two ends apart, takes it away. */
private suspend fun PointerInputScope.curveTaps(touch: CurveTouch) {
    val step = R.string.editor_step_curves
    detectTapGestures(
        onTap = { at -> touch.set(step, insert(touch.points(), toCurve(at, size)).first) },
        onDoubleTap = { at ->
            val now = touch.points()
            val i = nearest(now, at, size, touch.reach)
            if (i != null && i != 0 && i != now.lastIndex) touch.set(step, now - now[i])
        }
    )
}

/** A drag moves the point it starts on; from an empty spot it adds the point it then moves. */
private suspend fun PointerInputScope.curveDrags(touch: CurveTouch) {
    var working = touch.points()
    var dragging = -1
    detectDragGestures(
        onDragStart = { at ->
            working = touch.points()
            dragging = nearest(working, at, size, touch.reach) ?: run {
                val (added, index) = insert(working, toCurve(at, size))
                working = added
                index
            }
            touch.set(null, working)
        },
        onDrag = { change, _ ->
            if (dragging >= 0) {
                working = moved(working, dragging, toCurve(change.position, size))
                touch.set(null, working)
            }
        },
        onDragEnd = touch.commit,
        onDragCancel = touch.commit
    )
}

/** A quarter grid, the straight line the curve bends from, the curve and its points. */
private fun DrawScope.drawCurve(points: List<Point01>, ink: Color) {
    val w = size.width
    val h = size.height
    val grid = Color(GRID)
    for (k in 1 until QUARTERS) {
        drawLine(grid, Offset(w * k / QUARTERS, 0f), Offset(w * k / QUARTERS, h))
        drawLine(grid, Offset(0f, h * k / QUARTERS), Offset(w, h * k / QUARTERS))
    }
    drawLine(grid, Offset(0f, h), Offset(w, 0f))
    val table = ToneCurves.table(points)
    val path = Path()
    table.forEachIndexed { i, y ->
        val x = w * i / (table.size - 1)
        if (i == 0) path.moveTo(x, h * (1 - y)) else path.lineTo(x, h * (1 - y))
    }
    drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
    val outline = Stroke(1.dp.toPx())
    points.forEach { p ->
        val centre = Offset(w * p.x, h * (1 - p.y))
        drawCircle(ink, radius = DOT_DP.dp.toPx(), center = centre)
        drawCircle(Color.White, radius = DOT_DP.dp.toPx(), center = centre, style = outline)
    }
}

internal fun Curves.of(channel: CurveChannel): List<Point01> = when (channel) {
    CurveChannel.RGB -> rgb
    CurveChannel.RED -> red
    CurveChannel.GREEN -> green
    CurveChannel.BLUE -> blue
}

internal fun Curves.with(channel: CurveChannel, points: List<Point01>): Curves {
    val kept = points.takeUnless { it == STRAIGHT }.orEmpty()
    return when (channel) {
        CurveChannel.RGB -> copy(rgb = kept)
        CurveChannel.RED -> copy(red = kept)
        CurveChannel.GREEN -> copy(green = kept)
        CurveChannel.BLUE -> copy(blue = kept)
    }
}

private fun toCurve(at: Offset, size: IntSize) =
    Point01((at.x / size.width).coerceIn(0f, 1f), (1 - at.y / size.height).coerceIn(0f, 1f))

/** The point of [points] under screen point [at], no further than [within] pixels off. */
private fun nearest(points: List<Point01>, at: Offset, size: IntSize, within: Float): Int? {
    fun distance(p: Point01) = hypot(p.x * size.width - at.x, (1 - p.y) * size.height - at.y)
    return points.indices
        .filter { distance(points[it]) <= within }
        .minByOrNull { distance(points[it]) }
}

/**
 * [points] with [p] added in x order, and where it went; a point too close to another, or
 * outside the two ends, is not added.
 */
private fun insert(points: List<Point01>, p: Point01): Pair<List<Point01>, Int> {
    val at = points.indexOfFirst { it.x > p.x }.let { if (it < 0) points.size else it }
    val crowded = points.any { abs(it.x - p.x) < GAP }
    if (crowded || at == 0 || at == points.size) return points to -1
    return (points.take(at) + p + points.drop(at)) to at
}

/** Point [i] moved to [p], kept between its neighbours so the curve stays a function. */
private fun moved(points: List<Point01>, i: Int, p: Point01): List<Point01> {
    val low = if (i == 0) 0f else points[i - 1].x + GAP
    val high = if (i == points.lastIndex) 1f else points[i + 1].x - GAP
    val x = if (low > high) points[i].x else p.x.coerceIn(low, high)
    return points.toMutableList().also { it[i] = Point01(x, p.y) }
}

private val STRAIGHT = listOf(Point01(0f, 0f), Point01(1f, 1f))
private val LIGHTEN = listOf(Point01(0f, 0f), Point01(0.5f, 0.62f), Point01(1f, 1f))
private val DARKEN = listOf(Point01(0f, 0f), Point01(0.5f, 0.38f), Point01(1f, 1f))
private val CONTRAST =
    listOf(Point01(0f, 0f), Point01(0.25f, 0.18f), Point01(0.75f, 0.82f), Point01(1f, 1f))
private const val GAP = 0.02f
private const val QUARTERS = 4
private const val TOUCH_DP = 24
private const val DOT_DP = 6
private const val MAX_DP = 280
private const val GRID = 0x44FFFFFF
