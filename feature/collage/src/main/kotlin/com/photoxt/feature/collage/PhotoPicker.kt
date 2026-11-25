package com.photoxt.feature.collage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import kotlinx.coroutines.flow.Flow

/**
 * The photos to replace a cell's photo with (spec §4.11, "replace a photo"): every photo of the
 * Pictures tab, from the phone and Drive, newest first.
 */
@Composable
internal fun PhotoPicker(
    photos: Flow<PagingData<ViewerItem>>,
    onPick: (ViewerItem) -> Unit,
    onDismiss: () -> Unit
) {
    val items = photos.collectAsLazyPagingItems()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(DesignR.drawable.ic_close),
                            stringResource(R.string.collage_cancel)
                        )
                    }
                    Text(
                        stringResource(R.string.collage_replace_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                LazyVerticalGrid(GridCells.Adaptive(TILE_DP.dp), Modifier.fillMaxSize()) {
                    items(items.itemCount, key = items.itemKey { it.id }) { i ->
                        val item = items[i]
                        val name = stringResource(R.string.collage_photo_choice, i + 1)
                        AsyncImage(
                            model = item?.let {
                                thumbnailOf(
                                    MediaRef(
                                        it.id,
                                        it.source,
                                        it.contentUri,
                                        it.modifiedAt,
                                        it.accountId,
                                        it.remoteId,
                                        it.remoteVersion
                                    )
                                )
                            },
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp)
                                .clickable(enabled = item != null, role = Role.Button) {
                                    item?.let(onPick)
                                }
                        )
                    }
                }
            }
        }
    }
}

private const val TILE_DP = 96
