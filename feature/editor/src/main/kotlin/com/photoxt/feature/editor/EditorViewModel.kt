package com.photoxt.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.EditDao
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.ml.EraserModel
import com.photoxt.core.ml.EraserModels
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.SourceType
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.Preview
import com.photoxt.core.render.PreviewMode
import com.photoxt.core.render.PreviewSession
import com.photoxt.core.render.Size
import com.photoxt.core.transfer.EditClipboard
import com.photoxt.core.transfer.canOverwrite
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something the screen must do once: ask for a permission, close, say something. */
sealed interface EditorEvent {
    data object NeedLocation : EditorEvent

    data class Closed(val message: Int?) : EditorEvent

    data class Message(val text: Int) : EditorEvent
}

/**
 * What every tool shares (spec §4.10): the recipe and its history, the preview session, the
 * item being edited, and the screen's messages.
 */
class EditorCore internal constructor(
    val scope: CoroutineScope,
    val session: PreviewSession,
    private val events: Channel<EditorEvent>,
    private val render: () -> Unit
) : HistoryActions {
    val state = MutableStateFlow(EditorState())
    var item: ViewerItem? = null
        internal set

    val recipe: EditRecipe get() = state.value.recipe

    /** The cropped picture's size in the last preview, for sizing layers on screen. */
    @Volatile
    var content: Size = Size(DEFAULT_CONTENT, DEFAULT_CONTENT)
        internal set

    override fun edit(label: Int?, change: (EditRecipe) -> EditRecipe) {
        state.update { s ->
            val recipe = change(s.recipe)
            if (label == null) {
                s.copy(recipe = recipe)
            } else {
                val kept = s.steps.take(s.stepIndex + 1)
                if (kept.lastOrNull()?.recipe == recipe) {
                    s.copy(recipe = recipe)
                } else {
                    val steps = (kept + Step(label, recipe)).takeLast(MAX_STEPS)
                    s.copy(recipe = recipe, steps = steps, stepIndex = steps.lastIndex)
                }
            }
        }
        render()
    }

    override fun commit(label: Int) = edit(label) { it }

    override fun undo() = jumpTo(state.value.stepIndex - 1)

    override fun redo() = jumpTo(state.value.stepIndex + 1)

    override fun jumpTo(index: Int) {
        state.update { s ->
            if (index !in
                s.steps.indices
            ) {
                s
            } else {
                s.copy(recipe = s.steps[index].recipe, stepIndex = index)
            }
        }
        render()
    }

    override fun setTool(tool: Tool?) {
        state.update {
            it.copy(tool = tool, pickingColor = false, cornersMode = false, selectedLayer = null)
        }
        render()
    }

    override fun compare(on: Boolean) {
        state.update { it.copy(comparing = on) }
        render()
    }

    override fun resetTool() {
        val tool = state.value.tool ?: return
        edit(R.string.editor_step_reset) { r -> Resets.of(tool, r) }
    }

    fun update(change: (EditorState) -> EditorState) = state.update(change)

    fun say(text: Int) {
        scope.launch { events.send(EditorEvent.Message(text)) }
    }

    fun send(event: EditorEvent) {
        scope.launch { events.send(event) }
    }

    /** Runs [block] with [message] showing, saying so when it fails. */
    @Suppress("TooGenericExceptionCaught") // One step's boundary: it fails, the editor goes on.
    fun busy(message: Int, block: suspend () -> Unit) {
        scope.launch {
            state.update { it.copy(busy = message) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PxtLog.w(TAG, "An editor step failed", error)
                events.send(EditorEvent.Message(R.string.editor_step_failed))
            } finally {
                state.update { it.copy(busy = null) }
            }
        }
    }

    private companion object {
        const val TAG = "Editor"
        const val MAX_STEPS = 100
        const val DEFAULT_CONTENT = 1000
    }
}

/**
 * The image editor (spec §4.10): one recipe edited non-destructively, its history for undo and
 * redo, and a live preview rendered off the main thread by the same GPU pipeline as the export.
 * Saving goes through the transfer queue, so the screen can close at once.
 */
@HiltViewModel(assistedFactory = EditorViewModel.Factory::class)
class EditorViewModel @AssistedInject constructor(
    @Assisted private val mediaId: String,
    private val mediaDao: MediaDao,
    private val editDao: EditDao,
    private val sources: EditorSources,
    private val models: EraserModels,
    private val settings: SettingsStore,
    private val clipboard: EditClipboard,
    toolsFactory: EditorToolsFactory
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(mediaId: String): EditorViewModel
    }

    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val event = events.receiveAsFlow()
    private val requests = MutableStateFlow<Pair<EditRecipe, PreviewMode>?>(null)
    private val assets = toolsFactory.assets
    private val session = PreviewSession(assets)
    private val core = EditorCore(viewModelScope, session, events, ::requestRender)
    val state: StateFlow<EditorState> = core.state.asStateFlow()
    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()
    val tools: EditorTools = toolsFactory.create(core, mediaId)

    /** The raster as stored and its EXIF orientation, for mapping touches. */
    val raster: Size get() = session.raster
    val orientation: Int get() = session.orientation

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch {
            requests.filterNotNull().collect { (recipe, mode) ->
                runCatching { session.render(recipe, mode, PREVIEW_EDGE) }
                    .onSuccess {
                        if (mode == PreviewMode.EDIT) core.content = it.geometry.content
                        _preview.value = it
                    }
                    .onFailure { PxtLog.w(TAG, "A preview couldn't be drawn", it) }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                val model = runCatching {
                    EraserModel.valueOf(s.editor.eraserModel)
                }.getOrDefault(EraserModel.MIGAN)
                // LUT files and sticker pictures stay on the phone that added them, while
                // their entries sync with the settings: only what this phone has is offered.
                val (luts, stickers, ready) = withContext(Dispatchers.IO) {
                    Triple(
                        s.editor.luts.filter { assets.exists(it.asset) },
                        s.editor.stickers.filter(assets::exists),
                        models.ready(model)
                    )
                }
                core.update {
                    it.copy(
                        presets = s.editor.presets,
                        luts = luts,
                        stickers = stickers,
                        export = s.editor.export,
                        eraserModel = model,
                        modelReady = ready
                    )
                }
            }
        }
        viewModelScope.launch {
            clipboard.recipe.collect { r -> core.update { it.copy(clipboard = r) } }
        }
    }

    private suspend fun open() {
        val found = mediaDao.viewerItem(mediaId)
        val failure = if (found == null) R.string.editor_error_gone else load(found)
        if (failure == null) {
            core.update { it.copy(phase = Phase.Ready) }
            requestRender()
        } else {
            core.update { it.copy(phase = Phase.Failed(failure)) }
        }
    }

    /**
     * The photo's saved edit, if any, and its original, which the preview then shows; what to
     * say when the original can't be had, else null.
     */
    private suspend fun load(found: ViewerItem): Int? {
        core.item = found
        val edit = editDao.edit(mediaId)
        val recipe = edit?.let { runCatching { EditJson.decode(it.opsJson) }.getOrNull() }
            ?: EditRecipe()
        core.update {
            it.copy(
                title = found.name,
                recipe = recipe,
                steps = listOf(Step(R.string.editor_step_open, recipe)),
                stepIndex = 0,
                isDrive = found.source == SourceType.DRIVE,
                wasEdited = edit != null,
                mime = found.mime,
                overwritable = canOverwrite(found.mime)
            )
        }
        val file = runCatching {
            sources.original(found, edit) { f -> core.update { it.copy(phase = Phase.Loading(f)) } }
        }.onFailure { PxtLog.w(TAG, "The original couldn't be opened", it) }.getOrNull()
        val opened = file != null && runCatching { session.open(file) }
            .onFailure { PxtLog.w(TAG, "The photo couldn't be decoded", it) }
            .isSuccess
        return when {
            opened -> null

            // A Drive photo that didn't come down: most often there is no connection.
            file == null && found.source == SourceType.DRIVE -> R.string.editor_error_offline

            else -> R.string.editor_error_open
        }
    }

    private fun requestRender() {
        val s = core.state.value
        if (s.phase != Phase.Ready) return
        // Four-corner perspective is set on the photo as it was, before any correction.
        val shown = if (s.cornersMode) {
            s.recipe.copy(
                geometry = s.recipe.geometry.copy(
                    corners = null,
                    keystoneH = 0f,
                    keystoneV = 0f,
                    straighten = 0f
                )
            )
        } else {
            s.recipe
        }
        requests.value = shown to s.previewMode
    }

    override fun onCleared() {
        session.close()
    }

    private companion object {
        const val TAG = "Editor"
        const val PREVIEW_EDGE = 1600
    }
}
