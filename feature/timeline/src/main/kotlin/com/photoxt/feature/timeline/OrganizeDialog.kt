package com.photoxt.feature.timeline

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import com.photoxt.core.model.SmartRule
import com.photoxt.core.ui.BookmarkDialog
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.NameDialog
import com.photoxt.core.ui.PickAlbumDialog
import com.photoxt.core.ui.SmartAlbumDialog
import com.photoxt.core.ui.requestAlbumShortcut
import kotlinx.coroutines.launch

/** The organising dialog a timeline has open (spec §4.8). */
internal enum class OrganizeDialog {
    ADD_TO_ALBUM,
    NEW_VIRTUAL_ALBUM,
    BOOKMARK_ALBUM,
    VAULT,
    RENAME_OWN,
    DELETE_OWN,
    EDIT_RULE
}

/** The open organising dialog's content; each closes itself through [onDismiss]. */
@Composable
internal fun OrganizeDialogs(
    dialog: OrganizeDialog?,
    viewModel: TimelineViewModel,
    title: String,
    selected: Int,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onBack: (() -> Unit)?,
    onOpen: (OrganizeDialog) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        OrganizeDialog.ADD_TO_ALBUM, OrganizeDialog.NEW_VIRTUAL_ALBUM, OrganizeDialog.VAULT ->
            SelectionDialogs(dialog, viewModel, selected, onOpenAlbum, onOpen, onDismiss)

        OrganizeDialog.BOOKMARK_ALBUM,
        OrganizeDialog.RENAME_OWN,
        OrganizeDialog.DELETE_OWN,
        OrganizeDialog.EDIT_RULE -> AlbumDialogs(dialog, viewModel, title, onBack, onDismiss)

        null -> Unit
    }
}

/** The dialogs about the selection: *Add to album*, a new virtual album, the vault. */
@Composable
private fun SelectionDialogs(
    dialog: OrganizeDialog,
    viewModel: TimelineViewModel,
    selected: Int,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onOpen: (OrganizeDialog) -> Unit,
    onDismiss: () -> Unit
) {
    val organize = viewModel.organize
    val resources = LocalResources.current
    when (dialog) {
        OrganizeDialog.ADD_TO_ALBUM -> {
            val albums by organize.virtualAlbums.collectAsStateWithLifecycle(emptyList())
            PickAlbumDialog(
                albums = albums.filter { it.id != viewModel.albumId },
                onPick = {
                    onDismiss()
                    organize.selectionActions.addToAlbum(it)
                },
                onNew = { onOpen(OrganizeDialog.NEW_VIRTUAL_ALBUM) },
                onDismiss = onDismiss
            )
        }

        OrganizeDialog.NEW_VIRTUAL_ALBUM -> NameDialog(
            title = stringResource(R.string.new_virtual_album_title),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = { name ->
                onDismiss()
                organize.selectionActions.addToNewAlbum(name) { id -> onOpenAlbum(id, name) }
            },
            onDismiss = onDismiss
        )

        else -> ConfirmDialog(
            title = pluralStringResource(R.plurals.vault_title, selected, selected),
            body = stringResource(R.string.vault_body),
            confirm = stringResource(R.string.action_move_to_vault),
            onConfirm = {
                onDismiss()
                organize.selectionActions.moveToVault(
                    resources.getQuantityString(R.plurals.clip_items, selected, selected)
                )
            },
            onDismiss = onDismiss
        )
    }
}

/** The dialogs about the album: bookmark it, and PhotoXT's own albums' rename, delete, rule. */
@Composable
private fun AlbumDialogs(
    dialog: OrganizeDialog,
    viewModel: TimelineViewModel,
    title: String,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val organize = viewModel.organize
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (dialog) {
        OrganizeDialog.BOOKMARK_ALBUM -> {
            val collections by organize.collections.collectAsStateWithLifecycle(emptyList())
            BookmarkDialog(
                collections = collections,
                defaultName = stringResource(R.string.bookmark_default_collection),
                onSave = { collection, newCollection, note ->
                    organize.albumActions.bookmark(collection, newCollection, note)
                },
                onDismiss = onDismiss
            )
        }

        OrganizeDialog.RENAME_OWN -> NameDialog(
            title = stringResource(R.string.rename_album_title),
            initial = title,
            confirm = stringResource(R.string.action_rename),
            onConfirm = {
                onDismiss()
                organize.albumActions.rename(it)
            },
            onDismiss = onDismiss
        )

        OrganizeDialog.DELETE_OWN -> ConfirmDialog(
            title = stringResource(R.string.delete_own_album_title),
            body = stringResource(R.string.delete_own_album_body),
            confirm = stringResource(R.string.action_delete_album),
            onConfirm = {
                onDismiss()
                organize.albumActions.delete()
                onBack?.invoke()
            },
            onDismiss = onDismiss
        )

        else -> SmartAlbumDialog(
            initialName = title,
            initialRule = state.album?.rule ?: SmartRule(),
            onSave = { name, rule -> organize.albumActions.setRule(name, rule) },
            onDismiss = onDismiss
        )
    }
}

/**
 * *Add to home screen* for this album (spec §4.8), its first item as the icon, or a toast when
 * the launcher can't.
 */
@Composable
internal fun rememberShortcut(
    viewModel: TimelineViewModel,
    title: String,
    rows: LazyPagingItems<GridCell>
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unsupported = stringResource(R.string.shortcut_unsupported)
    return {
        val albumId = viewModel.albumId
        if (albumId != null) {
            val cover = rows.itemSnapshotList.firstNotNullOfOrNull {
                (it as? GridCell.Item)?.thumbnail
            }
            scope.launch {
                if (!requestAlbumShortcut(context, albumId, title, cover)) {
                    Toast.makeText(context, unsupported, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** [this] with the organising callbacks of [viewModel]'s selection and album (spec §4.8). */
@Composable
internal fun TimelineCallbacks.withOrganize(
    viewModel: TimelineViewModel,
    state: TimelineUiState,
    onShortcut: () -> Unit,
    onDialog: (OrganizeDialog) -> Unit
): TimelineCallbacks {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val organize = viewModel.organize
    val selectionOps = organize.selectionActions
    val albumOps = organize.albumActions
    val inAlbum = viewModel.albumId != null
    val custom = state.sort.field == com.photoxt.core.model.ItemSortField.CUSTOM
    val count = state.selection.size
    fun selectionTitle() = resources.getQuantityString(R.plurals.clip_items, count, count)
    val selectionOrganize = SelectionOrganizeCallbacks(
        onFavorite = { selectionOps.favorite(it, selectionTitle()) },
        onAddToAlbum = { onDialog(OrganizeDialog.ADD_TO_ALBUM) },
        onPin = selectionOps::pin.takeIf { inAlbum },
        onHide = { selectionOps.hide(true) },
        onUnhide = { selectionOps.hide(false) }.takeIf {
            viewModel.list == com.photoxt.core.model.MediaList.HIDDEN
        },
        onVault = { onDialog(OrganizeDialog.VAULT) },
        onRemoveFromAlbum = selectionOps::removeFromAlbum.takeIf {
            state.album?.type == com.photoxt.core.model.AlbumType.VIRTUAL
        },
        onSetCover = selectionOps::setAsCover.takeIf { inAlbum && count == 1 },
        onMove = { move: OrderMove ->
            scope.launch { selectionOps.move(viewModel.listQuery(state.sort), move) }
            Unit
        }.takeIf { inAlbum && custom }
    )
    val albumOrganize = AlbumOrganizeCallbacks(
        onPin = albumOps::pin,
        onShortcut = onShortcut,
        onBookmark = { onDialog(OrganizeDialog.BOOKMARK_ALBUM) },
        onHide = albumOps::hide,
        onHideFromPictures = albumOps::hideFromPictures,
        onEditOrder = { viewModel.setEditingOrder(true) },
        onResetCover = albumOps::resetCover,
        onRenameOwn = { onDialog(OrganizeDialog.RENAME_OWN) },
        onDeleteOwn = { onDialog(OrganizeDialog.DELETE_OWN) },
        onEditRule = { onDialog(OrganizeDialog.EDIT_RULE) }
    )
    return copy(
        selection = selection.copy(organize = selectionOrganize),
        album = album.copy(organize = albumOrganize),
        onEditOrderDone = { viewModel.setEditingOrder(false) },
        onReorder = viewModel::reorder
    )
}
