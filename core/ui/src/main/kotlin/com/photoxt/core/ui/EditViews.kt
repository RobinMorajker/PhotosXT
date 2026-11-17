package com.photoxt.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.PastePart

/**
 * *Paste edits* onto [count] photos (spec §4.10): the copied look always; crop, overlays and
 * frame only when ticked, as they rarely fit another photo. From the editor, viewer and grid.
 */
@Composable
fun PasteEditsDialog(count: Int, onPaste: (Set<PastePart>) -> Unit, onDismiss: () -> Unit) {
    var parts by rememberSaveable { mutableStateOf(emptySet<PastePart>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.edit_paste_title, count, count)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.edit_paste_look),
                    style = MaterialTheme.typography.bodyMedium
                )
                PastePart.entries.forEach { part ->
                    val on = part in parts
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = on, role = Role.Checkbox) {
                                parts = if (it) parts + part else parts - part
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(
                            stringResource(pasteLabel(part)),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPaste(parts)
                onDismiss()
            }) { Text(stringResource(R.string.action_paste)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private fun pasteLabel(part: PastePart) = when (part) {
    PastePart.GEOMETRY -> R.string.edit_paste_geometry
    PastePart.OVERLAYS -> R.string.edit_paste_overlays
    PastePart.FRAME -> R.string.edit_paste_frame
}

/** *Revert to original* (spec §4.10): asks first, as the edit goes. */
@Composable
fun RevertEditsDialog(drive: Boolean, onRevert: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = stringResource(R.string.edit_revert_title),
    body = stringResource(if (drive) R.string.edit_revert_drive else R.string.edit_revert_phone),
    confirm = stringResource(R.string.edit_revert),
    onConfirm = {
        onDismiss()
        onRevert()
    },
    onDismiss = onDismiss
)
