package com.photoxt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The recipe format this build writes; older ones read with their missing fields at defaults. */
const val EDIT_RECIPE_VERSION = 1

/**
 * A non-destructive edit (spec §4.10): every tool's settings, stored as JSON beside the original
 * and rendered by one GPU pipeline, for the preview and for the full-size export alike. It is
 * drawn in a fixed order: geometry, erasures, light and colour, filter, local masks, effects,
 * redaction, background, then the overlays (layers, drawing, watermark) and the frame.
 *
 * Coordinates are fractions, so a recipe means the same at any size. Masks, redactions, erasures
 * and retouch spots stick to the photo: they are fractions of the source raster as stored (before
 * its EXIF rotation). Layers, drawing and the watermark sit on the finished frame: fractions of
 * the cropped picture. Sizes are fractions of the short edge of whichever of the two they are in.
 */
@Serializable
data class EditRecipe(
    val version: Int = EDIT_RECIPE_VERSION,
    val geometry: Geometry = Geometry(),
    val light: Light = Light(),
    val color: ColorAdjust = ColorAdjust(),
    val curves: Curves = Curves(),
    val effects: Effects = Effects(),
    val filter: Filter? = null,
    val masks: List<LocalMask> = emptyList(),
    val retouch: Retouch = Retouch(),
    val erasures: List<Erasure> = emptyList(),
    val redactions: List<Redaction> = emptyList(),
    val background: Background = Background(),
    /** The subject mask (ML Kit's foreground confidence), an asset in source coordinates. */
    val subjectMask: String? = null,
    val layers: List<Layer> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    val frame: Frame = Frame(),
    val watermark: Watermark? = null
) {
    /** Whether rendering this changes nothing. */
    val isIdentity: Boolean get() = this == EditRecipe(version = version)

    /** Whether something here reads the subject mask, so a paste must find the new photo's own. */
    val needsSubject: Boolean
        get() = background.mode != BackgroundMode.KEEP ||
            retouch.backgroundBlur > 0f ||
            masks.any { it.kind == MaskKind.SUBJECT || it.kind == MaskKind.BACKGROUND }

    /** Whether the result has see-through parts, which JPEG can't hold. */
    val hasAlpha: Boolean get() = background.mode == BackgroundMode.REMOVE

    /** Every asset file the recipe refers to (masks, patches, LUTs, images). */
    val assets: Set<String>
        get() = buildSet {
            subjectMask?.let(::add)
            erasures.forEach { add(it.patch) }
            filter?.lut?.let(::add)
            background.image?.let(::add)
            watermark?.logo?.let(::add)
            layers.forEach { layer -> (layer as? StickerLayer)?.image?.let(::add) }
        }

    /**
     * The part of this recipe that fits any photo, for *Paste edits* (spec §4.10): light, colour,
     * curves, effects and filter always; the rest only when [parts] asks for it. Things drawn
     * around this photo's content (masks by hand, redactions, erasures, red-eye spots) stay here.
     */
    fun forPaste(parts: Set<PastePart>): EditRecipe = EditRecipe(
        light = light,
        color = color,
        curves = curves,
        effects = effects,
        filter = filter,
        geometry = if (PastePart.GEOMETRY in parts) geometry else Geometry(),
        masks = masks.filter { it.kind == MaskKind.SUBJECT || it.kind == MaskKind.BACKGROUND },
        retouch = Retouch(
            skinSmoothing = retouch.skinSmoothing,
            backgroundBlur = retouch.backgroundBlur
        ),
        background = background,
        layers = if (PastePart.OVERLAYS in parts) layers else emptyList(),
        strokes = if (PastePart.OVERLAYS in parts) strokes else emptyList(),
        frame = if (PastePart.FRAME in parts) frame else Frame(),
        watermark = if (PastePart.FRAME in parts) watermark else null
    )
}

/** What *Paste edits* carries over beyond light, colour and filters. */
enum class PastePart { GEOMETRY, OVERLAYS, FRAME }

/** A point as fractions of its space. */
@Serializable
data class Point01(val x: Float, val y: Float)

/** A rectangle as fractions of its space. */
@Serializable
data class Rect01(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = Rect01()
    }
}

/**
 * Transform (spec §4.10). Applied in order: quarter turns and flips, then the perspective quad
 * (keystone sliders, or four corners), then straightening (scaled to leave no empty corners),
 * then the crop, which is a rectangle of the straightened frame.
 */
@Serializable
data class Geometry(
    /** Clockwise quarter turns, 0 to 3. */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Degrees, −45 to 45, clockwise. */
    val straighten: Float = 0f,
    /** Horizontal keystone, −1 to 1: the right edge shrinks for positive values. */
    val keystoneH: Float = 0f,
    /** Vertical keystone, −1 to 1: the top edge shrinks for positive values. */
    val keystoneV: Float = 0f,
    /**
     * Four-corner perspective: top-left, top-right, bottom-right, bottom-left as x, y fractions
     * of the turned frame, mapped onto its full rectangle. Overrides the keystone sliders.
     */
    val corners: List<Float>? = null,
    val crop: Rect01 = Rect01.FULL,
    val aspect: CropAspect = CropAspect.FREE,
    /** Width over height for [CropAspect.CUSTOM], as typed in. */
    val customRatio: Float? = null
)

/** The crop's aspect lock (spec §4.10, Crop). */
@Serializable
enum class CropAspect(val ratio: Float?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_FIVE(4f / 5f),
    THREE_FOUR(3f / 4f),
    NINE_SIXTEEN(9f / 16f),
    SIXTEEN_NINE(16f / 9f),
    CUSTOM(null)
}

/** Light (spec §4.10). Exposure is in stops, −3 to 3; the others run −1 to 1. */
@Serializable
data class Light(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f
)

/** Colour (spec §4.10), each −1 to 1; [hue] turns every hue by up to half a circle. */
@Serializable
data class ColorAdjust(
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val hsl: Map<HslColor, HslBand> = emptyMap()
)

/** The eight colour ranges of HSL per colour. */
@Serializable
enum class HslColor(val degrees: Float) {
    RED(0f),
    ORANGE(30f),
    YELLOW(60f),
    GREEN(120f),
    AQUA(180f),
    BLUE(225f),
    PURPLE(270f),
    MAGENTA(315f)
}

/** One colour range's shift, each −1 to 1. */
@Serializable
data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

/** Curves (spec §4.10): control points from 0 to 1 per curve; none means a straight line. */
@Serializable
data class Curves(
    val rgb: List<Point01> = emptyList(),
    val red: List<Point01> = emptyList(),
    val green: List<Point01> = emptyList(),
    val blue: List<Point01> = emptyList()
)

/** Detail and effects (spec §4.10): sharpen, grain and fade 0 to 1; the rest −1 to 1. */
@Serializable
data class Effects(
    val sharpen: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val fade: Float = 0f
)

/**
 * A filter: a built-in look by id, or an imported `.cube` LUT (an asset), at [intensity] 0 to 1.
 */
@Serializable
data class Filter(val look: String? = null, val lut: String? = null, val intensity: Float = 1f)

/** Kinds of local mask (spec §4.10, Selective). */
@Serializable
enum class MaskKind { BRUSH, LINEAR, RADIAL, SUBJECT, BACKGROUND }

/**
 * A local adjustment: where ([kind] and its shape, in source coordinates) and what ([adjust]).
 * Linear: full effect at [start], none at [end]. Radial: an ellipse at [center] with radii as
 * fractions of the short edge, turned by [angle] degrees, softened by [feather].
 */
@Serializable
data class LocalMask(
    val id: String,
    val kind: MaskKind,
    val invert: Boolean = false,
    val start: Point01? = null,
    val end: Point01? = null,
    val center: Point01? = null,
    val radiusX: Float = 0.2f,
    val radiusY: Float = 0.2f,
    val angle: Float = 0f,
    val feather: Float = 0.5f,
    val strokes: List<MaskStroke> = emptyList(),
    val adjust: LocalAdjust = LocalAdjust()
)

/** What a local mask changes, each −1 to 1 (exposure in stops, −2 to 2). */
@Serializable
data class LocalAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val clarity: Float = 0f
)

/** A brush stroke of a mask: x, y pairs in source coordinates; [size] a fraction of the edge. */
@Serializable
data class MaskStroke(val points: List<Float>, val size: Float, val erase: Boolean = false)

/** Retouch (spec §4.10): red-eye spots, skin smoothing over found faces, background blur. */
@Serializable
data class Retouch(
    val redEyes: List<Circle01> = emptyList(),
    val skinSmoothing: Float = 0f,
    val faces: List<Ellipse01> = emptyList(),
    val backgroundBlur: Float = 0f
)

/** A circle in source coordinates; [radius] is a fraction of the short edge. */
@Serializable
data class Circle01(val x: Float, val y: Float, val radius: Float)

/** An axis-aligned ellipse in source coordinates; radii are fractions of the short edge. */
@Serializable
data class Ellipse01(val x: Float, val y: Float, val radiusX: Float, val radiusY: Float)

/**
 * An object erased (spec §4.10, Retouch): the inpainted [patch] (an asset with alpha) laid over
 * [rect] of the source before anything else is drawn.
 */
@Serializable
data class Erasure(val patch: String, val rect: Rect01)

/** Redact (spec §4.10): a region of the source, hidden by [style]. */
@Serializable
data class Redaction(
    val rect: Rect01,
    val shape: RedactShape = RedactShape.RECT,
    val style: RedactStyle = RedactStyle.PIXELATE,
    val color: Int = BLACK,
    val found: RedactFound = RedactFound.MANUAL
)

@Serializable
enum class RedactShape { RECT, ELLIPSE }

@Serializable
enum class RedactStyle { BLUR, PIXELATE, SOLID }

/** How a redaction was placed: by hand, or found by auto-detect. */
@Serializable
enum class RedactFound { MANUAL, FACE, TEXT, PLATE }

/** Cut-out (spec §4.10): what happens behind the subject. */
@Serializable
data class Background(
    val mode: BackgroundMode = BackgroundMode.KEEP,
    val color: Int = WHITE,
    val image: String? = null
)

@Serializable
enum class BackgroundMode { KEEP, REMOVE, COLOR, BLUR, IMAGE }

/**
 * Something laid on the finished frame (spec §4.10, Add): its centre at [x], [y] (fractions of
 * the cropped picture), turned by [rotation] degrees.
 */
@Serializable
sealed interface Layer {
    val id: String
    val x: Float
    val y: Float
    val rotation: Float

    fun moved(x: Float, y: Float, rotation: Float = this.rotation): Layer
}

/** Text: [size] is the line height as a fraction of the short edge. */
@Serializable
@SerialName("text")
data class TextLayer(
    override val id: String,
    val text: String,
    override val x: Float = 0.5f,
    override val y: Float = 0.5f,
    override val rotation: Float = 0f,
    val font: FontChoice = FontChoice.SANS,
    val size: Float = 0.08f,
    val color: Int = WHITE,
    val bold: Boolean = false,
    val outlineColor: Int? = null,
    val outlineWidth: Float = 0.08f,
    val shadow: Boolean = false,
    val background: Int? = null,
    val align: TextAlignment = TextAlignment.CENTER
) : Layer {
    override fun moved(x: Float, y: Float, rotation: Float) =
        copy(x = x, y = y, rotation = rotation)
}

/** An emoji, or a picture (an asset, such as a lifted subject); [size] its height. */
@Serializable
@SerialName("sticker")
data class StickerLayer(
    override val id: String,
    val emoji: String? = null,
    val image: String? = null,
    override val x: Float = 0.5f,
    override val y: Float = 0.5f,
    override val rotation: Float = 0f,
    val size: Float = 0.25f
) : Layer {
    override fun moved(x: Float, y: Float, rotation: Float) =
        copy(x = x, y = y, rotation = rotation)
}

/** A shape or an arrow, [width] by [height] (fractions of the short edge). */
@Serializable
@SerialName("shape")
data class ShapeLayer(
    override val id: String,
    val shape: ShapeKind,
    override val x: Float = 0.5f,
    override val y: Float = 0.5f,
    override val rotation: Float = 0f,
    val width: Float = 0.3f,
    val height: Float = 0.2f,
    val stroke: Int = WHITE,
    val strokeWidth: Float = 0.012f,
    val fill: Int? = null
) : Layer {
    override fun moved(x: Float, y: Float, rotation: Float) =
        copy(x = x, y = y, rotation = rotation)
}

@Serializable
enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

/** Typefaces the text tool offers; the Devanagari ones are the system's Noto fonts. */
@Serializable
enum class FontChoice {
    SANS,
    SERIF,
    MONO,
    CONDENSED,
    HANDWRITING,
    DEVANAGARI_SANS,
    DEVANAGARI_SERIF
}

@Serializable
enum class TextAlignment { START, CENTER, END }

/**
 * Freehand drawing (spec §4.10): x, y, pressure triples in frame coordinates; [width] a fraction
 * of the short edge at full pressure. The eraser removes drawing only.
 */
@Serializable
data class Stroke(val tool: BrushTool, val color: Int, val width: Float, val points: List<Float>)

@Serializable
enum class BrushTool { PEN, MARKER, HIGHLIGHTER, ERASER }

/** Frames and borders (spec §4.10): [width] a fraction of the short edge, [radius] too. */
@Serializable
data class Frame(
    val style: FrameStyle = FrameStyle.NONE,
    val color: Int = WHITE,
    val width: Float = 0.04f,
    val radius: Float = 0.04f
)

@Serializable
enum class FrameStyle { NONE, SOLID, POLAROID, ROUNDED }

/** A watermark (spec §4.10): text or a logo asset, once at [position] or tiled. */
@Serializable
data class Watermark(
    val text: String? = null,
    val logo: String? = null,
    val opacity: Float = 0.5f,
    val size: Float = 0.05f,
    val color: Int = WHITE,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_END,
    val tiled: Boolean = false
)

@Serializable
enum class WatermarkPosition { TOP_START, TOP_END, CENTER, BOTTOM_START, BOTTOM_END }

/** Output (spec §4.10): how an edited photo is written. */
@Serializable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.SAME,
    /** 1 to 100, for the lossy formats. */
    val quality: Int = DEFAULT_QUALITY,
    val resize: Resize = Resize(),
    /** Aim for a file of at most this many bytes (spec: "under 500 KB"); null for any size. */
    val maxBytes: Long? = null,
    val keepMetadata: Boolean = true
) {
    companion object {
        const val DEFAULT_QUALITY = 95
    }
}

@Serializable
enum class ExportFormat(val mime: String?, val extension: String?) {
    SAME(null, null),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic"),
    AVIF("image/avif", "avif");

    companion object {
        /** The format PhotoXT writes for [mime]; null for one it only reads (GIF, RAW). */
        fun ofMime(mime: String): ExportFormat? = entries.firstOrNull { it.mime == mime }
            ?: if (mime == "image/heif") HEIC else null

        /**
         * The format an export [asked] for comes out in: for [SAME], the one of a photo of
         * type [sourceMime], or JPEG when PhotoXT can't write that one.
         */
        fun written(asked: ExportFormat, sourceMime: String): ExportFormat =
            asked.takeIf { it != SAME } ?: ofMime(sourceMime) ?: JPEG
    }
}

/** Resize: the original size, a percentage, or a long edge in pixels. */
@Serializable
data class Resize(val percent: Int? = null, val longEdge: Int? = null)

/** An edit preset (spec §4.10, Filters): a name and the look it saves. */
@Serializable
data class EditPreset(val name: String, val recipe: EditRecipe)

private const val WHITE = -1
private const val BLACK = -0x1000000
