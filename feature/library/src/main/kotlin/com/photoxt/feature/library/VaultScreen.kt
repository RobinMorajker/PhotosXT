package com.photoxt.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaKind
import com.photoxt.core.transfer.OrganizeOps
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.EmptyState
import com.photoxt.core.ui.SecureWindow
import com.photoxt.core.ui.UnlockGate
import com.photoxt.core.ui.formatDuration
import com.photoxt.core.vault.Vault
import com.photoxt.core.vault.VaultEntry
import com.photoxt.core.vault.VaultImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The vault (spec §4.8): behind the unlock, screenshots blocked, the items encrypted on this
 * phone. Long-press an item to move it out or delete it for good.
 */
@Composable
fun VaultScreen(onBack: () -> Unit, onOpen: (index: Int) -> Unit, modifier: Modifier = Modifier) {
    UnlockGate(
        stringResource(R.string.vault_title),
        stringResource(R.string.vault_unlock),
        onBack,
        modifier
    ) {
        SecureWindow()
        val viewModel = hiltViewModel<VaultViewModel>()
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        VaultContent(entries, onBack, onOpen, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultContent(
    entries: List<VaultEntry>?,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    viewModel: VaultViewModel
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    var deleting by remember { mutableStateOf<VaultEntry?>(null) }
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = stringResource(R.string.vault_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { BackButton(onBack) }
        )
        if (entries?.isEmpty() == true) {
            EmptyState(
                icon = painterResource(DesignR.drawable.ic_lock),
                title = stringResource(R.string.vault_empty_title),
                body = stringResource(R.string.vault_empty_body),
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }
        VaultGrid(
            entries.orEmpty(),
            onOpen = onOpen,
            onMoveOut = viewModel::moveOut,
            onDelete = { deleting = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_title),
            body = stringResource(R.string.vault_delete_body),
            confirm = stringResource(R.string.vault_delete),
            onConfirm = {
                deleting = null
                viewModel.delete(entry)
            },
            onDismiss = { deleting = null }
        )
    }
}

/** The vault's note, then its items, newest first. */
@Composable
private fun VaultGrid(
    entries: List<VaultEntry>,
    onOpen: (Int) -> Unit,
    onMoveOut: (VaultEntry, title: String) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    modifier: Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.vault_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            VaultTile(
                entry,
                onOpen = { onOpen(index) },
                onMoveOut = { title -> onMoveOut(entry, title) },
                onDelete = { onDelete(entry) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultTile(
    entry: VaultEntry,
    onOpen: () -> Unit,
    onMoveOut: (title: String) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val actions = stringResource(R.string.vault_actions)
    Box {
        Box(
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = entry.meta.name }
                .combinedClickable(
                    onClick = onOpen,
                    onLongClickLabel = actions,
                    onLongClick = { menu = true }
                )
        ) {
            AsyncImage(
                model = VaultImage(entry.id, thumbnail = true),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (entry.meta.kind == MediaKind.VIDEO) {
                Text(
                    formatDuration(entry.meta.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_move_out)) },
                onClick = {
                    menu = false
                    onMoveOut(resources.getQuantityString(R.plurals.vault_items, 1, 1))
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_delete)) },
                onClick = {
                    menu = false
                    onDelete()
                }
            )
        }
    }
}
