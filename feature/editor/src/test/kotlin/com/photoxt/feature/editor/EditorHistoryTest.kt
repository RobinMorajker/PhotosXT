package com.photoxt.feature.editor

import com.photoxt.core.model.EditRecipe
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.PreviewSession
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo, redo, the history list and Reset (spec §4.10, Productivity). */
class EditorHistoryTest {
    private val assets = EditAssets(Files.createTempDirectory("assets").toFile())
    private val core = EditorCore(
        CoroutineScope(Dispatchers.Unconfined),
        PreviewSession(assets),
        Channel(Channel.UNLIMITED),
        render = {}
    ).apply {
        val opened = listOf(Step(R.string.editor_step_open, EditRecipe()))
        update { it.copy(steps = opened, stepIndex = 0) }
    }
    private val state get() = core.state.value
    private val labels get() = state.steps.map { it.label }

    /** One move of the exposure slider, before the finger lifts. */
    private fun exposure(v: Float) {
        core.edit(null) { it.copy(light = it.light.copy(exposure = v)) }
    }

    @Test
    fun aSliderDragIsOneStep() {
        // Each move of the finger changes the picture; letting go records one step.
        listOf(0.1f, 0.2f, 0.3f).forEach(::exposure)
        core.commit(R.string.editor_step_light)
        // Letting go again without a change records nothing.
        core.commit(R.string.editor_step_light)

        assertEquals(listOf(R.string.editor_step_open, R.string.editor_step_light), labels)
        assertEquals(0.3f, core.recipe.light.exposure)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun undoRedoAndAJumpInTheList() {
        exposure(0.3f)
        core.commit(R.string.editor_step_light)
        core.edit(R.string.editor_step_color) { it.copy(color = it.color.copy(saturation = 0.5f)) }

        core.undo()
        assertEquals(0f, core.recipe.color.saturation)
        assertEquals(0.3f, core.recipe.light.exposure)
        core.redo()
        assertEquals(0.5f, core.recipe.color.saturation)
        core.jumpTo(0)
        assertEquals(EditRecipe(), core.recipe)
        // Out of the list: nothing happens.
        core.undo()
        assertEquals(0, state.stepIndex)
    }

    @Test
    fun aChangeAfterUndoDropsWhatWasUndone() {
        exposure(0.3f)
        core.commit(R.string.editor_step_light)
        core.undo()
        core.edit(R.string.editor_step_color) { it.copy(color = it.color.copy(saturation = 0.5f)) }

        assertEquals(listOf(R.string.editor_step_open, R.string.editor_step_color), labels)
        assertEquals(0f, core.recipe.light.exposure)
        assertFalse(state.canRedo)
    }

    @Test
    fun resetTakesBackOneToolOnlyAndCanBeUndone() {
        exposure(0.3f)
        core.commit(R.string.editor_step_light)
        core.edit(R.string.editor_step_color) { it.copy(color = it.color.copy(saturation = 0.5f)) }
        core.setTool(Tool.COLOR)

        core.resetTool()
        assertEquals(0f, core.recipe.color.saturation)
        assertEquals(0.3f, core.recipe.light.exposure)
        assertEquals(R.string.editor_step_reset, labels.last())
        core.undo()
        assertEquals(0.5f, core.recipe.color.saturation)
    }
}
