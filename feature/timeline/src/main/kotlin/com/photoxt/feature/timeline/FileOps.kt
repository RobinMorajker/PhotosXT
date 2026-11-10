package com.photoxt.feature.timeline

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.ClipboardMode
import com.photoxt.core.transfer.Clip
import com.photoxt.core.transfer.RenamePattern
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.NameDialog
import com.photoxt.core.ui.PasteBar

// File operations in a timeline (spec §4.7): the selection's actions, the paste bar, an album's
// menu, and the dialogs they open.

/** What the selection bar can do; null actions are left out. */
data class SelectionCallbacks(
    val onShare: () -> Unit = {},
    val onCut: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onConvert: () -> Unit = {},
    val onExportPdf: () -> Unit = {},
    /** Set when exactly one item is selected. */
    val onCopyToClipboard: (() -> Unit)? = null,
    /** Set when exactly two items are selected. */
    val onCompare: (() -> Unit)? = null
)

/**
 * The selection's action bar, in place of the navigation bar (spec §4.1): Share, Cut, Copy,
 * Delete and More. Add to album and Favorite join it with Phase 6.
 */
@Composable
internal fun SelectionActions(actions: SelectionCallbacks) {
    Surface(tonalElevation = 3.dp) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp)
        ) {
            ActionButton(DesignR.drawable.ic_share, R.string.action_share, actions.onShare)
            ActionButton(DesignR.drawable.ic_content_cut, R.string.action_cut, actions.onCut)
            ActionButton(DesignR.drawable.ic_content_copy, R.string.action_copy, actions.onCopy)
            ActionButton(DesignR.drawable.ic_delete, R.string.action_delete, actions.onDelete)
            MoreActions(actions)
        }
    }
}

@Composable
private fun MoreActions(actions: SelectionCallbacks) {
    var open by rememberSaveable { mutableStateOf(false) }
    fun close(action: () -> Unit) {
        open = false
        action()
    }
    Box {
        ActionButton(DesignR.drawable.ic_more_vert, R.string.action_more) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.onCompare?.let { MenuItem(R.string.action_compare) { close(it) } }
            MenuItem(R.string.action_rename) { close(actions.onRename) }
            MenuItem(R.string.action_convert_jpeg) { close(actions.onConvert) }
            MenuItem(R.string.action_export_pdf) { close(actions.onExportPdf) }
            actions.onCopyToClipboard?.let {
                MenuItem(R.string.action_copy_to_clipboard) { close(it) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun ActionButton(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

/** The clipboard's bar: Paste in an album, a hint in Pictures (spec §4.7). */
@Composable
internal fun ClipboardBar(clip: Clip, inAlbum: Boolean, onPaste: () -> Unit, onClear: () -> Unit) {
    val what = if (clip.albumIds.isNotEmpty() && clip.mediaIds.isEmpty()) {
        pluralStringResource(R.plurals.clip_albums, clip.size, clip.size)
    } else {
        pluralStringResource(R.plurals.clip_items, clip.size, clip.size)
    }
    val text = when {
        !inAlbum -> stringResource(R.string.paste_open_album, what)
        clip.mode == ClipboardMode.CUT -> stringResource(R.string.paste_move_here, what)
        else -> stringResource(R.string.paste_copy_here, what)
    }
    PasteBar(text = text, onClear = onClear, onPaste = onPaste.takeIf { inAlbum })
}

/** What an album's menu offers; Drive's tracked-folder entries need [drive]. */
class AlbumCallbacks(
    val onNewFolder: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onClip: (ClipboardMode) -> Unit = {},
    val onMerge: (Boolean) -> Unit = {},
    val onOffline: (Boolean) -> Unit = {},
    val onRemove: () -> Unit = {}
)

/**
 * An album's menu (spec §4.7): a new folder inside it, rename, cut or copy the whole album; and
 * for a tracked Drive folder, merging subfolders, keeping it offline and removing it.
 */
@Composable
internal fun AlbumMenu(drive: DriveAlbumInfo?, callbacks: AlbumCallbacks) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painterResource(DesignR.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.action_more)
        )
    }
    fun close(action: () -> Unit) {
        open = false
        action()
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        MenuItem(R.string.action_new_folder) { close(callbacks.onNewFolder) }
        MenuItem(R.string.action_rename_album) { close(callbacks.onRename) }
        MenuItem(R.string.action_cut_album) { close { callbacks.onClip(ClipboardMode.CUT) } }
        MenuItem(R.string.action_copy_album) { close { callbacks.onClip(ClipboardMode.COPY) } }
        if (drive != null && drive.isRoot) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_merge_subfolders)) },
                trailingIcon = { Checkbox(checked = drive.merge, onCheckedChange = null) },
                onClick = {
                    open = false
                    callbacks.onMerge(!drive.merge)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_keep_album_offline)) },
                trailingIcon = { Checkbox(checked = drive.offline, onCheckedChange = null) },
                onClick = {
                    open = false
                    callbacks.onOffline(!drive.offline)
                }
            )
            MenuItem(R.string.action_remove_drive_folder) { close { confirmRemove = true } }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.remove_drive_folder_title),
            body = stringResource(R.string.remove_drive_folder_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = {
                confirmRemove = false
                callbacks.onRemove()
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

/** The dialog a timeline has open. */
internal enum class TimelineDialog { DELETE, RENAME_ITEMS, NEW_FOLDER, RENAME_ALBUM }

/** The open dialog's content; each one closes itself through [onDismiss]. */
@Composable
internal fun TimelineDialogs(
    dialog: TimelineDialog?,
    selected: Int,
    albumTitle: String,
    onDelete: () -> Unit,
    onRename: (pattern: String) -> Unit,
    onNewFolder: (name: String) -> Unit,
    onRenameAlbum: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        TimelineDialog.DELETE -> ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, selected, selected),
            body = stringResource(R.string.delete_body),
            confirm = stringResource(R.string.action_move_to_trash),
            onConfirm = {
                onDismiss()
                onDelete()
            },
            onDismiss = onDismiss
        )

        TimelineDialog.RENAME_ITEMS -> NameDialog(
            title = pluralStringResource(R.plurals.rename_title, selected, selected),
            initial = RenamePattern.DEFAULT,
            confirm = stringResource(R.string.action_rename),
            supporting = stringResource(R.string.rename_pattern_help),
            onConfirm = {
                onDismiss()
                onRename(it)
            },
            onDismiss = onDismiss
        )

        TimelineDialog.NEW_FOLDER -> NameDialog(
            title = stringResource(R.string.new_folder_title),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = {
                onDismiss()
                onNewFolder(it)
            },
            onDismiss = onDismiss
        )

        TimelineDialog.RENAME_ALBUM -> NameDialog(
            title = stringResource(R.string.rename_album_title),
            initial = albumTitle,
            confirm = stringResource(R.string.action_rename),
            onConfirm = {
                onDismiss()
                onRenameAlbum(it)
            },
            onDismiss = onDismiss
        )

        null -> Unit
    }
}
