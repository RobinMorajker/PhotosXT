package com.photoxt.feature.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Layer
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.SplitAxis
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.CollageGeometry
import com.photoxt.core.render.CollagePhotos
import com.photoxt.core.render.CollageRenderer
import com.photoxt.core.render.CollageSizing
import com.photoxt.core.render.Divider
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.LayoutEdits
import com.photoxt.core.render.OverlayPainter
import com.photoxt.core.render.PixelRect
import com.photoxt.core.render.toRectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a finger landed on. */
private sealed interface Target {
    data class OnLayer(val id: String) : Target

    data class OnDivider(val divider: Divider, val layout: LayoutNode) : Target

    data class OnCell(val index: Int) : Target

    data object Nothing : Target
}

/** The canvas on screen: where it sits in the view, the collage laid out on it, its layers. */
private class Placed(
    val geometry: CollageGeometry,
    val painter: OverlayPainter,
    val scale: Float,
    val left: Float,
    val top: Float,
    /** How near, in canvas pixels, a moving thing snaps. */
    val snap: Float
) {
    fun toCanvas(p: Offset) = Offset((p.x - left) / scale, (p.y - top) / scale)
}

/**
 * The collage as it will be saved (spec §4.11), drawn by the export's own renderer, fitted to
 * the view. A tap picks a photo or a text; dragging a photo pans it in its cell, pinching zooms
 * and twisting turns it; a long press then a drag swaps two photos; dividers drag to resize
 * cells; freeform photos and texts move, size and turn, snapping to the middle and to each other.
 */
@Composable
internal fun CollageCanvas(
    ui: CollageUi,
    photos: CollagePhotos,
    assets: EditAssets,
    actions: CanvasActions,
    modifier: Modifier = Modifier
) {
    val project = ui.project
    val size =
        remember(project, ui.photos) {
            CollageSizing.preview(project, PREVIEW_EDGE) { ui.size(it) }
        }
    val geometry = remember(project, size, ui.photos) {
        CollageGeometry(project, size.width, size.height) { ui.aspect(it) }
    }
    val renderer = remember(assets) { CollageRenderer(assets) }
    val painter =
        remember(project.layers, assets) {
            OverlayPainter(EditRecipe(layers = project.layers), assets)
        }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scale = min(w / size.width, h / size.height)
        val snap = with(LocalDensity.current) { SNAP_DP.dp.toPx() } / scale
        val placed = Placed(
            geometry,
            painter,
            scale,
            (w - size.width * scale) / 2,
            (h - size.height * scale) / 2,
            snap
        )
        CanvasView(ui, placed, renderer, photos, actions)
        Accessible(ui, placed, painter, actions)
    }
}

/** The collage drawn where [placed] says, the picked thing outlined, and the gestures on it. */
@Composable
private fun CanvasView(
    ui: CollageUi,
    placed: Placed,
    renderer: CollageRenderer,
    photos: CollagePhotos,
    actions: CanvasActions
) {
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    var ghost by remember { mutableStateOf<Ghost?>(null) }
    val now by rememberUpdatedState(placed)
    val uiNow by rememberUpdatedState(ui)
    val handles = ui.picked == null || ui.tool == CollageTool.LAYOUT
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val geometry = placed.geometry
    val scale = placed.scale
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(actions) {
                gestures({ now }, { uiNow }, actions, { guides = it }, { ghost = it })
            }
    ) {
        withTransform({
            translate(placed.left, placed.top)
            scale(scale, scale, Offset.Zero)
        }) {
            drawContext.canvas.nativeCanvas.let {
                renderer.draw(it, geometry, photos, opaque = false)
            }
            selection(ui, geometry, placed.painter, accent, scale)
            if (handles) dividerHandles(geometry, scale)
            guides.forEach {
                drawLine(accent, it.from, it.to, strokeWidth = GUIDE_DP.dp.toPx() / scale)
            }
            ghost?.let { swapGhost(it, geometry, accent, scale) }
        }
    }
}

/** A snapping guide, in canvas pixels. */
private data class Guide(val from: Offset, val to: Offset)

/** A photo being dragged to swap: from cell [from], now at [at] (canvas pixels). */
private data class Ghost(val from: Int, val at: Offset)

// --- Gestures --------------------------------------------------------------------------------

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures(
    placed: () -> Placed,
    ui: () -> CollageUi,
    actions: CanvasActions,
    onGuides: (List<Guide>) -> Unit,
    onGhost: (Ghost?) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = placed()
        val at = start.toCanvas(down.position)
        val target = targetAt(start, ui(), at, TOUCH_SLOP_DP.dp.toPx() / start.scale)
        val swappable = target is Target.OnCell && ui().mode != CollageMode.FREEFORM
        when (
            decide(
                viewConfiguration.touchSlop,
                viewConfiguration.longPressTimeoutMillis,
                swappable
            )
        ) {
            Decision.TAP -> tapped(target, ui(), actions)
            Decision.LONG -> swapDrag((target as Target.OnCell).index, placed, actions, onGhost)
            Decision.DRAG -> dragged(target, placed, ui, actions, onGuides)
        }
    }
}

private enum class Decision { TAP, LONG, DRAG }

/** A tap, a long press, or a drag: whichever the finger does first. */
private suspend fun AwaitPointerEventScope.decide(
    slop: Float,
    longPressMs: Long,
    canLong: Boolean
): Decision {
    var moved = Offset.Zero
    val decision = withTimeoutOrNull(longPressMs) {
        var result: Decision? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            moved += event.calculatePan()
            result = when {
                pressed.isEmpty() -> Decision.TAP
                pressed.size > 1 || moved.getDistance() > slop -> Decision.DRAG
                else -> null
            }
        }
        result
    }
    return decision ?: if (canLong) Decision.LONG else Decision.DRAG
}

private fun tapped(target: Target, ui: CollageUi, actions: CanvasActions) {
    when (target) {
        is Target.OnLayer -> {
            val layer = ui.project.layers.firstOrNull { it.id == target.id }
            // A second tap on a picked text opens it for editing.
            if (ui.pickedLayer == target.id && layer is TextLayer) {
                actions.editText(target.id)
            } else {
                actions.pick(Picked.Layer(target.id))
            }
        }

        is Target.OnCell -> actions.pick(Picked.Cell(target.index))

        is Target.OnDivider -> Unit

        Target.Nothing -> actions.pick(null)
    }
}

/** A long press, then the photo follows the finger: let go over another to swap them. */
private suspend fun AwaitPointerEventScope.swapDrag(
    from: Int,
    placed: () -> Placed,
    actions: CanvasActions,
    onGhost: (Ghost?) -> Unit
) {
    actions.pick(Picked.Cell(from))
    var last: Offset? = null
    do {
        val change = awaitPointerEvent().changes.firstOrNull()
        if (change != null) {
            val at = placed().toCanvas(change.position)
            last = at
            onGhost(Ghost(from, at))
            change.consume()
        }
    } while (change?.pressed == true)
    onGhost(null)
    val to = last?.let { placed().geometry.cellAt(it.x.toDouble(), it.y.toDouble()) }
    if (to != null && to != from) {
        actions.swap(from, to)
        actions.pick(Picked.Cell(to))
    }
}

private suspend fun AwaitPointerEventScope.dragged(
    target: Target,
    placed: () -> Placed,
    ui: () -> CollageUi,
    actions: CanvasActions,
    onGuides: (List<Guide>) -> Unit
) {
    when (target) {
        is Target.OnCell -> actions.pick(Picked.Cell(target.index))
        is Target.OnLayer -> actions.pick(Picked.Layer(target.id))
        else -> Unit
    }
    var along = 0.0
    var changed = false
    do {
        val event = awaitPointerEvent()
        val down = event.changes.any { it.pressed }
        val p = placed()
        val pan = event.calculatePan() / p.scale
        val zoom = event.calculateZoom()
        val turn = event.calculateRotation()
        if (down) event.changes.forEach { it.consume() }
        val moved = down && (pan != Offset.Zero || zoom != 1f || turn != 0f)
        if (moved) {
            changed = true
            when (target) {
                is Target.OnCell ->
                    moveCell(target.index, p, ui(), pan, zoom, turn, actions, onGuides)

                is Target.OnLayer ->
                    moveLayer(target.id, p, ui(), pan, zoom, turn, actions, onGuides)

                is Target.OnDivider -> {
                    along += target.divider.across(pan)
                    moveDivider(target, p, along, actions, onGuides)
                }

                Target.Nothing -> Unit
            }
        }
    } while (down)
    onGuides(emptyList())
    if (changed) {
        val label = when (target) {
            is Target.OnDivider -> R.string.collage_step_divider
            is Target.OnLayer -> R.string.collage_step_layer
            else -> R.string.collage_step_photo
        }
        actions.commit(label)
    }
}

/** A photo panned, zoomed and turned in its cell, or a freeform photo moved, sized and turned. */
@Suppress("LongParameterList") // One gesture's step: where, what moved, and who hears it.
private fun moveCell(
    index: Int,
    p: Placed,
    ui: CollageUi,
    pan: Offset,
    zoom: Float,
    turn: Float,
    actions: CanvasActions,
    onGuides: (List<Guide>) -> Unit
) {
    val cell = ui.project.cells.getOrNull(index) ?: return
    val free = cell.free
    if (ui.mode == CollageMode.FREEFORM && free != null) {
        val content = p.geometry.content
        var x = free.x + (pan.x / content.width).toFloat()
        var y = free.y + (pan.y / content.height).toFloat()
        val (sx, sy, lines) = snap(x, y, p, index)
        x = sx
        y = sy
        onGuides(lines)
        val width = (free.width * zoom).coerceIn(MIN_FREE, MAX_FREE)
        actions.cell(
            index,
            cell.copy(
                free = free.copy(
                    x = x,
                    y = y,
                    width = width,
                    rotation =
                        free.rotation + turn
                )
            )
        )
        return
    }
    val frame = p.geometry.photoFrame(index) ?: return
    val panned = frame.panned(pan.x.toDouble(), pan.y.toDouble())
    actions.cell(
        index,
        panned.copy(
            zoom = (panned.zoom * zoom).coerceIn(1f, MAX_ZOOM),
            rotation = panned.rotation + turn
        )
    )
}

/** A text or sticker moved, sized and turned; its centre snaps to the canvas's middle. */
@Suppress("LongParameterList") // One gesture's step: where, what moved, and who hears it.
private fun moveLayer(
    id: String,
    p: Placed,
    ui: CollageUi,
    pan: Offset,
    zoom: Float,
    turn: Float,
    actions: CanvasActions,
    onGuides: (List<Guide>) -> Unit
) {
    val layer = ui.project.layers.firstOrNull { it.id == id } ?: return
    val canvas = p.geometry.canvas
    var x = layer.x + (pan.x / canvas.width).toFloat()
    var y = layer.y + (pan.y / canvas.height).toFloat()
    val slopX = p.snap / canvas.width
    val slopY = p.snap / canvas.height
    val lines = ArrayList<Guide>()
    if (abs(x - HALF) < slopX) {
        x = HALF
        lines +=
            Guide(
                Offset(canvas.centerX.toFloat(), 0f),
                Offset(canvas.centerX.toFloat(), canvas.height.toFloat())
            )
    }
    if (abs(y - HALF) < slopY) {
        y = HALF
        lines +=
            Guide(
                Offset(0f, canvas.centerY.toFloat()),
                Offset(canvas.width.toFloat(), canvas.centerY.toFloat())
            )
    }
    onGuides(lines)
    actions.layer(id) { old ->
        val moved = old.moved(x, y, layer.rotation + turn)
        when (moved) {
            is TextLayer -> moved.copy(size = (moved.size * zoom).coerceIn(MIN_LAYER, MAX_LAYER))
            is StickerLayer -> moved.copy(size = (moved.size * zoom).coerceIn(MIN_LAYER, MAX_LAYER))
            is ShapeLayer -> moved.copy(width = moved.width * zoom, height = moved.height * zoom)
        }
    }
}

/** A divider dragged [along] pixels from where it was, snapping to even splits and its peers. */
private fun moveDivider(
    target: Target.OnDivider,
    p: Placed,
    along: Double,
    actions: CanvasActions,
    onGuides: (List<Guide>) -> Unit
) {
    val d = target.divider
    val wanted = d.position + along
    val (at, snapped) = LayoutEdits.snapDivider(p.geometry.dividers, d, wanted, p.snap.toDouble())
    actions.layout(LayoutEdits.dragDivider(target.layout, d, at))
    onGuides(
        if (!snapped) {
            emptyList()
        } else if (d.axis == SplitAxis.ROW) {
            listOf(
                Guide(Offset(at.toFloat(), d.from.toFloat()), Offset(at.toFloat(), d.to.toFloat()))
            )
        } else {
            listOf(
                Guide(Offset(d.from.toFloat(), at.toFloat()), Offset(d.to.toFloat(), at.toFloat()))
            )
        }
    )
}

/** A freeform photo's centre, snapped to the middle and to other photos' centres. */
private fun snap(x: Float, y: Float, p: Placed, index: Int): Triple<Float, Float, List<Guide>> {
    val content = p.geometry.content
    val slopX = p.snap / content.width
    val slopY = p.snap / content.height
    val others = p.geometry.project.cells.mapIndexedNotNull { i, c -> c.free.takeIf { i != index } }
    val xs = listOf(HALF) + others.map { it.x }
    val ys = listOf(HALF) + others.map { it.y }
    val nx = xs.minByOrNull { abs(it - x) }?.takeIf { abs(it - x) < slopX }
    val ny = ys.minByOrNull { abs(it - y) }?.takeIf { abs(it - y) < slopY }
    val lines = buildList {
        nx?.let {
            val gx = (content.left + it * content.width).toFloat()
            add(Guide(Offset(gx, content.top.toFloat()), Offset(gx, content.bottom.toFloat())))
        }
        ny?.let {
            val gy = (content.top + it * content.height).toFloat()
            add(Guide(Offset(content.left.toFloat(), gy), Offset(content.right.toFloat(), gy)))
        }
    }
    return Triple(nx ?: x, ny ?: y, lines)
}

/** What is under [at]: a text or sticker first, then a divider (not in freeform), then a photo. */
private fun targetAt(placed: Placed, ui: CollageUi, at: Offset, slop: Float): Target {
    val x = at.x.toDouble()
    val y = at.y.toDouble()
    val g = placed.geometry
    val layer = layerAt(ui.project, placed.painter, g, x, y)
    val divider = g.dividerAt(x, y, slop.toDouble()).takeIf { ui.mode != CollageMode.FREEFORM }
    val cell = g.cellAt(x, y)
    return when {
        layer != null -> Target.OnLayer(layer)
        divider != null -> Target.OnDivider(divider, ui.project.layout)
        cell != null -> Target.OnCell(cell)
        else -> Target.Nothing
    }
}

/** The topmost text or sticker under [x], [y], within its box turned with it. */
private fun layerAt(
    project: CollageProject,
    painter: OverlayPainter,
    g: CollageGeometry,
    x: Double,
    y: Double
): String? {
    val content = g.canvas.toRectF()
    return project.layers.lastOrNull { layer ->
        val box = painter.bounds(layer, content)
        val t = Math.toRadians(-layer.rotation.toDouble())
        val dx = x - box.centerX()
        val dy = y - box.centerY()
        val lx = dx * cos(t) - dy * sin(t)
        val ly = dx * sin(t) + dy * cos(t)
        abs(lx) <= box.width() / 2 && abs(ly) <= box.height() / 2
    }?.id
}

private operator fun Offset.div(scale: Float) = Offset(x / scale, y / scale)

/** How far [pan] moves this divider: across it, the way it can move. */
private fun Divider.across(pan: Offset): Float = if (axis == SplitAxis.ROW) pan.x else pan.y

// --- Drawing over the collage ------------------------------------------------------------------

private fun DrawScope.selection(
    ui: CollageUi,
    geometry: CollageGeometry,
    painter: OverlayPainter,
    accent: Color,
    scale: Float
) {
    val stroke = Stroke(SELECTION_DP.dp.toPx() / scale)
    ui.pickedCell?.let { index ->
        val place = geometry.place(index) ?: return@let
        val r = place.rect
        rotate(place.rotation.toFloat(), Offset(r.centerX.toFloat(), r.centerY.toFloat())) {
            drawRect(
                accent,
                Offset(r.left.toFloat(), r.top.toFloat()),
                androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat()),
                style = stroke
            )
        }
    }
    ui.pickedLayer?.let { id ->
        val layer = ui.project.layers.firstOrNull { it.id == id } ?: return@let
        val box = painter.bounds(layer, geometry.canvas.toRectF())
        rotate(layer.rotation, Offset(box.centerX(), box.centerY())) {
            drawRect(
                accent,
                Offset(box.left, box.top),
                androidx.compose.ui.geometry.Size(box.width(), box.height()),
                style = stroke
            )
        }
    }
}

/** A small handle in the middle of each divider, to show it can be dragged. */
private fun DrawScope.dividerHandles(geometry: CollageGeometry, scale: Float) {
    val long = HANDLE_LONG_DP.dp.toPx() / scale
    val short = HANDLE_SHORT_DP.dp.toPx() / scale
    geometry.dividers.forEach { d ->
        val mid = ((d.from + d.to) / 2).toFloat()
        val pos = d.position.toFloat()
        val (w, h) = if (d.axis == SplitAxis.ROW) short to long else long to short
        val center = if (d.axis == SplitAxis.ROW) Offset(pos, mid) else Offset(mid, pos)
        drawRoundRect(
            Color.White,
            center - Offset(w / 2, h / 2),
            androidx.compose.ui.geometry.Size(w, h),
            androidx.compose.ui.geometry.CornerRadius(short / 2, short / 2)
        )
        drawRoundRect(
            Color.Black.copy(alpha = HANDLE_RIM),
            center - Offset(w / 2, h / 2),
            androidx.compose.ui.geometry.Size(w, h),
            androidx.compose.ui.geometry.CornerRadius(short / 2, short / 2),
            style = Stroke(1.dp.toPx() / scale)
        )
    }
}

/** The cell a dragged photo would land in, outlined, and a dot under the finger. */
private fun DrawScope.swapGhost(
    ghost: Ghost,
    geometry: CollageGeometry,
    accent: Color,
    scale: Float
) {
    val over = geometry.cellAt(ghost.at.x.toDouble(), ghost.at.y.toDouble())
    over?.takeIf { it != ghost.from }?.let { geometry.place(it) }?.let { place ->
        val r = place.rect
        drawRect(
            accent.copy(alpha = GHOST_FILL),
            Offset(r.left.toFloat(), r.top.toFloat()),
            androidx.compose.ui.geometry.Size(r.width.toFloat(), r.height.toFloat())
        )
    }
    drawCircle(accent, GHOST_DP.dp.toPx() / scale, ghost.at)
}

// --- TalkBack ------------------------------------------------------------------------------

/**
 * Each photo, divider and text as a node TalkBack and switch access reach (spec §4.20): a tap
 * picks it; actions swap a photo with the next, or move a divider or a text a step.
 */
@Composable
private fun Accessible(
    ui: CollageUi,
    placed: Placed,
    painter: OverlayPainter,
    actions: CanvasActions
) {
    val density = LocalDensity.current
    val g = placed.geometry
    val n = g.places.size
    val swapNext = stringResource(R.string.collage_a11y_swap_next)
    g.places.forEachIndexed { order, place ->
        val description = stringResource(R.string.collage_a11y_photo, order + 1, n)
        Node(
            placed,
            place.rect.left,
            place.rect.top,
            place.rect.width,
            place.rect.height,
            density
        ) {
            contentDescription = description
            role = Role.Button
            selected = ui.pickedCell == place.cell
            onClick {
                actions.pick(Picked.Cell(place.cell))
                true
            }
            val next = g.places.getOrNull((order + 1) % n)?.cell
            if (next != null && next != place.cell && ui.mode != CollageMode.FREEFORM) {
                customActions = listOf(
                    CustomAccessibilityAction(swapNext) {
                        actions.swap(place.cell, next)
                        true
                    }
                )
            }
        }
    }
    DividerNodes(placed, actions, density)
    ui.project.layers.forEach { layer ->
        val box = painter.bounds(layer, g.canvas.toRectF())
        val name = (layer as? TextLayer)?.text ?: (layer as? StickerLayer)?.emoji ?: ""
        val description = stringResource(R.string.collage_a11y_layer, name)
        Node(
            placed,
            box.left.toDouble(),
            box.top.toDouble(),
            box.width().toDouble(),
            box.height().toDouble(),
            density
        ) {
            contentDescription = description
            role = Role.Button
            selected = ui.pickedLayer == layer.id
            onClick {
                actions.pick(Picked.Layer(layer.id))
                true
            }
        }
    }
}

@Composable
private fun DividerNodes(
    placed: Placed,
    actions: CanvasActions,
    density: androidx.compose.ui.unit.Density
) {
    val g = placed.geometry
    val back = stringResource(R.string.collage_a11y_divider_back)
    val forward = stringResource(R.string.collage_a11y_divider_forward)
    val name = stringResource(R.string.collage_a11y_divider)
    g.dividers.forEach { d ->
        val step = (d.end - d.start) * A11Y_STEP
        fun moved(by: Double) = actions.layout(
            LayoutEdits.dragDivider(
                g.project.layout,
                d,
                d.position + by
            )
        ).also {
            actions.commit(R.string.collage_step_divider)
        }
        val half = A11Y_WIDTH / 2
        val r = if (d.axis == SplitAxis.ROW) {
            PixelRect(d.position - half, d.from, d.position + half, d.to)
        } else {
            PixelRect(d.from, d.position - half, d.to, d.position + half)
        }
        Node(placed, r.left, r.top, r.width, r.height, density) {
            contentDescription = name
            customActions = listOf(
                CustomAccessibilityAction(back) {
                    moved(-step)
                    true
                },
                CustomAccessibilityAction(forward) {
                    moved(step)
                    true
                }
            )
        }
    }
}

/** An invisible node over canvas rectangle [x], [y], [w], [h], carrying [semantics]. */
@Suppress("LongParameterList") // A rectangle, where the canvas sits, and its semantics.
@Composable
private fun Node(
    placed: Placed,
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    density: androidx.compose.ui.unit.Density,
    semantics: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit
) {
    with(density) {
        val left = (placed.left + x * placed.scale).roundToInt()
        val top = (placed.top + y * placed.scale).roundToInt()
        Box(
            Modifier
                .offset { IntOffset(left, top) }
                .size((w * placed.scale).toFloat().toDp(), (h * placed.scale).toFloat().toDp())
                .semantics(properties = semantics)
        )
    }
}

/** The preview canvas's long edge in pixels; the view scales it to fit. */
internal const val PREVIEW_EDGE = 1600
private const val TOUCH_SLOP_DP = 14
private const val SNAP_DP = 24f
private const val GUIDE_DP = 1.5f
private const val SELECTION_DP = 3
private const val HANDLE_LONG_DP = 28
private const val HANDLE_SHORT_DP = 8
private const val HANDLE_RIM = 0.4f
private const val GHOST_DP = 14
private const val GHOST_FILL = 0.35f
private const val MAX_ZOOM = 8f
private const val MIN_FREE = 0.08f
private const val MAX_FREE = 1.5f
private const val MIN_LAYER = 0.015f
private const val MAX_LAYER = 1.5f
private const val HALF = 0.5f
private const val A11Y_STEP = 0.05
private const val A11Y_WIDTH = 48.0
