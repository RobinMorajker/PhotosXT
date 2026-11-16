package com.photoxt.feature.albums

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.SmartRule
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.NameDialog
import com.photoxt.core.ui.NamedChoice
import com.photoxt.core.ui.SmartAlbumDialog

/** What *New album* makes (spec §4.7, §4.8): a folder, a virtual album or a smart album. */
@Composable
internal fun NewAlbumKindDialog(
    onFolder: () -> Unit,
    onVirtual: () -> Unit,
    onSmart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_album_title)) },
        text = {
            Column {
                KindRow(
                    R.string.new_kind_folder,
                    R.string.new_kind_folder_body,
                    DesignR.drawable.ic_create_new_folder,
                    onFolder
                )
                KindRow(
                    R.string.new_kind_virtual,
                    R.string.new_kind_virtual_body,
                    DesignR.drawable.ic_photo_library,
                    onVirtual
                )
                KindRow(
                    R.string.new_kind_smart,
                    R.string.new_kind_smart_body,
                    DesignR.drawable.ic_auto_awesome,
                    onSmart
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun KindRow(title: Int, body: Int, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    )
}

/** What an album tile's long-press menu can do. */
data class TileMenuActions(
    val onPin: (AlbumTile, Boolean) -> Unit = { _, _ -> },
    /** Moves a pin among the pins, or any album in Custom order: up when true. */
    val onMove: ((AlbumTile, Boolean) -> Unit)? = null,
    /** The Albums tab is in Custom order, so every album can move, not only the pins. */
    val customOrder: Boolean = false,
    val onHide: (AlbumTile) -> Unit = {},
    val onHideFromPictures: (AlbumTile, Boolean) -> Unit = { _, _ -> },
    val onGroup: (AlbumTile) -> Unit = {},
    val onUngroupAlbum: (AlbumTile) -> Unit = {},
    val onShortcut: (AlbumTile) -> Unit = {},
    val onRename: (AlbumTile) -> Unit = {},
    val onDelete: (AlbumTile) -> Unit = {}
)

/**
 * An album tile's long-press menu (spec §4.8): pin, move, hide, group, home-screen shortcut, and
 * rename and delete for PhotoXT's own albums and groups.
 */
@Composable
internal fun TileMenu(
    tile: AlbumTile,
    expanded: Boolean,
    actions: TileMenuActions,
    onDismiss: () -> Unit
) {
    fun pick(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (tile.kind == TileKind.GROUP) {
            Entry(R.string.action_rename_group, pick { actions.onRename(tile) })
            Entry(R.string.action_ungroup, pick { actions.onDelete(tile) })
            return@DropdownMenu
        }
        Entry(
            if (tile.pinned) R.string.action_unpin_album else R.string.action_pin_album,
            pick { actions.onPin(tile, !tile.pinned) }
        )
        actions.onMove?.takeIf { tile.pinned || actions.customOrder }?.let { move ->
            Entry(R.string.action_move_up, pick { move(tile, true) })
            Entry(R.string.action_move_down, pick { move(tile, false) })
        }
        if (tile.groupId == null) {
            Entry(R.string.action_move_to_group, pick { actions.onGroup(tile) })
        } else {
            Entry(R.string.action_remove_from_group, pick { actions.onUngroupAlbum(tile) })
        }
        Entry(R.string.action_add_to_home_screen, pick { actions.onShortcut(tile) })
        HorizontalDivider()
        if (tile.kind == TileKind.FOLDER) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_hide_from_pictures)) },
                trailingIcon = {
                    Checkbox(checked = tile.hideFromPictures, onCheckedChange = null)
                },
                onClick = pick { actions.onHideFromPictures(tile, !tile.hideFromPictures) }
            )
        } else {
            Entry(R.string.action_rename_album, pick { actions.onRename(tile) })
            Entry(R.string.action_delete_album, pick { actions.onDelete(tile) })
        }
        Entry(R.string.action_hide_album, pick { actions.onHide(tile) })
    }
}

@Composable
private fun Entry(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

/** *Move to group* (spec §4.8): an existing group, or a new one. */
@Composable
internal fun GroupPickerDialog(
    groups: List<NamedChoice>,
    onPick: (groupId: String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_group_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_group)) },
                    leadingContent = {
                        Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                    },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onNew)
                )
                groups.forEach { group ->
                    ListItem(
                        headlineContent = { Text(group.name) },
                        leadingContent = {
                            Icon(
                                painterResource(DesignR.drawable.ic_folder),
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable(role = Role.Button) { onPick(group.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Which tile is being dragged in *Edit order*, and which one it would land next to. */
@Stable
internal class TileDrag {
    var picked by mutableStateOf<String?>(null)
    var target by mutableStateOf<String?>(null)
}

/**
 * *Edit order* (spec §4.2, Custom order; pins by dragging, §4.8): a long-press picks a tile up,
 * a drag shows where it goes, and letting go puts it before or after the tile under the finger.
 */
@Composable
internal fun Modifier.dragToReorder(
    enabled: Boolean,
    state: LazyGridState,
    drag: TileDrag,
    onReorder: (movedId: String, targetId: String, after: Boolean) -> Unit
): Modifier {
    if (!enabled) return this
    val reorder by rememberUpdatedState(onReorder)
    fun keyAt(offset: Offset): Pair<String, Int>? = state.layoutInfo.visibleItemsInfo.firstOrNull {
        offset.x.toInt() in it.offset.x until it.offset.x + it.size.width &&
            offset.y.toInt() in it.offset.y until it.offset.y + it.size.height
    }?.let { info -> (info.key as? String)?.let { it to info.index } }
    val indexes = remember { IntArray(2) }
    return pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                keyAt(offset)?.let { (key, index) ->
                    drag.picked = key
                    indexes[0] = index
                }
            },
            onDrag = { change, _ ->
                change.consume()
                keyAt(change.position)?.let { (key, index) ->
                    drag.target = key
                    indexes[1] = index
                }
            },
            onDragEnd = {
                val picked = drag.picked
                val target = drag.target
                if (picked != null && target != null && picked != target) {
                    reorder(picked, target, indexes[1] > indexes[0])
                }
                drag.picked = null
                drag.target = null
            },
            onDragCancel = {
                drag.picked = null
                drag.target = null
            }
        )
    }
}

/** A dragged tile fades; the one it would land next to is outlined. */
@Composable
internal fun Modifier.dragMark(drag: TileDrag, id: String): Modifier {
    val outline = MaterialTheme.colorScheme.primary
    return graphicsLayer { alpha = if (drag.picked == id) PICKED_ALPHA else 1f }
        .then(if (drag.target == id) Modifier.border(3.dp, outline) else Modifier)
}

private const val PICKED_ALPHA = 0.4f

/** The organising dialog the Albums tab has open (spec §4.8). */
internal enum class OwnDialog {
    NEW_KIND,
    NEW_VIRTUAL,
    NEW_SMART,
    RENAME,
    DELETE,
    PICK_GROUP,
    NEW_GROUP,
    RENAME_GROUP
}

/** The open dialog's content, about [tile] where one is concerned. */
@Composable
internal fun OwnDialogs(
    dialog: OwnDialog?,
    tile: AlbumTile?,
    groups: List<NamedChoice>,
    organize: AlbumsOrganize,
    onNewFolder: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onOpen: (OwnDialog) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        OwnDialog.NEW_KIND -> NewAlbumKindDialog(
            onFolder = {
                onDismiss()
                onNewFolder()
            },
            onVirtual = { onOpen(OwnDialog.NEW_VIRTUAL) },
            onSmart = { onOpen(OwnDialog.NEW_SMART) },
            onDismiss = onDismiss
        )

        OwnDialog.NEW_VIRTUAL -> NameDialog(
            title = stringResource(R.string.new_kind_virtual),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = { name ->
                onDismiss()
                organize.newVirtual(name) { onOpenAlbum(it, name) }
            },
            onDismiss = onDismiss
        )

        OwnDialog.NEW_SMART -> SmartAlbumDialog(
            initialName = "",
            initialRule = SmartRule(),
            onSave = { name, rule -> organize.newSmart(name, rule) { onOpenAlbum(it, name) } },
            onDismiss = onDismiss
        )

        null -> Unit

        else -> if (tile != null) TileDialogs(dialog, tile, groups, organize, onOpen, onDismiss)
    }
}

/** The dialogs about one tile: rename, delete, and its group. */
@Composable
private fun TileDialogs(
    dialog: OwnDialog,
    tile: AlbumTile,
    groups: List<NamedChoice>,
    organize: AlbumsOrganize,
    onOpen: (OwnDialog) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        OwnDialog.RENAME -> NameDialog(
            title = stringResource(R.string.action_rename_album),
            initial = tile.name.ifEmpty { tile.title },
            confirm = stringResource(R.string.action_rename),
            onConfirm = { name ->
                onDismiss()
                organize.renameOwn(tile.id, name)
            },
            onDismiss = onDismiss
        )

        OwnDialog.RENAME_GROUP -> NameDialog(
            title = stringResource(R.string.action_rename_group),
            initial = tile.name.ifEmpty { tile.title },
            confirm = stringResource(R.string.action_rename),
            onConfirm = { name ->
                onDismiss()
                organize.renameGroup(tile.id.removePrefix(AlbumOptions.GROUP_PREFIX), name)
            },
            onDismiss = onDismiss
        )

        OwnDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.delete_own_album_title),
            body = stringResource(R.string.delete_own_album_body),
            confirm = stringResource(R.string.action_delete_album),
            onConfirm = {
                onDismiss()
                organize.deleteOwn(tile.id)
            },
            onDismiss = onDismiss
        )

        OwnDialog.PICK_GROUP -> GroupPickerDialog(
            groups = groups,
            onPick = {
                onDismiss()
                organize.moveToGroup(tile.id, it)
            },
            onNew = { onOpen(OwnDialog.NEW_GROUP) },
            onDismiss = onDismiss
        )

        else -> NameDialog(
            title = stringResource(R.string.new_group),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = { name ->
                onDismiss()
                organize.newGroup(name, tile.id)
            },
            onDismiss = onDismiss
        )
    }
}
