package com.photoxt.feature.editor

import com.photoxt.core.datastore.LutEntry
import com.photoxt.core.ml.EraserModel
import com.photoxt.core.model.BrushTool
import com.photoxt.core.model.EditPreset
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.MaskStroke
import com.photoxt.core.model.RedactStyle
import com.photoxt.core.render.PreviewMode

/** The editor's tools, in the spec's order (§4.10, Phase 7 build step 2). */
enum class Tool {
    TRANSFORM,
    LIGHT,
    COLOR,
    DETAIL,
    FILTERS,
    TEXT,
    DRAW,
    STICKERS,
    FRAME,
    WATERMARK,
    REDACT,
    SELECTIVE,
    RETOUCH,
    CUTOUT,
    ERASER
}

/** The curve being edited: all three channels at once, or one. [ink] draws it. */
enum class CurveChannel(val label: Int, val ink: Long) {
    RGB(R.string.editor_curve_rgb, 0xFFFFFFFF),
    RED(R.string.editor_red, 0xFFFF6B6B),
    GREEN(R.string.editor_green, 0xFF6BE08A),
    BLUE(R.string.editor_blue, 0xFF6BA8FF)
}

/** What a drag on the photo does now. */
enum class Gesture {
    NONE,
    CROP,
    CORNERS,
    DRAW,
    BRUSH_MASK,
    LINEAR,
    RADIAL,
    REDACT_BOX,
    RED_EYE,
    ERASE,
    PICK_COLOR,
    LAYER
}

/** One step of the edit history (spec §4.10: "undo/redo with a history list"). */
data class Step(val label: Int, val recipe: EditRecipe)

/** Where the editor is with its photo. */
sealed interface Phase {
    data class Loading(val fraction: Float?) : Phase

    data object Ready : Phase

    data class Failed(val message: Int) : Phase
}

/**
 * Everything the editor screen shows but the preview picture itself, which changes too often to
 * share a state with the controls.
 */
data class EditorState(
    val phase: Phase = Phase.Loading(null),
    val title: String = "",
    /** The photo's own type, which *Save* keeps and *Save as copy* offers as "same". */
    val mime: String = "",
    val recipe: EditRecipe = EditRecipe(),
    val steps: List<Step> = emptyList(),
    val stepIndex: Int = -1,
    val tool: Tool? = null,
    val comparing: Boolean = false,
    val isDrive: Boolean = false,
    val wasEdited: Boolean = false,
    /** Whether *Save* can write the photo's own format; a GIF or RAW file is saved as a copy. */
    val overwritable: Boolean = true,
    val busy: Int? = null,
    val selectedLayer: String? = null,
    val selectedMask: String? = null,
    val curve: CurveChannel = CurveChannel.RGB,
    val hslColor: HslColor = HslColor.RED,
    val brush: BrushTool = BrushTool.PEN,
    val brushColor: Int = DEFAULT_INK,
    val brushWidth: Float = 0.012f,
    val maskBrushSize: Float = 0.06f,
    val maskErase: Boolean = false,
    val eraserSize: Float = 0.05f,
    val eraserModel: EraserModel = EraserModel.MIGAN,
    val modelProgress: Float? = null,
    val modelReady: Boolean = false,
    val pendingErase: List<MaskStroke> = emptyList(),
    val redactStyle: RedactStyle = RedactStyle.PIXELATE,
    /** The colour of solid redaction bars, new ones included. */
    val redactColor: Int = BLACK,
    val pickingColor: Boolean = false,
    val cornersMode: Boolean = false,
    val clipboard: EditRecipe? = null,
    val presets: List<EditPreset> = emptyList(),
    val luts: List<LutEntry> = emptyList(),
    val stickers: List<String> = emptyList(),
    /** The last *Save as copy* settings, the export sheet's starting point. */
    val export: ExportOptions = ExportOptions(),
    val lifted: String? = null
) {
    val canUndo: Boolean get() = stepIndex > 0
    val canRedo: Boolean get() = stepIndex < steps.lastIndex

    /** Whether Save would change the file: an edit to write, or one to take back. */
    val canSave: Boolean get() = phase == Phase.Ready &&
        (!recipe.isIdentity || (wasEdited && overwritable))

    /** The preview the current tool needs: the whole frame for transform, else the edit. */
    val previewMode: PreviewMode
        get() = when {
            comparing -> PreviewMode.ORIGINAL
            tool == Tool.TRANSFORM -> PreviewMode.FRAME
            else -> PreviewMode.EDIT
        }

    val gesture: Gesture
        get() = when {
            pickingColor -> Gesture.PICK_COLOR
            tool == Tool.TRANSFORM -> if (cornersMode) Gesture.CORNERS else Gesture.CROP
            tool == Tool.DRAW -> Gesture.DRAW
            tool == Tool.REDACT -> Gesture.REDACT_BOX
            tool == Tool.ERASER -> Gesture.ERASE
            tool == Tool.RETOUCH -> Gesture.RED_EYE
            tool == Tool.SELECTIVE -> maskGesture
            tool in LAYER_TOOLS -> Gesture.LAYER
            else -> Gesture.NONE
        }

    /** What a drag does to the mask picked: paint it, or move its line or its circle. */
    private val maskGesture: Gesture
        get() = when (recipe.masks.firstOrNull { it.id == selectedMask }?.kind) {
            MaskKind.BRUSH -> Gesture.BRUSH_MASK
            MaskKind.LINEAR -> Gesture.LINEAR
            MaskKind.RADIAL -> Gesture.RADIAL
            else -> Gesture.NONE
        }

    private companion object {
        const val DEFAULT_INK = -0x10000
        const val BLACK = -0x1000000
        val LAYER_TOOLS = setOf(Tool.TEXT, Tool.STICKERS)
    }
}
