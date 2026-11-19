package com.photoxt.feature.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import com.photoxt.core.common.PxtLog
import com.photoxt.core.datastore.LutEntry
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.ml.EraserModel
import com.photoxt.core.ml.EraserModels
import com.photoxt.core.ml.Inpainter
import com.photoxt.core.ml.PhotoDetectors
import com.photoxt.core.model.Background
import com.photoxt.core.model.BackgroundMode
import com.photoxt.core.model.BrushTool
import com.photoxt.core.model.Circle01
import com.photoxt.core.model.CropAspect
import com.photoxt.core.model.EditPreset
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Filter
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.Layer
import com.photoxt.core.model.LocalMask
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.MaskStroke
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.Point01
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.RedactStyle
import com.photoxt.core.model.Redaction
import com.photoxt.core.model.ShapeKind
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.Stroke
import com.photoxt.core.model.TextLayer
import com.photoxt.core.model.Watermark
import com.photoxt.core.render.AutoEnhance
import com.photoxt.core.render.CubeLut
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.MAX_MASKS
import com.photoxt.core.render.MAX_REDACTIONS
import com.photoxt.core.render.OverlayPainter
import com.photoxt.core.render.RenderGeometry
import com.photoxt.core.render.Size
import com.photoxt.core.transfer.EditClipboard
import com.photoxt.core.transfer.EditOps
import com.photoxt.core.transfer.Pasting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Makes the editor's tool controllers for one photo. */
class EditorToolsFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val assets: EditAssets,
    private val detectors: PhotoDetectors,
    private val models: EraserModels,
    private val inpainter: Inpainter,
    private val editOps: EditOps,
    private val settings: SettingsStore,
    private val clipboard: EditClipboard
) {
    fun create(core: EditorCore, mediaId: String): EditorTools {
        val detections = Detections(detectors)
        val subject = SubjectFinder(core, detections, assets)
        return EditorTools(
            history = core,
            transform = TransformController(core),
            look = LookController(core, context, assets, settings),
            layers = LayerController(core, context, assets, settings),
            draw = DrawController(core),
            redact = RedactController(core, detections),
            masks = MaskController(core, subject),
            retouch = RetouchController(
                core,
                detections,
                subject,
                context,
                assets,
                settings,
                editOps,
                mediaId
            ),
            eraser = EraserController(core, models, inpainter, assets, settings),
            output = OutputController(core, context, clipboard, editOps, settings, subject, mediaId)
        )
    }
}

/** What *Reset* puts back for each tool (spec §4.10: "reset per tool"). */
internal object Resets {
    fun of(tool: Tool, r: EditRecipe): EditRecipe {
        val none = EditRecipe()
        return when (tool) {
            Tool.TRANSFORM -> r.copy(geometry = Geometry())
            Tool.LIGHT -> r.copy(light = none.light)
            Tool.COLOR -> r.copy(color = none.color, curves = none.curves)
            Tool.DETAIL -> r.copy(effects = none.effects)
            Tool.FILTERS -> r.copy(filter = null)
            Tool.TEXT -> r.copy(layers = r.layers.filter { it !is TextLayer })
            Tool.DRAW -> r.copy(strokes = emptyList())
            Tool.STICKERS -> r.copy(layers = r.layers.filter { it is TextLayer })
            Tool.FRAME -> r.copy(frame = none.frame)
            Tool.WATERMARK -> r.copy(watermark = null)
            Tool.REDACT -> r.copy(redactions = emptyList())
            Tool.SELECTIVE -> r.copy(masks = emptyList())
            Tool.RETOUCH -> r.copy(retouch = none.retouch)
            Tool.CUTOUT -> r.copy(background = Background())
            Tool.ERASER -> r.copy(erasures = emptyList())
        }
    }
}

private fun newId() = UUID.randomUUID().toString().take(ID_CHARS)

private const val ID_CHARS = 8

/** Finds the subject mask once per photo, for masks, background blur and cut-out. */
internal class SubjectFinder(
    private val core: EditorCore,
    private val detections: Detections,
    private val assets: EditAssets
) {
    fun withSubject(then: () -> Unit) {
        if (core.recipe.subjectMask != null) {
            then()
            return
        }
        core.busy(R.string.editor_finding_subject) {
            val asset = detections.subjectMask(
                core.session.proxy(),
                core.session.orientation,
                assets
            )
            if (asset == null) {
                core.say(R.string.editor_found_no_subject)
                return@busy
            }
            core.edit(null) { it.copy(subjectMask = asset) }
            then()
        }
    }
}

internal class TransformController(private val core: EditorCore) : TransformActions {
    override fun frameSize(): Size = RenderGeometry(
        core.session.raster,
        core.session.orientation,
        core.recipe.geometry,
        1.0
    ).frame

    override fun rotate() = core.edit(R.string.editor_step_rotate) { r ->
        val g = r.geometry
        r.copy(
            geometry = g.copy(
                quarterTurns = (g.quarterTurns + 1) % TURNS,
                crop = Rect01.FULL,
                corners = null
            )
        )
    }

    override fun flip(horizontal: Boolean) = core.edit(R.string.editor_step_flip) { r ->
        val g = r.geometry
        r.copy(geometry = if (horizontal) g.copy(flipH = !g.flipH) else g.copy(flipV = !g.flipV))
    }

    override fun setAspect(aspect: CropAspect) = locked(aspect, custom = null)

    override fun setCustomAspect(ratio: Float) = locked(CropAspect.CUSTOM, ratio)

    /** The crop locked to [aspect], re-centred at its largest when it has a ratio. */
    private fun locked(aspect: CropAspect, custom: Float?) =
        core.edit(R.string.editor_step_crop) { recipe ->
            val g = recipe.geometry.copy(aspect = aspect, customRatio = custom)
            val frame = frameSize()
            val crop = g.lockedRatio(frame)?.let { RenderGeometry.centredCrop(frame, it) }
            recipe.copy(geometry = g.copy(crop = crop ?: g.crop))
        }

    override fun setCorners(on: Boolean) {
        core.update { it.copy(cornersMode = on) }
        if (on && core.recipe.geometry.corners == null) {
            core.edit(R.string.editor_step_perspective) { r ->
                r.copy(geometry = r.geometry.copy(corners = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
            }
        }
    }

    private companion object {
        const val TURNS = 4
    }
}

/** The width over height this crop keeps in [frame], in pixels; null when it is free. */
internal fun Geometry.lockedRatio(frame: Size): Float? = when (aspect) {
    CropAspect.FREE -> null
    CropAspect.ORIGINAL -> frame.width.toFloat() / frame.height
    CropAspect.CUSTOM -> customRatio
    else -> aspect.ratio
}

/** A ratio typed as "3:2", "3x2" or "1.91/1"; null unless both sides are positive and sane. */
internal fun typedRatio(text: String): Float? {
    val (w, h) = RATIO.matchEntire(text.trim())?.destructured ?: return null
    val ratio = w.replace(',', '.').toFloat() / h.replace(',', '.').toFloat()
    return ratio.takeIf { it.isFinite() && it in 1f / MAX_RATIO..MAX_RATIO }
}

private val RATIO = Regex("""(\d+(?:[.,]\d+)?)\s*[:x×/]\s*(\d+(?:[.,]\d+)?)""")

/** Ratios beyond 10:1 leave a strip too thin to see. */
private const val MAX_RATIO = 10f

internal class LookController(
    private val core: EditorCore,
    private val context: Context,
    private val assets: EditAssets,
    private val settings: SettingsStore
) : LookActions {
    override fun autoEnhance() {
        core.scope.launch {
            val proxy = core.session.proxy()
            val enhanced = withContext(Dispatchers.Default) { AutoEnhance.of(proxy, core.recipe) }
            core.edit(R.string.editor_step_auto) { enhanced }
        }
    }

    override fun setLook(look: String?) = core.edit(R.string.editor_step_filter) { r ->
        r.copy(filter = look?.let { Filter(look = it, intensity = r.filter?.intensity ?: 1f) })
    }

    override fun setLut(asset: String) = core.edit(R.string.editor_step_filter) { r ->
        r.copy(filter = Filter(lut = asset, intensity = r.filter?.intensity ?: 1f))
    }

    override fun setHslColor(color: HslColor) = core.update { it.copy(hslColor = color) }

    override fun setCurve(channel: CurveChannel) = core.update { it.copy(curve = channel) }

    override fun importLut(uri: Uri) {
        core.scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            val name =
                withContext(Dispatchers.IO) { displayName(context, uri) }?.substringBeforeLast('.')
                    ?: "LUT"
            val valid = bytes != null && bytes.size < MAX_LUT_BYTES &&
                runCatching { CubeLut.parse(bytes.inputStream().reader()) }.isSuccess
            if (!valid) {
                core.say(R.string.editor_lut_bad)
                return@launch
            }
            val asset = withContext(Dispatchers.IO) { assets.put(checkNotNull(bytes), "cube") }
            settings.updateEditor { e ->
                e.copy(
                    luts =
                        e.luts.filter { it.asset != asset } + LutEntry(name, asset)
                )
            }
            setLut(asset)
        }
    }

    override fun savePreset(name: String) {
        val r = core.recipe
        val look =
            EditRecipe(
                light = r.light,
                color = r.color,
                curves = r.curves,
                effects = r.effects,
                filter = r.filter
            )
        core.scope.launch {
            settings.updateEditor { e ->
                e.copy(
                    presets =
                        e.presets.filter { it.name != name } + EditPreset(name, look)
                )
            }
            core.say(R.string.editor_preset_saved)
        }
    }

    override fun applyPreset(preset: EditPreset) = core.edit(R.string.editor_step_preset) { r ->
        val p = preset.recipe
        r.copy(
            light = p.light,
            color = p.color,
            curves = p.curves,
            effects = p.effects,
            filter = p.filter
        )
    }

    override fun deletePreset(preset: EditPreset) {
        core.scope.launch { settings.updateEditor { e -> e.copy(presets = e.presets - preset) } }
    }

    override fun deleteLut(lut: LutEntry) {
        core.scope.launch { settings.updateEditor { e -> e.copy(luts = e.luts - lut) } }
    }

    private companion object {
        const val MAX_LUT_BYTES = 20_000_000
    }
}

internal class LayerController(
    private val core: EditorCore,
    private val context: Context,
    private val assets: EditAssets,
    private val settings: SettingsStore
) : LayerActions {
    override fun addText(text: String) =
        add(TextLayer(id = newId(), text = text), R.string.editor_step_text)

    override fun addEmoji(emoji: String) =
        add(StickerLayer(id = newId(), emoji = emoji), R.string.editor_step_sticker)

    override fun addSticker(asset: String) =
        add(StickerLayer(id = newId(), image = asset), R.string.editor_step_sticker)

    override fun deleteSticker(asset: String) {
        core.scope.launch { settings.updateEditor { e -> e.copy(stickers = e.stickers - asset) } }
    }

    override fun addShape(kind: ShapeKind) {
        val thin = kind == ShapeKind.LINE || kind == ShapeKind.ARROW
        add(
            ShapeLayer(
                id = newId(),
                shape = kind,
                height = if (thin) LINE_HEIGHT else SHAPE_HEIGHT
            ),
            R.string.editor_step_sticker
        )
    }

    private fun add(layer: Layer, label: Int) {
        core.edit(label) { it.copy(layers = it.layers + layer) }
        core.update { it.copy(selectedLayer = layer.id) }
    }

    override fun selectLayer(id: String?) = core.update { it.copy(selectedLayer = id) }

    override fun updateLayer(id: String, label: Int?, change: (Layer) -> Layer) =
        core.edit(label) { r ->
            r.copy(layers = r.layers.map { if (it.id == id) change(it) else it })
        }

    override fun deleteLayer(id: String) {
        core.edit(R.string.editor_step_delete) { r ->
            r.copy(layers = r.layers.filter { it.id != id })
        }
        core.update { it.copy(selectedLayer = null) }
    }

    override fun layerBox(id: String): Rect01? {
        val layer = core.recipe.layers.firstOrNull { it.id == id } ?: return null
        val content = core.content
        val box = OverlayPainter(core.recipe, assets)
            .bounds(
                layer,
                android.graphics.RectF(0f, 0f, content.width.toFloat(), content.height.toFloat())
            )
        return Rect01(0f, 0f, box.width() / content.width, box.height() / content.height)
    }

    override fun importLogo(uri: Uri) {
        core.scope.launch {
            val asset =
                withContext(Dispatchers.IO) { importPicture(context, assets, uri, "png") }
                    ?: return@launch
            core.edit(R.string.editor_step_watermark) { r ->
                r.copy(watermark = (r.watermark ?: Watermark()).copy(logo = asset, text = null))
            }
        }
    }

    private companion object {
        const val SHAPE_HEIGHT = 0.2f
        const val LINE_HEIGHT = 0.06f
    }
}

internal class DrawController(private val core: EditorCore) : DrawActions {
    override fun setBrush(tool: BrushTool) = core.update { it.copy(brush = tool) }

    override fun setBrushColor(color: Int) = core.update {
        it.copy(brushColor = color, pickingColor = false)
    }

    override fun setBrushWidth(width: Float) = core.update { it.copy(brushWidth = width) }

    override fun addStroke(points: List<Float>) {
        val s = core.state.value
        if (points.size < POINT) return
        val stroke = Stroke(s.brush, s.brushColor, s.brushWidth, points)
        core.edit(R.string.editor_step_draw) { it.copy(strokes = it.strokes + stroke) }
    }

    override fun startPicking() = core.update { it.copy(pickingColor = true) }

    override fun stopPicking() = core.update { it.copy(pickingColor = false) }

    override fun picked(color: Int) {
        val s = core.state.value
        val layer = s.selectedLayer
        when {
            layer != null && (s.tool == Tool.TEXT || s.tool == Tool.STICKERS) ->
                core.edit(R.string.editor_step_color) { r ->
                    r.copy(
                        layers = r.layers.map {
                            when {
                                it.id != layer -> it
                                it is TextLayer -> it.copy(color = color)
                                it is ShapeLayer -> it.copy(stroke = color)
                                else -> it
                            }
                        }
                    )
                }

            s.tool == Tool.FRAME -> core.edit(R.string.editor_step_frame) {
                it.copy(frame = it.frame.copy(color = color))
            }

            s.tool == Tool.CUTOUT -> core.edit(R.string.editor_step_background) {
                it.copy(background = it.background.copy(mode = BackgroundMode.COLOR, color = color))
            }

            s.tool == Tool.WATERMARK -> core.edit(R.string.editor_step_watermark) { r ->
                r.copy(watermark = (r.watermark ?: Watermark()).copy(color = color))
            }

            s.tool == Tool.REDACT -> {
                core.update { it.copy(redactColor = color) }
                core.edit(R.string.editor_step_redact) { r ->
                    r.copy(redactions = r.redactions.map { it.copy(color = color) })
                }
            }

            else -> setBrushColor(color)
        }
        core.update { it.copy(pickingColor = false) }
    }

    private companion object {
        const val POINT = 3
    }
}

internal class RedactController(private val core: EditorCore, private val detections: Detections) :
    RedactActions {
    override fun setRedactStyle(style: RedactStyle) {
        core.update { it.copy(redactStyle = style) }
        core.edit(R.string.editor_step_redact) { r ->
            r.copy(redactions = r.redactions.map { it.copy(style = style) })
        }
    }

    override fun addRedaction(rect: Rect01) {
        val state = core.state.value
        val added = Redaction(rect, style = state.redactStyle, color = state.redactColor)
        core.edit(R.string.editor_step_redact) { r ->
            r.copy(redactions = (r.redactions + added).take(MAX_REDACTIONS))
        }
    }

    override fun removeRedaction(index: Int) = core.edit(R.string.editor_step_redact) { r ->
        r.copy(redactions = r.redactions.filterIndexed { i, _ -> i != index })
    }

    override fun findRedactions() = core.busy(R.string.editor_finding) {
        val state = core.state.value
        val found = detections
            .redactions(core.session.proxy(), core.session.orientation, state.redactStyle)
            .map { it.copy(color = state.redactColor) }
        if (found.isEmpty()) {
            core.say(R.string.editor_found_nothing)
        } else {
            core.edit(R.string.editor_step_redact_auto) { r ->
                r.copy(redactions = (r.redactions + found).take(MAX_REDACTIONS))
            }
        }
    }
}

internal class MaskController(private val core: EditorCore, private val subject: SubjectFinder) :
    MaskActions {
    override fun addMask(kind: MaskKind) {
        if (core.recipe.masks.size >= MAX_MASKS) {
            core.say(R.string.editor_masks_full)
            return
        }
        val mask = LocalMask(
            id = newId(),
            kind = kind,
            start = LINEAR_START.takeIf { kind == MaskKind.LINEAR },
            end = LINEAR_END.takeIf { kind == MaskKind.LINEAR },
            center = RADIAL_CENTRE.takeIf { kind == MaskKind.RADIAL }
        )
        val add = {
            core.edit(R.string.editor_step_mask) { it.copy(masks = it.masks + mask) }
            core.update { it.copy(selectedMask = mask.id) }
        }
        // A subject or background mask needs the subject found first.
        val needsSubject = kind == MaskKind.SUBJECT || kind == MaskKind.BACKGROUND
        if (needsSubject) subject.withSubject(add) else add()
    }

    override fun selectMask(id: String?) = core.update { it.copy(selectedMask = id) }

    override fun updateMask(id: String, label: Int?, change: (LocalMask) -> LocalMask) =
        core.edit(label) { r ->
            r.copy(masks = r.masks.map { if (it.id == id) change(it) else it })
        }

    override fun deleteMask(id: String) {
        core.edit(R.string.editor_step_delete) { r ->
            r.copy(masks = r.masks.filter { it.id != id })
        }
        core.update { it.copy(selectedMask = null) }
    }

    override fun setMaskBrush(size: Float?, erase: Boolean?) = core.update {
        it.copy(maskBrushSize = size ?: it.maskBrushSize, maskErase = erase ?: it.maskErase)
    }

    override fun addMaskStroke(points: List<Float>) {
        val s = core.state.value
        val id = s.selectedMask ?: return
        val stroke = MaskStroke(points, s.maskBrushSize, s.maskErase)
        updateMask(id, R.string.editor_step_brush) { it.copy(strokes = it.strokes + stroke) }
    }

    private companion object {
        // Where a new linear mask runs and a new radial one sits, until the user moves them.
        val LINEAR_START = Point01(0.5f, 0.2f)
        val LINEAR_END = Point01(0.5f, 0.6f)
        val RADIAL_CENTRE = Point01(0.5f, 0.5f)
    }
}

@Suppress("LongParameterList")
internal class RetouchController(
    private val core: EditorCore,
    private val detections: Detections,
    private val subject: SubjectFinder,
    private val context: Context,
    private val assets: EditAssets,
    private val settings: SettingsStore,
    private val editOps: EditOps,
    private val mediaId: String
) : RetouchActions {
    override fun autoRedEye() = core.busy(R.string.editor_finding) {
        val eyes = detections.eyes(core.session.proxy(), core.session.orientation)
        if (eyes.isEmpty()) {
            core.say(R.string.editor_found_no_faces)
        } else {
            core.edit(R.string.editor_step_red_eye) { r ->
                r.copy(
                    retouch = r.retouch.copy(
                        redEyes = (r.retouch.redEyes + eyes).takeLast(MAX_EYES)
                    )
                )
            }
        }
    }

    override fun addRedEye(point: Point01) = core.edit(R.string.editor_step_red_eye) { r ->
        val spot = Circle01(point.x, point.y, RED_EYE_RADIUS)
        r.copy(retouch = r.retouch.copy(redEyes = (r.retouch.redEyes + spot).takeLast(MAX_EYES)))
    }

    /** Skin smoothing: faces are found the first time it is turned up. */
    override fun setSkinSmoothing(amount: Float, done: Boolean) {
        if (core.recipe.retouch.faces.isEmpty() && amount > 0f && done) {
            core.busy(R.string.editor_finding) {
                val faces = detections.faceAreas(core.session.proxy(), core.session.orientation)
                if (faces.isEmpty()) core.say(R.string.editor_found_no_faces)
                core.edit(R.string.editor_step_skin) {
                    it.copy(retouch = it.retouch.copy(faces = faces, skinSmoothing = amount))
                }
            }
            return
        }
        core.edit(if (done) R.string.editor_step_skin else null) {
            it.copy(retouch = it.retouch.copy(skinSmoothing = amount))
        }
    }

    override fun setBackgroundBlur(amount: Float, done: Boolean) {
        val apply = {
            core.edit(if (done) R.string.editor_step_background else null) {
                it.copy(retouch = it.retouch.copy(backgroundBlur = amount))
            }
        }
        if (done && amount > 0f) subject.withSubject(apply) else apply()
    }

    override fun setBackground(mode: BackgroundMode, color: Int?) {
        val apply = {
            core.edit(R.string.editor_step_background) { r ->
                val keep = r.background
                r.copy(background = keep.copy(mode = mode, color = color ?: keep.color))
            }
        }
        if (mode != BackgroundMode.KEEP) subject.withSubject(apply) else apply()
    }

    override fun setBackgroundImage(uri: Uri) {
        core.scope.launch {
            val asset =
                withContext(Dispatchers.IO) { importPicture(context, assets, uri, "jpg") }
                    ?: return@launch
            subject.withSubject {
                core.edit(R.string.editor_step_background) { r ->
                    r.copy(
                        background = r.background.copy(mode = BackgroundMode.IMAGE, image = asset)
                    )
                }
            }
        }
    }

    override fun liftSubject(action: LiftAction) = subject.withSubject {
        core.busy(R.string.editor_lifting) {
            val r = core.recipe
            val lifted = Lifts.cutOut(core.session, r)
            if (lifted == null) {
                core.say(R.string.editor_found_no_subject)
                return@busy
            }
            when (action) {
                LiftAction.STICKER -> {
                    val asset = withContext(Dispatchers.IO) { assets.putPng(lifted) }
                    settings.updateEditor { e -> e.copy(stickers = (e.stickers - asset) + asset) }
                    core.say(R.string.editor_lifted_sticker)
                }

                LiftAction.CLIPBOARD -> {
                    withContext(Dispatchers.IO) { Lifts.toClipboard(context, lifted) }
                    core.say(R.string.editor_lifted_copied)
                }

                LiftAction.SAVE -> {
                    val item = core.item ?: return@busy
                    val only = Lifts.subjectOnly(
                        r,
                        core.session.raster,
                        core.session.orientation,
                        assets
                    )
                    editOps.saveCopy(
                        mediaId,
                        EditJson.encode(only),
                        ExportOptions(format = ExportFormat.PNG),
                        item.name.substringBeforeLast('.') + "_subject.png",
                        item.name
                    )
                    core.say(R.string.editor_lifted_saving)
                }
            }
        }
    }

    private companion object {
        const val MAX_EYES = 8
        const val RED_EYE_RADIUS = 0.012f
    }
}

internal class EraserController(
    private val core: EditorCore,
    private val models: EraserModels,
    inpainter: Inpainter,
    assets: EditAssets,
    private val settings: SettingsStore
) : EraserActions {
    private val erasing = Erasing(core.session, inpainter, assets)

    override fun setEraserSize(size: Float) = core.update { it.copy(eraserSize = size) }

    override fun addEraseStroke(points: List<Float>) = core.update {
        it.copy(pendingErase = it.pendingErase + MaskStroke(points, it.eraserSize))
    }

    override fun clearErase() = core.update { it.copy(pendingErase = emptyList()) }

    override fun setEraserModel(model: EraserModel) {
        core.scope.launch {
            val ready = withContext(Dispatchers.IO) { models.ready(model) }
            core.update { it.copy(eraserModel = model, modelReady = ready) }
            settings.updateEditor { it.copy(eraserModel = model.name) }
        }
    }

    override fun downloadModel() {
        val model = core.state.value.eraserModel
        core.scope.launch {
            core.update { it.copy(modelProgress = 0f) }
            runCatching {
                models.download(model) { p -> core.update { it.copy(modelProgress = p) } }
            }
                .onFailure {
                    PxtLog.w(TAG, "The eraser model didn't download", it)
                    core.say(R.string.editor_model_failed)
                }
            val ready = withContext(Dispatchers.IO) { models.ready(model) }
            core.update { it.copy(modelProgress = null, modelReady = ready) }
        }
    }

    override fun erase() {
        val s = core.state.value
        if (s.pendingErase.isEmpty()) return
        core.busy(R.string.editor_erasing) {
            val erasure = erasing.erase(s.eraserModel, s.recipe, s.pendingErase)
            if (erasure == null) {
                core.say(R.string.editor_erase_failed)
            } else {
                core.edit(R.string.editor_step_erase) { it.copy(erasures = it.erasures + erasure) }
            }
            core.update { it.copy(pendingErase = emptyList()) }
        }
    }

    private companion object {
        const val TAG = "Eraser"
    }
}

@Suppress("LongParameterList")
internal class OutputController(
    private val core: EditorCore,
    private val context: Context,
    private val clipboard: EditClipboard,
    private val editOps: EditOps,
    private val settings: SettingsStore,
    private val subject: SubjectFinder,
    private val mediaId: String
) : OutputActions {
    override fun copyEdits() {
        core.scope.launch {
            clipboard.copy(core.recipe)
            core.say(R.string.editor_copied)
        }
    }

    override fun pasteEdits(parts: Set<PastePart>) {
        val pasted = core.state.value.clipboard ?: return
        val merged = Pasting.merge(core.recipe, pasted, parts)
        core.edit(R.string.editor_step_paste) { merged }
        if (merged.needsSubject && merged.subjectMask == null) subject.withSubject {}
    }

    /** *Save* (spec §4.10): over the item, revertible; an edit taken all the way back reverts. */
    override fun save() {
        val s = core.state.value
        val item = core.item
        val located = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        when {
            item == null || !s.canSave -> Unit

            // The original is kept with its place, which Android hides without this permission.
            item.source == SourceType.DEVICE && !located -> core.send(EditorEvent.NeedLocation)

            else -> core.scope.launch {
                if (s.recipe.isIdentity) {
                    // Nothing is left of the edit: the photo goes back to its original.
                    editOps.revert(listOf(mediaId), item.name)
                } else {
                    // Save keeps the photo's format, size and metadata, at full quality.
                    editOps.save(mediaId, EditJson.encode(s.recipe), ExportOptions(), item.name)
                }
                core.send(EditorEvent.Closed(R.string.editor_saving))
            }
        }
    }

    override fun saveCopy(options: ExportOptions) {
        val item = core.item ?: return
        core.scope.launch {
            settings.updateEditor { it.copy(export = options) }
            editOps.saveCopy(mediaId, EditJson.encode(core.recipe), options, null, item.name)
            core.send(EditorEvent.Closed(R.string.editor_saving_copy))
        }
    }

    override fun revert() {
        val item = core.item ?: return
        core.scope.launch {
            editOps.revert(listOf(mediaId), item.name)
            core.send(EditorEvent.Closed(R.string.editor_reverting))
        }
    }
}

/** The name a document provider shows for [uri], if it says. */
internal fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** A picked picture stored as an asset; null when it can't be read or is too big. */
internal fun importPicture(
    context: Context,
    assets: EditAssets,
    uri: Uri,
    extension: String
): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    require(bytes.size < MAX_PICTURE_BYTES)
    assets.put(bytes, extension)
}.getOrNull()

private const val MAX_PICTURE_BYTES = 40_000_000
