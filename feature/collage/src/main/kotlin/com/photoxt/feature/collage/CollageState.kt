package com.photoxt.feature.collage

import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.Layer
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.render.Size

/** The studio's tools (spec §4.11), in the row at the bottom. */
enum class CollageTool { LAYOUT, CANVAS, CELL, TEXT, STICKERS, FRAME, LAYERS }

/** What a tap picked on the canvas: a photo's cell, or a text or sticker layer. */
sealed interface Picked {
    data class Cell(val index: Int) : Picked

    data class Layer(val id: String) : Picked
}

/** Where the studio is with its photos. */
sealed interface CollagePhase {
    /** Reading the photos; Drive ones download first, [fraction] of them done. */
    data class Loading(val fraction: Float?) : CollagePhase

    data object Ready : CollagePhase

    data class Failed(val message: Int) : CollagePhase
}

/** One step of the history (spec §4.11: undo/redo). */
data class CollageStep(val label: Int, val project: CollageProject)

/** One photo as the studio knows it: upright size and where it lives. */
data class PhotoInfo(val size: Size, val drive: Boolean) {
    val aspect: Double get() = size.width.toDouble() / size.height
}

/** Everything the studio shows but the pictures themselves. */
data class CollageUi(
    val phase: CollagePhase = CollagePhase.Loading(null),
    val draftId: String = "",
    val project: CollageProject = CollageProject(),
    val steps: List<CollageStep> = emptyList(),
    val stepIndex: Int = -1,
    val tool: CollageTool? = CollageTool.LAYOUT,
    val picked: Picked? = null,
    /** The photos by media id. */
    val photos: Map<String, PhotoInfo> = emptyMap(),
    /** A step that is working (finding faces, saving): its message. */
    val busy: Int? = null,
    /** Drive photos still downloading: how far, 0 to 1; null when none is. */
    val downloading: Float? = null,
    /** The replace-photo picker is open for this cell. */
    val replacing: Int? = null
) {
    val canUndo: Boolean get() = stepIndex > 0
    val canRedo: Boolean get() = stepIndex < steps.lastIndex
    val mode: CollageMode get() = project.mode

    val pickedCell: Int? get() = (picked as? Picked.Cell)?.index
    val pickedLayer: String? get() = (picked as? Picked.Layer)?.id

    /** Cell [cell]'s photo's upright aspect in [of] (one being built, or this); 1 until known. */
    fun aspect(cell: Int, of: CollageProject = project): Double =
        of.cells.getOrNull(cell)?.mediaId?.let { photos[it]?.aspect } ?: 1.0

    /** Cell [cell]'s photo's upright size in [of], when known. */
    fun size(cell: Int, of: CollageProject = project): Size? =
        of.cells.getOrNull(cell)?.mediaId?.let { photos[it]?.size }
}

/** Something the screen must do once. */
sealed interface CollageEvent {
    /** Close the studio, saying [message] if there is one. */
    data class Closed(val message: Int?) : CollageEvent

    data class Message(val text: Int) : CollageEvent
}

/** What the canvas's gestures change, answered by the studio's view model. */
@Suppress("LongParameterList") // One callback for each kind of thing a gesture changes.
class CanvasActions(
    val pick: (Picked?) -> Unit,
    val cell: (Int, CollageCell) -> Unit,
    val layout: (LayoutNode) -> Unit,
    val layer: (String, (Layer) -> Layer) -> Unit,
    val swap: (Int, Int) -> Unit,
    val commit: (Int) -> Unit,
    val editText: (String) -> Unit
)
