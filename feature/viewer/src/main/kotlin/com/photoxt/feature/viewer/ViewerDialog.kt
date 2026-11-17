package com.photoxt.feature.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.model.SourceType
import com.photoxt.core.ui.BookmarkDialog
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.NameDialog
import com.photoxt.core.ui.PasteEditsDialog
import com.photoxt.core.ui.PickAlbumDialog
import com.photoxt.core.ui.RevertEditsDialog

/** The organising dialog the viewer has open (spec §4.8). */
internal enum class ViewerDialog {
    BOOKMARK,
    ADD_TO_ALBUM,
    NEW_ALBUM,
    VAULT,
    PASTE_EDITS,
    REVERT_EDITS
}

/** The open dialog's content about [item]; each closes itself through [onDismiss]. */
@Composable
internal fun ViewerDialogs(
    dialog: ViewerDialog,
    item: ViewerItem,
    organize: ViewerOrganize,
    edits: ViewerEdits,
    onOpen: (ViewerDialog) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        ViewerDialog.BOOKMARK -> {
            val collections by organize.collections.collectAsStateWithLifecycle(emptyList())
            BookmarkDialog(
                collections = collections,
                defaultName = stringResource(R.string.bookmark_default_collection),
                onSave = { collection, newCollection, note ->
                    organize.bookmark(item, collection, newCollection, note)
                },
                onDismiss = onDismiss
            )
        }

        ViewerDialog.ADD_TO_ALBUM -> {
            val albums by organize.virtualAlbums.collectAsStateWithLifecycle(emptyList())
            PickAlbumDialog(
                albums = albums,
                onPick = {
                    onDismiss()
                    organize.addToAlbum(item, it)
                },
                onNew = { onOpen(ViewerDialog.NEW_ALBUM) },
                onDismiss = onDismiss
            )
        }

        ViewerDialog.NEW_ALBUM -> NameDialog(
            title = stringResource(R.string.action_add_to_album),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = {
                onDismiss()
                organize.addToNewAlbum(item, it)
            },
            onDismiss = onDismiss
        )

        ViewerDialog.VAULT -> ConfirmDialog(
            title = stringResource(R.string.vault_one_title),
            body = stringResource(R.string.vault_body),
            confirm = stringResource(R.string.action_move_to_vault),
            onConfirm = {
                onDismiss()
                organize.moveToVault(item)
            },
            onDismiss = onDismiss
        )

        ViewerDialog.PASTE_EDITS -> PasteEditsDialog(count = 1, onPaste = {
            edits.paste(item, it)
        }, onDismiss = onDismiss)

        ViewerDialog.REVERT_EDITS -> RevertEditsDialog(
            drive = item.source == SourceType.DRIVE,
            onRevert = { edits.revert(item) },
            onDismiss = onDismiss
        )
    }
}
