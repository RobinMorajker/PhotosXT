package com.photoxt.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.BrushTool
import com.photoxt.core.model.FontChoice
import com.photoxt.core.model.Frame
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.Layer
import com.photoxt.core.model.ShapeKind
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextAlignment
import com.photoxt.core.model.TextLayer
import com.photoxt.core.model.Watermark
import com.photoxt.core.model.WatermarkPosition

/*
 * The panels of the tools that lay something on the photo (spec §4.10, Add): text, drawing,
 * stickers and shapes, a frame, a watermark.
 */

// --- Text --------------------------------------------------------------------------------

@Composable
internal fun TextPanel(state: EditorState, tools: EditorTools, onDialog: (EditorDialog) -> Unit) {
    val layer = state.recipe.layers.firstOrNull { it.id == state.selectedLayer } as? TextLayer
    ButtonRow {
        ActionButton(R.string.editor_add_text) { onDialog(EditorDialog.ADD_TEXT) }
        if (layer != null) {
            ActionButton(R.string.editor_edit_text) { onDialog(EditorDialog.EDIT_TEXT) }
            ActionButton(R.string.editor_delete_layer) { tools.layers.deleteLayer(layer.id) }
        }
    }
    if (layer == null) {
        Hint(R.string.editor_text_hint)
        return
    }
    val step = R.string.editor_step_text
    fun change(label: Int?, to: TextLayer.() -> TextLayer) =
        tools.layers.updateLayer(layer.id, label) { (it as? TextLayer)?.to() ?: it }
    ChipRow(FontChoice.entries, layer.font, { stringResource(fontLabel(it)) }) {
        change(step) { copy(font = it) }
    }
    SliderRow(
        label = R.string.editor_text_size,
        value = layer.size,
        onChange = { change(null) { copy(size = it) } },
        onDone = { tools.history.commit(step) },
        range = MIN_TEXT..MAX_TEXT,
        zero = DEFAULT_TEXT,
        shown = ::plain
    )
    ColorRow(layer.color, tools) { change(R.string.editor_step_color) { copy(color = it) } }
    ButtonRow {
        TextAlignment.entries.forEach { align ->
            ToggleIcon(alignIcon(align), alignLabel(align), layer.align == align) {
                change(step) { copy(align = align) }
            }
        }
        ToggleIcon(DesignR.drawable.ic_bold, R.string.editor_bold, layer.bold) {
            change(step) { copy(bold = !bold) }
        }
    }
    SwitchRow(R.string.editor_outline, layer.outlineColor != null) {
        change(step) { copy(outlineColor = if (it) BLACK else null) }
    }
    SwitchRow(R.string.editor_shadow, layer.shadow) { change(step) { copy(shadow = it) } }
    SwitchRow(R.string.editor_text_background, layer.background != null) {
        change(step) { copy(background = if (it) TEXT_BACKGROUND else null) }
    }
}

private fun alignIcon(align: TextAlignment) = when (align) {
    TextAlignment.START -> DesignR.drawable.ic_align_start
    TextAlignment.CENTER -> DesignR.drawable.ic_align_center
    TextAlignment.END -> DesignR.drawable.ic_align_end
}

private fun alignLabel(align: TextAlignment) = when (align) {
    TextAlignment.START -> R.string.editor_align_start
    TextAlignment.CENTER -> R.string.editor_align_center
    TextAlignment.END -> R.string.editor_align_end
}

private fun fontLabel(font: FontChoice) = when (font) {
    FontChoice.SANS -> R.string.font_sans
    FontChoice.SERIF -> R.string.font_serif
    FontChoice.MONO -> R.string.font_mono
    FontChoice.CONDENSED -> R.string.font_condensed
    FontChoice.HANDWRITING -> R.string.font_handwriting
    FontChoice.DEVANAGARI_SANS -> R.string.font_devanagari_sans
    FontChoice.DEVANAGARI_SERIF -> R.string.font_devanagari_serif
}

// --- Draw --------------------------------------------------------------------------------

@Composable
internal fun DrawPanel(state: EditorState, tools: EditorTools) {
    ChipRow(BrushTool.entries, state.brush, { stringResource(brushLabel(it)) }) {
        tools.draw.setBrush(it)
    }
    if (state.brush != BrushTool.ERASER) {
        ColorRow(state.brushColor, tools, tools.draw::setBrushColor)
    }
    SliderRow(
        label = R.string.editor_brush_width,
        value = state.brushWidth,
        onChange = tools.draw::setBrushWidth,
        onDone = {},
        range = MIN_PEN..MAX_PEN,
        zero = DEFAULT_PEN,
        shown = { plain(it * PEN_SCALE) }
    )
    Hint(if (state.pickingColor) R.string.editor_pick_hint else R.string.editor_draw_hint)
}

private fun brushLabel(tool: BrushTool) = when (tool) {
    BrushTool.PEN -> R.string.editor_pen
    BrushTool.MARKER -> R.string.editor_marker
    BrushTool.HIGHLIGHTER -> R.string.editor_highlighter
    BrushTool.ERASER -> R.string.editor_draw_eraser
}

// --- Stickers and shapes -----------------------------------------------------------------

@Composable
internal fun StickersPanel(state: EditorState, tools: EditorTools) {
    val layers = tools.layers
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        EMOJI.forEach { emoji ->
            Text(
                emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button) { layers.addEmoji(emoji) }
                    .padding(6.dp)
            )
        }
    }
    ButtonRow {
        BarIcon(DesignR.drawable.ic_square, R.string.editor_rectangle) {
            layers.addShape(ShapeKind.RECTANGLE)
        }
        BarIcon(DesignR.drawable.ic_circle, R.string.editor_ellipse) {
            layers.addShape(ShapeKind.ELLIPSE)
        }
        BarIcon(DesignR.drawable.ic_line, R.string.editor_line) { layers.addShape(ShapeKind.LINE) }
        BarIcon(DesignR.drawable.ic_arrow_forward, R.string.editor_arrow) {
            layers.addShape(ShapeKind.ARROW)
        }
    }
    if (state.stickers.isNotEmpty()) {
        Heading(R.string.editor_my_stickers)
        RemovableChips(
            items = state.stickers,
            selected = null,
            name = { stringResource(R.string.editor_sticker_n, state.stickers.indexOf(it) + 1) },
            onPick = layers::addSticker,
            onRemove = layers::deleteSticker
        )
    }
    PickedLayer(state.recipe.layers.firstOrNull { it.id == state.selectedLayer }, tools)
}

/** What can be done to the sticker or shape picked on the photo. */
@Composable
private fun PickedLayer(layer: Layer?, tools: EditorTools) {
    val delete = @Composable {
        ButtonRow {
            ActionButton(R.string.editor_delete_layer) {
                layer?.let { tools.layers.deleteLayer(it.id) }
            }
        }
    }
    when (layer) {
        is ShapeLayer -> {
            fun change(label: Int, to: ShapeLayer.() -> ShapeLayer) =
                tools.layers.updateLayer(layer.id, label) { (it as? ShapeLayer)?.to() ?: it }
            ColorRow(layer.stroke, tools) {
                change(R.string.editor_step_color) { copy(stroke = it) }
            }
            SwitchRow(R.string.editor_fill, layer.fill != null) {
                change(R.string.editor_step_sticker) { copy(fill = if (it) stroke else null) }
            }
            delete()
        }

        is StickerLayer -> delete()

        else -> Hint(R.string.editor_sticker_hint)
    }
}

// --- Frame and watermark -----------------------------------------------------------------

@Composable
internal fun FramePanel(state: EditorState, tools: EditorTools) {
    val frame = state.recipe.frame
    val step = R.string.editor_step_frame

    @Composable
    fun Adjust(label: Int, value: Float, upTo: Float, set: Frame.(Float) -> Frame) = RecipeSlider(
        tools,
        label,
        step,
        value,
        range = 0f..upTo,
        zero = DEFAULT_FRAME,
        shown = ::percent
    ) { copy(frame = this.frame.set(it)) }
    ChipRow(FrameStyle.entries, frame.style, { stringResource(frameLabel(it)) }) { style ->
        tools.history.edit(step) { it.copy(frame = it.frame.copy(style = style)) }
    }
    if (frame.style == FrameStyle.NONE) return
    ColorRow(frame.color, tools) { color ->
        tools.history.edit(step) { it.copy(frame = it.frame.copy(color = color)) }
    }
    Adjust(R.string.editor_frame_width, frame.width, MAX_FRAME) { copy(width = it) }
    if (frame.style == FrameStyle.ROUNDED) {
        Adjust(R.string.editor_frame_radius, frame.radius, MAX_RADIUS) { copy(radius = it) }
    }
}

private fun frameLabel(style: FrameStyle) = when (style) {
    FrameStyle.NONE -> R.string.frame_none
    FrameStyle.SOLID -> R.string.frame_solid
    FrameStyle.POLAROID -> R.string.frame_polaroid
    FrameStyle.ROUNDED -> R.string.frame_rounded
}

@Composable
internal fun WatermarkPanel(
    state: EditorState,
    tools: EditorTools,
    onDialog: (EditorDialog) -> Unit
) {
    val mark = state.recipe.watermark
    val step = R.string.editor_step_watermark
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(tools.layers::importLogo)
    }
    ButtonRow {
        ActionButton(R.string.editor_watermark_text) { onDialog(EditorDialog.WATERMARK_TEXT) }
        ActionButton(R.string.editor_watermark_logo) {
            val images = ActivityResultContracts.PickVisualMedia.ImageOnly
            pickLogo.launch(PickVisualMediaRequest(images))
        }
    }
    if (mark == null) return
    fun change(label: Int?, to: Watermark.() -> Watermark) =
        tools.history.edit(label) { it.copy(watermark = it.watermark?.to()) }

    @Composable
    fun Adjust(
        label: Int,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        zero: Float,
        set: Watermark.(Float) -> Watermark
    ) = RecipeSlider(tools, label, step, value, range, zero, ::percent) {
        copy(watermark = watermark?.set(it))
    }
    ChipRow(WatermarkPosition.entries, mark.position, { stringResource(positionLabel(it)) }) {
        change(step) { copy(position = it) }
    }
    SwitchRow(R.string.editor_tiled, mark.tiled) { change(step) { copy(tiled = it) } }
    Adjust(R.string.editor_opacity, mark.opacity, 0f..1f, DEFAULT_OPACITY) { copy(opacity = it) }
    Adjust(R.string.editor_size, mark.size, MIN_MARK..MAX_MARK, DEFAULT_MARK) { copy(size = it) }
    if (mark.logo == null) ColorRow(mark.color, tools) { change(step) { copy(color = it) } }
}

private fun positionLabel(position: WatermarkPosition) = when (position) {
    WatermarkPosition.TOP_START -> R.string.position_top_start
    WatermarkPosition.TOP_END -> R.string.position_top_end
    WatermarkPosition.CENTER -> R.string.position_center
    WatermarkPosition.BOTTOM_START -> R.string.position_bottom_start
    WatermarkPosition.BOTTOM_END -> R.string.position_bottom_end
}

private const val MIN_TEXT = 0.02f
private const val MAX_TEXT = 0.4f
private const val DEFAULT_TEXT = 0.08f
private const val MIN_PEN = 0.002f
private const val MAX_PEN = 0.06f
private const val DEFAULT_PEN = 0.012f
private const val PEN_SCALE = 10
private const val MAX_FRAME = 0.2f
private const val MAX_RADIUS = 0.3f
private const val DEFAULT_FRAME = 0.04f
private const val DEFAULT_OPACITY = 0.5f
private const val MIN_MARK = 0.02f
private const val MAX_MARK = 0.3f
private const val DEFAULT_MARK = 0.05f
private const val BLACK = -0x1000000
private const val TEXT_BACKGROUND = -0x67000000

private val EMOJI = listOf(
    "😀", "😂", "😍", "🥳", "😎", "🤔", "😢", "😡", "👍", "👏", "🙏", "💪", "❤️", "🔥", "⭐",
    "🎉", "🌸", "🌞", "🌈", "⚡", "🎂", "📍", "✅", "❌", "➡️", "🐶", "🐱", "🌴", "🏖️", "🪔"
)
