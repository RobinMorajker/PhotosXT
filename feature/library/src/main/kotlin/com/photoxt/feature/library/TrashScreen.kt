package com.photoxt.feature.library

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.transfer.TrashEntry
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.EmptyState
import kotlin.math.ceil

/**
 * Trash (spec §4.7): the phone's trash and the Drive bin of every account together. Tap items to
 * pick them, then Restore or Delete forever; Empty deletes everything shown. Each bin deletes its
 * items itself after 30 days.
 */
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A hidden tab keeps this screen: read the bins again each time it shows.
    LifecycleStartEffect(Unit) {
        viewModel.load()
        onStopOrDispose { }
    }
    var confirm by rememberSaveable { mutableStateOf<TrashConfirm?>(null) }
    val resources = LocalResources.current
    val titleOf = { count: Int -> resources.getQuantityString(R.plurals.trash_items, count, count) }
    BackHandler(enabled = state.selection.isNotEmpty()) { viewModel.clearSelection() }
    TrashContent(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onClearSelection = viewModel::clearSelection,
        onRestore = { viewModel.restore(titleOf(state.selection.size)) },
        onDelete = { confirm = TrashConfirm.DELETE },
        onEmpty = { confirm = TrashConfirm.EMPTY },
        onRefresh = viewModel::load,
        modifier = modifier
    )
    confirm?.let { which ->
        val count = if (which == TrashConfirm.EMPTY) {
            state.entries.count { it.owned }
        } else {
            state.selection.size
        }
        val title = titleOf(count)
        ConfirmDialog(
            title = if (which == TrashConfirm.EMPTY) {
                stringResource(R.string.trash_empty_title)
            } else {
                pluralStringResource(R.plurals.trash_delete_title, count, count)
            },
            body = stringResource(R.string.trash_delete_body),
            confirm = stringResource(R.string.trash_delete_forever),
            onConfirm = {
                confirm = null
                if (which == TrashConfirm.EMPTY) {
                    viewModel.empty(title)
                } else {
                    viewModel.deleteForever(title)
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

private enum class TrashConfirm { DELETE, EMPTY }

/** The Trash screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashContent(
    state: TrashUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEmpty: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val selected = state.selection.size
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            PxtCollapsingHeader(
                title = if (selected > 0) {
                    pluralStringResource(R.plurals.trash_selected, selected, selected)
                } else {
                    stringResource(R.string.trash_title)
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selected > 0) {
                        HeaderButton(DesignR.drawable.ic_close, R.string.trash_cancel_selection) {
                            onClearSelection()
                        }
                    } else {
                        HeaderButton(DesignR.drawable.ic_arrow_back, R.string.action_back, onBack)
                    }
                },
                actions = {
                    if (selected == 0 && state.entries.any { it.owned }) {
                        TextButton(onClick = onEmpty) { Text(stringResource(R.string.trash_empty)) }
                    }
                }
            )
            TrashGrid(state, onToggle, Modifier.weight(1f).fillMaxWidth())
            if (selected > 0) TrashActions(onRestore, onDelete)
        }
    }
}

@Composable
private fun HeaderButton(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

/** The bins' items, with the 30-day note and any Drive bin that couldn't be read. */
@Composable
private fun TrashGrid(state: TrashUiState, onToggle: (String) -> Unit, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TileSize),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Note(stringResource(R.string.trash_note), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.failedAccounts.forEach { email ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Note(
                    stringResource(R.string.trash_drive_failed, email),
                    MaterialTheme.colorScheme.error
                )
            }
        }
        if (!state.loading && state.entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    icon = painterResource(DesignR.drawable.ic_delete),
                    title = stringResource(R.string.trash_empty_state_title),
                    body = stringResource(R.string.trash_empty_state_body)
                )
            }
        }
        items(state.entries, key = { it.key }) { entry ->
            TrashTile(entry, entry.key in state.selection, onToggle = { onToggle(entry.key) })
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun TrashTile(entry: TrashEntry, selected: Boolean, onToggle: () -> Unit) {
    val days = entry.daysLeft()
    val left = days?.let { pluralStringResource(R.plurals.trash_days_left, it, it) }
    val where = entry.account ?: stringResource(R.string.trash_on_phone)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                enabled = entry.owned,
                onValueChange = { onToggle() }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(entry.ref.name, where, left).joinToString(", ")
            }
    ) {
        AsyncImage(
            model = entry.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (left != null) DaysLeft(left, Modifier.align(Alignment.BottomStart))
        if (entry.ref.isDrive) {
            Icon(
                painterResource(DesignR.drawable.ic_cloud),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(3.dp)
                    .size(14.dp)
            )
        }
        if (selected) {
            Icon(
                painterResource(DesignR.drawable.ic_check_circle_filled),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .size(22.dp)
            )
        }
    }
}

/** How long a binned item has left, on its tile. */
@Composable
private fun DaysLeft(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = LABEL_ALPHA),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun TrashActions(onRestore: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 4.dp)
        ) {
            TextButton(onClick = onRestore) {
                Icon(
                    painterResource(DesignR.drawable.ic_restore_from_trash),
                    contentDescription = null
                )
                Text(
                    stringResource(R.string.trash_restore),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            TextButton(onClick = onDelete) {
                Icon(painterResource(DesignR.drawable.ic_delete_forever), contentDescription = null)
                Text(
                    stringResource(R.string.trash_delete_forever),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** Whole days until the bin deletes [this] for good, never below 0. */
private fun TrashEntry.daysLeft(now: Long = System.currentTimeMillis()): Int? =
    expiresAt?.let { ceil((it - now) / DAY_MS).toInt().coerceAtLeast(0) }

private const val DAY_MS = 24.0 * 60 * 60 * 1000
private const val LABEL_ALPHA = 0.8f
private val TileSize = 104.dp
