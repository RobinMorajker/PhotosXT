package com.photoxt.feature.editor

import android.net.Uri
import com.photoxt.core.datastore.LutEntry
import com.photoxt.core.model.BackgroundMode
import com.photoxt.core.model.BrushTool
import com.photoxt.core.model.CropAspect
import com.photoxt.core.model.EditPreset
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.Layer
import com.photoxt.core.model.LocalMask
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.Point01
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.RedactStyle
import com.photoxt.core.model.ShapeKind
import com.photoxt.core.render.Size

/*
 * What the editor's controls can ask for, one interface per kind of tool. Every action does
 * nothing by default, so previews and screenshot tests need no editor behind them.
 */

/** The recipe, its history and the tool shown (spec §4.10, Productivity). */
interface HistoryActions {
    /** Changes the recipe; [label] names the history step, null while a drag goes on. */
    fun edit(label: Int?, change: (EditRecipe) -> EditRecipe) {}

    /** Ends a drag that called [edit] without a label: one step for all of it. */
    fun commit(label: Int) {}

    fun undo() {}

    fun redo() {}

    fun jumpTo(index: Int) {}

    fun setTool(tool: Tool?) {}

    /** Press and hold: the original. */
    fun compare(on: Boolean) {}

    /** Puts the current tool's settings back (spec: "reset per tool"). */
    fun resetTool() {}
}

interface TransformActions {
    fun rotate() {}

    fun flip(horizontal: Boolean) {}

    fun setAspect(aspect: CropAspect) {}

    /** Locks the crop to a typed width over height. */
    fun setCustomAspect(ratio: Float) {}

    fun setCorners(on: Boolean) {}

    /** The frame the crop is a rectangle of: the photo turned, in pixels. */
    fun frameSize(): Size = Size(1, 1)
}

interface LookActions {
    fun autoEnhance() {}

    fun setLook(look: String?) {}

    fun setLut(asset: String) {}

    /** Reads a .cube file; its name comes from the file. */
    fun importLut(uri: Uri) {}

    fun setHslColor(color: HslColor) {}

    fun setCurve(channel: CurveChannel) {}

    fun savePreset(name: String) {}

    fun applyPreset(preset: EditPreset) {}

    fun deletePreset(preset: EditPreset) {}

    /** Takes an imported LUT off the list; photos already saved with it keep their look. */
    fun deleteLut(lut: LutEntry) {}
}

interface LayerActions {
    fun addText(text: String) {}

    fun addEmoji(emoji: String) {}

    fun addSticker(asset: String) {}

    /** Takes a sticker of the user's own off the list; photos that use it keep it. */
    fun deleteSticker(asset: String) {}

    fun addShape(kind: ShapeKind) {}

    fun selectLayer(id: String?) {}

    /** Changes layer [id]; [label] null while a drag goes on. */
    fun updateLayer(id: String, label: Int?, change: (Layer) -> Layer) {}

    fun deleteLayer(id: String) {}

    fun importLogo(uri: Uri) {}

    /** Layer [id]'s box before its rotation: width and height as fractions of the picture. */
    fun layerBox(id: String): Rect01? = null
}

interface DrawActions {
    fun setBrush(tool: BrushTool) {}

    fun setBrushColor(color: Int) {}

    fun setBrushWidth(width: Float) {}

    /** A finished stroke: x, y, pressure triples in frame fractions. */
    fun addStroke(points: List<Float>) {}

    fun startPicking() {}

    fun stopPicking() {}

    /** The colour picked, for whatever the current tool colours. */
    fun picked(color: Int) {}
}

interface RedactActions {
    fun setRedactStyle(style: RedactStyle) {}

    fun addRedaction(rect: Rect01) {}

    fun removeRedaction(index: Int) {}

    fun findRedactions() {}
}

interface MaskActions {
    fun addMask(kind: MaskKind) {}

    fun selectMask(id: String?) {}

    fun updateMask(id: String, label: Int?, change: (LocalMask) -> LocalMask) {}

    fun deleteMask(id: String) {}

    fun setMaskBrush(size: Float? = null, erase: Boolean? = null) {}

    /** A brush stroke on the selected mask: x, y pairs in source fractions. */
    fun addMaskStroke(points: List<Float>) {}
}

interface RetouchActions {
    fun autoRedEye() {}

    fun addRedEye(point: Point01) {}

    fun setSkinSmoothing(amount: Float, done: Boolean) {}

    fun setBackgroundBlur(amount: Float, done: Boolean) {}

    fun setBackground(mode: BackgroundMode, color: Int? = null) {}

    fun setBackgroundImage(uri: Uri) {}

    fun liftSubject(action: LiftAction) {}
}

interface EraserActions {
    fun setEraserSize(size: Float) {}

    /** A stroke over what to erase: x, y pairs in source fractions. */
    fun addEraseStroke(points: List<Float>) {}

    fun clearErase() {}

    fun downloadModel() {}

    fun erase() {}
}

interface OutputActions {
    fun copyEdits() {}

    fun pasteEdits(parts: Set<PastePart>) {}

    fun save() {}

    fun saveCopy(options: ExportOptions) {}

    fun revert() {}
}

/** Every tool's actions together, as the screen gets them. */
@Suppress("LongParameterList") // One group of actions for each kind of tool.
class EditorTools(
    val history: HistoryActions = object : HistoryActions {},
    val transform: TransformActions = object : TransformActions {},
    val look: LookActions = object : LookActions {},
    val layers: LayerActions = object : LayerActions {},
    val draw: DrawActions = object : DrawActions {},
    val redact: RedactActions = object : RedactActions {},
    val masks: MaskActions = object : MaskActions {},
    val retouch: RetouchActions = object : RetouchActions {},
    val eraser: EraserActions = object : EraserActions {},
    val output: OutputActions = object : OutputActions {}
)

/** What *Lift subject* does with the cut-out. */
enum class LiftAction { SAVE, STICKER, CLIPBOARD }
