package com.photoxt.core.render

import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.SplitAxis
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A rectangle in canvas pixels. */
data class PixelRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom

    fun inset(l: Double, t: Double = l, r: Double = l, b: Double = t) =
        PixelRect(left + l, top + t, right - r, bottom - b)
}

/** Where cell [cell] is drawn: [rect] in canvas pixels, turned [rotation] degrees on its centre. */
data class CellPlace(val cell: Int, val rect: PixelRect, val rotation: Double = 0.0)

/**
 * A divider between children [index] and [index] + 1 of the split at [path] (child indices from
 * the root): a line at [position] across [axis] (an x for side-by-side children, a y for stacked
 * ones), from [from] to [to] along the other axis, with the two children spanning [start] to [end]
 * and [gap] pixels between them.
 */
data class Divider(
    val path: List<Int>,
    val index: Int,
    val axis: SplitAxis,
    val position: Double,
    val from: Double,
    val to: Double,
    val start: Double,
    val end: Double,
    val gap: Double = 0.0
)

/**
 * A collage laid out on a canvas of [width] × [height] pixels (spec §4.11): the content inside
 * the frame and margin, each cell's place, and the dividers between cells. [aspect] gives the
 * upright aspect (width / height) of cell i's photo, which freeform cells take as their shape.
 * Everything the studio's gestures and both renderers place comes from here.
 */
class CollageGeometry(
    val project: CollageProject,
    val width: Int,
    val height: Int,
    private val aspect: (Int) -> Double
) {
    val shortEdge: Double = min(width, height).toDouble()
    val canvas = PixelRect(0.0, 0.0, width.toDouble(), height.toDouble())

    /** The canvas inside the frame's band. */
    val framed: PixelRect = frameInset()

    /** Where the cells go: inside the frame and the margin. */
    val content: PixelRect = framed.inset(project.canvas.margin * shortEdge)

    private val gap: Double = project.canvas.spacing * shortEdge

    /** The cells in drawing order: a freeform collage's last cell is on top. */
    val places: List<CellPlace>

    val dividers: List<Divider>

    init {
        if (project.mode == CollageMode.FREEFORM) {
            places = project.cells.indices.map(::freePlace)
            dividers = emptyList()
        } else {
            val rects = HashMap<Int, PixelRect>()
            val lines = ArrayList<Divider>()
            // A stitch's cells are its photos' own shapes, whatever its stored weights say, so a
            // swap or a new photo never cuts one; and it has no divider to drag.
            val stitch = project.mode == CollageMode.STITCH
            val layout = if (stitch) {
                Stitch.layout(
                    project.cells.indices.map {
                        aspect(it).toFloat()
                    },
                    project.horizontal
                )
            } else {
                project.layout
            }
            lay(layout, content, emptyList(), rects, lines)
            places = rects.entries.sortedBy { it.key }.map { CellPlace(it.key, it.value) }
            dividers = if (stitch) emptyList() else lines
        }
    }

    fun place(cell: Int): CellPlace? = places.firstOrNull { it.cell == cell }

    /** The cell under [x], [y], the topmost one for freeform; null in a gap. */
    fun cellAt(x: Double, y: Double): Int? = places.lastOrNull { place ->
        val (lx, ly) = toLocal(place, x, y)
        abs(lx) <= place.rect.width / 2 && abs(ly) <= place.rect.height / 2
    }?.cell

    /** [x], [y] relative to [place]'s centre, along its own (turned) axes. */
    fun toLocal(place: CellPlace, x: Double, y: Double): Pair<Double, Double> {
        val dx = x - place.rect.centerX
        val dy = y - place.rect.centerY
        val t = Math.toRadians(-place.rotation)
        return dx * cos(t) - dy * sin(t) to dx * sin(t) + dy * cos(t)
    }

    /** The divider nearest [x], [y] within [slop] pixels, or null. */
    fun dividerAt(x: Double, y: Double, slop: Double): Divider? = dividers.filter { d ->
        val along = if (d.axis == SplitAxis.ROW) y else x
        val across = if (d.axis == SplitAxis.ROW) x else y
        along in d.from..d.to && abs(across - d.position) <= slop
    }.minByOrNull { d -> abs((if (d.axis == SplitAxis.ROW) x else y) - d.position) }

    // --- The photo in its cell ----------------------------------------------------------------

    /** How one cell shows its photo: where the photo's upright fractions land on the canvas. */
    inner class PhotoFrame(val place: CellPlace, val state: CollageCell, val photoAspect: Double) {
        private val inner: Double = if (project.mode ==
            CollageMode.FREEFORM
        ) {
            0.0
        } else {
            state.rotation.toDouble()
        }
        private val turn = Math.toRadians(place.rotation + inner)

        /** The cell's extents along the photo's own axes. */
        private val spanW: Double
        private val spanH: Double

        /** The photo's size on the canvas, in pixels. */
        val shownWidth: Double
        val shownHeight: Double

        init {
            val w = place.rect.width
            val h = place.rect.height
            val t = Math.toRadians(inner)
            spanW = w * abs(cos(t)) + h * abs(sin(t))
            spanH = w * abs(sin(t)) + h * abs(cos(t))
            shownWidth = max(spanW, spanH * photoAspect) * max(1.0, state.zoom.toDouble())
            shownHeight = shownWidth / photoAspect
        }

        /** The focus kept where the photo still covers the whole cell. */
        val focusX: Double = clampFocus(state.focusX.toDouble(), spanW / (2 * shownWidth))
        val focusY: Double = clampFocus(state.focusY.toDouble(), spanH / (2 * shownHeight))

        /** Upright photo fractions → canvas pixels (flips included). */
        val map: Mat3
            get() {
                val flip = Mat3.affine(
                    if (state.flipH) -1.0 else 1.0,
                    0.0,
                    if (state.flipH) 1.0 else 0.0,
                    0.0,
                    if (state.flipV) -1.0 else 1.0,
                    if (state.flipV) 1.0 else 0.0
                )
                val c = cos(turn)
                val s = sin(turn)
                return Mat3.translate(place.rect.centerX, place.rect.centerY) *
                    Mat3.affine(c, -s, 0.0, s, c, 0.0) *
                    Mat3.scale(shownWidth, shownHeight) *
                    Mat3.translate(-focusX, -focusY) *
                    flip
            }

        /** The part of the upright photo the cell shows, as fractions. */
        fun visible(): Box {
            val back = map.inverse()
            val (cx, cy) = place.rect.centerX to place.rect.centerY
            val hw = place.rect.width / 2
            val hh = place.rect.height / 2
            val t = Math.toRadians(place.rotation)
            val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (dx, dy) ->
                back.map(cx + dx * cos(t) - dy * sin(t), cy + dx * sin(t) + dy * cos(t))
            }
            return Box(
                corners.minOf { it.first }.coerceIn(0.0, 1.0),
                corners.minOf { it.second }.coerceIn(0.0, 1.0),
                corners.maxOf { it.first }.coerceIn(0.0, 1.0),
                corners.maxOf { it.second }.coerceIn(0.0, 1.0)
            )
        }

        /** Canvas pixels per pixel of a photo [photoWidth] pixels wide (upright). */
        fun density(photoWidth: Int): Double = shownWidth / photoWidth

        /** [state] with its focus moved by a drag of [dx], [dy] canvas pixels, kept covering. */
        fun panned(dx: Double, dy: Double): CollageCell {
            val c = cos(-turn)
            val s = sin(-turn)
            val du = (dx * c - dy * s) / shownWidth
            val dv = (dx * s + dy * c) / shownHeight
            return state.copy(
                focusX = clampFocus(focusX - du, spanW / (2 * shownWidth)).toFloat(),
                focusY = clampFocus(focusY - dv, spanH / (2 * shownHeight)).toFloat()
            )
        }

        private fun clampFocus(value: Double, half: Double): Double =
            if (half >= HALF) HALF else value.coerceIn(half, 1 - half)
    }

    fun photoFrame(cell: Int): PhotoFrame? {
        val place = place(cell) ?: return null
        val state = project.cells.getOrNull(cell) ?: return null
        return PhotoFrame(place, state, aspect(cell))
    }

    // --- Layout ---------------------------------------------------------------------------------

    private fun frameInset(): PixelRect {
        val frame = project.frame
        if (frame.style == FrameStyle.NONE) return canvas
        val band = frame.width * shortEdge
        val bottom = if (frame.style == FrameStyle.POLAROID) band * POLAROID_BOTTOM else band
        return canvas.inset(band, band, band, bottom)
    }

    private fun lay(
        node: LayoutNode,
        rect: PixelRect,
        path: List<Int>,
        out: MutableMap<Int, PixelRect>,
        lines: MutableList<Divider>
    ) {
        node.cell?.let {
            out[it] = rect
            return
        }
        val row = node.axis == SplitAxis.ROW
        val length = if (row) rect.width else rect.height
        val free = max(0.0, length - gap * (node.children.size - 1))
        val total = node.weights.sum().toDouble()
        var at = if (row) rect.left else rect.top
        val parts = node.children.indices.map { i ->
            val size = free * node.weights[i] / total
            val part = if (row) {
                PixelRect(at, rect.top, at + size, rect.bottom)
            } else {
                PixelRect(rect.left, at, rect.right, at + size)
            }
            at += size + gap
            part
        }
        parts.forEachIndexed { i, part -> lay(node.children[i], part, path + i, out, lines) }
        for (i in 0 until parts.lastIndex) {
            val a = parts[i]
            val b = parts[i + 1]
            lines += Divider(
                path = path,
                index = i,
                axis = node.axis,
                position = (if (row) a.right else a.bottom) + gap / 2,
                from = if (row) rect.top else rect.left,
                to = if (row) rect.bottom else rect.right,
                start = if (row) a.left else a.top,
                end = if (row) b.right else b.bottom,
                gap = gap
            )
        }
    }

    private fun freePlace(cell: Int): CellPlace {
        val state = project.cells[cell]
        val free =
            state.free
                ?: com.photoxt.core.model.FreePlacement(
                    HALF.toFloat(),
                    HALF.toFloat(),
                    HALF.toFloat()
                )
        val w = free.width * content.width
        val h = w / aspect(cell)
        val cx = content.left + free.x * content.width
        val cy = content.top + free.y * content.height
        return CellPlace(
            cell,
            PixelRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
            free.rotation.toDouble()
        )
    }

    companion object {
        private const val HALF = 0.5
        const val POLAROID_BOTTOM = 4.0
    }
}

/** Layout changes the studio's gestures make (spec §4.11: "drag dividers to resize cells"). */
object LayoutEdits {
    /**
     * [layout] with divider [divider] moved to [position] (canvas pixels): the two children on
     * either side share their weight anew, neither under [MIN_SHARE] of what the two span.
     */
    fun dragDivider(layout: LayoutNode, divider: Divider, position: Double): LayoutNode {
        val span = divider.end - divider.start - divider.gap
        if (span <= 0) return layout
        val t = ((position - divider.gap / 2 - divider.start) / span)
            .coerceIn(MIN_SHARE, 1 - MIN_SHARE).toFloat()
        return update(layout, divider.path) { node ->
            val i = divider.index
            val pair = node.weights[i] + node.weights[i + 1]
            node.copy(
                weights = node.weights.toMutableList().also {
                    it[i] = pair * t
                    it[i + 1] = pair * (1 - t)
                }
            )
        }
    }

    /**
     * Where a divider dragged to [position] settles: on an even split of its two children, or in
     * line with another divider of the same direction, when within [slop] pixels. Returns the
     * position and whether it snapped, so the studio can draw the guide.
     */
    fun snapDivider(
        dividers: List<Divider>,
        divider: Divider,
        position: Double,
        slop: Double
    ): Pair<Double, Boolean> {
        val half = (divider.start + divider.end) / 2
        val targets = listOf(half) + dividers.filter {
            it.axis == divider.axis && (it.path != divider.path || it.index != divider.index)
        }.map { it.position }
        val near = targets.minByOrNull { abs(it - position) } ?: return position to false
        return if (abs(near - position) <= slop) near to true else position to false
    }

    /** [layout] with cells [a] and [b]'s places swapped; their photos travel, not the layout. */
    fun swapCells(cells: List<CollageCell>, a: Int, b: Int): List<CollageCell> {
        if (a == b || a !in cells.indices || b !in cells.indices) return cells
        return cells.toMutableList().also {
            val first = it[a]
            it[a] = it[b].reframed().copy(free = first.free, look = it[b].look)
            it[b] = first.reframed().copy(free = it[b].free, look = first.look)
        }
    }

    private fun update(
        node: LayoutNode,
        path: List<Int>,
        change: (LayoutNode) -> LayoutNode
    ): LayoutNode {
        if (path.isEmpty()) return change(node)
        val i = path.first()
        return node.copy(
            children = node.children.toMutableList().also {
                it[i] = update(it[i], path.drop(1), change)
            }
        )
    }

    private const val MIN_SHARE = 0.12
}

/** The canvas size of a collage (spec §4.11, Canvas and Export). */
object CollageSizing {
    /**
     * The pixel size [project] is drawn at for [output]: a ratio's long edge at
     * [CollageOutput.longEdge]; a print size or custom pixels as they are (or scaled to that
     * long edge); a stitch from its photos, each at the narrowest one's own size, never
     * enlarged. [photoSize] gives cell i's photo's upright size, when known.
     */
    fun size(project: CollageProject, output: CollageOutput, photoSize: (Int) -> Size?): Size {
        val natural = natural(project, photoSize)
        val long = output.longEdge ?: return capped(natural, project)
        val scale = long.toDouble() / natural.longEdge
        return capped(
            Size(
                max(1, (natural.width * scale).roundToInt()),
                max(
                    1,
                    (
                        natural.height *
                            scale
                        ).roundToInt()
                )
            ),
            project
        )
    }

    /** The canvas at [maxEdge] pixels on its long edge, for previews. */
    fun preview(project: CollageProject, maxEdge: Int, photoSize: (Int) -> Size?): Size {
        val natural = natural(project, photoSize)
        val scale = min(1.0, maxEdge.toDouble() / natural.longEdge)
        return Size(
            max(1, (natural.width * scale).roundToInt()),
            max(1, (natural.height * scale).roundToInt())
        )
    }

    /** The width over height a canvas of [project] has. */
    fun aspect(project: CollageProject, photoSize: (Int) -> Size?): Double {
        val n = natural(project, photoSize)
        return n.width.toDouble() / n.height
    }

    private fun natural(project: CollageProject, photoSize: (Int) -> Size?): Size {
        val canvas = project.canvas
        return when (canvas.aspect) {
            CanvasAspect.CUSTOM -> Size(
                canvas.customWidth.coerceIn(MIN_CUSTOM, MAX_CUSTOM),
                canvas.customHeight.coerceIn(MIN_CUSTOM, MAX_CUSTOM)
            )

            CanvasAspect.A4, CanvasAspect.LETTER -> Size(canvas.aspect.width, canvas.aspect.height)

            CanvasAspect.AUTO -> stitched(project, photoSize)

            else -> {
                val a = canvas.aspect
                if (a.width >= a.height) {
                    Size(RATIO_EDGE, (RATIO_EDGE.toDouble() * a.height / a.width).roundToInt())
                } else {
                    Size((RATIO_EDGE.toDouble() * a.width / a.height).roundToInt(), RATIO_EDGE)
                }
            }
        }
    }

    /**
     * A stitch at the narrowest photo's own size: its photos side by side at one height, or
     * stacked at one width, with the gaps and margins the canvas asks for.
     */
    private fun stitched(project: CollageProject, photoSize: (Int) -> Size?): Size {
        val sizes = project.cells.indices.map { photoSize(it) ?: Size(DEFAULT_SIDE, DEFAULT_SIDE) }
        if (sizes.isEmpty()) return Size(DEFAULT_SIDE, DEFAULT_SIDE)
        val horizontal = project.horizontal
        val across = if (horizontal) sizes.minOf { it.height } else sizes.minOf { it.width }
        val aspects = sizes.map { it.width.toDouble() / it.height }
        val c = project.canvas
        val band =
            project.frame.width.toDouble().takeIf { project.frame.style != FrameStyle.NONE } ?: 0.0
        // In units of the short edge (the strip's width): margins, gaps and the photos' run.
        val inner = 1 - 2 * (c.margin + band)
        val run = aspects.sumOf { if (horizontal) it * inner else inner / it }
        val long = run + 2 * (c.margin + band) + c.spacing * (sizes.size - 1) +
            if (project.frame.style ==
                FrameStyle.POLAROID
            ) {
                band * (CollageGeometry.POLAROID_BOTTOM - 1)
            } else {
                0.0
            }
        val length = (across * long).roundToInt()
        return if (horizontal) Size(length, across) else Size(across, length)
    }

    /** No more than the memory a whole canvas may take; a stitch can't pass [MAX_LONG] either. */
    private fun capped(size: Size, project: CollageProject): Size {
        val longCap = if (project.mode ==
            CollageMode.STITCH
        ) {
            MAX_LONG
        } else {
            CollageOutput.LONG_EDGE_8K
        }
        val byEdge = min(1.0, longCap.toDouble() / size.longEdge)
        val byPixels = min(1.0, sqrt(MAX_PIXELS.toDouble() / size.pixels))
        val scale = min(byEdge, byPixels)
        if (scale >= 1.0) return size
        return Size(max(1, (size.width * scale).toInt()), max(1, (size.height * scale).toInt()))
    }

    /** A ratio's natural long edge: 4K. */
    private const val RATIO_EDGE = CollageOutput.LONG_EDGE_4K
    private const val MIN_CUSTOM = 64
    private const val MAX_CUSTOM = CollageOutput.LONG_EDGE_8K
    private const val DEFAULT_SIDE = 1000
    private const val MAX_LONG = 65_000
    private const val MAX_PIXELS = 7680L * 4320
}
