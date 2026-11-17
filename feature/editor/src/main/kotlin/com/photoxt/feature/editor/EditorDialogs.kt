package com.photoxt.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Resize
import com.photoxt.core.model.TextLayer
import com.photoxt.core.model.Watermark
import com.photoxt.core.ui.PasteEditsDialog
import com.photoxt.core.ui.RevertEditsDialog
import kotlin.math.roundToInt

/** The editor's dialogs, one at a time. */
@Composable
internal fun EditorDialogs(
    dialog: EditorDialog,
    state: EditorState,
    tools: EditorTools,
    onDismiss: () -> Unit
) {
    when (dialog) {
        EditorDialog.HISTORY -> HistoryDialog(state, tools, onDismiss)

        EditorDialog.EXPORT -> ExportDialog(
            start = state.export,
            source = state.mime,
            onSave = tools.output::saveCopy,
            onDismiss = onDismiss
        )

        EditorDialog.PASTE -> PasteEditsDialog(
            count = 1,
            onPaste = tools.output::pasteEdits,
            onDismiss = onDismiss
        )

        EditorDialog.REVERT -> RevertEditsDialog(
            drive = state.isDrive,
            onRevert = tools.output::revert,
            onDismiss = onDismiss
        )

        EditorDialog.ADD_TEXT -> TextDialog(
            R.string.editor_add_text,
            "",
            onDone = tools.layers::addText,
            onDismiss = onDismiss
        )

        EditorDialog.EDIT_TEXT -> EditTextDialog(state, tools, onDismiss)

        EditorDialog.SAVE_PRESET -> TextDialog(
            R.string.editor_save_preset,
            "",
            R.string.editor_preset_name,
            tools.look::savePreset,
            onDismiss
        )

        EditorDialog.CROP_RATIO -> TextDialog(
            R.string.editor_crop_ratio,
            "",
            R.string.editor_crop_ratio_label,
            onDone = { typedRatio(it)?.let(tools.transform::setCustomAspect) },
            onDismiss = onDismiss,
            valid = { typedRatio(it) != null }
        )

        EditorDialog.WATERMARK_TEXT -> TextDialog(
            R.string.editor_watermark_text,
            state.recipe.watermark?.text.orEmpty(),
            onDone = { text ->
                tools.history.edit(R.string.editor_step_watermark) { r ->
                    val mark = r.watermark ?: Watermark()
                    r.copy(watermark = mark.copy(text = text, logo = null))
                }
            },
            onDismiss = onDismiss
        )
    }
}

/** The words of the text layer picked; closes at once when none is. */
@Composable
private fun EditTextDialog(state: EditorState, tools: EditorTools, onDismiss: () -> Unit) {
    val layer = state.recipe.layers.firstOrNull { it.id == state.selectedLayer } as? TextLayer
    if (layer == null) {
        onDismiss()
        return
    }
    TextDialog(
        R.string.editor_edit_text,
        layer.text,
        onDone = { text ->
            tools.layers.updateLayer(layer.id, R.string.editor_step_text) {
                (it as? TextLayer)?.copy(text = text) ?: it
            }
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun HistoryDialog(state: EditorState, tools: EditorTools, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_history)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(state.steps) { index, step ->
                    val current = index == state.stepIndex
                    val type = MaterialTheme.typography
                    val colors = MaterialTheme.colorScheme
                    Text(
                        stringResource(step.label),
                        style = if (current) type.titleSmall else type.bodyLarge,
                        // Steps undone, which the next change drops, are greyed.
                        color = if (index > state.stepIndex) colors.outline else colors.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = current, role = Role.RadioButton) {
                                tools.history.jumpTo(index)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_close)) }
        }
    )
}

/** A line of text to type: a caption, a preset's name, a watermark. Blank text is not taken. */
@Composable
private fun TextDialog(
    title: Int,
    start: String,
    label: Int? = null,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    valid: (String) -> Boolean = String::isNotBlank
) {
    var text by rememberSaveable { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TEXT) },
                label = label?.let { { Text(stringResource(it)) } },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = valid(text), onClick = {
                onDone(text.trim())
                onDismiss()
            }) { Text(stringResource(R.string.editor_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        }
    )
}

private const val MAX_TEXT = 500
