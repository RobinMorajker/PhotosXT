package com.photoxt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.BookmarkCollectionRow
import com.photoxt.core.database.BookmarkRow
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.organize.Bookmarks
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.NameDialog
import com.photoxt.core.ui.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One collection with its bookmarks. */
data class BookmarkSection(val collection: BookmarkCollectionRow, val bookmarks: List<BookmarkRow>)

/** Bookmark collections and their bookmarks (spec §4.8). */
@HiltViewModel
class BookmarksViewModel @Inject constructor(private val bookmarks: Bookmarks) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val sections: StateFlow<List<BookmarkSection>?> = bookmarks.collections
        .flatMapLatest { collections ->
            if (collections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    collections.map { collection ->
                        bookmarks.inCollection(collection.id).map {
                            BookmarkSection(collection, it)
                        }
                    }
                ) { it.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun newCollection(name: String) = launch { bookmarks.newCollection(name) }

    fun rename(collection: BookmarkCollectionRow, name: String) =
        launch { bookmarks.editCollection(collection.id, name, collection.note) }

    fun setCollectionNote(collection: BookmarkCollectionRow, note: String) =
        launch { bookmarks.editCollection(collection.id, collection.name, note) }

    fun deleteCollection(id: String) = launch { bookmarks.deleteCollection(id) }

    fun setNote(bookmark: BookmarkRow, note: String) = launch { bookmarks.setNote(bookmark, note) }

    fun delete(bookmark: BookmarkRow) = launch { bookmarks.delete(bookmark.id) }

    /** A gone Drive item's kept thumbnail, else the item's own. */
    fun thumbnail(row: BookmarkRow): Any? =
        bookmarks.keptThumbnail(row.id).takeIf { row.mediaId == null }
            ?: row.mediaId?.let {
                thumbnailOf(
                    MediaRef(
                        it,
                        row.source ?: SourceType.DEVICE,
                        row.contentUri,
                        row.modifiedAt ?: 0,
                        row.accountId,
                        remoteVersion = row.remoteVersion
                    )
                )
            }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Where a bookmark leads. */
class BookmarkNavigation(
    val onOpen: (ViewerStart) -> Unit = {},
    val onPlay: (mediaId: String, positionMs: Long) -> Unit = { _, _ -> },
    val onOpenAlbum: (id: String, title: String) -> Unit = { _, _ -> }
)

private sealed interface BookmarkDialog {
    data object NewCollection : BookmarkDialog
    data class Rename(val collection: BookmarkCollectionRow) : BookmarkDialog
    data class CollectionNote(val collection: BookmarkCollectionRow) : BookmarkDialog
    data class DeleteCollection(val collection: BookmarkCollectionRow) : BookmarkDialog
    data class Note(val bookmark: BookmarkRow) : BookmarkDialog
}

/** Bookmarks (spec §4.8): each collection with its note, then its bookmarks, newest first. */
@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    navigation: BookmarkNavigation,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<BookmarksViewModel>()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<BookmarkDialog?>(null) }
    SubScreen(
        title = stringResource(R.string.bookmarks_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { dialog = BookmarkDialog.NewCollection }) {
                Icon(
                    painterResource(DesignR.drawable.ic_add),
                    contentDescription = stringResource(R.string.bookmarks_new_collection)
                )
            }
        }
    ) {
        if (sections?.isEmpty() == true) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.bookmarks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        sections.orEmpty().forEach { section ->
            item(key = "c:" + section.collection.id) {
                CollectionHeader(section.collection) { dialog = it }
            }
            items(section.bookmarks, key = { "b:" + it.id }) { bookmark ->
                BookmarkItem(
                    bookmark,
                    viewModel.thumbnail(bookmark),
                    onOpen = { open(bookmark, navigation) },
                    onNote = { dialog = BookmarkDialog.Note(bookmark) },
                    onDelete = { viewModel.delete(bookmark) }
                )
            }
        }
    }
    dialog?.let { BookmarkDialogs(it, viewModel) { dialog = null } }
}

private fun open(bookmark: BookmarkRow, navigation: BookmarkNavigation) {
    if (!bookmark.available) return
    val mediaId = bookmark.mediaId
    when {
        bookmark.targetType == TargetType.ALBUM ->
            navigation.onOpenAlbum(bookmark.targetId, bookmark.title)

        mediaId == null -> Unit

        bookmark.positionMs != null -> navigation.onPlay(mediaId, bookmark.positionMs ?: 0)

        // The viewer pages through the item's own album, finding the item there.
        else -> navigation.onOpen(ViewerStart(bookmark.mediaAlbumId, MediaFilter.ALL, 0, mediaId))
    }
}

@Composable
private fun CollectionHeader(
    collection: BookmarkCollectionRow,
    onDialog: (BookmarkDialog) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                collection.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        supportingContent = collection.note?.let { note -> { Text(note) } },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painterResource(DesignR.drawable.ic_more_vert),
                        contentDescription = stringResource(
                            R.string.bookmarks_collection_menu,
                            collection.name
                        )
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_rename)) },
                        onClick = {
                            menu = false
                            onDialog(BookmarkDialog.Rename(collection))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_edit_note)) },
                        onClick = {
                            menu = false
                            onDialog(BookmarkDialog.CollectionNote(collection))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_delete_collection)) },
                        onClick = {
                            menu = false
                            onDialog(BookmarkDialog.DeleteCollection(collection))
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun BookmarkItem(
    bookmark: BookmarkRow,
    thumbnail: Any?,
    onOpen: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val lines = buildList {
        if (!bookmark.available) add(stringResource(R.string.bookmarks_unavailable))
        bookmark.positionMs?.let {
            add(stringResource(R.string.bookmarks_moment, formatDuration(it)))
        }
        bookmark.note?.let(::add)
    }
    ListItem(
        headlineContent = { Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = lines.takeIf {
            it.isNotEmpty()
        }?.let { { Text(it.joinToString(" · ")) } },
        leadingContent = {
            if (bookmark.targetType == TargetType.ALBUM) {
                Icon(painterResource(DesignR.drawable.ic_photo_library), contentDescription = null)
            } else {
                Thumb(thumbnail)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painterResource(DesignR.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.bookmarks_menu, bookmark.title)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_edit_note)) },
                        onClick = {
                            menu = false
                            onNote()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_remove)) },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        },
        modifier = Modifier.clickable(
            enabled = bookmark.available,
            role = Role.Button,
            onClick = onOpen
        )
    )
}

@Composable
private fun BookmarkDialogs(
    dialog: BookmarkDialog,
    viewModel: BookmarksViewModel,
    onDismiss: () -> Unit
) {
    when (dialog) {
        BookmarkDialog.NewCollection -> NameDialog(
            title = stringResource(R.string.bookmarks_new_collection),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = {
                onDismiss()
                viewModel.newCollection(it)
            },
            onDismiss = onDismiss
        )

        is BookmarkDialog.Rename -> NameDialog(
            title = stringResource(R.string.bookmarks_rename),
            initial = dialog.collection.name,
            confirm = stringResource(R.string.action_save),
            onConfirm = {
                onDismiss()
                viewModel.rename(dialog.collection, it)
            },
            onDismiss = onDismiss
        )

        is BookmarkDialog.CollectionNote -> NoteDialog(
            title = stringResource(R.string.bookmarks_edit_note),
            initial = dialog.collection.note.orEmpty(),
            onSave = { viewModel.setCollectionNote(dialog.collection, it) },
            onDismiss = onDismiss
        )

        is BookmarkDialog.DeleteCollection -> ConfirmDialog(
            title = stringResource(R.string.bookmarks_delete_title, dialog.collection.name),
            body = stringResource(R.string.bookmarks_delete_body),
            confirm = stringResource(R.string.bookmarks_delete_collection),
            onConfirm = {
                onDismiss()
                viewModel.deleteCollection(dialog.collection.id)
            },
            onDismiss = onDismiss
        )

        is BookmarkDialog.Note -> NoteDialog(
            title = stringResource(R.string.bookmarks_edit_note),
            initial = dialog.bookmark.note.orEmpty(),
            onSave = { viewModel.setNote(dialog.bookmark, it) },
            onDismiss = onDismiss
        )
    }
}
