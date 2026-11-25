package com.photoxt.feature.collage

import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CollageCanvas
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.Frame
import com.photoxt.core.model.FreePlacement
import com.photoxt.core.model.Layer
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.ShapeOutline
import com.photoxt.core.model.SplitAxis
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.CollageSizing
import com.photoxt.core.render.CollageTemplate
import com.photoxt.core.render.CollageTemplates
import com.photoxt.core.render.LayoutEdits
import com.photoxt.core.render.Scatter
import com.photoxt.core.render.SmartLayout
import com.photoxt.core.render.Stitch
import kotlinx.coroutines.launch

/** How a new collage starts (spec §4.11): the first grid template for its photos, on a square. */
object Starts {
    fun grid(mediaIds: List<String>): CollageProject {
        val template = CollageTemplates.grid(mediaIds.size).first()
        return CollageProject(
            layout = template.layout,
            template = template.id,
            cells = mediaIds.map { CollageCell(mediaId = it) }
        )
    }
}

/** The words Before and After, in the user's language, for the labels of that mode. */
data class BeforeAfterLabels(val before: String, val after: String)

/**
 * The arrangement (spec §4.11, Modes and Canvas): the mode and its layout, the template, the
 * canvas and the frame. Each change is one step of the history unless a finger is still moving.
 */
class CollageEdits(
    private val core: CollageCore,
    private val faces: suspend (List<String>) -> Map<String, List<Rect01>>,
    private val labels: BeforeAfterLabels
) {
    /** The cells and the layers over them. */
    val cells = CellEdits(core)

    private val project: CollageProject get() = core.project
    private val ui: CollageUi get() = core.state.value

    fun setMode(mode: CollageMode) {
        if (mode == project.mode) return
        val n = project.cells.size
        val base = project.copy(
            mode = mode,
            template = null,
            cells = project.cells.map { it.reframed().copy(free = null) },
            layers = project.layers.filterNot { it.id in LABEL_IDS },
            canvas = project.canvas.ratioUnlessAuto()
        )
        when (mode) {
            CollageMode.GRID -> pick(base, CollageTemplates.grid(n).first())

            CollageMode.STORY -> pick(
                base.copy(canvas = base.canvas.copy(aspect = CanvasAspect.NINE_SIXTEEN)),
                CollageTemplates.story(n).first()
            )

            CollageMode.SHAPES -> core.edit(R.string.collage_step_mode) {
                base.copy(layout = CollageTemplates.plain(n))
            }

            CollageMode.STITCH -> core.edit(R.string.collage_step_mode) {
                stitched(base.copy(canvas = base.canvas.copy(aspect = CanvasAspect.AUTO)))
            }

            CollageMode.FREEFORM -> core.edit(R.string.collage_step_mode) { scattered(base) }

            CollageMode.SMART -> {
                core.edit(R.string.collage_step_mode) {
                    base.copy(layout = CollageTemplates.plain(n))
                }
                fitSmart()
            }

            CollageMode.BEFORE_AFTER -> core.edit(R.string.collage_step_mode) {
                beforeAfter(base, SplitAxis.ROW)
            }
        }
    }

    /** A template from the strip (spec §4.11, Grid templates and Story/Status). */
    fun pickTemplate(template: CollageTemplate) = pick(project, template)

    /** Smart layout again, from the photos' shapes and faces (spec §4.11, Smart layout). */
    fun fitSmart() {
        core.update { it.copy(busy = R.string.collage_finding_faces) }
        core.scope.launch {
            try {
                val p = project
                val found = faces(p.cells.mapNotNull { it.mediaId })
                val aspects = p.cells.indices.map { ui.aspect(it, p).toFloat() }
                val shapes = p.cells.map { found[it.mediaId].orEmpty() }
                val content = CollageSizing.aspect(p) { ui.size(it, p) }.toFloat()
                val (layout, focus) = SmartLayout.build(aspects, shapes, content)
                core.edit(R.string.collage_step_smart) { now ->
                    now.copy(
                        mode = CollageMode.SMART,
                        layout = layout,
                        template = null,
                        cells = now.cells.mapIndexed { i, cell ->
                            cell.reframed().copy(focusX = focus[i].first, focusY = focus[i].second)
                        }
                    )
                }
            } finally {
                core.update { it.copy(busy = null) }
            }
        }
    }

    /** Freeform: the photos laid out loosely again. */
    fun scatter() = core.edit(R.string.collage_step_scatter) { scattered(it) }

    fun setStitchHorizontal(horizontal: Boolean) = core.edit(R.string.collage_step_mode) {
        stitched(it.copy(horizontal = horizontal))
    }

    fun setShape(shape: ShapeOutline) = core.edit(R.string.collage_step_shape) {
        it.copy(shape = shape)
    }

    fun setLetters(letters: String) = core.edit(R.string.collage_step_shape) {
        it.copy(letters = letters.take(MAX_LETTERS))
    }

    fun setBeforeAfter(axis: SplitAxis) = core.edit(R.string.collage_step_mode) {
        beforeAfter(it, axis)
    }

    /** The canvas's shape (spec §4.11, aspect presets); a story leaves 9:16 as a grid. */
    fun setAspect(aspect: CanvasAspect, width: Int? = null, height: Int? = null) {
        core.edit(R.string.collage_step_canvas) { p ->
            val canvas = p.canvas.copy(
                aspect = aspect,
                customWidth = width ?: p.canvas.customWidth,
                customHeight = height ?: p.canvas.customHeight
            )
            val mode = if (p.mode == CollageMode.STORY && aspect != CanvasAspect.NINE_SIXTEEN) {
                CollageMode.GRID
            } else {
                p.mode
            }
            p.copy(canvas = canvas, mode = mode)
        }
        if (project.mode == CollageMode.SMART) fitSmart()
    }

    /** Spacing, margin, corners, borders and background (spec §4.11, Canvas). */
    fun canvas(label: Int?, change: (CollageCanvas) -> CollageCanvas) =
        core.edit(label) { it.copy(canvas = change(it.canvas)) }

    /** The layout after a divider moved (spec §4.11, "drag dividers to resize cells"). */
    fun layout(label: Int?, layout: LayoutNode) = core.edit(label) { it.copy(layout = layout) }

    fun frame(label: Int?, change: (Frame) -> Frame) = core.edit(label) {
        it.copy(frame = change(it.frame))
    }

    // --- Builders ---------------------------------------------------------------------------

    private fun pick(base: CollageProject, template: CollageTemplate) {
        core.edit(R.string.collage_step_template) {
            base.copy(
                layout = template.layout,
                template = template.id,
                mode = if (base.mode == CollageMode.STORY) CollageMode.STORY else CollageMode.GRID,
                cells = base.cells.map { it.reframed().copy(free = null) }
            )
        }
    }

    private fun scattered(p: CollageProject): CollageProject {
        val aspects = p.cells.indices.map { ui.aspect(it, p).toFloat() }
        val content = CollageSizing.aspect(p) { ui.size(it, p) }.toFloat()
        val places = Scatter.places(aspects, content)
        val canvas = if (p.canvas.borderWidth ==
            0f
        ) {
            p.canvas.copy(borderWidth = SCRAPBOOK_BORDER)
        } else {
            p.canvas
        }
        return p.copy(
            mode = CollageMode.FREEFORM,
            canvas = canvas,
            cells = p.cells.mapIndexed { i, cell -> cell.copy(free = places[i]) }
        )
    }

    /** A stitch of [p]'s photos; the geometry keeps each cell its photo's shape from then on. */
    private fun stitched(p: CollageProject): CollageProject {
        val aspects = p.cells.indices.map { ui.aspect(it, p).toFloat() }
        return p.copy(
            mode = CollageMode.STITCH,
            layout = Stitch.layout(aspects, p.horizontal),
            cells = p.cells.map { it.reframed() }
        )
    }

    private fun beforeAfter(p: CollageProject, axis: SplitAxis): CollageProject {
        val side = axis == SplitAxis.ROW
        fun label(id: String, text: String, x: Float, y: Float) = TextLayer(
            id = id,
            text = text,
            x = x,
            y = y,
            size = LABEL_SIZE,
            bold = true,
            background = LABEL_BACKGROUND
        )
        val labelsNow = listOf(
            label(BEFORE, labels.before, if (side) QUARTER else HALF, if (side) LOW else MID_LOW),
            label(AFTER, labels.after, if (side) THREE_QUARTERS else HALF, LOW)
        )
        return p.copy(
            mode = CollageMode.BEFORE_AFTER,
            layout = LayoutNode.split(axis, listOf(LayoutNode(cell = 0), LayoutNode(cell = 1))),
            template = null,
            layers = p.layers.filterNot { it.id in LABEL_IDS } + labelsNow
        )
    }

    private fun CollageCanvas.ratioUnlessAuto(): CollageCanvas =
        if (aspect == CanvasAspect.AUTO) copy(aspect = CanvasAspect.SQUARE) else this

    private companion object {
        const val BEFORE = "before"
        const val AFTER = "after"
        val LABEL_IDS = setOf(BEFORE, AFTER)
        const val LABEL_SIZE = 0.05f
        const val LABEL_BACKGROUND = -0x67000000
        const val QUARTER = 0.25f
        const val HALF = 0.5f
        const val THREE_QUARTERS = 0.75f
        const val LOW = 0.92f
        const val MID_LOW = 0.42f
        const val MAX_LETTERS = 6
        const val SCRAPBOOK_BORDER = 0.012f
    }
}

/** The cells (spec §4.11, Cells) and the text and stickers over them (Extras). */
class CellEdits internal constructor(private val core: CollageCore) {
    /** Changes cell [index]; with a [label], one step of the history. */
    fun cell(index: Int, label: Int?, change: (CollageCell) -> CollageCell) =
        core.edit(label) { p ->
            p.copy(cells = p.cells.mapIndexed { i, c -> if (i == index) change(c) else c })
        }

    /** Records what a gesture did, once the finger lifts. */
    fun commit(label: Int) = core.commit(label)

    fun flip(index: Int, horizontal: Boolean) = cell(index, R.string.collage_step_flip) {
        if (horizontal) it.copy(flipH = !it.flipH) else it.copy(flipV = !it.flipV)
    }

    /** A quarter turn: the photo inside its cell, or a freeform photo itself. */
    fun turn(index: Int) = cell(index, R.string.collage_step_turn) { c ->
        val free = c.free
        if (free != null) {
            c.copy(free = free.copy(rotation = normalized(free.rotation + QUARTER_TURN)))
        } else {
            c.copy(rotation = normalized(c.rotation + QUARTER_TURN))
        }
    }

    fun reset(index: Int) = cell(index, R.string.collage_step_reset) { c ->
        c.reframed().copy(
            flipH = false,
            flipV = false,
            look = null,
            free = c.free?.copy(rotation = 0f)
        )
    }

    /** Puts photo [mediaId] in cell [index], framed afresh (spec §4.11, "replace a photo"). */
    fun replacePhoto(index: Int, mediaId: String) = core.edit(R.string.collage_step_replace) { p ->
        p.copy(
            cells = p.cells.mapIndexed { i, c ->
                if (i ==
                    index
                ) {
                    c.copy(mediaId = mediaId).reframed()
                } else {
                    c
                }
            }
        )
    }

    /** Cells [a] and [b] swap photos (spec §4.11, "drag to swap"). */
    fun swap(a: Int, b: Int) = core.edit(R.string.collage_step_swap) { p ->
        p.copy(cells = LayoutEdits.swapCells(p.cells, a, b))
    }

    /** Freeform layer order (spec §4.11): one step toward the top ([delta] 1) or the bottom. */
    fun order(index: Int, delta: Int): Int {
        val to = (index + delta).coerceIn(0, core.project.cells.lastIndex)
        if (to == index) return index
        core.edit(R.string.collage_step_order) { p ->
            p.copy(cells = p.cells.toMutableList().apply { add(to, removeAt(index)) })
        }
        return to
    }

    /** Cell [index]'s look on every photo. */
    fun lookForAll(index: Int) = core.edit(R.string.collage_step_look) { p ->
        val look = p.cells.getOrNull(index)?.look
        p.copy(cells = p.cells.map { it.copy(look = look) })
    }

    /** New text in the middle (spec §4.11, Extras); returns its id. */
    fun addText(text: String): String {
        val id = newId("t")
        core.edit(R.string.collage_step_text) {
            it.copy(
                layers =
                    it.layers + TextLayer(id = id, text = text, size = TEXT_SIZE, shadow = true)
            )
        }
        return id
    }

    fun addSticker(emoji: String): String {
        val id = newId("s")
        core.edit(R.string.collage_step_sticker) {
            it.copy(layers = it.layers + StickerLayer(id = id, emoji = emoji, size = STICKER_SIZE))
        }
        return id
    }

    fun layer(id: String, label: Int?, change: (Layer) -> Layer) = core.edit(label) { p ->
        p.copy(layers = p.layers.map { if (it.id == id) change(it) else it })
    }

    fun deleteLayer(id: String) = core.edit(R.string.collage_step_delete) { p ->
        p.copy(layers = p.layers.filterNot { it.id == id })
    }

    /** A layer one step up ([delta] 1, drawn later) or down the order. */
    fun moveLayer(id: String, delta: Int) = core.edit(R.string.collage_step_order) { p ->
        val from = p.layers.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, p.layers.lastIndex)
        if (from < 0 ||
            from == to
        ) {
            p
        } else {
            p.copy(layers = p.layers.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    private fun normalized(degrees: Float): Float {
        var d = degrees % FULL_TURN
        if (d > HALF_TURN) d -= FULL_TURN
        if (d <= -HALF_TURN) d += FULL_TURN
        return d
    }

    private fun newId(prefix: String) = prefix + System.nanoTime().toString(RADIX)

    private companion object {
        const val QUARTER_TURN = 90f
        const val HALF_TURN = 180f
        const val FULL_TURN = 360f
        const val TEXT_SIZE = 0.07f
        const val STICKER_SIZE = 0.18f
        const val RADIX = 36
    }
}
