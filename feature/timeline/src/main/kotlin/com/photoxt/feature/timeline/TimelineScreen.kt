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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.photoxt.core.media.shareMedia
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.ui.FastScroller
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.GridDensity
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest
import com.photoxt.core.ui.MediaGrid
import com.photoxt.core.ui.heroSourceKey
import com.photoxt.core.ui.rememberDateGroupLabels
import kotlinx.coroutines.launch

/**
 * Where a timeline sends the user: the viewer, Compare for two selected items, a nested Drive
 * album, or the Drive browser to relink an unavailable album.
 */
class TimelineNavigation(
    val onOpen: (ViewerStart) -> Unit = {},
    val onCompare: (firstId: String, secondId: String) -> Unit = { _, _ -> },
    val onOpenAlbum: (id: String, title: String) -> Unit = { _, _ -> },
    val onRelink: (albumId: String, accountId: String) -> Unit = { _, _ -> }
)

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
    modifier: Modifier = Modifier,
    navigation: TimelineNavigation = TimelineNavigation()
) {
    val viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory> { it.create(null) }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, null, navigation, modifier)
}

/**
 * One album's photos and videos, with a back button. A Drive album also shows its subfolders as
 * nested albums ([onOpenAlbum]) and, for a tracked folder, merging and removal.
 */
@Composable
fun AlbumScreen(
    albumId: String,
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navigation: TimelineNavigation = TimelineNavigation()
) {
    val viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory>(key = albumId) {
        it.create(albumId)
    }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, onBack, navigation, modifier)
}

@Composable
private fun TimelineScreen(
    viewModel: TimelineViewModel,
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    navigation: TimelineNavigation,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val share = rememberShare(viewModel)
    val refresh = rememberRefresh(viewModel)
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
            onShare = share,
            onOpen = { item, index ->
                navigation.onOpen(ViewerStart(viewModel.albumId, state.filter, index, item.id))
            },
            onCompare = {
                viewModel.comparePair()?.let { (newer, older) ->
                    viewModel.clearSelection()
                    navigation.onCompare(newer, older)
                }
            },
            onOpenAlbum = navigation.onOpenAlbum,
            onMerge = viewModel::setMerge,
            onOffline = viewModel::setOffline,
            onRemoveDriveFolder = {
                scope.launch {
                    viewModel.removeDriveFolder()
                    onBack?.invoke()
                }
            },
            onRelink = {
                val albumId = viewModel.albumId
                val drive = state.drive
                if (albumId != null && drive != null) navigation.onRelink(albumId, drive.accountId)
            },
            onRefresh = refresh
        ),
        empty = empty,
        modifier = modifier,
        onBack = onBack,
        heroSourceKey = heroSourceKey(viewModel.albumId, state.filter)
    )
}

/** Pull-to-refresh, saying so when Google Drive could not be reached. */
@Composable
private fun rememberRefresh(viewModel: TimelineViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unreachable = stringResource(R.string.refresh_failed)
    return {
        scope.launch {
            if (!viewModel.refresh()) {
                Toast.makeText(
                    context,
                    unreachable,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/** Shares the selection through the system sheet, or says it is too big. */
@Composable
private fun rememberShare(viewModel: TimelineViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tooManyToShare = pluralStringResource(
        R.plurals.share_too_many,
        TimelineViewModel.MAX_SHARE,
        TimelineViewModel.MAX_SHARE
    )
    return {
        scope.launch {
            val targets = viewModel.shareTargets()
            if (targets == null) {
                Toast.makeText(context, tooManyToShare, Toast.LENGTH_SHORT).show()
            } else {
                context.shareMedia(targets)
            }
        }
    }
}

/** Everything a timeline screen can be asked to do. */
data class TimelineCallbacks(
    val onAccessResult: () -> Unit = {},
    val onFilterChange: (MediaFilter) -> Unit = {},
    val onDensityChange: (GridDensity) -> Unit = {},
    val onSelectionChange: (Map<String, Long>) -> Unit = {},
    val onSelectGroup: (groupKey: Int, select: Boolean) -> Unit = { _, _ -> },
    val onShare: () -> Unit = {},
    /** Opens an item, given with its position among items only. */
    val onOpen: (GridCell.Item, index: Int) -> Unit = { _, _ -> },
    /** Compares the two selected items. */
    val onCompare: () -> Unit = {},
    /** Opens a nested Drive album. */
    val onOpenAlbum: (id: String, title: String) -> Unit = { _, _ -> },
    /** Nests or merges a tracked Drive folder's subfolders. */
    val onMerge: (Boolean) -> Unit = {},
    /** Keeps a tracked Drive folder available offline, or stops. */
    val onOffline: (Boolean) -> Unit = {},
    /** Removes a tracked Drive folder from PhotoXT. */
    val onRemoveDriveFolder: () -> Unit = {},
    /** Points an unavailable Drive album at a folder again. */
    val onRelink: () -> Unit = {},
    /** Pull-to-refresh. */
    val onRefresh: () -> Unit = {}
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
    onBack: (() -> Unit)? = null,
    heroSourceKey: String? = null
) {
    // Outside the header's scroll connection, so pulling down first expands the header and
    // only a pull past it refreshes (spec §4.3, pull-to-refresh).
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = callbacks.onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        TimelineColumn(title, state, rows, permissions, callbacks, empty, onBack, heroSourceKey)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineColumn(
    title: String,
    state: TimelineUiState,
    rows: LazyPagingItems<GridCell>,
    permissions: Array<String>,
    callbacks: TimelineCallbacks,
    empty: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    heroSourceKey: String?
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
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = if (selected > 0) {
                pluralStringResource(R.plurals.selected_count, selected, selected)
            } else {
                title
            },
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = {
                val drive = state.drive
                if (selected == 0 && drive != null && drive.isRoot) {
                    DriveAlbumMenu(
                        drive,
                        callbacks.onMerge,
                        callbacks.onOffline,
                        callbacks.onRemoveDriveFolder
                    )
                }
            }
        )
        if (state.access == MediaAccess.DENIED) {
            MediaAccessRequest(permissions, callbacks.onAccessResult, Modifier.weight(1f))
        } else {
            TimelineGrid(
                state,
                rows,
                permissions,
                callbacks,
                empty,
                heroSourceKey,
                inAlbum = onBack != null,
                modifier = Modifier.weight(1f)
            )
            if (selected > 0) {
                SelectionActions(
                    onShare = callbacks.onShare,
                    onCompare = callbacks.onCompare.takeIf { selected == 2 }
                )
            }
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
    heroSourceKey: String?,
    inAlbum: Boolean,
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
            onOpen = callbacks.onOpen,
            heroSourceKey = heroSourceKey,
            leading = {
                Column {
                    if (state.access == MediaAccess.LIMITED) {
                        LimitedAccessBanner(permissions, callbacks.onAccessResult)
                    }
                    val drive = state.drive
                    if (drive != null) {
                        DriveAlbumHeader(
                            drive,
                            callbacks.onOpenAlbum,
                            callbacks.onRelink,
                            callbacks.onRemoveDriveFolder
                        )
                    }
                    FilterChips(
                        state.filter,
                        callbacks.onFilterChange,
                        // Inside one album every item has the same source; only Drive items can
                        // be offline.
                        when {
                            !inAlbum -> MediaFilter.entries
                            drive != null -> AlbumFilters
                            else -> AlbumFilters - MediaFilter.OFFLINE
                        }
                    )
                    if (loaded && rows.itemCount == 0 && drive?.children.isNullOrEmpty()) {
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

private val AlbumFilters = MediaFilter.entries - setOf(MediaFilter.DEVICE, MediaFilter.DRIVE)
