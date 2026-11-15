package com.photoxt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.HiddenAlbumRow
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.organize.Hiding
import com.photoxt.core.ui.UnlockGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hidden (spec §4.8), behind the unlock: the hidden items, and the hidden albums with *Unhide*.
 * Hidden things stay out of Pictures, albums, search and history.
 */
@Composable
fun HiddenScreen(
    onBack: () -> Unit,
    onOpenItems: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    UnlockGate(
        stringResource(R.string.hidden_title),
        stringResource(R.string.hidden_unlock),
        onBack,
        modifier
    ) {
        val viewModel = hiltViewModel<HiddenViewModel>()
        val count by viewModel.count.collectAsStateWithLifecycle()
        val albums by viewModel.albums.collectAsStateWithLifecycle()
        SubScreen(title = stringResource(R.string.hidden_title), onBack = onBack) {
            item(key = "items") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.hidden_items)) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.hidden_items_count, count, count))
                    },
                    leadingContent = {
                        Icon(
                            painterResource(DesignR.drawable.ic_visibility_off),
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenItems)
                )
            }
            item(key = "albums-title") { SectionTitle(stringResource(R.string.hidden_albums)) }
            if (albums.isEmpty()) {
                item(key = "albums-none") {
                    Text(
                        stringResource(R.string.hidden_albums_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            items(albums, key = { it.id }) { album ->
                HiddenAlbumItem(
                    album,
                    onOpen = { onOpenAlbum(album.id, album.title) },
                    onUnhide = { viewModel.unhide(album.id) }
                )
            }
        }
    }
}

@Composable
private fun HiddenAlbumItem(album: HiddenAlbumRow, onOpen: () -> Unit, onUnhide: () -> Unit) {
    ListItem(
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(painterResource(DesignR.drawable.ic_folder), contentDescription = null)
        },
        trailingContent = {
            IconButton(onClick = onUnhide) {
                Icon(
                    painterResource(DesignR.drawable.ic_visibility),
                    contentDescription = stringResource(R.string.hidden_unhide, album.title)
                )
            }
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onOpen)
    )
}
