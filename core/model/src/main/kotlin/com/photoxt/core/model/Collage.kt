package com.photoxt.core.model

import kotlinx.serialization.Serializable

/** The collage format this build writes; older drafts read with missing fields at defaults. */
const val COLLAGE_VERSION = 1

/** How many photos a collage takes (spec §4.11: 2–20). */
const val COLLAGE_MIN_PHOTOS = 2
const val COLLAGE_MAX_PHOTOS = 20

/**
 * A collage (spec §4.11), saved as a draft's JSON and drawn by one renderer for the studio's
 * preview and for the export. It refers to its photos by media id; nothing is copied.
 *
 * Positions are fractions of the canvas. Sizes (spacing, margins, radii, borders, text) are
 * fractions of the canvas's short edge, so a collage means the same at any output size.
 */
@Serializable
data class CollageProject(
    val version: Int = COLLAGE_VERSION,
    val mode: CollageMode = CollageMode.GRID,
    val canvas: CollageCanvas = CollageCanvas(),
    /** Where the cells sit, for every mode but freeform: a slicing tree over the content. */
    val layout: LayoutNode = LayoutNode(cell = 0),
    /** The template [layout] started from, so the template strip shows it picked. */
    val template: String? = null,
    val cells: List<CollageCell> = emptyList(),
    /** Shapes mode: the outline the photos fill, and the letters for [ShapeOutline.LETTERS]. */
    val shape: ShapeOutline = ShapeOutline.HEART,
    val letters: String = "",
    /** Stitch: side by side when true, else stacked. */
    val horizontal: Boolean = false,
    /** Text, stickers and shapes, over everything; positions are fractions of the canvas. */
    val layers: List<Layer> = emptyList(),
    val frame: Frame = Frame()
) {
    /** The cells the collage shows: every one in freeform, else those its layout places. */
    val shownCells: List<Int>
        get() = if (mode == CollageMode.FREEFORM) cells.indices.toList() else layout.leaves()

    /**
     * The photos the collage shows, each once: what its export reads, and what *delete
     * sources* bins. A photo kept in the draft but not shown (before and after takes two) isn't.
     */
    val mediaIds: List<String>
        get() = shownCells.mapNotNull { cells.getOrNull(it)?.mediaId }.distinct()

    /** Every photo the draft holds, shown or not. */
    val allMediaIds: List<String> get() = cells.mapNotNull { it.mediaId }.distinct()
}

/** The studio's modes (spec §4.11). */
@Serializable
enum class CollageMode {
    /** A template from the strip. */
    GRID,

    /** Rows or columns fitted to the photos' shapes and faces. */
    SMART,

    /** Scrapbook: each photo placed, turned and sized freely, in a layer order. */
    FREEFORM,

    /** One long picture, every photo whole, stacked or side by side. */
    STITCH,

    /** The photos fill a heart, circle, hexagon or letters. */
    SHAPES,

    /** 9:16 templates for stories and status updates. */
    STORY,

    /** Two photos, labelled. */
    BEFORE_AFTER
}

/**
 * A node of the layout: a leaf shows cell [cell]; a split lays its [children] side by side
 * ([SplitAxis.ROW]) or stacked ([SplitAxis.COLUMN]), each taking its share of [weights].
 * Dragging a divider moves weight between the two children on either side of it.
 */
@Serializable
data class LayoutNode(
    val cell: Int? = null,
    val axis: SplitAxis = SplitAxis.ROW,
    val children: List<LayoutNode> = emptyList(),
    val weights: List<Float> = emptyList()
) {
    val isLeaf: Boolean get() = cell != null

    /** The cells this node shows, in reading order. */
    fun leaves(): List<Int> = cell?.let(::listOf) ?: children.flatMap { it.leaves() }

    companion object {
        /** A split of [children] in equal shares. */
        fun split(axis: SplitAxis, children: List<LayoutNode>, weights: List<Float>? = null) =
            LayoutNode(
                axis = axis,
                children = children,
                weights = weights ?: List(children.size) { 1f }
            )
    }
}

@Serializable
enum class SplitAxis { ROW, COLUMN }

/**
 * One photo's place in the collage (spec §4.11, Cells). The photo covers its cell; [focusX] and
 * [focusY] are the point of the upright, flipped photo (fractions) at the cell's centre, [zoom]
 * how far past covering it is enlarged, [rotation] how far it is turned inside the cell
 * (degrees, clockwise). [look] is a filter and adjustments, drawn by the editor's pipeline.
 * In freeform mode [free] places the cell itself, which then shows the whole photo.
 */
@Serializable
data class CollageCell(
    val mediaId: String? = null,
    val focusX: Float = CENTRE,
    val focusY: Float = CENTRE,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val look: EditRecipe? = null,
    val free: FreePlacement? = null
) {
    /** The same photo, framed as a new cell shows it: centred, uncut, unturned. */
    fun reframed(): CollageCell = copy(focusX = CENTRE, focusY = CENTRE, zoom = 1f, rotation = 0f)

    private companion object {
        const val CENTRE = 0.5f
    }
}

/**
 * A freeform cell's place: its centre ([x], [y], fractions of the content), its [width] (a
 * fraction of the content's width; its height follows the photo) and its turn in degrees.
 */
@Serializable
data class FreePlacement(val x: Float, val y: Float, val width: Float, val rotation: Float = 0f)

/** The canvas (spec §4.11, Canvas). */
@Serializable
data class CollageCanvas(
    val aspect: CanvasAspect = CanvasAspect.SQUARE,
    /** Pixels for [CanvasAspect.CUSTOM]. */
    val customWidth: Int = DEFAULT_CUSTOM,
    val customHeight: Int = DEFAULT_CUSTOM,
    val spacing: Float = DEFAULT_GAP,
    val margin: Float = DEFAULT_GAP,
    val cornerRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Int = WHITE,
    val background: CanvasBackground = CanvasBackground()
) {
    companion object {
        const val DEFAULT_CUSTOM = 3000
        const val DEFAULT_GAP = 0.012f
    }
}

/**
 * The canvas's shape: a ratio, a print size in pixels at 300 DPI (A4, Letter), the user's own
 * pixels ([CUSTOM]), or [AUTO], which a stitch takes from its photos.
 */
@Serializable
enum class CanvasAspect(val width: Int, val height: Int) {
    SQUARE(1, 1),
    FOUR_FIVE(4, 5),
    THREE_FOUR(3, 4),
    NINE_SIXTEEN(9, 16),
    SIXTEEN_NINE(16, 9),
    THREE_TWO(3, 2),
    A4(2480, 3508),
    LETTER(2550, 3300),
    CUSTOM(0, 0),
    AUTO(0, 0);

    /** Whether this is a size in pixels, exported as it is, rather than a ratio. */
    val isPixels: Boolean get() = this == A4 || this == LETTER || this == CUSTOM
}

/**
 * What shows behind and between the photos (spec §4.11): a [color], a gradient from [color] to
 * [color2] at [angle] degrees, cell [photoCell]'s photo blurred, a [pattern] in [color2] over
 * [color], or nothing (PNG and WebP keep it see-through; JPEG puts white there).
 */
@Serializable
data class CanvasBackground(
    val kind: BackgroundKind = BackgroundKind.SOLID,
    val color: Int = WHITE,
    val color2: Int = DEFAULT_SECOND,
    val angle: Float = DEFAULT_ANGLE,
    val pattern: PatternKind = PatternKind.DOTS,
    val photoCell: Int = 0
) {
    companion object {
        const val DEFAULT_SECOND = -0x3f3f40
        const val DEFAULT_ANGLE = 90f
    }
}

@Serializable
enum class BackgroundKind { SOLID, GRADIENT, BLURRED_PHOTO, PATTERN, TRANSPARENT }

@Serializable
enum class PatternKind { DOTS, STRIPES, CHECKS, GRID }

/** The outlines of Shapes mode (spec §4.11). */
@Serializable
enum class ShapeOutline { HEART, CIRCLE, HEXAGON, LETTERS }

/**
 * How a collage is written (spec §4.11, Export): JPEG, PNG or WebP at [quality], its long edge
 * [longEdge] pixels, or the canvas's own size when null (print sizes, custom pixels, a stitch).
 */
@Serializable
data class CollageOutput(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = ExportOptions.DEFAULT_QUALITY,
    val longEdge: Int? = LONG_EDGE_4K
) {
    companion object {
        const val LONG_EDGE_HD = 1920
        const val LONG_EDGE_2K = 2560
        const val LONG_EDGE_4K = 3840
        const val LONG_EDGE_8K = 7680
        val FORMATS = listOf(ExportFormat.JPEG, ExportFormat.PNG, ExportFormat.WEBP)
    }
}

private const val WHITE = -1
