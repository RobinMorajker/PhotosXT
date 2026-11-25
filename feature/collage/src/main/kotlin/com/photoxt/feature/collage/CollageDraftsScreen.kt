package com.photoxt.feature.collage

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.photoxt.core.database.CollageProjectEntity
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.EmptyState

/** Library → Collages: each draft's picture, to open it again or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageDraftsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<CollageDraftsViewModel>()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collage_drafts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(DesignR.drawable.ic_arrow_back),
                            stringResource(R.string.collage_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val list = drafts
        when {
            list == null -> Unit

            list.isEmpty() -> EmptyState(
                icon = painterResource(DesignR.drawable.ic_collage),
                title = stringResource(R.string.collage_drafts_empty),
                body = stringResource(R.string.collage_drafts_empty_body),
                modifier = Modifier.padding(padding)
            )

            else -> LazyVerticalGrid(
                GridCells.Adaptive(DRAFT_DP.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(list, key = { it.id }) { draft ->
                    DraftTile(draft, onOpen = {
                        onOpen(draft.id)
                    }, onDelete = { deleting = draft.id })
                }
            }
        }
    }
    deleting?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.collage_draft_delete_title),
            body = stringResource(R.string.collage_draft_delete_body),
            confirm = stringResource(R.string.collage_delete),
            onConfirm = {
                deleting = null
                viewModel.delete(id)
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun DraftTile(draft: CollageProjectEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val photos = draft.itemRefs.lines().count { it.isNotBlank() }
    val count = pluralStringResource(R.plurals.collage_draft_photos, photos, photos)
    val whenText = DateUtils.getRelativeTimeSpanString(draft.updatedAt).toString()
    val open = stringResource(R.string.collage_draft_open, count, whenText)
    Column(Modifier.padding(4.dp)) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(draftPicture(context, draft.id))
                    .memoryCacheKey("${draft.id}:${draft.updatedAt}")
                    .diskCacheKey("${draft.id}:${draft.updatedAt}")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .semantics { contentDescription = open }
                    .clickable(role = Role.Button, onClick = onOpen)
            )
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    painterResource(DesignR.drawable.ic_delete),
                    stringResource(R.string.collage_draft_delete)
                )
            }
        }
        Text(count, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (draft.exportedMediaId !=
                null
            ) {
                stringResource(R.string.collage_draft_saved, whenText)
            } else {
                whenText
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private const val DRAFT_DP = 160
