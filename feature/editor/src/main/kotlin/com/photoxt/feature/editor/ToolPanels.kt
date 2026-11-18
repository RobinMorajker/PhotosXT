package com.photoxt.feature.editor

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.ml.EraserModel
import com.photoxt.core.model.BackgroundMode
import com.photoxt.core.model.ColorAdjust
import com.photoxt.core.model.CropAspect
import com.photoxt.core.model.Effects
import com.photoxt.core.model.HslBand
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.Light
import com.photoxt.core.model.LocalAdjust
import com.photoxt.core.model.LocalMask
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.RedactStyle
import com.photoxt.core.render.Looks
import java.util.Locale
import kotlin.math.roundToInt

/** The settings of [tool], above the tool row; they scroll when they don't fit (200% text). */
@Composable
internal fun ToolPanel(
    tool: Tool,
    state: EditorState,
    tools: EditorTools,
    onDialog: (EditorDialog) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(PANEL))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(toolLabel(tool)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = tools.history::resetTool) {
                Text(stringResource(R.string.editor_reset))
            }
        }
        when (tool) {
            Tool.TRANSFORM -> TransformPanel(state, tools, onDialog)
            Tool.LIGHT -> LightPanel(state, tools)
            Tool.COLOR -> ColorPanel(state, tools)
            Tool.DETAIL -> DetailPanel(state, tools)
            Tool.FILTERS -> FiltersPanel(state, tools, onDialog)
            Tool.TEXT -> TextPanel(state, tools, onDialog)
            Tool.DRAW -> DrawPanel(state, tools)
            Tool.STICKERS -> StickersPanel(state, tools)
            Tool.FRAME -> FramePanel(state, tools)
            Tool.WATERMARK -> WatermarkPanel(state, tools, onDialog)
            Tool.REDACT -> RedactPanel(state, tools)
            Tool.SELECTIVE -> SelectivePanel(state, tools)
            Tool.RETOUCH -> RetouchPanel(state, tools)
            Tool.CUTOUT -> CutoutPanel(state, tools)
            Tool.ERASER -> EraserPanel(state, tools)
        }
    }
}

// --- Transform ---------------------------------------------------------------------------

@Composable
private fun TransformPanel(
    state: EditorState,
    tools: EditorTools,
    onDialog: (EditorDialog) -> Unit
) {
    val g = state.recipe.geometry
    ChipRow(CropAspect.entries, g.aspect, { stringResource(aspectLabel(it)) }) {
        when (it) {
            CropAspect.CUSTOM -> onDialog(EditorDialog.CROP_RATIO)
            else -> tools.transform.setAspect(it)
        }
    }
    ButtonRow {
        BarIcon(DesignR.drawable.ic_rotate, R.string.editor_rotate) { tools.transform.rotate() }
        BarIcon(DesignR.drawable.ic_flip, R.string.editor_flip_h) { tools.transform.flip(true) }
        TextButton(onClick = { tools.transform.flip(false) }) {
            Text(stringResource(R.string.editor_flip_v))
        }
        FilterChip(
            selected = state.cornersMode,
            onClick = { tools.transform.setCorners(!state.cornersMode) },
            label = { Text(stringResource(R.string.editor_corners)) }
        )
    }
    RecipeSlider(
        tools,
        R.string.editor_straighten,
        R.string.editor_step_straighten,
        g.straighten,
        range = -MAX_STRAIGHTEN..MAX_STRAIGHTEN,
        shown = ::degrees
    ) { copy(geometry = geometry.copy(straighten = it)) }
    // The sliders and the dragged corners are two ways to the same end: one clears the other.
    val step = R.string.editor_step_perspective
    RecipeSlider(tools, R.string.editor_keystone_v, step, g.keystoneV) {
        copy(geometry = geometry.copy(keystoneV = it, corners = null))
    }
    RecipeSlider(tools, R.string.editor_keystone_h, step, g.keystoneH) {
        copy(geometry = geometry.copy(keystoneH = it, corners = null))
    }
    if (state.cornersMode) Hint(R.string.editor_corners_hint)
}

private fun aspectLabel(aspect: CropAspect) = when (aspect) {
    CropAspect.FREE -> R.string.editor_aspect_free
    CropAspect.ORIGINAL -> R.string.editor_aspect_original
    CropAspect.SQUARE -> R.string.editor_aspect_square
    CropAspect.FOUR_FIVE -> R.string.editor_aspect_4_5
    CropAspect.THREE_FOUR -> R.string.editor_aspect_3_4
    CropAspect.NINE_SIXTEEN -> R.string.editor_aspect_9_16
    CropAspect.SIXTEEN_NINE -> R.string.editor_aspect_16_9
    CropAspect.CUSTOM -> R.string.editor_aspect_custom
}

// --- Light, colour, detail ---------------------------------------------------------------

@Composable
private fun LightPanel(state: EditorState, tools: EditorTools) {
    val l = state.recipe.light
    val step = R.string.editor_step_light

    @Composable
    fun Adjust(label: Int, value: Float, set: Light.(Float) -> Light) =
        RecipeSlider(tools, label, step, value) { copy(light = light.set(it)) }
    ButtonRow { ActionButton(R.string.editor_auto) { tools.look.autoEnhance() } }
    RecipeSlider(
        tools,
        R.string.editor_exposure,
        step,
        l.exposure,
        range = -MAX_EV..MAX_EV,
        shown = ::stops
    ) { copy(light = light.copy(exposure = it)) }
    Adjust(R.string.editor_brightness, l.brightness) { copy(brightness = it) }
    Adjust(R.string.editor_contrast, l.contrast) { copy(contrast = it) }
    Adjust(R.string.editor_highlights, l.highlights) { copy(highlights = it) }
    Adjust(R.string.editor_shadows, l.shadows) { copy(shadows = it) }
    Adjust(R.string.editor_whites, l.whites) { copy(whites = it) }
    Adjust(R.string.editor_blacks, l.blacks) { copy(blacks = it) }
}

@Composable
private fun ColorPanel(state: EditorState, tools: EditorTools) {
    val c = state.recipe.color
    val step = R.string.editor_step_color

    @Composable
    fun Adjust(label: Int, value: Float, set: ColorAdjust.(Float) -> ColorAdjust) =
        RecipeSlider(tools, label, step, value) { copy(color = color.set(it)) }
    Adjust(R.string.editor_saturation, c.saturation) { copy(saturation = it) }
    Adjust(R.string.editor_vibrance, c.vibrance) { copy(vibrance = it) }
    Adjust(R.string.editor_temperature, c.temperature) { copy(temperature = it) }
    Adjust(R.string.editor_tint, c.tint) { copy(tint = it) }
    RecipeSlider(tools, R.string.editor_hue, step, c.hue, shown = ::halfTurns) {
        copy(color = color.copy(hue = it))
    }
    Heading(R.string.editor_hsl)
    HslMix(state, tools)
    Heading(R.string.editor_curves)
    ChipRow(CurveChannel.entries, state.curve, { stringResource(it.label) }) {
        tools.look.setCurve(it)
    }
    CurveEditor(state, tools)
}

/** Hue, saturation and luminance of one colour range at a time (spec §4.10: "HSL per colour"). */
@Composable
private fun HslMix(state: EditorState, tools: EditorTools) {
    val range = state.hslColor
    val band = state.recipe.color.hsl[range] ?: HslBand()

    @Composable
    fun Adjust(label: Int, value: Float, set: HslBand.(Float) -> HslBand) =
        RecipeSlider(tools, label, R.string.editor_step_color, value) {
            val changed = (color.hsl[range] ?: HslBand()).set(it)
            copy(color = color.copy(hsl = color.hsl + (range to changed)))
        }
    ChipRow(HslColor.entries, range, { stringResource(hslLabel(it)) }) {
        tools.look.setHslColor(it)
    }
    Adjust(R.string.editor_hsl_hue, band.hue) { copy(hue = it) }
    Adjust(R.string.editor_hsl_saturation, band.saturation) { copy(saturation = it) }
    Adjust(R.string.editor_hsl_luminance, band.luminance) { copy(luminance = it) }
}

/** The hue slider's −1 to 1 as the half turn of the colour wheel it is: −180° to +180°. */
private fun halfTurns(value: Float): String =
    String.format(Locale.getDefault(), "%+d°", (value * HALF_TURN).roundToInt())

private fun hslLabel(color: HslColor) = when (color) {
    HslColor.RED -> R.string.editor_red
    HslColor.ORANGE -> R.string.editor_orange
    HslColor.YELLOW -> R.string.editor_yellow
    HslColor.GREEN -> R.string.editor_green
    HslColor.AQUA -> R.string.editor_aqua
    HslColor.BLUE -> R.string.editor_blue
    HslColor.PURPLE -> R.string.editor_purple
    HslColor.MAGENTA -> R.string.editor_magenta
}

@Composable
private fun DetailPanel(state: EditorState, tools: EditorTools) {
    val e = state.recipe.effects

    /** Sharpen, grain and fade only add, so they start [from] 0; the others go both ways. */
    @Composable
    fun Adjust(label: Int, value: Float, from: Float = -1f, set: Effects.(Float) -> Effects) =
        RecipeSlider(tools, label, R.string.editor_step_detail, value, range = from..1f) {
            copy(effects = effects.set(it))
        }
    Adjust(R.string.editor_sharpen, e.sharpen, from = 0f) { copy(sharpen = it) }
    Adjust(R.string.editor_clarity, e.clarity) { copy(clarity = it) }
    Adjust(R.string.editor_dehaze, e.dehaze) { copy(dehaze = it) }
    Adjust(R.string.editor_vignette, e.vignette) { copy(vignette = it) }
    Adjust(R.string.editor_grain, e.grain, from = 0f) { copy(grain = it) }
    Adjust(R.string.editor_fade, e.fade, from = 0f) { copy(fade = it) }
}

// --- Filters -----------------------------------------------------------------------------

@Composable
private fun FiltersPanel(state: EditorState, tools: EditorTools, onDialog: (EditorDialog) -> Unit) {
    val filter = state.recipe.filter
    val importCube = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(tools.look::importLut)
    }
    // "None" is the null look; a LUT filter has no look, so no chip of this row is lit.
    val look = if (filter != null && filter.look == null) NO_LOOK else filter?.look
    ChipRow(listOf<String?>(null) + Looks.ids, look, { lookLabel(it) }) { tools.look.setLook(it) }
    if (state.luts.isNotEmpty()) {
        RemovableChips(
            items = state.luts,
            selected = state.luts.firstOrNull { it.asset == filter?.lut },
            name = { it.name },
            onPick = { tools.look.setLut(it.asset) },
            onRemove = tools.look::deleteLut
        )
    }
    if (filter != null) {
        RecipeSlider(
            tools,
            R.string.editor_intensity,
            R.string.editor_step_filter,
            filter.intensity,
            range = 0f..1f,
            zero = 1f,
            shown = ::percent
        ) { copy(filter = this.filter?.copy(intensity = it)) }
    }
    ButtonRow {
        ActionButton(R.string.editor_import_lut) { importCube.launch(arrayOf("*/*")) }
        ActionButton(R.string.editor_save_preset) { onDialog(EditorDialog.SAVE_PRESET) }
    }
    if (state.presets.isNotEmpty()) {
        Heading(R.string.editor_presets)
        RemovableChips(
            items = state.presets,
            selected = null,
            name = { it.name },
            onPick = tools.look::applyPreset,
            onRemove = tools.look::deletePreset
        )
    }
}

@Composable
private fun lookLabel(id: String?): String = stringResource(
    when (id) {
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
        else -> R.string.look_lut
    }
)

// --- Redact ------------------------------------------------------------------------------

@Composable
private fun RedactPanel(state: EditorState, tools: EditorTools) {
    val count = state.recipe.redactions.size
    ChipRow(RedactStyle.entries, state.redactStyle, { stringResource(redactLabel(it)) }) {
        tools.redact.setRedactStyle(it)
    }
    if (state.redactStyle == RedactStyle.SOLID) {
        ColorRow(state.redactColor, tools, tools.draw::picked)
    }
    ButtonRow {
        ActionButton(R.string.editor_find_redactions, enabled = state.busy == null) {
            tools.redact.findRedactions()
        }
    }
    Hint(R.string.editor_redact_hint)
    if (count > 0) {
        Text(
            pluralStringResource(R.plurals.editor_redactions, count, count),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun redactLabel(style: RedactStyle) = when (style) {
    RedactStyle.BLUR -> R.string.redact_blur
    RedactStyle.PIXELATE -> R.string.redact_pixelate
    RedactStyle.SOLID -> R.string.redact_solid
}

// --- Selective ---------------------------------------------------------------------------

@Composable
private fun SelectivePanel(state: EditorState, tools: EditorTools) {
    val masks = state.recipe.masks
    ButtonRow {
        MaskKind.entries.forEach { kind ->
            ActionButton(maskLabel(kind), enabled = state.busy == null) {
                tools.masks.addMask(kind)
            }
        }
    }
    if (masks.isEmpty()) {
        Hint(R.string.editor_mask_hint)
        return
    }
    val mask = masks.firstOrNull { it.id == state.selectedMask }
    ChipRow(masks, mask, { "${stringResource(maskLabel(it.kind))} ${masks.indexOf(it) + 1}" }) {
        tools.masks.selectMask(it.id)
    }
    if (mask == null) return
    ButtonRow {
        FilterChip(
            selected = mask.invert,
            onClick = {
                tools.masks.updateMask(mask.id, R.string.editor_step_mask) {
                    it.copy(invert = !it.invert)
                }
            },
            label = { Text(stringResource(R.string.editor_invert)) }
        )
        ActionButton(R.string.editor_delete_mask) { tools.masks.deleteMask(mask.id) }
    }
    MaskShape(mask, state, tools)
    MaskAdjust(mask, tools)
}

/** A brush mask's brush, or a radial mask's size and softness; the others have no settings. */
@Composable
private fun MaskShape(mask: LocalMask, state: EditorState, tools: EditorTools) {
    @Composable
    fun Shape(
        label: Int,
        value: Float,
        from: Float,
        zero: Float,
        set: LocalMask.(Float) -> LocalMask
    ) {
        SliderRow(
            label = label,
            value = value,
            onChange = { v -> tools.masks.updateMask(mask.id, null) { it.set(v) } },
            onDone = { tools.history.commit(R.string.editor_step_mask) },
            range = from..1f,
            zero = zero,
            shown = ::plain
        )
    }
    when (mask.kind) {
        MaskKind.BRUSH -> {
            ChipRow(listOf(false, true), state.maskErase, { stringResource(brushModeLabel(it)) }) {
                tools.masks.setMaskBrush(erase = it)
            }
            SliderRow(
                label = R.string.editor_brush_size,
                value = state.maskBrushSize,
                onChange = { tools.masks.setMaskBrush(size = it) },
                onDone = {},
                range = MIN_BRUSH..MAX_BRUSH,
                zero = DEFAULT_MASK_BRUSH,
                shown = ::plain
            )
        }

        MaskKind.RADIAL -> {
            Shape(R.string.editor_radius_x, mask.radiusX, MIN_RADIUS, DEFAULT_RADIUS) {
                copy(radiusX = it)
            }
            Shape(R.string.editor_radius_y, mask.radiusY, MIN_RADIUS, DEFAULT_RADIUS) {
                copy(radiusY = it)
            }
            Shape(R.string.editor_feather, mask.feather, MIN_FEATHER, DEFAULT_FEATHER) {
                copy(feather = it)
            }
        }

        else -> Unit
    }
}

/** What the mask changes where it covers the photo. */
@Composable
private fun MaskAdjust(mask: LocalMask, tools: EditorTools) {
    val a = mask.adjust

    @Composable
    fun Adjust(
        label: Int,
        value: Float,
        reach: Float = 1f,
        shown: (Float) -> String = ::signed,
        set: LocalAdjust.(Float) -> LocalAdjust
    ) = SliderRow(
        label = label,
        value = value,
        onChange = { v ->
            tools.masks.updateMask(mask.id, null) { it.copy(adjust = it.adjust.set(v)) }
        },
        onDone = { tools.history.commit(R.string.editor_step_mask) },
        range = -reach..reach,
        shown = shown
    )
    Adjust(R.string.editor_exposure, a.exposure, MAX_LOCAL_EV, ::stops) { copy(exposure = it) }
    Adjust(R.string.editor_contrast, a.contrast) { copy(contrast = it) }
    Adjust(R.string.editor_highlights, a.highlights) { copy(highlights = it) }
    Adjust(R.string.editor_shadows, a.shadows) { copy(shadows = it) }
    Adjust(R.string.editor_saturation, a.saturation) { copy(saturation = it) }
    Adjust(R.string.editor_temperature, a.temperature) { copy(temperature = it) }
    Adjust(R.string.editor_tint, a.tint) { copy(tint = it) }
    Adjust(R.string.editor_clarity, a.clarity) { copy(clarity = it) }
}

/** A brush mask's brush adds to the mask, or rubs it out. */
private fun brushModeLabel(erase: Boolean) =
    if (erase) R.string.editor_brush_erase else R.string.editor_brush_paint

private fun maskLabel(kind: MaskKind) = when (kind) {
    MaskKind.BRUSH -> R.string.mask_brush
    MaskKind.LINEAR -> R.string.mask_linear
    MaskKind.RADIAL -> R.string.mask_radial
    MaskKind.SUBJECT -> R.string.mask_subject
    MaskKind.BACKGROUND -> R.string.mask_background
}

// --- Retouch, cut-out, eraser ------------------------------------------------------------

@Composable
private fun RetouchPanel(state: EditorState, tools: EditorTools) {
    val r = state.recipe.retouch
    val retouch = tools.retouch
    ButtonRow {
        ActionButton(R.string.editor_red_eye_auto, enabled = state.busy == null) {
            retouch.autoRedEye()
        }
    }
    Hint(R.string.editor_red_eye_hint)
    // Both find faces or the subject first, so they go through the controller, not the recipe.
    SliderRow(
        label = R.string.editor_skin,
        value = r.skinSmoothing,
        onChange = { retouch.setSkinSmoothing(it, done = false) },
        onDone = { retouch.setSkinSmoothing(state.recipe.retouch.skinSmoothing, done = true) },
        range = 0f..1f,
        shown = ::plain
    )
    SliderRow(
        label = R.string.editor_background_blur,
        value = r.backgroundBlur,
        onChange = { retouch.setBackgroundBlur(it, done = false) },
        onDone = { retouch.setBackgroundBlur(state.recipe.retouch.backgroundBlur, done = true) },
        range = 0f..1f,
        shown = ::plain
    )
}

@Composable
private fun CutoutPanel(state: EditorState, tools: EditorTools) {
    val background = state.recipe.background
    val idle = state.busy == null
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { it?.let(tools.retouch::setBackgroundImage) }
    ChipRow(BackgroundMode.entries, background.mode, { stringResource(backgroundLabel(it)) }) {
        if (it == BackgroundMode.IMAGE) {
            val images = ActivityResultContracts.PickVisualMedia.ImageOnly
            pickPicture.launch(PickVisualMediaRequest(images))
        } else {
            tools.retouch.setBackground(it)
        }
    }
    if (background.mode == BackgroundMode.COLOR) {
        ColorRow(background.color, tools) {
            tools.retouch.setBackground(BackgroundMode.COLOR, it)
        }
    }
    if (background.mode == BackgroundMode.REMOVE) Hint(R.string.editor_remove_hint)
    Heading(R.string.editor_lift)
    ButtonRow {
        ActionButton(R.string.editor_lift_save, idle) { tools.retouch.liftSubject(LiftAction.SAVE) }
        ActionButton(R.string.editor_lift_sticker, idle) {
            tools.retouch.liftSubject(LiftAction.STICKER)
        }
        ActionButton(R.string.editor_lift_copy, idle) {
            tools.retouch.liftSubject(LiftAction.CLIPBOARD)
        }
    }
}

private fun backgroundLabel(mode: BackgroundMode) = when (mode) {
    BackgroundMode.KEEP -> R.string.background_keep
    BackgroundMode.REMOVE -> R.string.background_remove
    BackgroundMode.COLOR -> R.string.background_color
    BackgroundMode.BLUR -> R.string.background_blur
    BackgroundMode.IMAGE -> R.string.background_image
}

@Composable
private fun EraserPanel(state: EditorState, tools: EditorTools) {
    if (!state.modelReady) {
        ModelDownload(stringResource(R.string.model_migan), state, tools)
        return
    }
    val painted = state.pendingErase.isNotEmpty()
    SliderRow(
        label = R.string.editor_brush_size,
        value = state.eraserSize,
        onChange = tools.eraser::setEraserSize,
        onDone = {},
        range = MIN_BRUSH..MAX_BRUSH,
        zero = DEFAULT_ERASER,
        shown = ::plain
    )
    Hint(R.string.editor_erase_hint)
    ButtonRow {
        ActionButton(R.string.editor_erase, enabled = painted && state.busy == null) {
            tools.eraser.erase()
        }
        ActionButton(R.string.editor_erase_clear, enabled = painted) { tools.eraser.clearErase() }
    }
}

/** The eraser's model is downloaded once, when the user asks (spec §4.10: on-device model). */
@Composable
private fun ModelDownload(model: String, state: EditorState, tools: EditorTools) {
    val size = Formatter.formatShortFileSize(LocalContext.current, EraserModel.MIGAN.bytes)
    val progress = state.modelProgress
    Text(
        stringResource(R.string.editor_model_needed, model, size),
        style = MaterialTheme.typography.bodyMedium
    )
    if (progress == null) {
        ButtonRow { ActionButton(R.string.editor_model_download) { tools.eraser.downloadModel() } }
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

private const val PANEL = 0xFF181818
private const val NO_LOOK = ""
private const val MAX_STRAIGHTEN = 45f
private const val MAX_EV = 3f
private const val MAX_LOCAL_EV = 2f
private const val HALF_TURN = 180
private const val MIN_BRUSH = 0.01f
private const val MAX_BRUSH = 0.25f
private const val DEFAULT_MASK_BRUSH = 0.06f
private const val DEFAULT_ERASER = 0.05f
private const val MIN_RADIUS = 0.02f
private const val DEFAULT_RADIUS = 0.2f
private const val MIN_FEATHER = 0.05f
private const val DEFAULT_FEATHER = 0.5f
