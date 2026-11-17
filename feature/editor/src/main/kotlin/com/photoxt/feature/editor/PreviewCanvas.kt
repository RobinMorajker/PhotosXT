package com.photoxt.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as UiSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import com.photoxt.core.model.Layer
import com.photoxt.core.model.Point01
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.RenderGeometry
import com.photoxt.core.render.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The photo being edited and everything drawn and done on it (spec §4.10): crop handles,
 * perspective corners, strokes, mask brushes and handles, redaction boxes, red-eye taps, layer
 * moves, and the colour picker. Touches become fractions of the cropped picture (layers and
 * drawing) or of the source (masks, redactions, retouch), through the preview's own geometry.
 */
@Composable
internal fun PreviewCanvas(
    bitmap: Bitmap?,
    geometry: RenderGeometry?,
    state: EditorState,
    tools: EditorTools,
    modifier: Modifier = Modifier
) {
    val picture = bitmap ?: return
    val geo = geometry ?: return
    val description = stringResource(R.string.editor_preview_description, state.title)
    BoxWithConstraints(modifier.fillMaxSize().semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val box = with(density) { UiSize(maxWidth.toPx(), maxHeight.toPx()) }
        val fit = remember(box, picture.width, picture.height) {
            Fit.of(box, picture.width, picture.height)
        }
        // A gesture outlives a render: it reads the latest of each of these at every touch.
        val stateNow by rememberUpdatedState(state)
        val geoNow by rememberUpdatedState(geo)
        val fitNow by rememberUpdatedState(fit)
        val pictureNow by rememberUpdatedState(picture)
        val touch = remember(tools) {
            Touch(tools, { stateNow }, { Shown(pictureNow, geoNow, fitNow) })
        }
        val live = remember { LiveMarks() }
        val slop = with(density) { HANDLE_SLOP.dp.toPx() }
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state.gesture) { gestures(touch, live, slop) }
        ) {
            drawTool(geo, fit, state, tools, live)
        }
    }
}

/** What a gesture under way shows before it becomes an edit: the line drawn, the box dragged. */
internal class LiveMarks {
    val line = mutableStateListOf<Offset>()
    var box by mutableStateOf<Rect?>(null)
}

/** Where the picture sits in the box: its scale and top-left corner. */
internal data class Fit(
    val scale: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    fun toPicture(p: Offset): Pair<Float, Float> = (p.x - left) / scale to (p.y - top) / scale

    fun toScreen(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)

    /** A fraction of the whole picture as a point on screen. */
    fun fraction(x: Float, y: Float) = Offset(left + x * width * scale, top + y * height * scale)

    /** A point on screen as a fraction of the whole picture, kept inside it. */
    fun toFraction(p: Offset): Point01 {
        val (x, y) = toPicture(p)
        return Point01((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
    }

    companion object {
        fun of(box: UiSize, w: Int, h: Int): Fit {
            val s = min(box.width / w, box.height / h)
            val left = (box.width - w * s) / 2
            val top = (box.height - h * s) / 2
            return Fit(s, left, top, w.toFloat(), h.toFloat())
        }
    }
}

/** The preview on screen: its picture, the geometry it was rendered with, and where it sits. */
internal class Shown(val picture: Bitmap, val geo: RenderGeometry, val fit: Fit)

/** The latest state and preview, read at each touch: gestures outlive a render. */
internal class Touch(
    val tools: EditorTools,
    private val stateOf: () -> EditorState,
    private val shownOf: () -> Shown
) {
    val state: EditorState get() = stateOf()
    val geo: RenderGeometry get() = shownOf().geo
    val fit: Fit get() = shownOf().fit

    fun toSource(p: Offset) = toSource(geo, fit, p)

    fun toContent(p: Offset) = toContent(geo, fit, p)

    fun sourceToScreen(x: Float, y: Float) = sourceToScreen(geo, fit, x, y)

    /** The colour of the preview under screen point [p]. */
    fun colorAt(p: Offset): Int {
        val shown = shownOf()
        val (x, y) = shown.fit.toPicture(p)
        val px = x.toInt().coerceIn(0, shown.picture.width - 1)
        val py = y.toInt().coerceIn(0, shown.picture.height - 1)
        return shown.picture[px, py]
    }
}

private fun toSource(geo: RenderGeometry, fit: Fit, p: Offset): Point01 {
    val (x, y) = fit.toPicture(p)
    val (sx, sy) = geo.outToSource.map(x.toDouble(), y.toDouble())
    return Point01(sx.toFloat().coerceIn(0f, 1f), sy.toFloat().coerceIn(0f, 1f))
}

private fun toContent(geo: RenderGeometry, fit: Fit, p: Offset): Point01 {
    val (x, y) = fit.toPicture(p)
    val (cx, cy) = geo.outToContent.map(x.toDouble(), y.toDouble())
    return Point01(cx.toFloat(), cy.toFloat())
}

internal fun sourceToScreen(geo: RenderGeometry, fit: Fit, x: Float, y: Float): Offset {
    val (ox, oy) = geo.sourceToOut.map(x.toDouble(), y.toDouble())
    return fit.toScreen(ox.toFloat(), oy.toFloat())
}

internal fun contentToScreen(geo: RenderGeometry, fit: Fit, x: Float, y: Float): Offset {
    val (ox, oy) = geo.contentToOut.map(x.toDouble(), y.toDouble())
    return fit.toScreen(ox.toFloat(), oy.toFloat())
}

/** The touches the tool shown takes; with no tool, press and hold shows the original. */
private suspend fun PointerInputScope.gestures(g: Touch, live: LiveMarks, slop: Float) {
    val tools = g.tools
    when (g.state.gesture) {
        Gesture.NONE -> detectTapGestures(onPress = {
            tools.history.compare(true)
            tryAwaitRelease()
            tools.history.compare(false)
        })

        Gesture.PICK_COLOR -> detectTapGestures { tools.draw.picked(g.colorAt(it)) }

        Gesture.RED_EYE -> detectTapGestures { tools.retouch.addRedEye(g.toSource(it)) }

        Gesture.CROP -> cropGestures(g, slop)

        Gesture.CORNERS -> cornerGestures(g, slop)

        Gesture.REDACT_BOX -> boxGestures(g) { live.box = it }

        Gesture.LINEAR, Gesture.RADIAL -> handleGestures(g, slop)

        Gesture.DRAW, Gesture.BRUSH_MASK, Gesture.ERASE -> strokeGestures(g, live.line)

        Gesture.LAYER -> layerGestures(g)
    }
}

// --- Crop -------------------------------------------------------------------------------

/** A drag from a corner sizes the crop; a drag from inside slides it. */
private suspend fun PointerInputScope.cropGestures(g: Touch, slop: Float) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        val fit = g.fit
        val state = g.state
        var crop = state.recipe.geometry.crop
        val corners = cornersOf(crop).map { (x, y) -> fit.fraction(x, y) }
        val grabbed = corners.indexOfFirst { (it - down.position).getDistance() < slop * 2 }
        val inside = Rect(corners[TOP_LEFT], corners[BOTTOM_RIGHT]).contains(down.position)
        if (grabbed < 0 && !inside) return@awaitEachGesture
        val ratio = cropRatio(state, tools)
        drag(down.id) { change ->
            crop = if (grabbed >= 0) {
                val frame = tools.transform.frameSize()
                movedCorner(crop, grabbed, fit.toFraction(change.position), ratio, frame)
            } else {
                val by = change.positionChange()
                slid(crop, by.x / (fit.width * fit.scale), by.y / (fit.height * fit.scale))
            }
            val now = crop
            tools.history.edit(null) { r -> r.copy(geometry = r.geometry.copy(crop = now)) }
            change.consume()
        }
        tools.history.commit(R.string.editor_step_crop)
    }
}

/** The corners of [crop], clockwise from the top left. */
internal fun cornersOf(crop: Rect01) = listOf(
    crop.left to crop.top,
    crop.right to crop.top,
    crop.right to crop.bottom,
    crop.left to crop.bottom
)

private fun cropRatio(state: EditorState, tools: EditorTools): Float? =
    state.recipe.geometry.lockedRatio(tools.transform.frameSize())

/** [crop] moved by [dx], [dy] without leaving the picture. */
private fun slid(crop: Rect01, dx: Float, dy: Float): Rect01 {
    val left = (crop.left + dx).coerceIn(0f, 1f - crop.width)
    val top = (crop.top + dy).coerceIn(0f, 1f - crop.height)
    return Rect01(left, top, left + crop.width, top + crop.height)
}

/**
 * [crop] with corner [index] (clockwise from the top left) dragged [to] a point, the opposite
 * corner staying put, keeping [ratio] (width over height in pixels of [frame]) if set.
 */
private fun movedCorner(crop: Rect01, index: Int, to: Point01, ratio: Float?, frame: Size): Rect01 {
    val ax = if (index == TOP_LEFT || index == BOTTOM_LEFT) crop.right else crop.left
    val ay = if (index == TOP_LEFT || index == TOP_RIGHT) crop.bottom else crop.top
    var w = abs(to.x - ax).coerceAtLeast(MIN_CROP)
    var h = abs(to.y - ay).coerceAtLeast(MIN_CROP)
    if (ratio != null) {
        // Width over height in pixels, as fractions of this frame.
        val fractionRatio = ratio * frame.height / frame.width
        if (w / h > fractionRatio) w = h * fractionRatio else h = w / fractionRatio
    }
    val left = if (to.x < ax) ax - w else ax
    val top = if (to.y < ay) ay - h else ay
    return Rect01(
        left.coerceIn(0f, 1f - MIN_CROP),
        top.coerceIn(0f, 1f - MIN_CROP),
        (left + w).coerceIn(MIN_CROP, 1f),
        (top + h).coerceIn(MIN_CROP, 1f)
    )
}

// --- Perspective corners -----------------------------------------------------------------

private suspend fun PointerInputScope.cornerGestures(g: Touch, slop: Float) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        val fit = g.fit
        val corners = g.state.recipe.geometry.corners ?: return@awaitEachGesture
        val points = (0 until QUAD).map { fit.fraction(corners[it * 2], corners[it * 2 + 1]) }
        val grabbed = points.indexOfFirst { (it - down.position).getDistance() < slop * 2 }
        if (grabbed < 0) return@awaitEachGesture
        drag(down.id) { change ->
            val to = fit.toFraction(change.position)
            val moved = (g.state.recipe.geometry.corners ?: corners).toMutableList()
            moved[grabbed * 2] = to.x
            moved[grabbed * 2 + 1] = to.y
            tools.history.edit(null) { r ->
                r.copy(geometry = r.geometry.copy(corners = moved.toList()))
            }
            change.consume()
        }
        tools.history.commit(R.string.editor_step_perspective)
    }
}

// --- Strokes: drawing, mask brush, eraser -----------------------------------------------

private suspend fun PointerInputScope.strokeGestures(g: Touch, line: MutableList<Offset>) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        val gesture = g.state.gesture
        val points = mutableListOf<Float>()
        fun add(change: PointerInputChange) {
            line += change.position
            if (gesture == Gesture.DRAW) {
                // A drawing sits on the cropped picture, and keeps the stylus' pressure.
                val p = g.toContent(change.position)
                points += listOf(p.x, p.y, change.pressure)
            } else {
                val p = g.toSource(change.position)
                points += listOf(p.x, p.y)
            }
        }
        line.clear()
        add(down)
        drag(down.id) { change ->
            add(change)
            change.consume()
        }
        line.clear()
        when (gesture) {
            Gesture.DRAW -> tools.draw.addStroke(points)
            Gesture.BRUSH_MASK -> tools.masks.addMaskStroke(points)
            Gesture.ERASE -> tools.eraser.addEraseStroke(points)
            else -> Unit
        }
    }
}

// --- Mask handles ----------------------------------------------------------------------

private suspend fun PointerInputScope.handleGestures(g: Touch, slop: Float) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        val state = g.state
        val mask = state.recipe.masks.firstOrNull { it.id == state.selectedMask }
            ?: return@awaitEachGesture
        val handles = listOfNotNull(mask.start, mask.end, mask.center)
            .map { g.sourceToScreen(it.x, it.y) }
        val grabbed = handles.indexOfFirst { (it - down.position).getDistance() < slop * 2 }
        if (grabbed < 0) return@awaitEachGesture
        drag(down.id) { change ->
            val p = g.toSource(change.position)
            tools.masks.updateMask(mask.id, null) { m ->
                when {
                    m.center != null -> m.copy(center = p)
                    grabbed == 0 -> m.copy(start = p)
                    else -> m.copy(end = p)
                }
            }
            change.consume()
        }
        tools.history.commit(R.string.editor_step_mask)
    }
}

// --- Redaction boxes ---------------------------------------------------------------------

/** A drag draws a box to hide; a tap on a box takes it away (Undo brings it back). */
private suspend fun PointerInputScope.boxGestures(g: Touch, show: (Rect?) -> Unit) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        var last = down.position
        var moved = false
        drag(down.id) { change ->
            last = change.position
            if ((last - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (moved) show(Rect(down.position, last))
            change.consume()
        }
        show(null)
        val a = g.toSource(down.position)
        val b = g.toSource(last)
        val rect = Rect01(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        val tapped = g.state.recipe.redactions.indexOfLast { r ->
            a.x in r.rect.left..r.rect.right && a.y in r.rect.top..r.rect.bottom
        }
        when {
            !moved -> if (tapped >= 0) tools.redact.removeRedaction(tapped)
            rect.width > MIN_BOX && rect.height > MIN_BOX -> tools.redact.addRedaction(rect)
        }
    }
}

// --- Layers ------------------------------------------------------------------------------

private suspend fun PointerInputScope.layerGestures(g: Touch) {
    val tools = g.tools
    awaitEachGesture {
        val down = awaitFirstDown()
        // A touch on a layer picks it; a drag, pinch or twist then moves, sizes or turns it.
        val c = g.toContent(down.position)
        val hit = g.state.recipe.layers.lastOrNull { layer ->
            val box = tools.layers.layerBox(layer.id) ?: return@lastOrNull false
            abs(c.x - layer.x) <= box.width / 2 && abs(c.y - layer.y) <= box.height / 2
        }
        if (hit != null) tools.layers.selectLayer(hit.id)
        var moved = false
        var changed = false
        do {
            val event = awaitPointerEvent()
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            val rotation = event.calculateRotation()
            val far = pan.getDistance() > viewConfiguration.touchSlop
            if (far || zoom != 1f || rotation != 0f) moved = true
            val id = g.state.selectedLayer
            if (moved && id != null) {
                val geo = g.geo
                val fit = g.fit
                val dx = pan.x / (geo.content.width * fit.scale)
                val dy = pan.y / (geo.content.height * fit.scale)
                tools.layers.updateLayer(id, null) { layer ->
                    Layers.transformed(layer, dx, dy, zoom, rotation)
                }
                changed = true
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (!moved && hit == null) tools.layers.selectLayer(null)
        if (changed) tools.history.commit(R.string.editor_step_move)
    }
}

/** Moving, sizing and turning a layer with two fingers. */
internal object Layers {
    fun transformed(layer: Layer, dx: Float, dy: Float, zoom: Float, rotation: Float): Layer {
        val moved = layer.moved(layer.x + dx, layer.y + dy, layer.rotation + rotation)
        if (zoom == 1f) return moved
        return when (moved) {
            is TextLayer -> moved.copy(size = (moved.size * zoom).coerceIn(MIN_SIZE, MAX_TEXT))

            is StickerLayer -> moved.copy(
                size = (moved.size * zoom).coerceIn(MIN_SIZE, MAX_STICKER)
            )

            is ShapeLayer -> moved.copy(
                width = (moved.width * zoom).coerceIn(MIN_SIZE, MAX_STICKER),
                height = (moved.height * zoom).coerceIn(MIN_SIZE, MAX_STICKER)
            )
        }
    }

    private const val MIN_SIZE = 0.01f
    private const val MAX_TEXT = 0.6f
    private const val MAX_STICKER = 2f
}

private const val HANDLE_SLOP = 16
internal const val QUAD = 4

// A crop's corners, clockwise from the top left.
private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_RIGHT = 2
private const val BOTTOM_LEFT = 3
private const val MIN_CROP = 0.05f
private const val MIN_BOX = 0.01f
