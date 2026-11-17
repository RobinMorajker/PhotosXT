package com.photoxt.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as UiSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.photoxt.core.model.LocalMask
import com.photoxt.core.model.Rect01
import com.photoxt.core.render.RenderGeometry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * What the editor draws over the photo: the handles and outlines of the tool shown, and the
 * line or box of a gesture under way.
 */

internal fun DrawScope.drawTool(
    geo: RenderGeometry,
    fit: Fit,
    state: EditorState,
    tools: EditorTools,
    live: LiveMarks
) {
    val turned = state.recipe.geometry
    when (state.gesture) {
        // A finer grid helps to level a horizon.
        Gesture.CROP -> drawCrop(fit, turned.crop, fineGrid = turned.straighten != 0f)

        Gesture.CORNERS -> drawCorners(fit, turned.corners)

        Gesture.LINEAR, Gesture.RADIAL -> drawMaskHandles(geo, fit, state)

        Gesture.REDACT_BOX -> drawRedactions(geo, fit, state, live.box)

        Gesture.ERASE -> drawPendingErase(geo, fit, state)

        Gesture.LAYER -> drawSelectedLayer(geo, fit, state, tools)

        else -> Unit
    }
    drawLiveLine(live.line)
}

/** The line a finger is drawing, before it becomes a stroke of the recipe. */
private fun DrawScope.drawLiveLine(points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val style = DrawStroke(width = 6f, cap = StrokeCap.Round)
    drawPath(path, Color.White.copy(alpha = 0.8f), style = style)
}

/** The crop: what falls outside dimmed, its outline, a grid, and a handle at each corner. */
private fun DrawScope.drawCrop(fit: Fit, crop: Rect01, fineGrid: Boolean) {
    val tl = fit.fraction(crop.left, crop.top)
    val br = fit.fraction(crop.right, crop.bottom)
    val right = fit.left + fit.width * fit.scale
    val bottom = fit.top + fit.height * fit.scale
    val dim = Color.Black.copy(alpha = 0.55f)
    drawRect(dim, Offset(fit.left, fit.top), UiSize(right - fit.left, tl.y - fit.top))
    drawRect(dim, Offset(fit.left, br.y), UiSize(right - fit.left, bottom - br.y))
    drawRect(dim, Offset(fit.left, tl.y), UiSize(tl.x - fit.left, br.y - tl.y))
    drawRect(dim, Offset(br.x, tl.y), UiSize(right - br.x, br.y - tl.y))
    drawRect(Color.White, tl, UiSize(br.x - tl.x, br.y - tl.y), style = DrawStroke(width = 3f))
    val lines = if (fineGrid) FINE_GRID else THIRDS
    val faint = Color.White.copy(alpha = 0.45f)
    for (i in 1 until lines) {
        val x = tl.x + (br.x - tl.x) * i / lines
        val y = tl.y + (br.y - tl.y) * i / lines
        drawLine(faint, Offset(x, tl.y), Offset(x, br.y), strokeWidth = 1.5f)
        drawLine(faint, Offset(tl.x, y), Offset(br.x, y), strokeWidth = 1.5f)
    }
    for (corner in listOf(tl, Offset(br.x, tl.y), br, Offset(tl.x, br.y))) {
        drawCircle(Color.White, radius = HANDLE_RADIUS, center = corner)
    }
}

/** The four corners dragged onto something flat in the photo, and the shape they make. */
private fun DrawScope.drawCorners(fit: Fit, corners: List<Float>?) {
    val c = corners ?: return
    val points = (0 until QUAD).map { fit.fraction(c[it * 2], c[it * 2 + 1]) }
    drawPath(closed(points), Color.White, style = DrawStroke(width = 3f))
    points.forEach { drawCircle(Color.White, radius = HANDLE_RADIUS, center = it) }
}

private fun closed(points: List<Offset>) = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** What is painted for the object eraser but not yet erased. */
private fun DrawScope.drawPendingErase(geo: RenderGeometry, fit: Fit, state: EditorState) {
    val short = min(geo.raster.width, geo.raster.height)
    for (stroke in state.pendingErase) {
        val radius = (stroke.size * short * geo.scale * fit.scale).toFloat()
        // Points come in pairs: x, then y.
        stroke.points.chunked(2).filter { it.size == 2 }.forEach { (x, y) ->
            val centre = sourceToScreen(geo, fit, x, y)
            drawCircle(ERASE_TINT, radius = max(radius, MIN_ERASE_DOT), center = centre)
        }
    }
}

/** A linear mask's two ends, or a radial mask's centre and reach. */
private fun DrawScope.drawMaskHandles(geo: RenderGeometry, fit: Fit, state: EditorState) {
    val mask = state.recipe.masks.firstOrNull { it.id == state.selectedMask } ?: return
    val start = mask.start
    val end = mask.end
    if (start != null && end != null) {
        val a = sourceToScreen(geo, fit, start.x, start.y)
        val b = sourceToScreen(geo, fit, end.x, end.y)
        drawLine(Color.White, a, b, strokeWidth = 2f, pathEffect = LONG_DASH)
        // The mask is full at the solid end and gone at the hollow one.
        drawCircle(Color.White, HANDLE_RADIUS, a)
        drawCircle(Color.White, HANDLE_RADIUS, b, style = DrawStroke(width = 4f))
    }
    if (mask.center != null) drawRadial(geo, fit, mask)
}

private fun DrawScope.drawRadial(geo: RenderGeometry, fit: Fit, mask: LocalMask) {
    val c = mask.center ?: return
    val centre = sourceToScreen(geo, fit, c.x, c.y)
    // The radii are fractions of the photo's short edge.
    val short = min(geo.raster.width, geo.raster.height).toFloat()
    val edgeX = sourceToScreen(geo, fit, c.x + mask.radiusX * short / geo.raster.width, c.y)
    val edgeY = sourceToScreen(geo, fit, c.x, c.y + mask.radiusY * short / geo.raster.height)
    val rx = hypot(edgeX.x - centre.x, edgeX.y - centre.y)
    val ry = hypot(edgeY.x - centre.x, edgeY.y - centre.y)
    rotate(mask.angle, centre) {
        val corner = Offset(centre.x - rx, centre.y - ry)
        drawOval(Color.White, corner, UiSize(rx * 2, ry * 2), style = DrawStroke(width = 2f))
    }
    drawCircle(Color.White, HANDLE_RADIUS, centre)
}

/** The boxes that hide something, outlined, and the one being dragged out. */
private fun DrawScope.drawRedactions(
    geo: RenderGeometry,
    fit: Fit,
    state: EditorState,
    dragging: Rect?
) {
    val dashed = DrawStroke(width = 2f, pathEffect = SHORT_DASH)
    for (redaction in state.recipe.redactions) {
        val corners = cornersOf(redaction.rect).map { (x, y) -> sourceToScreen(geo, fit, x, y) }
        drawPath(closed(corners), Color.White, style = dashed)
    }
    dragging?.let { drawRect(Color.White, it.topLeft, it.size, style = DrawStroke(width = 3f)) }
}

/** A box around the text, sticker or shape picked. */
private fun DrawScope.drawSelectedLayer(
    geo: RenderGeometry,
    fit: Fit,
    state: EditorState,
    tools: EditorTools
) {
    val layer = state.recipe.layers.firstOrNull { it.id == state.selectedLayer } ?: return
    val box = tools.layers.layerBox(layer.id) ?: return
    val centre = contentToScreen(geo, fit, layer.x, layer.y)
    val w = box.width * geo.content.width * fit.scale
    val h = box.height * geo.content.height * fit.scale
    rotate(layer.rotation, centre) {
        val corner = Offset(centre.x - w / 2, centre.y - h / 2)
        drawRect(Color.White, corner, UiSize(w, h), style = DrawStroke(width = 2f))
    }
}

private const val HANDLE_RADIUS = 14f
private const val THIRDS = 3
private const val FINE_GRID = 8
private const val MIN_ERASE_DOT = 6f
private val ERASE_TINT = Color(0x66FF3B30)
private val LONG_DASH = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
private val SHORT_DASH = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
