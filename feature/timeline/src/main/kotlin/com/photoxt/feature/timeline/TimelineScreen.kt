package com.photoxt.feature.timeline

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import com.photoxt.core.model.ClipboardMode
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaList
import com.photoxt.core.model.PINNED_KEY
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.transfer.PasteException
import com.photoxt.core.transfer.PasteProblem
import com.photoxt.core.ui.FastScroller
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.GridDensity
import com.photoxt.core.ui.ITEM_SORTS
import com.photoxt.core.ui.ItemSortSheet
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest
import com.photoxt.core.ui.MediaGrid
import com.photoxt.core.ui.PICTURES_SORTS
import com.photoxt.core.ui.heroSourceKey
import com.photoxt.core.ui.initialOf
import com.photoxt.core.ui.rememberGroupLabels
import java.time.LocalDate
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
    val viewModel =
        hiltViewModel<TimelineViewModel, TimelineViewModel.Factory> { it.create(null, null) }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, null, navigation, modifier)
}

/**
 * A Library list (spec §4.1): Favorites, Videos, Screenshots, Offline, or the Hidden list behind
 * its unlock, each with its own sort (spec §4.2).
 */
@Composable
fun MediaListScreen(
    list: MediaList,
    title: String,
    empty: @Composable () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navigation: TimelineNavigation = TimelineNavigation()
) {
    val viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory>(key = list.name) {
        it.create(null, list)
    }
    TimelineScreen(viewModel, title, empty, onSelectionModeChange, onBack, navigation, modifier)
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
        it.create(albumId, null)
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
    val selecting = state.selection.isNotEmpty()
    val reportSelectionMode by rememberUpdatedState(onSelectionModeChange)
    LaunchedEffect(selecting) { reportSelectionMode(selecting) }
    DisposableEffect(Unit) { onDispose { reportSelectionMode(false) } }
    BackHandler(enabled = selecting) { viewModel.setSelection(emptyMap()) }

    var dialog by rememberSaveable { mutableStateOf<TimelineDialog?>(null) }
    var organizeDialog by rememberSaveable { mutableStateOf<OrganizeDialog?>(null) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val fileOps = rememberFileOps(viewModel, title, navigation, onBack)
    val callbacks = timelineCallbacks(viewModel, state, navigation, fileOps, onBack) {
        dialog = it
    }.copy(onSort = { sorting = true })
    TimelineContent(
        title = title,
        state = state,
        rows = rows,
        permissions = viewModel.permissions,
        callbacks = callbacks.withOrganize(
            viewModel,
            state,
            rememberShortcut(viewModel, title, rows)
        ) { organizeDialog = it },
        empty = empty,
        modifier = modifier,
        onBack = onBack,
        heroSourceKey = heroSourceKey(viewModel.albumId, state.filter),
        inAlbum = viewModel.albumId != null
    )
    if (sorting) TimelineSortSheet(viewModel, state.sort) { sorting = false }
    OrganizeDialogs(
        dialog = organizeDialog,
        viewModel = viewModel,
        title = title,
        selected = state.selection.size,
        onOpenAlbum = navigation.onOpenAlbum,
        onBack = onBack,
        onOpen = { organizeDialog = it },
        onDismiss = { organizeDialog = null }
    )
    val selectionTitle = fileOps.selectionTitle(state.selection.size)
    TimelineDialogs(
        dialog = dialog,
        selected = state.selection.size,
        albumTitle = title,
        onDelete = { viewModel.fileOps.delete(selectionTitle) },
        onRename = { viewModel.fileOps.rename(it, selectionTitle) },
        onNewFolder = fileOps.newFolder,
        onRenameAlbum = fileOps.renameAlbum,
        onPasteEdits = fileOps.pasteEdits,
        onDismiss = { dialog = null }
    )
}

/** What the timeline's parts can ask for, answered by [viewModel], [navigation] and [fileOps]. */
@Composable
private fun timelineCallbacks(
    viewModel: TimelineViewModel,
    state: TimelineUiState,
    navigation: TimelineNavigation,
    fileOps: FileOps,
    onBack: (() -> Unit)?,
    onDialog: (TimelineDialog) -> Unit
): TimelineCallbacks {
    val scope = rememberCoroutineScope()
    val removeDriveFolder: () -> Unit = {
        scope.launch {
            viewModel.removeDriveFolder()
            onBack?.invoke()
        }
    }
    return TimelineCallbacks(
        onAccessResult = viewModel::refreshAccess,
        onFilterChange = viewModel::setFilter,
        onDensityChange = viewModel::setDensity,
        onSelectionChange = viewModel::setSelection,
        onSelectGroup = viewModel::selectGroup,
        onOpen = { item, index ->
            navigation.onOpen(
                ViewerStart(
                    viewModel.albumId,
                    state.filter,
                    index,
                    item.id,
                    state.sort,
                    viewModel.list
                )
            )
        },
        onOpenAlbum = navigation.onOpenAlbum,
        onRemoveDriveFolder = removeDriveFolder,
        onRelink = {
            val albumId = viewModel.albumId
            val drive = state.drive
            if (albumId != null && drive != null) navigation.onRelink(albumId, drive.accountId)
        },
        onRefresh = rememberRefresh(viewModel),
        selection = selectionCallbacks(
            viewModel,
            state.selection.size,
            navigation,
            fileOps,
            onDialog
        ),
        album = AlbumCallbacks(
            onNewFolder = { onDialog(TimelineDialog.NEW_FOLDER) },
            onRename = { onDialog(TimelineDialog.RENAME_ALBUM) },
            onClip = viewModel.fileOps::clipAlbum,
            onMerge = viewModel::setMerge,
            onOffline = viewModel::setOffline,
            onRemove = removeDriveFolder
        ),
        onPaste = fileOps.paste,
        onClearClipboard = viewModel.fileOps::clearClipboard
    )
}

/** The selection bar's actions for [selected] items. */
@Composable
private fun selectionCallbacks(
    viewModel: TimelineViewModel,
    selected: Int,
    navigation: TimelineNavigation,
    fileOps: FileOps,
    onDialog: (TimelineDialog) -> Unit
): SelectionCallbacks {
    val canPasteEdits by viewModel.edits.canPaste.collectAsStateWithLifecycle(false)
    val compare: () -> Unit = {
        viewModel.fileOps.comparePair()?.let { (newer, older) ->
            viewModel.setSelection(emptyMap())
            navigation.onCompare(newer, older)
        }
    }
    return SelectionCallbacks(
        onShare = rememberShare(viewModel),
        onCut = { viewModel.fileOps.clip(ClipboardMode.CUT) },
        onCopy = { viewModel.fileOps.clip(ClipboardMode.COPY) },
        onDelete = { onDialog(TimelineDialog.DELETE) },
        onRename = { onDialog(TimelineDialog.RENAME_ITEMS) },
        onConvert = fileOps.convert,
        onExportPdf = fileOps.exportPdf,
        onCopyToClipboard = fileOps.copyToClipboard.takeIf { selected == 1 },
        onCompare = compare.takeIf { selected == 2 },
        onPasteEdits = { onDialog(TimelineDialog.PASTE_EDITS) }.takeIf { canPasteEdits }
    )
}

/** File operations that answer with a toast or open something (spec §4.7). */
private data class FileOps(
    val paste: () -> Unit,
    val convert: () -> Unit,
    val pasteEdits: (Set<PastePart>) -> Unit,
    val exportPdf: () -> Unit,
    val copyToClipboard: () -> Unit,
    val newFolder: (String) -> Unit,
    val renameAlbum: (String) -> Unit,
    val selectionTitle: (Int) -> String
)

@Composable
private fun rememberFileOps(
    viewModel: TimelineViewModel,
    title: String,
    navigation: TimelineNavigation,
    onBack: (() -> Unit)?
): FileOps {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val ops = viewModel.fileOps
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    return FileOps(
        paste = {
            scope.launch {
                ops.paste().exceptionOrNull()?.let { toast(resources.getString(pasteError(it))) }
            }
        },
        convert = {
            scope.launch {
                if (!ops.convert(title)) toast(resources.getString(R.string.convert_none))
            }
        },
        pasteEdits = { parts ->
            scope.launch {
                val queued = viewModel.edits.paste(parts, title)
                if (!queued) toast(resources.getString(R.string.paste_edits_none))
            }
        },
        exportPdf = rememberPdfExport(ops, ::toast),
        copyToClipboard = {
            scope.launch {
                if (ops.copyToSystemClipboard()) {
                    toast(resources.getString(R.string.copied_to_clipboard))
                } else {
                    toast(resources.getString(R.string.copy_to_clipboard_failed))
                }
            }
        },
        newFolder = { name ->
            scope.launch {
                runCatching { ops.newFolder(name) }
                    .onSuccess { id ->
                        if (id != null) {
                            navigation.onOpenAlbum(id, name)
                        } else {
                            toast(resources.getString(R.string.folder_creating, name))
                        }
                    }
                    .onFailure { toast(resources.getString(R.string.folder_failed)) }
            }
        },
        renameAlbum = { name ->
            scope.launch {
                // A phone album's items move into a new folder, which is a new album.
                if (ops.renameAlbum(name) != null && viewModel.isPhoneAlbum) onBack?.invoke()
            }
        },
        selectionTitle = { resources.getQuantityString(R.plurals.clip_items, it, it) }
    )
}

/** Export as PDF: the system's save screen, then a toast with the pages written. */
@Composable
private fun rememberPdfExport(ops: TimelineFileOps, toast: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val pdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val pages = ops.exportPdf(context.contentResolver, uri)
                toast(
                    if (pages > 0) {
                        resources.getQuantityString(R.plurals.pdf_saved, pages, pages)
                    } else {
                        resources.getString(R.string.pdf_failed)
                    }
                )
            }
        }
    }
    return { pdf.launch(resources.getString(R.string.pdf_file_name, LocalDate.now())) }
}

/** Why a paste queued nothing, to say so. */
private fun pasteError(error: Throwable): Int =
    if ((error as? PasteException)?.problem == PasteProblem.INTO_ITSELF) {
        R.string.paste_into_itself
    } else {
        R.string.paste_nothing
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
        TimelineFileOps.MAX_SHARE,
        TimelineFileOps.MAX_SHARE
    )
    return {
        scope.launch {
            val targets = viewModel.fileOps.shareTargets()
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
    /** Opens the Sort sheet (spec §4.2). */
    val onSort: () -> Unit = {},
    /** Custom order's *Edit order* ends. */
    val onEditOrderDone: () -> Unit = {},
    /** Drops a dragged tile in Custom order. */
    val onReorder: (movedId: String, targetId: String, after: Boolean) -> Unit = { _, _, _ -> },
    val onAccessResult: () -> Unit = {},
    val onFilterChange: (MediaFilter) -> Unit = {},
    val onDensityChange: (GridDensity) -> Unit = {},
    val onSelectionChange: (Map<String, Long>) -> Unit = {},
    val onSelectGroup: (groupKey: Int, select: Boolean) -> Unit = { _, _ -> },
    /** Opens an item, given with its position among items only. */
    val onOpen: (GridCell.Item, index: Int) -> Unit = { _, _ -> },
    /** Opens a nested Drive album. */
    val onOpenAlbum: (id: String, title: String) -> Unit = { _, _ -> },
    /** Removes a tracked Drive folder from PhotoXT. */
    val onRemoveDriveFolder: () -> Unit = {},
    /** Points an unavailable Drive album at a folder again. */
    val onRelink: () -> Unit = {},
    /** Pull-to-refresh. */
    val onRefresh: () -> Unit = {},
    /** The selection's actions (spec §4.1, §4.7). */
    val selection: SelectionCallbacks = SelectionCallbacks(),
    /** An album's menu. */
    val album: AlbumCallbacks = AlbumCallbacks(),
    val onPaste: () -> Unit = {},
    val onClearClipboard: () -> Unit = {}
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
    heroSourceKey: String? = null,
    /** An album's grid, with its menu; a Library list has Back but no album. */
    inAlbum: Boolean = onBack != null
) {
    // Outside the header's scroll connection, so pulling down first expands the header and
    // only a pull past it refreshes (spec §4.3, pull-to-refresh).
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = callbacks.onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        TimelineColumn(
            title,
            state,
            rows,
            permissions,
            callbacks,
            empty,
            onBack,
            heroSourceKey,
            inAlbum
        )
    }
}

/** The Sort sheet (spec §4.2): Custom order in albums only, Apply to all albums from one. */
@Composable
private fun TimelineSortSheet(viewModel: TimelineViewModel, sort: ItemSort, onDismiss: () -> Unit) {
    val inAlbum = viewModel.albumId != null
    ItemSortSheet(
        sort = sort,
        fields = if (!inAlbum && viewModel.list == null) {
            PICTURES_SORTS
        } else {
            ITEM_SORTS.filter { it != ItemSortField.CUSTOM || inAlbum }
        },
        onSort = viewModel::setSort,
        onDismiss = onDismiss,
        onApplyToAll = viewModel::applySortToAll.takeIf { inAlbum }
    )
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
    heroSourceKey: String?,
    inAlbum: Boolean
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    val selected = state.selection.size
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        TimelineHeader(title, state, callbacks, onBack, scrollBehavior, inAlbum)
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
                inAlbum = inAlbum,
                modifier = Modifier.weight(1f)
            )
            val clip = state.clip
            if (selected > 0) {
                SelectionActions(callbacks.selection)
            } else if (clip != null) {
                ClipboardBar(
                    clip = clip,
                    inAlbum = inAlbum,
                    onPaste = callbacks.onPaste,
                    onClear = callbacks.onClearClipboard
                )
            }
        }
    }
}

/** The title, or the selection count, or *Edit order*, with what each can do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineHeader(
    title: String,
    state: TimelineUiState,
    callbacks: TimelineCallbacks,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
    inAlbum: Boolean
) {
    val selected = state.selection.size
    val navigationIcon: (@Composable () -> Unit)? = when {
        state.editingOrder -> {
            { DoneButton(callbacks.onEditOrderDone) }
        }

        selected > 0 -> {
            { CancelSelectionButton { callbacks.onSelectionChange(emptyMap()) } }
        }

        onBack != null -> {
            { BackButton(onBack) }
        }

        else -> null
    }
    PxtCollapsingHeader(
        title = when {
            state.editingOrder -> stringResource(R.string.editing_order_title)
            selected > 0 -> pluralStringResource(R.plurals.selected_count, selected, selected)
            else -> title
        },
        scrollBehavior = scrollBehavior,
        navigationIcon = navigationIcon,
        actions = {
            if (selected == 0 && !state.editingOrder) {
                SortButton(callbacks.onSort)
                if (inAlbum) {
                    AlbumMenu(
                        state.drive,
                        callbacks.album,
                        state.album,
                        custom = state.sort.field == ItemSortField.CUSTOM
                    )
                }
            }
        }
    )
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
    val labels = rememberGroupLabels(state.density.grouping)
    val context = LocalContext.current
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
            onOpen = callbacks.onOpen.takeIf { !state.editingOrder },
            heroSourceKey = heroSourceKey,
            onReorder = callbacks.onReorder.takeIf { state.editingOrder },
            leading = { GridLeading(state, rows, permissions, callbacks, empty, inAlbum) }
        )
        FastScroller(
            state = gridState,
            itemCount = rows.itemCount + 1,
            labelAt = { index -> scrollLabel(state, rows, labels, context, index - 1) },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

/** Above the items: the limited-access banner, a Drive album's header, the filters, empty. */
@Composable
private fun GridLeading(
    state: TimelineUiState,
    rows: LazyPagingItems<GridCell>,
    permissions: Array<String>,
    callbacks: TimelineCallbacks,
    empty: @Composable () -> Unit,
    inAlbum: Boolean
) {
    val loaded = rows.loadState.refresh is LoadState.NotLoading
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
            // Inside one album every item has the same source; only Drive items can be offline.
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

/**
 * The fast scroller's bubble at item [index]: its group's date, or in a plain grid the sort key
 * (spec §4.2, Layout): the name's initial or the size.
 */
private fun scrollLabel(
    state: TimelineUiState,
    rows: LazyPagingItems<GridCell>,
    labels: (Int) -> String,
    context: Context,
    index: Int
): String? {
    val group = state.layout.groupOf(index)
    return when {
        group < 0 -> null

        state.layout.headed || state.layout.keys[group] == PINNED_KEY -> labels(
            state.layout.keys[group]
        )

        else -> (rows.peek(index) as? GridCell.Item)?.let { item ->
            when (state.sort.field) {
                ItemSortField.NAME -> initialOf(item.name)
                ItemSortField.SIZE -> Formatter.formatShortFileSize(context, item.bytes)
                else -> null
            }
        }
    }
}

private val AlbumFilters = MediaFilter.entries - setOf(MediaFilter.DEVICE, MediaFilter.DRIVE)
