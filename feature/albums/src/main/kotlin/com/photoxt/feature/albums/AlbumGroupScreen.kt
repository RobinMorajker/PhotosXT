package com.photoxt.feature.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** An album group (spec §4.8): its albums, and ungrouping them. */
@Composable
fun AlbumGroupScreen(
    groupId: String,
    title: String,
    onBack: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<AlbumGroupViewModel, AlbumGroupViewModel.Factory>(
        key = groupId
    ) { it.create(groupId) }
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val organize = viewModel.organize
    AlbumGroupContent(
        title = title,
        albums = albums,
        onBack = onBack,
        onOpenAlbum = onOpenAlbum,
        onUngroup = {
            organize.deleteGroup(groupId)
            onBack()
        },
        actions = TileMenuActions(
            onPin = { tile, pinned -> organize.pin(tile.id, pinned) },
            onHide = { organize.hide(it.id) },
            onHideFromPictures = { tile, hidden -> organize.hideFromPictures(tile.id, hidden) },
            onUngroupAlbum = { organize.moveToGroup(it.id, null) }
        ),
        modifier = modifier
    )
}

/** The group screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGroupContent(
    title: String,
    albums: List<AlbumTile>?,
    onBack: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onUngroup: () -> Unit,
    actions: TileMenuActions,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = title,
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(DesignR.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
            actions = {
                IconButton(onClick = onUngroup) {
                    Icon(
                        painterResource(DesignR.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_ungroup)
                    )
                }
            }
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(albums.orEmpty(), key = { it.id }) { album ->
                val name = album.displayName()
                var menu by remember { mutableStateOf(false) }
                Box {
                    AlbumTileView(
                        album,
                        name,
                        onClick = { onOpenAlbum(album.id, name) },
                        onLongClick = { menu = true }
                    )
                    TileMenu(album, menu, actions, onDismiss = { menu = false })
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AlbumGroupPreview() {
    PxtTheme {
        Surface {
            AlbumGroupContent(
                title = "Trips",
                albums = listOf(
                    previewTile("device:1", "Goa", count = 120),
                    previewTile("device:2", "Pune", count = 34)
                ),
                onBack = {},
                onOpenAlbum = { _, _ -> },
                onUngroup = {},
                actions = TileMenuActions()
            )
        }
    }
}

private fun previewTile(id: String, title: String, count: Int) =
    AlbumTile(id, title, DeviceFolder.OTHER, count, cover = null, groupId = "g")
