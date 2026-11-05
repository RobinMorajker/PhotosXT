package com.photoxt.feature.timeline

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.ui.FastScroller
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.GridDensity
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest
import com.photoxt.core.ui.MediaGrid
import com.photoxt.core.ui.rememberDateGroupLabels
import kotlinx.coroutines.launch

/**
 * The Pictures tab: every photo and video. [empty] shows when there is nothing to show yet.
 * [onSelectionModeChange] reports multi-select starting and ending, so the app can swap its
 * navigation bar for the selection actions.
 */
@Composable
fun PicturesScreen(
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory> { it.create(null) }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, onBack = null, modifier)
}

/** One album's photos and videos, with a back button. */
@Composable
fun AlbumScreen(
    albumId: String,
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory>(key = albumId) {
        it.create(albumId)
    }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, onBack, modifier)
}

@Composable
private fun TimelineScreen(
    viewModel: TimelineViewModel,
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val context = LocalContext.current
    val tooManyToShare = pluralStringResource(
        R.plurals.share_too_many,
        TimelineViewModel.MAX_SHARE,
        TimelineViewModel.MAX_SHARE
    )
    val scope = rememberCoroutineScope()
    val selecting = state.selection.isNotEmpty()
    val reportSelectionMode by rememberUpdatedState(onSelectionModeChange)
    LaunchedEffect(selecting) { reportSelectionMode(selecting) }
    DisposableEffect(Unit) { onDispose { reportSelectionMode(false) } }
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    TimelineContent(
        title = title,
        state = state,
        rows = rows,
        permissions = viewModel.permissions,
        callbacks = TimelineCallbacks(
            onAccessResult = viewModel::refreshAccess,
            onFilterChange = viewModel::setFilter,
            onDensityChange = viewModel::setDensity,
            onSelectionChange = viewModel::setSelection,
            onSelectGroup = viewModel::selectGroup,
            onShare = {
                scope.launch {
                    val targets = viewModel.shareTargets()
                    if (targets == null) {
                        Toast.makeText(context, tooManyToShare, Toast.LENGTH_SHORT).show()
                    } else {
                        context.shareMedia(targets)
                    }
                }
            }
        ),
        empty = empty,
        modifier = modifier,
        onBack = onBack
    )
}

/** Everything a timeline screen can be asked to do. */
class TimelineCallbacks(
    val onAccessResult: () -> Unit = {},
    val onFilterChange: (MediaFilter) -> Unit = {},
    val onDensityChange: (GridDensity) -> Unit = {},
    val onSelectionChange: (Map<String, Long>) -> Unit = {},
    val onSelectGroup: (groupKey: Int, select: Boolean) -> Unit = { _, _ -> },
    val onShare: () -> Unit = {}
)

/** The timeline screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineContent(
    title: String,
    state: TimelineUiState,
    rows: LazyPagingItems<GridCell>,
    permissions: Array<String>,
    callbacks: TimelineCallbacks,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    val selected = state.selection.size
    val navigationIcon: (@Composable () -> Unit)? = when {
        selected > 0 -> {
            { CancelSelectionButton { callbacks.onSelectionChange(emptyMap()) } }
        }

        onBack != null -> {
            { BackButton(onBack) }
        }

        else -> null
    }
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = if (selected > 0) {
                pluralStringResource(R.plurals.selected_count, selected, selected)
            } else {
                title
            },
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon
        )
        if (state.access == MediaAccess.DENIED) {
            MediaAccessRequest(permissions, callbacks.onAccessResult, Modifier.weight(1f))
        } else {
            TimelineGrid(state, rows, permissions, callbacks, empty, Modifier.weight(1f))
            if (selected > 0) SelectionActions(onShare = callbacks.onShare)
        }
    }
}

@Composable
private fun TimelineGrid(
    state: TimelineUiState,
    rows: LazyPagingItems<GridCell>,
    permissions: Array<String>,
    callbacks: TimelineCallbacks,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val labels = rememberDateGroupLabels(state.density.grouping)
    val loaded = rows.loadState.refresh is LoadState.NotLoading
    Box(modifier) {
        MediaGrid(
            rows = rows,
            layout = state.layout,
            density = state.density,
            onDensityChange = callbacks.onDensityChange,
            selection = state.selection,
            onSelectionChange = callbacks.onSelectionChange,
            onSelectGroup = callbacks.onSelectGroup,
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            leading = {
                Column {
                    if (state.access == MediaAccess.LIMITED) {
                        LimitedAccessBanner(permissions, callbacks.onAccessResult)
                    }
                    FilterChips(state.filter, callbacks.onFilterChange)
                    if (loaded && rows.itemCount == 0) {
                        if (state.filter == MediaFilter.ALL) empty() else FilteredEmpty()
                    }
                }
            }
        )
        FastScroller(
            state = gridState,
            itemCount = rows.itemCount + 1,
            labelAt = { index ->
                val group = state.layout.groupOf(index - 1)
                if (group < 0) null else labels(state.layout.keys[group])
            },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
