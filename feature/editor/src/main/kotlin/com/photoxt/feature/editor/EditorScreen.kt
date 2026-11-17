package com.photoxt.feature.editor

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.render.Preview
import com.photoxt.core.ui.ConfirmDialog

/**
 * The image editor (spec §4.10), full screen over the viewer: the photo with its edits live,
 * the tools in a row at the bottom with their settings above them, and Save, Undo, Redo, the
 * history, Compare, Copy and Paste edits, Save as copy and Revert at the top.
 */
@Composable
fun EditorScreen(mediaId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<EditorViewModel, EditorViewModel.Factory> { it.create(mediaId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.tools.output.save()
        }
    LaunchedEffect(viewModel) {
        viewModel.event.collect { event ->
            when (event) {
                EditorEvent.NeedLocation -> askLocation.launch(
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )

                is EditorEvent.Closed -> {
                    event.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    onClose()
                }

                is EditorEvent.Message -> snackbar.showSnackbar(resources.getString(event.text))
            }
        }
    }
    EditorContent(state, preview, viewModel.tools, onClose, snackbar, modifier)
}

/** The editor without its view model, for previews and screenshot tests. */
@Composable
fun EditorContent(
    state: EditorState,
    preview: Preview?,
    tools: EditorTools,
    onClose: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    // Always dark, whatever the app's theme: the controls sit on black around the photo.
    PxtTheme(darkTheme = true) {
        EditorLayout(state, preview, tools, onClose, snackbar, modifier)
    }
}

@Composable
private fun EditorLayout(
    state: EditorState,
    preview: Preview?,
    tools: EditorTools,
    onClose: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<EditorDialog?>(null) }
    // Leaving with steps not saved asks first.
    val close = { if (state.stepIndex > 0) confirmDiscard = true else onClose() }
    BackHandler { if (state.tool != null) tools.history.setTool(null) else close() }
    Surface(modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                EditorTopBar(state, tools, onClose = close, onDialog = { dialog = it })
                Workspace(state, preview, tools, Modifier.weight(1f)) { dialog = it }
                ToolRow(state.tool, enabled = state.phase == Phase.Ready) { picked ->
                    // A second tap on the tool shown closes it.
                    tools.history.setTool(picked.takeIf { it != state.tool })
                }
            }
            // Messages show above the tool row.
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = TOOL_ROW_CLEARANCE.dp)
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_title),
            body = stringResource(R.string.editor_discard_text),
            confirm = stringResource(R.string.editor_discard),
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false }
        )
    }
    dialog?.let { EditorDialogs(it, state, tools, onDismiss = { dialog = null }) }
}

/**
 * The photo, and under it the settings of the tool shown, which never take more than a share
 * of the room: the photo stays in sight while a slider moves.
 */
@Composable
private fun Workspace(
    state: EditorState,
    preview: Preview?,
    tools: EditorTools,
    modifier: Modifier = Modifier,
    onDialog: (EditorDialog) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val panelMax = maxHeight * PANEL_SHARE
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val phase = state.phase) {
                    is Phase.Loading -> Opening(phase.fraction)

                    is Phase.Failed -> Text(
                        stringResource(phase.message),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Phase.Ready -> PreviewCanvas(preview?.bitmap, preview?.geometry, state, tools)
                }
                state.busy?.let { BusyBar(it, Modifier.align(Alignment.TopCenter)) }
            }
            val tool = state.tool
            if (tool != null && state.phase == Phase.Ready) {
                ToolPanel(tool, state, tools, onDialog, Modifier.heightIn(max = panelMax))
            }
        }
    }
}

/** The photo is being read; a Drive photo downloads first, and [fraction] says how far it is. */
@Composable
private fun Opening(fraction: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.editor_opening),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        fraction?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.width(160.dp).padding(top = 8.dp)
            )
        }
    }
}

/**
 * Close, Undo, Redo and Compare; the rest in a menu; Save. Save is measured first, so its label
 * is never cut short: with large text the icons before it scroll instead.
 */
@Composable
private fun EditorTopBar(
    state: EditorState,
    tools: EditorTools,
    onClose: () -> Unit,
    onDialog: (EditorDialog) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarIcon(DesignR.drawable.ic_close, R.string.editor_close, onClick = onClose)
            BarIcon(DesignR.drawable.ic_undo, R.string.editor_undo, enabled = state.canUndo) {
                tools.history.undo()
            }
            BarIcon(DesignR.drawable.ic_redo, R.string.editor_redo, enabled = state.canRedo) {
                tools.history.redo()
            }
            CompareButton(
                enabled = state.phase == Phase.Ready,
                comparing = state.comparing,
                onCompare = tools.history::compare
            )
        }
        EditorMenu(state, tools, onDialog)
        FilledTonalButton(
            onClick = {
                if (state.overwritable) tools.output.save() else onDialog(EditorDialog.EXPORT)
            },
            enabled = state.canSave,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.padding(end = 4.dp)
        ) {
            // A GIF or RAW file can't be written over: Save makes a copy instead.
            val copy = !state.overwritable
            val label = if (copy) R.string.editor_save_copy_short else R.string.editor_save
            Text(stringResource(label), maxLines = 1)
        }
    }
}

@Composable
private fun EditorMenu(state: EditorState, tools: EditorTools, onDialog: (EditorDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Entry(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
        DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = {
            open = false
            onClick()
        })
    }
    Box {
        val ready = state.phase == Phase.Ready
        BarIcon(DesignR.drawable.ic_more_vert, R.string.editor_more, enabled = ready) {
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Entry(R.string.editor_history, enabled = state.steps.size > 1) {
                onDialog(EditorDialog.HISTORY)
            }
            Entry(R.string.editor_copy_edits, enabled = !state.recipe.isIdentity) {
                tools.output.copyEdits()
            }
            if (state.clipboard !=
                null
            ) {
                Entry(R.string.editor_paste_edits) { onDialog(EditorDialog.PASTE) }
            }
            Entry(R.string.editor_save_copy) { onDialog(EditorDialog.EXPORT) }
            if (state.wasEdited) Entry(R.string.editor_revert) { onDialog(EditorDialog.REVERT) }
        }
    }
}

/** Press and hold to see the original (spec §4.10, Compare). */
@Composable
private fun CompareButton(enabled: Boolean, comparing: Boolean, onCompare: (Boolean) -> Unit) {
    val label = stringResource(R.string.editor_compare)
    Box(
        Modifier
            .padding(12.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
                // TalkBack can't press and hold: a double tap shows the original until the next.
                onClick {
                    onCompare(!comparing)
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    onCompare(true)
                    waitForUpOrCancellation()
                    onCompare(false)
                }
            }
    ) {
        Icon(
            painterResource(DesignR.drawable.ic_compare),
            contentDescription = null,
            tint = if (enabled) Color.White else Color.Gray
        )
    }
}

@Composable
internal fun BarIcon(
    @DrawableRes icon: Int,
    label: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun BusyBar(message: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(8.dp),
        shape = MaterialTheme.shapes.small,
        color = Color(BUSY_BACKGROUND)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(message),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The tools, in the spec's order: a row of tabs that scrolls, each as wide as its label. */
@Composable
private fun ToolRow(selected: Tool?, enabled: Boolean, onSelect: (Tool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().background(Color(TOOL_ROW)),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(Tool.entries, key = { it.name }) { tool ->
            val on = tool == selected
            val tint = when {
                !enabled -> Color.Gray
                on -> MaterialTheme.colorScheme.primary
                else -> Color.White
            }
            Column(
                Modifier
                    .widthIn(min = TOOL_MIN_WIDTH.dp)
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) {
                        onSelect(tool)
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(painterResource(toolIcon(tool)), contentDescription = null, tint = tint)
                Text(
                    stringResource(toolLabel(tool)),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

internal fun toolLabel(tool: Tool): Int = when (tool) {
    Tool.TRANSFORM -> R.string.tool_transform
    Tool.LIGHT -> R.string.tool_light
    Tool.COLOR -> R.string.tool_color
    Tool.DETAIL -> R.string.tool_detail
    Tool.FILTERS -> R.string.tool_filters
    Tool.TEXT -> R.string.tool_text
    Tool.DRAW -> R.string.tool_draw
    Tool.STICKERS -> R.string.tool_stickers
    Tool.FRAME -> R.string.tool_frame
    Tool.WATERMARK -> R.string.tool_watermark
    Tool.REDACT -> R.string.tool_redact
    Tool.SELECTIVE -> R.string.tool_selective
    Tool.RETOUCH -> R.string.tool_retouch
    Tool.CUTOUT -> R.string.tool_cutout
    Tool.ERASER -> R.string.tool_eraser
}

private fun toolIcon(tool: Tool): Int = when (tool) {
    Tool.TRANSFORM -> DesignR.drawable.ic_crop
    Tool.LIGHT -> DesignR.drawable.ic_light
    Tool.COLOR -> DesignR.drawable.ic_palette
    Tool.DETAIL -> DesignR.drawable.ic_detail
    Tool.FILTERS -> DesignR.drawable.ic_filter
    Tool.TEXT -> DesignR.drawable.ic_text
    Tool.DRAW -> DesignR.drawable.ic_brush
    Tool.STICKERS -> DesignR.drawable.ic_sticker
    Tool.FRAME -> DesignR.drawable.ic_frame
    Tool.WATERMARK -> DesignR.drawable.ic_watermark
    Tool.REDACT -> DesignR.drawable.ic_redact
    Tool.SELECTIVE -> DesignR.drawable.ic_mask
    Tool.RETOUCH -> DesignR.drawable.ic_retouch
    Tool.CUTOUT -> DesignR.drawable.ic_cutout
    Tool.ERASER -> DesignR.drawable.ic_eraser
}

/** What the screen can open above itself. */
enum class EditorDialog {
    HISTORY,
    EXPORT,
    PASTE,
    REVERT,
    ADD_TEXT,
    EDIT_TEXT,
    SAVE_PRESET,
    WATERMARK_TEXT,
    CROP_RATIO
}

private const val PANEL_SHARE = 0.45f
private const val TOOL_MIN_WIDTH = 72
private const val TOOL_ROW_CLEARANCE = 96
private const val TOOL_ROW = 0xFF121212
private const val BUSY_BACKGROUND = 0xCC202020
