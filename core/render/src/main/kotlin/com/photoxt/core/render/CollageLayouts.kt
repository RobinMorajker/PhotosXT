package com.photoxt.core.render

import com.photoxt.core.model.COLLAGE_MAX_PHOTOS
import com.photoxt.core.model.COLLAGE_MIN_PHOTOS
import com.photoxt.core.model.FreePlacement
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.SplitAxis
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A grid template (spec §4.11): a layout for [count] photos, named by its shape. */
data class CollageTemplate(val id: String, val count: Int, val layout: LayoutNode)

/**
 * The template strip (spec §4.11, "100+ layouts for 2–20 photos, filtered by count"): grids of
 * even rows and columns, a big photo beside, above or between the others, two big photos over a
 * row, and spirals, made for each count and kept only where no cell gets too thin.
 */
object CollageTemplates {
    /** Templates for [count] photos on a square canvas, plainest first. */
    fun grid(count: Int): List<CollageTemplate> = cached.getOrPut(count to SQUARE) {
        made(count).filter { fits(it, SQUARE) }
    }

    /** Templates for [count] photos on a 9:16 story canvas (spec §4.11, Story/Status). */
    fun story(count: Int): List<CollageTemplate> = cached.getOrPut(count to STORY) {
        made(count).filter { fits(it, STORY) }
    }

    /** Before and after: side by side, or one over the other. */
    fun beforeAfter(): List<CollageTemplate> = listOf(SplitAxis.ROW, SplitAxis.COLUMN).map {
        named(LayoutNode.split(it, listOf(leaf(0), leaf(1))), 2)
    }

    /** The plainest grid for [count]: rows as even as they come, about as many as columns. */
    fun plain(count: Int): LayoutNode = rows(evenSizes(count, rowsFor(count)))

    /** Every grid template for 2–20 photos. */
    val all: List<CollageTemplate>
        get() = (COLLAGE_MIN_PHOTOS..COLLAGE_MAX_PHOTOS).flatMap(::grid)

    /** The cells of [layout] as fractions of the content, without gaps. */
    fun cellRects(layout: LayoutNode): Map<Int, Rect01> {
        val out = HashMap<Int, Rect01>()
        place(layout, Rect01.FULL, out)
        return out
    }

    /** A canonical name for [node]'s shape: equal shapes, equal names. */
    fun signature(node: LayoutNode): String {
        if (node.isLeaf) return "x"
        val axis = if (node.axis == SplitAxis.ROW) "r" else "c"
        val total = node.weights.sum()
        val parts = node.children.indices.joinToString(",") { i ->
            signature(node.children[i]) + ":" +
                String.format(Locale.ROOT, "%.2f", node.weights[i] / total)
        }
        return "$axis($parts)"
    }

    // --- Families ---------------------------------------------------------------------------

    private fun made(count: Int): List<CollageTemplate> {
        val shapes = buildList {
            for (rows in 1..min(count, MAX_LINES)) {
                for (sizes in rowSizes(count, rows)) {
                    add(rows(sizes))
                    add(columns(sizes))
                }
            }
            if (count >= 2) Side.entries.forEach { add(hero(count, it)) }
            if (count >= MIN_CENTRE) {
                add(centre(count, SplitAxis.COLUMN))
                add(centre(count, SplitAxis.ROW))
            }
            if (count >= MIN_TWO_BIG) {
                add(twoBig(count, SplitAxis.COLUMN))
                add(twoBig(count, SplitAxis.ROW))
            }
            if (count in SPIRAL_COUNTS) {
                add(spiral(count, SplitAxis.ROW))
                add(spiral(count, SplitAxis.COLUMN))
            }
        }
        return shapes.map { named(it, count) }.distinctBy { it.id }
    }

    /** [count] cells in rows of [sizes] cells each, top to bottom. */
    private fun rows(sizes: List<Int>): LayoutNode = numbered(lines(sizes, SplitAxis.COLUMN))

    /** The same as columns, left to right. */
    private fun columns(sizes: List<Int>): LayoutNode = numbered(lines(sizes, SplitAxis.ROW))

    private fun lines(sizes: List<Int>, across: SplitAxis): LayoutNode {
        val along = if (across == SplitAxis.COLUMN) SplitAxis.ROW else SplitAxis.COLUMN
        val lines = sizes.map { n ->
            if (n ==
                1
            ) {
                leaf(0)
            } else {
                LayoutNode.split(along, List(n) { leaf(0) })
            }
        }
        return if (lines.size == 1) lines.single() else LayoutNode.split(across, lines)
    }

    private enum class Side { START, TOP, END, BOTTOM }

    /** One big photo beside, above or below the rest, which sit in a strip of even lines. */
    private fun hero(count: Int, side: Side): LayoutNode {
        val rest = count - 1
        val vertical = side == Side.START || side == Side.END
        val lineCount = ceil(rest / STRIP_LINE.toFloat()).toInt()
        val sizes = evenSizes(rest, lineCount)
        // A vertical strip is columns of stacked cells; a horizontal one, rows side by side.
        val strip = lines(sizes, if (vertical) SplitAxis.ROW else SplitAxis.COLUMN)
        val axis = if (vertical) SplitAxis.ROW else SplitAxis.COLUMN
        val stripWeight = if (lineCount == 1) 1f else HERO_TWO_LINES
        val node = if (side == Side.START || side == Side.TOP) {
            LayoutNode.split(axis, listOf(leaf(0), strip), listOf(HERO_WEIGHT, stripWeight))
        } else {
            LayoutNode.split(axis, listOf(strip, leaf(0)), listOf(stripWeight, HERO_WEIGHT))
        }
        return numberedHeroFirst(node)
    }

    /** One big photo between two lines of the rest. */
    private fun centre(count: Int, across: SplitAxis): LayoutNode {
        val rest = count - 1
        val first = rest / 2
        val along = if (across == SplitAxis.COLUMN) SplitAxis.ROW else SplitAxis.COLUMN
        fun line(n: Int) = if (n == 1) leaf(0) else LayoutNode.split(along, List(n) { leaf(0) })
        val node = LayoutNode.split(
            across,
            listOf(line(first), leaf(0), line(rest - first)),
            listOf(1f, HERO_WEIGHT, 1f)
        )
        return numberedHeroAt(node, 1)
    }

    /** Two big photos over (or beside) a line or two of the rest. */
    private fun twoBig(count: Int, across: SplitAxis): LayoutNode {
        val along = if (across == SplitAxis.COLUMN) SplitAxis.ROW else SplitAxis.COLUMN
        val rest = count - 2
        val lineCount = ceil(rest / STRIP_LINE.toFloat()).toInt()
        val bottom = lines(evenSizes(rest, lineCount), across)
        val big = LayoutNode.split(along, listOf(leaf(0), leaf(0)))
        return numbered(
            LayoutNode.split(across, listOf(big, bottom), listOf(HERO_WEIGHT, lineCount.toFloat()))
        )
    }

    /** Each photo takes half of what is left, turning a quarter each time. */
    private fun spiral(count: Int, first: SplitAxis): LayoutNode {
        fun node(i: Int, axis: SplitAxis): LayoutNode = if (i == count - 1) {
            leaf(0)
        } else {
            val next = if (axis == SplitAxis.ROW) SplitAxis.COLUMN else SplitAxis.ROW
            LayoutNode.split(axis, listOf(leaf(0), node(i + 1, next)))
        }
        return numbered(node(0, first))
    }

    // --- Helpers ------------------------------------------------------------------------------

    /** Ways to put [count] cells in [rows] lines that differ by at most one cell. */
    private fun rowSizes(count: Int, rows: Int): List<List<Int>> {
        val even = evenSizes(count, rows)
        if (even.max() > MAX_LINE) return emptyList()
        // The bigger lines last, and first: both read well.
        return listOf(even, even.reversed()).distinct()
    }

    /** [count] cells in [lines] lines as evenly as they go, smaller lines first. */
    fun evenSizes(count: Int, lines: Int): List<Int> {
        val base = count / lines
        val extra = count % lines
        return List(lines) { i -> if (i >= lines - extra) base + 1 else base }
    }

    private fun rowsFor(count: Int): Int = max(1, sqrt(count.toDouble()).roundToInt())

    private fun leaf(cell: Int) = LayoutNode(cell = cell)

    /** [node] with its leaves numbered 0, 1, 2… in reading order. */
    private fun numbered(node: LayoutNode): LayoutNode {
        var next = 0
        fun walk(n: LayoutNode): LayoutNode =
            if (n.isLeaf) n.copy(cell = next++) else n.copy(children = n.children.map(::walk))
        return walk(node)
    }

    /** Numbered with the big cell, the split's first or last child, as cell 0. */
    private fun numberedHeroFirst(node: LayoutNode): LayoutNode {
        val heroIndex = node.children.indexOfFirst { it.isLeaf }
        return numberedHeroAt(node, heroIndex)
    }

    private fun numberedHeroAt(node: LayoutNode, heroIndex: Int): LayoutNode {
        var next = 1
        fun walk(n: LayoutNode): LayoutNode =
            if (n.isLeaf) n.copy(cell = next++) else n.copy(children = n.children.map(::walk))
        return node.copy(
            children = node.children.mapIndexed { i, child ->
                if (i == heroIndex) child.copy(cell = 0) else walk(child)
            }
        )
    }

    private fun named(layout: LayoutNode, count: Int): CollageTemplate {
        check(layout.leaves().sorted() == (0 until count).toList()) { "Cells must be 0 to n-1" }
        return CollageTemplate(signature(layout), count, layout)
    }

    /** Whether no cell is thinner than [MAX_CELL_RATIO] on a canvas of [aspect]. */
    private fun fits(template: CollageTemplate, aspect: Float): Boolean =
        cellRects(template.layout).values.all { rect ->
            val ratio = rect.width * aspect / rect.height
            ratio in (1 / MAX_CELL_RATIO)..MAX_CELL_RATIO
        }

    private fun place(node: LayoutNode, rect: Rect01, out: MutableMap<Int, Rect01>) {
        val cell = node.cell
        if (cell != null) {
            out[cell] = rect
            return
        }
        val total = node.weights.sum()
        var start = 0f
        node.children.forEachIndexed { i, child ->
            val share = node.weights[i] / total
            val part = if (node.axis == SplitAxis.ROW) {
                val left = rect.left + rect.width * start
                Rect01(left, rect.top, left + rect.width * share, rect.bottom)
            } else {
                val top = rect.top + rect.height * start
                Rect01(rect.left, top, rect.right, top + rect.height * share)
            }
            place(child, part, out)
            start += share
        }
    }

    private val cached = HashMap<Pair<Int, Float>, List<CollageTemplate>>()
    private const val SQUARE = 1f
    private const val STORY = 9f / 16f
    private const val MAX_LINES = 6
    private const val MIN_CENTRE = 3
    private const val MIN_TWO_BIG = 4
    private const val MAX_LINE = 6
    private const val STRIP_LINE = 4
    private const val HERO_WEIGHT = 2f
    private const val HERO_TWO_LINES = 1.4f
    private const val MAX_CELL_RATIO = 3.2f
    private val SPIRAL_COUNTS = 3..6
}

/**
 * Smart layout (spec §4.11): the photos, in order, in rows (or columns) that each take the
 * content's width, every photo in a row at the row's height, so a cell has its photo's own shape.
 * Of the ways to break the photos into lines, the one whose lines stack closest to the content's
 * height wins; lines of very different heights, and faces the cells would cut, count against a
 * way. Each cell then centres on its photo's faces.
 */
object SmartLayout {
    /** The layout and each photo's focus, from [aspects] (width / height) and [faces]. */
    fun build(
        aspects: List<Float>,
        faces: List<List<Rect01>>,
        contentAspect: Float
    ): Pair<LayoutNode, List<Pair<Float, Float>>> {
        require(aspects.isNotEmpty())
        val rows = best(aspects, faces, contentAspect, transposed = false)
        val columns = best(
            aspects.map { 1 / it },
            faces.map { it.map(::transpose) },
            1 / contentAspect,
            transposed = true
        )
        val pick = if (rows.score <= columns.score) rows else columns
        val node = treeOf(pick, aspects)
        val focus = aspects.indices.map { i ->
            focusOf(faces.getOrNull(i).orEmpty(), pick.windows[i])
        }
        return node to focus
    }

    /** One way to break the photos into lines, and how well it does. */
    private class Way(
        val groups: List<List<Int>>,
        val transposed: Boolean,
        val score: Double,
        /** How much of each photo shows: width and height fractions. */
        val windows: List<Pair<Float, Float>>
    )

    private fun best(
        aspects: List<Float>,
        faces: List<List<Rect01>>,
        contentAspect: Float,
        transposed: Boolean
    ): Way = (1..aspects.size).map { lines ->
        val groups = LinearPartition.split(aspects, lines)
        scored(groups, aspects, faces, contentAspect, transposed)
    }.minBy { it.score }

    private fun scored(
        groups: List<List<Int>>,
        aspects: List<Float>,
        faces: List<List<Rect01>>,
        contentAspect: Float,
        transposed: Boolean
    ): Way {
        // At width 1, a line of photos side by side is 1 / (sum of their aspects) tall.
        val heights = groups.map { group -> 1.0 / group.sumOf { aspects[it].toDouble() } }
        val natural = heights.sum()
        val wanted = 1.0 / contentAspect
        // Every cell's aspect is its photo's times natural / wanted.
        val stretch = natural / wanted
        val fit = abs(ln(stretch))
        val balance = ln(heights.max() / heights.min())
        val windows = aspects.indices.map {
            if (stretch >= 1) 1f to (1 / stretch).toFloat() else stretch.toFloat() to 1f
        }
        val cut = aspects.indices.sumOf { i ->
            val box = union(faces.getOrNull(i).orEmpty()) ?: return@sumOf 0.0
            val (w, h) = windows[i]
            max(0.0, (box.width - w).toDouble()) + max(0.0, (box.height - h).toDouble())
        }
        val score = fit + BALANCE_WEIGHT * balance + FACE_WEIGHT * cut
        return Way(
            groups,
            transposed,
            score,
            windows.map {
                if (transposed) it.second to it.first else it
            }
        )
    }

    private fun treeOf(way: Way, aspects: List<Float>): LayoutNode {
        val across = if (way.transposed) SplitAxis.ROW else SplitAxis.COLUMN
        val along = if (way.transposed) SplitAxis.COLUMN else SplitAxis.ROW

        // In a column the photos share by height: 1 / aspect each.
        fun share(i: Int) = if (way.transposed) 1 / aspects[i] else aspects[i]
        val lines = way.groups.map { group ->
            if (group.size == 1) {
                LayoutNode(cell = group.single())
            } else {
                LayoutNode.split(along, group.map { LayoutNode(cell = it) }, group.map(::share))
            }
        }
        if (lines.size == 1) return lines.single()
        val weights = way.groups.map { group ->
            1f / group.sumOf { share(it).toDouble() }.toFloat()
        }
        return LayoutNode.split(across, lines, weights)
    }

    /** Where a cell showing [window] of a photo centres, to keep [faces] in it. */
    fun focusOf(faces: List<Rect01>, window: Pair<Float, Float>): Pair<Float, Float> {
        val box = union(faces) ?: return CENTRE to CENTRE
        val (w, h) = window
        val x = ((box.left + box.right) / 2).coerceIn(w / 2, 1 - w / 2)
        val y = ((box.top + box.bottom) / 2).coerceIn(h / 2, 1 - h / 2)
        return x to y
    }

    private fun union(boxes: List<Rect01>): Rect01? = boxes.reduceOrNull { a, b ->
        Rect01(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
    }

    private fun transpose(r: Rect01) = Rect01(r.top, r.left, r.bottom, r.right)

    private const val CENTRE = 0.5f
    private const val BALANCE_WEIGHT = 0.25
    private const val FACE_WEIGHT = 4.0
}

/** The linear partition problem: a sequence cut into [k] runs whose largest sum is smallest. */
internal object LinearPartition {
    fun split(values: List<Float>, k: Int): List<List<Int>> {
        val n = values.size
        val parts = min(k, n)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        // cost[j][i]: the best largest sum of the first i values in j runs; cut[j][i]: last cut.
        val cost = Array(parts + 1) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        val cut = Array(parts + 1) { IntArray(n + 1) }
        cost[0][0] = 0.0
        for (j in 1..parts) {
            for (i in j..n) {
                fun largest(c: Int) = max(cost[j - 1][c], prefix[i] - prefix[c])
                val c = ((j - 1) until i).minBy(::largest)
                cost[j][i] = largest(c)
                cut[j][i] = c
            }
        }
        val runs = ArrayDeque<List<Int>>()
        var end = n
        for (j in parts downTo 1) {
            val start = cut[j][end]
            runs.addFirst((start until end).toList())
            end = start
        }
        return runs.toList()
    }
}

/** Stitch (spec §4.11): every photo whole, side by side or stacked, each its own shape. */
object Stitch {
    fun layout(aspects: List<Float>, horizontal: Boolean): LayoutNode {
        val axis = if (horizontal) SplitAxis.ROW else SplitAxis.COLUMN
        val leaves = aspects.indices.map { LayoutNode(cell = it) }
        if (leaves.size == 1) return leaves.single()
        return LayoutNode.split(axis, leaves, aspects.map { if (horizontal) it else 1 / it })
    }
}

/**
 * Freeform (spec §4.11, scrapbook): the photos laid out loosely on a grid, each a little turned,
 * for the user to move, size and turn from there.
 */
object Scatter {
    fun places(aspects: List<Float>, contentAspect: Float): List<FreePlacement> {
        val n = aspects.size
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n / cols.toDouble()).toInt()
        return aspects.mapIndexed { i, aspect ->
            val col = i % cols
            val row = i / cols
            var width = FILL / cols
            // Its height, as a fraction of the content's, must fit its row too.
            val height = width * contentAspect / aspect
            if (height > FILL / rows) width *= (FILL / rows) / height
            val turn = ((i * TURN_STEP) % TURN_SPREAD) - TURN_SPREAD / 2
            FreePlacement(
                x = (col + 0.5f) / cols,
                y = (row + 0.5f) / rows,
                width = width,
                rotation = turn.toFloat()
            )
        }
    }

    private const val FILL = 0.9f
    private const val TURN_STEP = 7
    private const val TURN_SPREAD = 13
}
