package com.photoxt.feature.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest

/**
 * The Albums tab: one tile per phone folder (spec §4.2). [onOpenAlbum] gets the album id and the
 * title to show. [empty] shows when there are no albums yet.
 */
@Composable
fun AlbumsScreen(
    title: String,
    empty: @Composable () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AlbumsContent(
        title = title,
        state = state,
        permissions = viewModel.permissions,
        onAccessResult = viewModel::refreshAccess,
        onOpenAlbum = onOpenAlbum,
        empty = empty,
        modifier = modifier
    )
}

/** The Albums screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsContent(
    title: String,
    state: AlbumsUiState,
    permissions: Array<String>,
    onAccessResult: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(title = title, scrollBehavior = scrollBehavior)
        if (state.access == MediaAccess.DENIED) {
            MediaAccessRequest(permissions, onAccessResult, Modifier.weight(1f))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TileMinSize),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (state.access == MediaAccess.LIMITED) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LimitedAccessBanner(permissions, onAccessResult)
                }
            }
            val albums = state.albums
            if (albums != null && albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { empty() }
            }
            items(albums.orEmpty(), key = { it.id }) { album ->
                val name = album.displayName()
                AlbumTileView(album, name, onClick = { onOpenAlbum(album.id, name) })
            }
        }
    }
}

@Composable
private fun AlbumTile.displayName(): String = when (folder) {
    DeviceFolder.CAMERA -> stringResource(R.string.album_camera)

    DeviceFolder.SCREENSHOTS -> stringResource(R.string.album_screenshots)

    DeviceFolder.DOWNLOADS -> stringResource(R.string.album_downloads)

    DeviceFolder.WHATSAPP, DeviceFolder.OTHER ->
        title.ifEmpty { stringResource(R.string.album_phone_storage) }
}

@Composable
private fun AlbumTileView(album: AlbumTile, name: String, onClick: () -> Unit) {
    val count = pluralStringResource(R.plurals.album_item_count, album.count, album.count)
    Column(
        modifier = Modifier
            .clip(TileShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $count" }
    ) {
        AsyncImage(
            model = album.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(TileShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp)
        )
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private val TileMinSize = 100.dp
private val TileShape = RoundedCornerShape(12.dp)
