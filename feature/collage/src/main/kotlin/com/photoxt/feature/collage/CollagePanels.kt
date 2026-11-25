package com.photoxt.feature.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.BackgroundKind
import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CollageCanvas
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.PatternKind
import com.photoxt.core.model.ShapeOutline
import com.photoxt.core.model.SplitAxis
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.CollageTemplate
import com.photoxt.core.render.CollageTemplates

// The Layout, Canvas, Frame and Layers panels (spec §4.11, Modes, Canvas and Extras).

@Composable
internal fun LayoutPanel(ui: CollageUi, edits: CollageEdits) {
    val n = ui.project.cells.size
    Heading(R.string.collage_mode)
    ChipRow(CollageMode.entries, ui.mode, { stringResource(modeLabel(it)) }) { edits.setMode(it) }
    when (ui.mode) {
        CollageMode.GRID -> TemplateStrip(
            CollageTemplates.grid(n),
            ui.project.template,
            edits::pickTemplate
        )

        CollageMode.STORY -> TemplateStrip(
            CollageTemplates.story(n),
            ui.project.template,
            edits::pickTemplate
        )

        CollageMode.SMART -> {
            Hint(R.string.collage_smart_hint)
            ActionButton(R.string.collage_fit_again, enabled = ui.busy == null) { edits.fitSmart() }
        }

        CollageMode.FREEFORM -> {
            Hint(R.string.collage_freeform_hint)
            ActionButton(R.string.collage_scatter) { edits.scatter() }
        }

        CollageMode.STITCH -> {
            Hint(R.string.collage_stitch_hint)
            ChipRow(listOf(false, true), ui.project.horizontal, {
                stringResource(if (it) R.string.collage_across else R.string.collage_down)
            }) { edits.setStitchHorizontal(it) }
        }

        CollageMode.SHAPES -> ShapeChoice(ui, edits)

        CollageMode.BEFORE_AFTER -> {
            val axis = ui.project.layout.axis
            ChipRow(listOf(SplitAxis.ROW, SplitAxis.COLUMN), axis, {
                stringResource(
                    if (it ==
                        SplitAxis.ROW
                    ) {
                        R.string.collage_side_by_side
                    } else {
                        R.string.collage_stacked
                    }
                )
            }) { edits.setBeforeAfter(it) }
            if (n > 2) Hint(R.string.collage_before_after_hint)
        }
    }
}

@Composable
private fun ShapeChoice(ui: CollageUi, edits: CollageEdits) {
    ChipRow(ShapeOutline.entries, ui.project.shape, { stringResource(shapeLabel(it)) }) {
        edits.setShape(it)
    }
    if (ui.project.shape == ShapeOutline.LETTERS) {
        OutlinedTextField(
            value = ui.project.letters,
            onValueChange = edits::setLetters,
            label = { Text(stringResource(R.string.collage_letters)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

/** The templates for this many photos, each drawn as its cells (spec §4.11, Grid templates). */
@Composable
private fun TemplateStrip(
    templates: List<CollageTemplate>,
    selected: String?,
    onPick: (CollageTemplate) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        pluralStringResource(R.plurals.collage_templates_count, templates.size, templates.size),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 4.dp)
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(templates, key = { _, t -> t.id }) { i, template ->
            val on = template.id == selected
            val name = stringResource(R.string.collage_template_n, i + 1, templates.size)
            Canvas(
                Modifier
                    .size(TEMPLATE_DP.dp)
                    .border(
                        if (on) 3.dp else 1.dp,
                        if (on) accent else Color.Gray,
                        RoundedCornerShape(4.dp)
                    )
                    .selectable(selected = on, role = Role.RadioButton) { onPick(template) }
                    .semantics { contentDescription = name }
                    .padding(4.dp)
            ) {
                val gap = 2.dp.toPx()
                CollageTemplates.cellRects(template.layout).values.forEach { r ->
                    drawRect(
                        Color.LightGray,
                        Offset(r.left * size.width + gap / 2, r.top * size.height + gap / 2),
                        androidx.compose.ui.geometry.Size(
                            r.width * size.width - gap,
                            r.height * size.height - gap
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun CanvasPanel(ui: CollageUi, edits: CollageEdits, onCustomSize: () -> Unit) {
    val canvas = ui.project.canvas
    Heading(R.string.collage_shape)
    if (ui.mode == CollageMode.STITCH) {
        Hint(R.string.collage_stitch_shape_hint)
    } else {
        ChipRow(ASPECTS, canvas.aspect, {
            aspectLabel(it, canvas.customWidth, canvas.customHeight)
        }) { aspect ->
            if (aspect == CanvasAspect.CUSTOM) onCustomSize() else edits.setAspect(aspect)
        }
    }
    CanvasSlider(R.string.collage_spacing, canvas.spacing, MAX_GAP, edits) { copy(spacing = it) }
    CanvasSlider(R.string.collage_margin, canvas.margin, MAX_MARGIN, edits) { copy(margin = it) }
    CanvasSlider(R.string.collage_corners, canvas.cornerRadius, MAX_RADIUS, edits) {
        copy(cornerRadius = it)
    }
    CanvasSlider(R.string.collage_border, canvas.borderWidth, MAX_BORDER, edits) {
        copy(borderWidth = it)
    }
    if (canvas.borderWidth > 0f) {
        ColorRow(canvas.borderColor) { c ->
            edits.canvas(R.string.collage_step_canvas) { it.copy(borderColor = c) }
        }
    }
    BackgroundChoice(ui, edits)
}

/** A canvas measure from 0 to [max], one step of the history when the finger lifts. */
@Composable
private fun CanvasSlider(
    label: Int,
    value: Float,
    max: Float,
    edits: CollageEdits,
    change: CollageCanvas.(Float) -> CollageCanvas
) {
    SliderRow(
        label,
        value,
        { v -> edits.canvas(null) { it.change(v) } },
        { edits.canvas(R.string.collage_step_canvas) { it } },
        0f..max,
        shown = ::plainScaled
    )
}

@Composable
private fun BackgroundChoice(ui: CollageUi, edits: CollageEdits) {
    val background = ui.project.canvas.background
    fun set(
        change: (com.photoxt.core.model.CanvasBackground) -> com.photoxt.core.model.CanvasBackground
    ) = edits.canvas(R.string.collage_step_background) {
        it.copy(background = change(it.background))
    }
    Heading(R.string.collage_background)
    ChipRow(BackgroundKind.entries, background.kind, {
        stringResource(backgroundLabel(it))
    }) { kind ->
        set { it.copy(kind = kind) }
    }
    when (background.kind) {
        BackgroundKind.SOLID -> ColorRow(background.color) { c -> set { it.copy(color = c) } }

        BackgroundKind.GRADIENT -> {
            ColorRow(background.color) { c -> set { it.copy(color = c) } }
            ColorRow(background.color2) { c -> set { it.copy(color2 = c) } }
            SliderRow(
                R.string.collage_angle,
                background.angle,
                { v -> edits.canvas(null) { it.copy(background = it.background.copy(angle = v)) } },
                { edits.canvas(R.string.collage_step_background) { it } },
                0f..FULL_TURN,
                zero = CanvasBackgroundDefaults.ANGLE,
                shown = ::degrees
            )
        }

        BackgroundKind.PATTERN -> {
            ChipRow(PatternKind.entries, background.pattern, {
                stringResource(patternLabel(it))
            }) { p ->
                set { it.copy(pattern = p) }
            }
            ColorRow(background.color) { c -> set { it.copy(color = c) } }
            ColorRow(background.color2) { c -> set { it.copy(color2 = c) } }
        }

        BackgroundKind.BLURRED_PHOTO -> {
            val count = ui.project.cells.size
            ChipRow((0 until count).toList(), background.photoCell, {
                stringResource(R.string.collage_photo_n, it + 1)
            }) { i -> set { it.copy(photoCell = i) } }
        }

        BackgroundKind.TRANSPARENT -> Hint(R.string.collage_transparent_hint)
    }
}

@Composable
internal fun FramePanel(ui: CollageUi, edits: CollageEdits) {
    val frame = ui.project.frame
    ChipRow(FrameStyle.entries, frame.style, { stringResource(frameLabel(it)) }) { style ->
        edits.frame(R.string.collage_step_frame) { it.copy(style = style) }
    }
    if (frame.style == FrameStyle.NONE) return
    val commit = { edits.frame(R.string.collage_step_frame) { it } }
    SliderRow(
        R.string.collage_frame_width,
        frame.width,
        { v ->
            edits.frame(null) { it.copy(width = v) }
        },
        commit,
        MIN_FRAME..MAX_FRAME,
        shown = ::plainScaled
    )
    if (frame.style == FrameStyle.ROUNDED) {
        SliderRow(
            R.string.collage_corners,
            frame.radius,
            { v ->
                edits.frame(null) { it.copy(radius = v) }
            },
            commit,
            0f..MAX_RADIUS,
            shown = ::plainScaled
        )
    }
    ColorRow(frame.color) { c -> edits.frame(R.string.collage_step_frame) { it.copy(color = c) } }
}

/**
 * The layers (spec §4.11, "a layers panel"): texts and stickers, topmost first, to pick, raise,
 * lower or delete; in freeform the photos too, whose order is theirs to change.
 */
@Composable
internal fun LayersPanel(ui: CollageUi, cells: CellEdits, onPick: (Picked) -> Unit) {
    val layers = ui.project.layers
    if (layers.isEmpty() && ui.mode != CollageMode.FREEFORM) Hint(R.string.collage_layers_empty)
    layers.asReversed().forEach { layer ->
        val name = when (layer) {
            is TextLayer -> layer.text
            is StickerLayer -> layer.emoji ?: stringResource(R.string.collage_sticker)
            else -> stringResource(R.string.collage_shape_layer)
        }
        LayerRow(name, ui.pickedLayer == layer.id, { onPick(Picked.Layer(layer.id)) }, {
            cells.moveLayer(layer.id, 1)
        }, { cells.moveLayer(layer.id, -1) }, { cells.deleteLayer(layer.id) })
    }
    if (ui.mode == CollageMode.FREEFORM) {
        Heading(R.string.collage_photos)
        ui.project.cells.indices.reversed().forEach { i ->
            LayerRow(stringResource(R.string.collage_photo_n, i + 1), ui.pickedCell == i, {
                onPick(Picked.Cell(i))
            }, {
                onPick(Picked.Cell(cells.order(i, 1)))
            }, { onPick(Picked.Cell(cells.order(i, -1))) }, null)
        }
    }
}

@Composable
private fun LayerRow(
    name: String,
    picked: Boolean,
    onPick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = picked, role = Role.Button, onClick = onPick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            color = if (picked) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onUp) {
            Icon(
                painterResource(DesignR.drawable.ic_arrow_upward),
                stringResource(R.string.collage_layer_up, name)
            )
        }
        IconButton(onClick = onDown) {
            Icon(
                painterResource(DesignR.drawable.ic_arrow_downward),
                stringResource(R.string.collage_layer_down, name)
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(DesignR.drawable.ic_delete),
                    stringResource(R.string.collage_delete_named, name)
                )
            }
        }
    }
}

// --- Labels ------------------------------------------------------------------------------------

internal fun modeLabel(mode: CollageMode): Int = when (mode) {
    CollageMode.GRID -> R.string.collage_mode_grid
    CollageMode.SMART -> R.string.collage_mode_smart
    CollageMode.FREEFORM -> R.string.collage_mode_freeform
    CollageMode.STITCH -> R.string.collage_mode_stitch
    CollageMode.SHAPES -> R.string.collage_mode_shapes
    CollageMode.STORY -> R.string.collage_mode_story
    CollageMode.BEFORE_AFTER -> R.string.collage_mode_before_after
}

private fun shapeLabel(shape: ShapeOutline): Int = when (shape) {
    ShapeOutline.HEART -> R.string.collage_heart
    ShapeOutline.CIRCLE -> R.string.collage_circle
    ShapeOutline.HEXAGON -> R.string.collage_hexagon
    ShapeOutline.LETTERS -> R.string.collage_letters
}

@Composable
private fun aspectLabel(aspect: CanvasAspect, w: Int, h: Int): String = when (aspect) {
    CanvasAspect.A4 -> stringResource(R.string.collage_a4)
    CanvasAspect.LETTER -> stringResource(R.string.collage_letter)
    CanvasAspect.CUSTOM -> stringResource(R.string.collage_custom_size, w, h)
    CanvasAspect.AUTO -> stringResource(R.string.collage_auto)
    else -> "${aspect.width}:${aspect.height}"
}

private fun backgroundLabel(kind: BackgroundKind): Int = when (kind) {
    BackgroundKind.SOLID -> R.string.collage_bg_color
    BackgroundKind.GRADIENT -> R.string.collage_bg_gradient
    BackgroundKind.BLURRED_PHOTO -> R.string.collage_bg_blurred
    BackgroundKind.PATTERN -> R.string.collage_bg_pattern
    BackgroundKind.TRANSPARENT -> R.string.collage_bg_none
}

private fun patternLabel(pattern: PatternKind): Int = when (pattern) {
    PatternKind.DOTS -> R.string.collage_pattern_dots
    PatternKind.STRIPES -> R.string.collage_pattern_stripes
    PatternKind.CHECKS -> R.string.collage_pattern_checks
    PatternKind.GRID -> R.string.collage_pattern_grid
}

private fun frameLabel(style: FrameStyle): Int = when (style) {
    FrameStyle.NONE -> R.string.collage_frame_none
    FrameStyle.SOLID -> R.string.collage_frame_solid
    FrameStyle.POLAROID -> R.string.collage_frame_polaroid
    FrameStyle.ROUNDED -> R.string.collage_frame_rounded
}

/** A size as a fraction of the short edge, shown in thousandths (0 to 100 for 0 to 0.1). */
internal fun plainScaled(value: Float): String = plain(value * SCALE_SHOWN)

private object CanvasBackgroundDefaults {
    const val ANGLE = 90f
}

private val ASPECTS = listOf(
    CanvasAspect.SQUARE,
    CanvasAspect.FOUR_FIVE,
    CanvasAspect.THREE_FOUR,
    CanvasAspect.NINE_SIXTEEN,
    CanvasAspect.SIXTEEN_NINE,
    CanvasAspect.THREE_TWO,
    CanvasAspect.A4,
    CanvasAspect.LETTER,
    CanvasAspect.CUSTOM
)

private const val TEMPLATE_DP = 56
private const val MAX_GAP = 0.08f
private const val MAX_MARGIN = 0.12f
private const val MAX_RADIUS = 0.2f
private const val MAX_BORDER = 0.04f
private const val MIN_FRAME = 0.01f
private const val MAX_FRAME = 0.1f
private const val FULL_TURN = 360f
private const val SCALE_SHOWN = 10f
