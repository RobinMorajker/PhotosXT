package com.photoxt.feature.collage

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.TextLayer
import com.photoxt.core.render.CollagePhotos
import com.photoxt.core.render.EditAssets
import com.photoxt.core.transfer.SourceCounts

/**
 * The collage studio (spec §4.11), full screen and always dark, like the editor: the collage
 * as it will be saved, the tools in a row at the bottom with their settings above them, and
 * Close, Undo, Redo and Save at the top. Work is kept as a draft as it goes.
 */
@Composable
fun CollageScreen(
    draftId: String?,
    mediaIds: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<CollageViewModel, CollageViewModel.Factory>(
        key = draftId ?: mediaIds.joinToString()
    ) { it.create(draftId, mediaIds) }
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val proxies by viewModel.proxies.collectAsStateWithLifecycle()
    val looked by viewModel.looked.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.event.collect { event ->
            when (event) {
                is CollageEvent.Closed -> {
                    event.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    onClose()
                }

                is CollageEvent.Message -> snackbar.showSnackbar(resources.getString(event.text))
            }
        }
    }
    val photos =
        remember(ui.project, proxies, looked) { viewModel.photosFor(ui.project, proxies, looked) }
    CollageContent(ui, photos, viewModel, onClose, snackbar, modifier)
}

/**
 * The studio drawn from [ui] and [photos], asking [studio] for everything else; always dark,
 * like the editor.
 */
@Composable
fun CollageContent(
    ui: CollageUi,
    photos: CollagePhotos,
    studio: CollageStudio,
    onClose: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    PxtTheme(darkTheme = true) {
        CollageLayout(ui, photos, studio, onClose, snackbar, modifier)
    }
}

@Composable
private fun CollageLayout(
    ui: CollageUi,
    photos: CollagePhotos,
    studio: CollageStudio,
    onClose: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    var dialog by rememberSaveable { mutableStateOf<CollageDialog?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler {
        when {
            ui.picked != null -> studio.pick(null)
            else -> onClose()
        }
    }
    // Kept across recompositions: the canvas restarts its gestures when its actions change.
    val actions = remember(studio) {
        canvasActions(studio) { id ->
            editing = id
            dialog = CollageDialog.EDIT_TEXT
        }
    }
    Surface(modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(ui, studio, onClose) { dialog = CollageDialog.EXPORT }
                Workspace(ui, photos, studio, actions, Modifier.weight(1f)) { d, id ->
                    editing = id
                    dialog = d
                }
                ToolRow(ui.tool, enabled = ui.phase == CollagePhase.Ready) { picked ->
                    studio.setTool(picked.takeIf { it != ui.tool })
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = TOOL_ROW_DP.dp)
            )
        }
    }
    dialog?.let { Dialogs(it, editing, ui, studio) { dialog = null } }
    ui.replacing?.let { cell ->
        PhotoPicker(studio.library, onPick = {
            studio.replace(cell, it)
        }, onDismiss = { studio.replacing(null) })
    }
}

private fun canvasActions(studio: CollageStudio, onEditText: (String) -> Unit): CanvasActions {
    val cells = studio.edits.cells
    return CanvasActions(
        pick = studio::pick,
        cell = { index, cell -> cells.cell(index, null) { cell } },
        layout = { studio.edits.layout(null, it) },
        layer = { id, change -> cells.layer(id, null, change) },
        swap = cells::swap,
        commit = cells::commit,
        editText = onEditText
    )
}

/** What the studio can open above itself. */
internal enum class CollageDialog { EXPORT, ADD_TEXT, EDIT_TEXT, CUSTOM_SIZE }

@Composable
private fun Dialogs(
    dialog: CollageDialog,
    editing: String?,
    ui: CollageUi,
    studio: CollageStudio,
    onDismiss: () -> Unit
) {
    val cells = studio.edits.cells
    when (dialog) {
        CollageDialog.EXPORT -> {
            val albums by studio.albums.collectAsStateWithLifecycle(emptyList())
            val start by produceState<Pair<String, String>?>(null) { value = studio.defaultAlbum() }
            val counts by produceState<SourceCounts?>(null) { value = studio.counts() }
            ExportDialog(
                ui,
                albums,
                start,
                counts,
                onExport = { output, id, title, delete ->
                    onDismiss()
                    studio.export(output, id, title, delete)
                },
                onDismiss = onDismiss
            )
        }

        CollageDialog.ADD_TEXT -> TextDialog("", R.string.collage_add_text, onDone = {
            onDismiss()
            studio.pick(Picked.Layer(cells.addText(it)))
        }, onDismiss = onDismiss)

        CollageDialog.EDIT_TEXT -> {
            val layer = ui.project.layers.firstOrNull { it.id == editing } as? TextLayer
            if (layer == null) {
                onDismiss()
            } else {
                TextDialog(layer.text, R.string.collage_edit_text, onDone = { text ->
                    onDismiss()
                    cells.layer(layer.id, R.string.collage_step_text) {
                        (it as? TextLayer)?.copy(text = text)
                            ?: it
                    }
                }, onDismiss = onDismiss)
            }
        }

        CollageDialog.CUSTOM_SIZE -> CustomSizeDialog(
            ui.project.canvas.customWidth,
            ui.project.canvas.customHeight,
            onDone = { w, h ->
                onDismiss()
                studio.edits.setAspect(com.photoxt.core.model.CanvasAspect.CUSTOM, w, h)
            },
            onDismiss = onDismiss
        )
    }
}

/**
 * The collage, and under it the settings of the tool shown, which never take more than a share
 * of the room.
 */
@Composable
private fun Workspace(
    ui: CollageUi,
    photos: CollagePhotos,
    studio: CollageStudio,
    actions: CanvasActions,
    modifier: Modifier = Modifier,
    onDialog: (CollageDialog, String?) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val panelMax = maxHeight * PANEL_SHARE
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val phase = ui.phase) {
                    is CollagePhase.Loading -> CircularProgressIndicator()

                    is CollagePhase.Failed -> Text(
                        stringResource(phase.message),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    CollagePhase.Ready -> CollageCanvas(
                        ui,
                        photos,
                        studio.assets,
                        actions,
                        Modifier.fillMaxSize().padding(CANVAS_PAD_DP.dp)
                    )
                }
                Status(ui, Modifier.align(Alignment.TopCenter))
            }
            val tool = ui.tool
            if (tool != null && ui.phase == CollagePhase.Ready) {
                Column(
                    Modifier
                        .heightIn(max = panelMax)
                        .fillMaxWidth()
                        .background(Color(PANEL))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Panel(tool, ui, studio, onDialog)
                }
            }
        }
    }
}

@Composable
private fun Panel(
    tool: CollageTool,
    ui: CollageUi,
    studio: CollageStudio,
    onDialog: (CollageDialog, String?) -> Unit
) {
    val edits = studio.edits
    when (tool) {
        CollageTool.LAYOUT -> LayoutPanel(ui, edits)

        CollageTool.CANVAS -> CanvasPanel(ui, edits) { onDialog(CollageDialog.CUSTOM_SIZE, null) }

        CollageTool.CELL -> CellPanel(ui, edits.cells, onReplace = studio::replacing)

        CollageTool.TEXT -> TextPanel(
            ui,
            edits.cells,
            onAdd = { onDialog(CollageDialog.ADD_TEXT, null) },
            onEdit = { onDialog(CollageDialog.EDIT_TEXT, it) }
        )

        CollageTool.STICKERS -> StickerPanel(ui, edits.cells, studio::pick)

        CollageTool.FRAME -> FramePanel(ui, edits)

        CollageTool.LAYERS -> LayersPanel(ui, edits.cells, studio::pick)
    }
}

/** Drive photos downloading, or a step working. */
@Composable
private fun Status(ui: CollageUi, modifier: Modifier = Modifier) {
    val downloading = ui.downloading
    val busy = ui.busy
    if (downloading == null && busy == null) return
    Surface(modifier.padding(8.dp), shape = MaterialTheme.shapes.small, color = Color(BUSY)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(busy ?: R.string.collage_downloading),
                style = MaterialTheme.typography.bodyMedium
            )
            if (downloading != null && busy == null) {
                LinearProgressIndicator(
                    progress = { downloading },
                    modifier = Modifier.width(PROGRESS_DP.dp).padding(top = 6.dp)
                )
            }
        }
    }
}

/** Close, Undo and Redo, then Save, measured first so its label is never cut short. */
@Composable
private fun TopBar(ui: CollageUi, studio: CollageStudio, onClose: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarIcon(DesignR.drawable.ic_close, R.string.collage_close, onClick = onClose)
            BarIcon(
                DesignR.drawable.ic_undo,
                R.string.collage_undo,
                enabled = ui.canUndo,
                onClick = studio::undo
            )
            BarIcon(
                DesignR.drawable.ic_redo,
                R.string.collage_redo,
                enabled = ui.canRedo,
                onClick = studio::redo
            )
            Text(
                stringResource(R.string.collage_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        FilledTonalButton(
            onClick = onSave,
            enabled = ui.phase == CollagePhase.Ready,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.padding(end = 4.dp)
        ) { Text(stringResource(R.string.collage_save), maxLines = 1) }
    }
}

@Composable
private fun BarIcon(icon: Int, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

/** The tools: a row of tabs that scrolls, each as wide as its label. */
@Composable
private fun ToolRow(selected: CollageTool?, enabled: Boolean, onSelect: (CollageTool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().background(Color(TOOL_ROW)),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(CollageTool.entries, key = { it.name }) { tool ->
            val on = tool == selected
            val tint = when {
                !enabled -> Color.Gray
                on -> MaterialTheme.colorScheme.primary
                else -> Color.White
            }
            Column(
                Modifier
                    .widthIn(min = TOOL_MIN_DP.dp)
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

private fun toolLabel(tool: CollageTool): Int = when (tool) {
    CollageTool.LAYOUT -> R.string.collage_tool_layout
    CollageTool.CANVAS -> R.string.collage_tool_canvas
    CollageTool.CELL -> R.string.collage_tool_cell
    CollageTool.TEXT -> R.string.collage_tool_text
    CollageTool.STICKERS -> R.string.collage_tool_stickers
    CollageTool.FRAME -> R.string.collage_tool_frame
    CollageTool.LAYERS -> R.string.collage_tool_layers
}

private fun toolIcon(tool: CollageTool): Int = when (tool) {
    CollageTool.LAYOUT -> DesignR.drawable.ic_dashboard
    CollageTool.CANVAS -> DesignR.drawable.ic_aspect_ratio
    CollageTool.CELL -> DesignR.drawable.ic_image
    CollageTool.TEXT -> DesignR.drawable.ic_text
    CollageTool.STICKERS -> DesignR.drawable.ic_sticker
    CollageTool.FRAME -> DesignR.drawable.ic_frame
    CollageTool.LAYERS -> DesignR.drawable.ic_layers
}

private const val PANEL_SHARE = 0.45f
private const val TOOL_MIN_DP = 72
private const val TOOL_ROW_DP = 96
private const val TOOL_ROW = 0xFF121212
private const val PANEL = 0xFF181818
private const val BUSY = 0xCC202020
private const val CANVAS_PAD_DP = 12
private const val PROGRESS_DP = 160
