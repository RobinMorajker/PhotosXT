package com.photoxt.feature.collage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Filter
import com.photoxt.core.model.FontChoice
import com.photoxt.core.model.Layer
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.TextAlignment
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.Looks
import java.util.Locale

// The Cell panel (spec §4.11, Cells) and the Text and Stickers panels (Extras).

/** The picked photo's cell; [onReplace] opens the picker, for "replace a photo". */
@Composable
internal fun CellPanel(ui: CollageUi, cells: CellEdits, onReplace: (Int) -> Unit) {
    val index = ui.pickedCell
    val cell = index?.let { ui.project.cells.getOrNull(it) }
    if (index == null || cell == null) {
        Hint(R.string.collage_cell_hint)
        return
    }
    ButtonRow {
        ActionButton(R.string.collage_replace) { onReplace(index) }
        ActionButton(R.string.collage_flip_h) { cells.flip(index, horizontal = true) }
        ActionButton(R.string.collage_flip_v) { cells.flip(index, horizontal = false) }
        ActionButton(R.string.collage_turn) { cells.turn(index) }
        ActionButton(R.string.collage_reset_cell) { cells.reset(index) }
    }
    val done = { cells.commit(R.string.collage_step_photo) }
    val free = cell.free
    if (ui.mode == CollageMode.FREEFORM && free != null) {
        SliderRow(R.string.collage_size, free.width, { v ->
            cells.cell(index, null) { it.copy(free = free.copy(width = v)) }
        }, done, MIN_FREE..MAX_FREE, zero = DEFAULT_FREE, shown = ::plain)
        SliderRow(R.string.collage_turning, free.rotation, { v ->
            cells.cell(index, null) { it.copy(free = free.copy(rotation = v)) }
        }, done, -HALF_TURN..HALF_TURN, shown = ::degrees)
    } else {
        SliderRow(
            R.string.collage_zoom,
            cell.zoom,
            { v ->
                cells.cell(index, null) { it.copy(zoom = v) }
            },
            done,
            1f..MAX_ZOOM,
            zero = 1f,
            shown = ::times
        )
        SliderRow(
            R.string.collage_turning,
            cell.rotation,
            { v ->
                cells.cell(index, null) { it.copy(rotation = v) }
            },
            done,
            -HALF_TURN..HALF_TURN,
            shown = ::degrees
        )
    }
    LookControls(index, cell, cells)
}

/** A cell's filter and adjustments (spec §4.11), drawn by the editor's pipeline. */
@Composable
private fun LookControls(index: Int, cell: CollageCell, cells: CellEdits) {
    val look = cell.look ?: EditRecipe()
    fun change(label: Int?, edit: (EditRecipe) -> EditRecipe) = cells.cell(index, label) {
        it.copy(look = edit(it.look ?: EditRecipe()).takeUnless { r -> r.isIdentity })
    }
    val done = { cells.commit(R.string.collage_step_look) }
    Heading(R.string.collage_filter)
    ChipRow(listOf<String?>(null) + Looks.ids, look.filter?.look, {
        stringResource(lookLabel(it))
    }) { id ->
        change(R.string.collage_step_look) { r -> r.copy(filter = id?.let { Filter(look = it) }) }
    }
    look.filter?.let { filter ->
        SliderRow(R.string.collage_intensity, filter.intensity, { v ->
            change(null) { r -> r.copy(filter = filter.copy(intensity = v)) }
        }, done, 0f..1f, zero = 1f, shown = ::plain)
    }
    Heading(R.string.collage_adjust)
    LookSlider(R.string.collage_brightness, look.light.brightness, done) { v ->
        change(null) { it.copy(light = it.light.copy(brightness = v)) }
    }
    LookSlider(R.string.collage_contrast, look.light.contrast, done) { v ->
        change(null) { it.copy(light = it.light.copy(contrast = v)) }
    }
    LookSlider(R.string.collage_highlights, look.light.highlights, done) { v ->
        change(null) { it.copy(light = it.light.copy(highlights = v)) }
    }
    LookSlider(R.string.collage_shadows, look.light.shadows, done) { v ->
        change(null) { it.copy(light = it.light.copy(shadows = v)) }
    }
    LookSlider(R.string.collage_saturation, look.color.saturation, done) { v ->
        change(null) { it.copy(color = it.color.copy(saturation = v)) }
    }
    LookSlider(R.string.collage_warmth, look.color.temperature, done) { v ->
        change(null) { it.copy(color = it.color.copy(temperature = v)) }
    }
    SliderRow(R.string.collage_sharpen, look.effects.sharpen, { v ->
        change(null) { it.copy(effects = it.effects.copy(sharpen = v)) }
    }, done, 0f..1f, shown = ::plain)
    LookSlider(R.string.collage_vignette, look.effects.vignette, done) { v ->
        change(null) { it.copy(effects = it.effects.copy(vignette = v)) }
    }
    ButtonRow {
        ActionButton(R.string.collage_look_all, enabled = !look.isIdentity) {
            cells.lookForAll(index)
        }
    }
}

@Composable
private fun LookSlider(label: Int, value: Float, done: () -> Unit, onChange: (Float) -> Unit) =
    SliderRow(label, value, onChange, done)

@Composable
internal fun TextPanel(
    ui: CollageUi,
    cells: CellEdits,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit
) {
    ButtonRow { ActionButton(R.string.collage_add_text, onClick = onAdd) }
    val layer = ui.project.layers.firstOrNull { it.id == ui.pickedLayer } as? TextLayer
    if (layer == null) {
        Hint(R.string.collage_text_hint)
        return
    }
    fun change(label: Int?, edit: (TextLayer) -> TextLayer) =
        cells.layer(layer.id, label) { (it as? TextLayer)?.let(edit) ?: it }
    ButtonRow {
        ActionButton(R.string.collage_edit_text) { onEdit(layer.id) }
        ActionButton(R.string.collage_delete) { cells.deleteLayer(layer.id) }
    }
    ChipRow(FontChoice.entries, layer.font, { stringResource(fontLabel(it)) }) { f ->
        change(R.string.collage_step_text) { it.copy(font = f) }
    }
    SliderRow(R.string.collage_size, layer.size, { v -> change(null) { it.copy(size = v) } }, {
        cells.commit(R.string.collage_step_text)
    }, MIN_TEXT..MAX_TEXT, zero = DEFAULT_TEXT, shown = ::plainScaled)
    ColorRow(layer.color) { c -> change(R.string.collage_step_text) { it.copy(color = c) } }
    ChipRow(TextAlignment.entries, layer.align, { stringResource(alignLabel(it)) }) { a ->
        change(R.string.collage_step_text) { it.copy(align = a) }
    }
    ChipRow(TextStyle.entries, null, { stringResource(it.label) }) { style ->
        change(R.string.collage_step_text) { style.toggle(it) }
    }
}

/** The text's look toggles, each a chip. */
private enum class TextStyle(val label: Int, val toggle: (TextLayer) -> TextLayer) {
    BOLD(R.string.collage_bold, { it.copy(bold = !it.bold) }),
    OUTLINE(R.string.collage_outline, {
        it.copy(
            outlineColor = if (it.outlineColor ==
                null
            ) {
                BLACK
            } else {
                null
            }
        )
    }),
    SHADOW(R.string.collage_text_shadow, { it.copy(shadow = !it.shadow) }),
    BACKGROUND(R.string.collage_text_background, {
        it.copy(
            background = if (it.background ==
                null
            ) {
                SHADE
            } else {
                null
            }
        )
    })
}

@Composable
internal fun StickerPanel(ui: CollageUi, cells: CellEdits, onPick: (Picked) -> Unit) {
    val picked = ui.project.layers.firstOrNull { it.id == ui.pickedLayer } as? StickerLayer
    if (picked != null) {
        SliderRow(R.string.collage_size, picked.size, { v ->
            cells.layer(picked.id, null) { (it as? StickerLayer)?.copy(size = v) ?: it }
        }, {
            cells.commit(R.string.collage_step_sticker)
        }, MIN_STICKER..MAX_STICKER, zero = DEFAULT_STICKER, shown = ::plainScaled)
        ButtonRow { ActionButton(R.string.collage_delete) { cells.deleteLayer(picked.id) } }
    }
    Hint(R.string.collage_sticker_hint)
    EMOJI.chunked(EMOJI_PER_ROW).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { emoji ->
                val add = stringResource(R.string.collage_add_sticker_named, emoji)
                Box(
                    Modifier
                        .minimumInteractiveComponentSize()
                        .size(EMOJI_DP.dp)
                        .semantics { contentDescription = add }
                        .clickable(role = Role.Button) {
                            onPick(Picked.Layer(cells.addSticker(emoji)))
                        },
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = EMOJI_SP.sp) }
            }
        }
    }
}

private fun lookLabel(id: String?): Int = when (id) {
    null -> R.string.look_none
    "vivid" -> R.string.look_vivid
    "warm" -> R.string.look_warm
    "cool" -> R.string.look_cool
    "mono" -> R.string.look_mono
    "noir" -> R.string.look_noir
    "sepia" -> R.string.look_sepia
    "matte" -> R.string.look_matte
    "vintage" -> R.string.look_vintage
    "teal_orange" -> R.string.look_teal_orange
    "pastel" -> R.string.look_pastel
    else -> R.string.look_other
}

private fun fontLabel(font: FontChoice): Int = when (font) {
    FontChoice.SANS -> R.string.font_sans
    FontChoice.SERIF -> R.string.font_serif
    FontChoice.MONO -> R.string.font_mono
    FontChoice.CONDENSED -> R.string.font_condensed
    FontChoice.HANDWRITING -> R.string.font_handwriting
    FontChoice.DEVANAGARI_SANS -> R.string.font_devanagari_sans
    FontChoice.DEVANAGARI_SERIF -> R.string.font_devanagari_serif
}

private fun alignLabel(align: TextAlignment): Int = when (align) {
    TextAlignment.START -> R.string.collage_align_start
    TextAlignment.CENTER -> R.string.collage_align_center
    TextAlignment.END -> R.string.collage_align_end
}

/** Whether a layer is text, for the screen's dialogs. */
internal val Layer.text: String? get() = (this as? TextLayer)?.text

/** A zoom, such as 1.5×. */
private fun times(value: Float): String = String.format(Locale.getDefault(), "%.1f×", value)

private val EMOJI = listOf(
    "❤️", "😍", "😂", "🥳", "😎", "🤩", "😘", "🙏",
    "👍", "👏", "🎉", "🎂", "🎁", "🎈", "✨", "⭐",
    "🌸", "🌺", "🌻", "🍀", "🌈", "☀️", "🌙", "🔥",
    "🏖️", "⛰️", "✈️", "🚗", "📸", "🎵", "🍕", "☕",
    "🐶", "🐱", "🦋", "💯", "🪔", "🇮🇳", "💐", "🥂"
)

private const val EMOJI_PER_ROW = 8
private const val EMOJI_DP = 40
private const val EMOJI_SP = 24
private const val MIN_FREE = 0.08f
private const val MAX_FREE = 1.5f
private const val DEFAULT_FREE = 0.45f
private const val MAX_ZOOM = 5f
private const val HALF_TURN = 180f
private const val MIN_TEXT = 0.02f
private const val MAX_TEXT = 0.3f
private const val DEFAULT_TEXT = 0.07f
private const val MIN_STICKER = 0.05f
private const val MAX_STICKER = 0.8f
private const val DEFAULT_STICKER = 0.18f
private const val BLACK = -0x1000000
private const val SHADE = -0x67000000
