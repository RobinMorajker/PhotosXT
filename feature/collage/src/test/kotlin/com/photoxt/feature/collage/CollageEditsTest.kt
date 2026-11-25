package com.photoxt.feature.collage

import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.SplitAxis
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.CollageGeometry
import com.photoxt.core.render.CollageSizing
import com.photoxt.core.render.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The studio's history, mode switches and swaps (spec §4.11). */
class CollageEditsTest {
    private val ids = listOf("wide", "tall", "square")
    private val core = CollageCore(
        CoroutineScope(Dispatchers.Unconfined),
        Channel(Channel.UNLIMITED)
    ) {
            _,
            _
        ->
    }
        .apply {
            val project = Starts.grid(ids)
            val photos = mapOf(
                "wide" to PhotoInfo(Size(4000, 2000), drive = false),
                "tall" to PhotoInfo(Size(1000, 2000), drive = true),
                "square" to PhotoInfo(Size(1500, 1500), drive = false)
            )
            update {
                it.copy(
                    project = project,
                    photos = photos,
                    steps = listOf(CollageStep(R.string.collage_step_open, project)),
                    stepIndex = 0
                )
            }
        }
    private val edits =
        CollageEdits(core, faces = { emptyMap() }, labels = BeforeAfterLabels("Before", "After"))
    private val state get() = core.state.value

    @Test
    fun aGestureIsOneStepAndUndoRestoresIt() {
        // A pan while the finger moves changes the picture; letting go records one step.
        listOf(0.4f, 0.3f, 0.2f).forEach { x -> edits.cells.cell(0, null) { it.copy(focusX = x) } }
        edits.cells.commit(R.string.collage_step_photo)
        edits.cells.swap(0, 2)

        assertEquals(listOf("square", "tall", "wide"), core.project.cells.map { it.mediaId })
        assertEquals(3, state.steps.size)
        core.undo()
        assertEquals(ids, core.project.cells.map { it.mediaId })
        assertEquals(0.2f, core.project.cells[0].focusX)
        core.undo()
        assertEquals(0.5f, core.project.cells[0].focusX)
        assertFalse(state.canUndo)
        core.redo()
        core.redo()
        assertFalse(state.canRedo)
        assertEquals("square", core.project.cells[0].mediaId)
    }

    @Test
    fun aStitchKeepsEachPhotoWholeAfterASwap() {
        edits.setMode(CollageMode.STITCH)
        edits.cells.swap(0, 1)
        val p = core.project
        val size = CollageSizing.preview(p, 1000) { state.size(it) }
        val geometry = CollageGeometry(p, size.width, size.height) { state.aspect(it) }
        // Tall, wide, square: each cell is its new photo's shape.
        val shapes = geometry.places.map { it.rect.width / it.rect.height }
        listOf(0.5, 2.0, 1.0).zip(shapes).forEach { (want, got) -> assertEquals(want, got, 0.02) }
    }

    @Test
    fun beforeAfterLabelsComeAndGoWithTheMode() {
        edits.setMode(CollageMode.BEFORE_AFTER)
        assertEquals(listOf("Before", "After"), core.project.layers.map { (it as TextLayer).text })
        assertEquals(listOf(0, 1), core.project.layout.leaves())
        edits.setBeforeAfter(SplitAxis.COLUMN)
        assertEquals(2, core.project.layers.size)

        edits.setMode(CollageMode.GRID)
        assertTrue(core.project.layers.isEmpty())
        // The third photo, kept by the draft while hidden, is shown again.
        assertEquals(3, core.project.shownCells.size)
    }
}
