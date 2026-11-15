package com.photoxt.feature.albums

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.SignInResult
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.transfer.Clip
import com.photoxt.core.ui.AlbumSortSheet
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest
import com.photoxt.core.ui.PasteBar
import com.photoxt.core.ui.requestAlbumShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Albums tab: phone folders and Drive folders (spec §4.2), the Google accounts behind them,
 * and "Add Drive folder". [onOpenAlbum] gets the album id and the title to show;
 * [onBrowseDrive] opens the Drive folder browser for an account. [empty] shows when there are
 * no albums yet.
 */
@Composable
fun AlbumsScreen(
    title: String,
    empty: @Composable () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onBrowseDrive: (accountId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGroup: (id: String, title: String) -> Unit = { _, _ -> },
    viewModel: AlbumsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberAlbumsUi()
    val actions = rememberAccountActions(viewModel, onBrowseDrive, closeSheet = { ui.sheet = null })
    val scope = rememberCoroutineScope()
    val tileActions = rememberTileActions(viewModel, state) { dialog, tile ->
        ui.target = tile?.id
        ui.own = dialog
    }
    AlbumsContent(
        title = title,
        state = state,
        permissions = viewModel.permissions,
        onAccessResult = viewModel::refreshAccess,
        onOpenAlbum = { id, name ->
            if (id.startsWith(AlbumOptions.GROUP_PREFIX)) {
                onOpenGroup(id.removePrefix(AlbumOptions.GROUP_PREFIX), name)
            } else {
                onOpenAlbum(id, name)
            }
        },
        onAddDriveFolder = {
            when (state.accounts.size) {
                0 -> actions.onAddAccount(true)
                1 -> onBrowseDrive(state.accounts.single().id)
                else -> ui.sheet = AccountsSheetMode.PICK
            }
        },
        onAccounts = { ui.sheet = AccountsSheetMode.MANAGE },
        onNewAlbum = { ui.own = OwnDialog.NEW_KIND },
        onPasteAlbums = { ui.place = PlaceDialog.PASTE },
        onClearClipboard = viewModel::clearClipboard,
        onReconnect = actions.onReconnect,
        onRefresh = rememberRefresh(viewModel, scope),
        empty = empty,
        modifier = modifier,
        order = AlbumsOrderCallbacks(
            onSort = { ui.sorting = true },
            onEditOrder = { viewModel.setEditingOrder(true) },
            onEditOrderDone = { viewModel.setEditingOrder(false) },
            onReorder = viewModel::reorder
        ),
        tileActions = tileActions
    )
    AlbumsDialogs(ui, state, viewModel, actions, scope, onOpenAlbum)
}

/** Which sheet or dialog of the Albums tab is open, kept through a rotation. */
@Stable
private class AlbumsUi(
    sheet: MutableState<AccountsSheetMode?>,
    place: MutableState<PlaceDialog?>,
    sorting: MutableState<Boolean>,
    own: MutableState<OwnDialog?>,
    target: MutableState<String?>
) {
    var sheet by sheet
    var place by place
    var sorting by sorting
    var own by own

    /** The tile an [own] dialog is about. */
    var target by target
}

@Composable
private fun rememberAlbumsUi() = AlbumsUi(
    rememberSaveable { mutableStateOf(null) },
    rememberSaveable { mutableStateOf(null) },
    rememberSaveable { mutableStateOf(false) },
    rememberSaveable { mutableStateOf(null) },
    rememberSaveable { mutableStateOf(null) }
)

/** Pull-to-refresh; a toast when Drive can't be reached. */
@Composable
private fun rememberRefresh(viewModel: AlbumsViewModel, scope: CoroutineScope): () -> Unit {
    val context = LocalContext.current
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

@Composable
private fun AlbumsDialogs(
    ui: AlbumsUi,
    state: AlbumsUiState,
    viewModel: AlbumsViewModel,
    actions: AccountActions,
    scope: CoroutineScope,
    onOpenAlbum: (id: String, title: String) -> Unit
) {
    ui.sheet?.let { mode ->
        AccountsSheet(mode, state.accounts, actions, onDismiss = { ui.sheet = null })
    }
    ui.place?.let { dialog ->
        // The screen's scope: the dialog's own would end as it closes, before the album is made.
        PlaceDialogFor(dialog, state, viewModel, scope, onOpenAlbum) { ui.place = null }
    }
    if (ui.sorting) {
        AlbumSortSheet(state.sort, viewModel::setSort, onDismiss = { ui.sorting = false })
    }
    OwnDialogs(
        dialog = ui.own,
        tile = state.albums?.firstOrNull { it.id == ui.target },
        groups = state.groups,
        organize = viewModel.organize,
        onNewFolder = { ui.place = PlaceDialog.NEW_ALBUM },
        onOpenAlbum = onOpenAlbum,
        onOpen = { ui.own = it },
        onDismiss = { ui.own = null }
    )
}

/** Which question about a place is open: a new album, or where clipped albums go. */
private enum class PlaceDialog { NEW_ALBUM, PASTE }

@Composable
private fun PlaceDialogFor(
    dialog: PlaceDialog,
    state: AlbumsUiState,
    viewModel: AlbumsViewModel,
    scope: CoroutineScope,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val newAlbum = dialog == PlaceDialog.NEW_ALBUM
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    AlbumPlaceDialog(
        title = stringResource(
            if (newAlbum) R.string.new_album_title else R.string.paste_albums_title
        ),
        confirm = stringResource(
            if (newAlbum) R.string.action_create else R.string.action_paste_here
        ),
        accounts = state.accounts,
        askName = newAlbum,
        onConfirm = { name, place ->
            onDismiss()
            scope.launch {
                if (newAlbum) {
                    runCatching { viewModel.newAlbum(name, place) }
                        .onSuccess { id ->
                            if (id != null) {
                                onOpenAlbum(id, name)
                            } else {
                                toast(resources.getString(R.string.new_album_drive, name))
                            }
                        }
                        .onFailure { toast(resources.getString(R.string.new_album_failed)) }
                } else if (viewModel.pasteAlbums(place).isFailure) {
                    toast(resources.getString(R.string.paste_failed))
                }
            }
        },
        onDismiss = onDismiss
    )
}

/** The long-press menu's actions, some through a dialog ([onDialog]) first. */
@Composable
private fun rememberTileActions(
    viewModel: AlbumsViewModel,
    state: AlbumsUiState,
    onDialog: (OwnDialog, AlbumTile?) -> Unit
): TileMenuActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unsupported = stringResource(R.string.shortcut_unsupported)
    val organize = viewModel.organize
    val movable = state.sort.field == AlbumSortField.CUSTOM
    return TileMenuActions(
        onPin = { tile, pinned -> organize.pin(tile.id, pinned) },
        onMove = { tile: AlbumTile, up: Boolean -> viewModel.move(tile.id, up) }
            .takeIf { movable || state.albums.orEmpty().any { it.pinned } },
        onHide = { organize.hide(it.id) },
        onHideFromPictures = { tile, hidden -> organize.hideFromPictures(tile.id, hidden) },
        onGroup = { onDialog(OwnDialog.PICK_GROUP, it) },
        onUngroupAlbum = { organize.moveToGroup(it.id, null) },
        onShortcut = { tile ->
            scope.launch {
                val name = tile.name.ifEmpty { tile.title }
                if (!requestAlbumShortcut(context, tile.id, name, tile.cover)) {
                    Toast.makeText(context, unsupported, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onRename = { tile ->
            onDialog(
                if (tile.kind ==
                    TileKind.GROUP
                ) {
                    OwnDialog.RENAME_GROUP
                } else {
                    OwnDialog.RENAME
                },
                tile
            )
        },
        onDelete = { tile ->
            if (tile.kind == TileKind.GROUP) {
                organize.deleteGroup(tile.id.removePrefix(AlbumOptions.GROUP_PREFIX))
            } else {
                onDialog(OwnDialog.DELETE, tile)
            }
        }
    )
}

/** Adding, removing and reconnecting Google accounts, with their system screens. */
@Composable
private fun rememberAccountActions(
    viewModel: AlbumsViewModel,
    onBrowseDrive: (accountId: String) -> Unit,
    closeSheet: () -> Unit
): AccountActions {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consentFor by rememberSaveable { mutableStateOf<String?>(null) }
    val notConfigured = stringResource(R.string.sign_in_not_configured)
    val failed = stringResource(R.string.sign_in_failed)
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> consentFor?.let { viewModel.consentResult(it, result.data) } }
    return AccountActions(
        onAddAccount = { thenBrowse ->
            if (activity != null) {
                scope.launch {
                    when (val result = viewModel.signIn(activity)) {
                        is SignInResult.Added -> {
                            closeSheet()
                            viewModel.accountAdded(result.account.id)
                            if (thenBrowse) onBrowseDrive(result.account.id)
                        }

                        SignInResult.NotConfigured ->
                            Toast.makeText(context, notConfigured, Toast.LENGTH_LONG).show()

                        SignInResult.Failed ->
                            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()

                        SignInResult.Cancelled -> Unit
                    }
                }
            }
        },
        onRemove = viewModel::removeAccount,
        onReconnect = { accountId ->
            scope.launch {
                val result = viewModel.reconnect(accountId)
                if (result is Authorization.NeedsConsent) {
                    consentFor = accountId
                    consent.launch(IntentSenderRequest.Builder(result.intent).build())
                }
            }
        },
        onPick = { accountId ->
            closeSheet()
            onBrowseDrive(accountId)
        }
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
    modifier: Modifier = Modifier,
    onAddDriveFolder: () -> Unit = {},
    onAccounts: () -> Unit = {},
    onNewAlbum: () -> Unit = {},
    onPasteAlbums: () -> Unit = {},
    onClearClipboard: () -> Unit = {},
    onReconnect: (accountId: String) -> Unit = {},
    onRefresh: () -> Unit = {},
    order: AlbumsOrderCallbacks = AlbumsOrderCallbacks(),
    tileActions: TileMenuActions = TileMenuActions()
) {
    // Outside the header's scroll connection: a pull expands the header first (spec §4.3).
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        AlbumsColumn(
            title = title,
            state = state,
            permissions = permissions,
            onAccessResult = onAccessResult,
            onOpenAlbum = onOpenAlbum,
            empty = empty,
            onAddDriveFolder = onAddDriveFolder,
            onAccounts = onAccounts,
            onNewAlbum = onNewAlbum,
            onPasteAlbums = onPasteAlbums,
            onClearClipboard = onClearClipboard,
            onReconnect = onReconnect,
            order = order,
            tileActions = tileActions
        )
    }
}

/** Sorting and Custom order of the Albums tab (spec §4.2). */
class AlbumsOrderCallbacks(
    val onSort: () -> Unit = {},
    val onEditOrder: () -> Unit = {},
    val onEditOrderDone: () -> Unit = {},
    val onReorder: (movedId: String, targetId: String, after: Boolean) -> Unit = { _, _, _ -> }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsColumn(
    title: String,
    state: AlbumsUiState,
    permissions: Array<String>,
    onAccessResult: () -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    empty: @Composable () -> Unit,
    onAddDriveFolder: () -> Unit,
    onAccounts: () -> Unit,
    onNewAlbum: () -> Unit,
    onPasteAlbums: () -> Unit,
    onClearClipboard: () -> Unit,
    onReconnect: (accountId: String) -> Unit,
    order: AlbumsOrderCallbacks,
    tileActions: TileMenuActions
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    val gridState = rememberLazyGridState()
    val drag = remember { TileDrag() }
    KeepAtTop(gridState, state.albums?.firstOrNull()?.id, state.sort)
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        AlbumsHeader(title, state, scrollBehavior, order) {
            HeaderActions(onNewAlbum, onAddDriveFolder, onAccounts, order, state)
        }
        val albums = state.albums
        // Without phone access and without Drive albums there is nothing else to show.
        if (state.access == MediaAccess.DENIED && albums.isNullOrEmpty()) {
            MediaAccessRequest(permissions, onAccessResult, Modifier.weight(1f))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TileMinSize),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .dragToReorder(state.editingOrder, gridState, drag, order.onReorder)
        ) {
            banners(state, permissions, onAccessResult, onReconnect, empty)
            items(albums.orEmpty(), key = { it.id }) { album ->
                val name = album.displayName()
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.dragMark(drag, album.id)) {
                    AlbumTileView(
                        album,
                        name,
                        onClick = { if (!state.editingOrder) onOpenAlbum(album.id, name) },
                        onLongClick = { menu = true }.takeIf { !state.editingOrder }
                    )
                    TileMenu(album, menu, tileActions, onDismiss = { menu = false })
                }
            }
        }
        state.clip?.let { AlbumsPasteBar(it, onPasteAlbums, onClearClipboard) }
    }
}

/** A tile's cover with its badges: pinned at the start, its kind or Drive status at the end. */
@Composable
private fun TileCover(album: AlbumTile) {
    Box {
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
        if (album.pinned) TileBadge(DesignR.drawable.ic_push_pin, Alignment.TopStart, pin = true)
        album.kindIcon()?.let { TileBadge(it, Alignment.TopEnd) }
        album.drive?.let { TileBadge(it.icon(), Alignment.TopEnd) }
    }
}

/** A small round badge on a tile's cover; decorative, as the tile's description says it. */
@Composable
private fun BoxScope.TileBadge(@DrawableRes icon: Int, alignment: Alignment, pin: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = if (pin) colors.onPrimaryContainer else colors.onSecondaryContainer,
        modifier = Modifier
            .align(alignment)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (pin) colors.primaryContainer else colors.secondaryContainer)
            .padding(4.dp)
            .size(16.dp)
    )
}

/**
 * Back to the top for a new sort, and kept there when albums arrive above the first one, as when
 * Camera and Screenshots are pinned on their first appearance: the grid would otherwise keep its
 * old first album in view, with the pins out of sight above it (seen on the A15, 2026-11-15).
 */
@Composable
private fun KeepAtTop(grid: LazyGridState, firstId: String?, sort: AlbumSort) {
    val shown = remember { ShownTop(firstId, sort) }
    // Before the next measure, which would otherwise keep the old first album in place.
    SideEffect {
        val atTop = grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
        if (sort != shown.sort || (firstId != shown.firstId && atTop)) grid.requestScrollToItem(0)
        shown.firstId = firstId
        shown.sort = sort
    }
}

private class ShownTop(var firstId: String?, var sort: AlbumSort)

/** The title, or *Edit order* with Done; [actions] when not editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsHeader(
    title: String,
    state: AlbumsUiState,
    scrollBehavior: TopAppBarScrollBehavior,
    order: AlbumsOrderCallbacks,
    actions: @Composable () -> Unit
) {
    PxtCollapsingHeader(
        title = if (state.editingOrder) stringResource(R.string.editing_order_title) else title,
        scrollBehavior = scrollBehavior,
        navigationIcon = if (state.editingOrder) {
            {
                IconButton(onClick = order.onEditOrderDone) {
                    Icon(
                        painterResource(DesignR.drawable.ic_check),
                        contentDescription = stringResource(R.string.action_done)
                    )
                }
            }
        } else {
            null
        },
        actions = { if (!state.editingOrder) actions() }
    )
}

/** Above the albums: accounts to reconnect, phone access, and the empty state. */
private fun LazyGridScope.banners(
    state: AlbumsUiState,
    permissions: Array<String>,
    onAccessResult: () -> Unit,
    onReconnect: (accountId: String) -> Unit,
    empty: @Composable () -> Unit
) {
    state.reconnect.forEach { account ->
        item(key = "reconnect:${account.id}", span = { GridItemSpan(maxLineSpan) }) {
            ReconnectBanner(account, onReconnect)
        }
    }
    if (state.access == MediaAccess.DENIED) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MediaAccessRequest(permissions, onAccessResult, Modifier.height(AccessHeight))
        }
    }
    if (state.access == MediaAccess.LIMITED) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LimitedAccessBanner(permissions, onAccessResult)
        }
    }
    if (state.albums?.isEmpty() == true) {
        item(span = { GridItemSpan(maxLineSpan) }) { empty() }
    }
}

/** The clipboard on the Albums tab: clipped albums paste at the top; items need an album. */
@Composable
private fun AlbumsPasteBar(clip: Clip, onPasteAlbums: () -> Unit, onClearClipboard: () -> Unit) {
    val albums = clip.albumIds.isNotEmpty()
    PasteBar(
        text = if (albums) {
            pluralStringResource(R.plurals.clip_albums_bar, clip.size, clip.size)
        } else {
            pluralStringResource(R.plurals.clip_items_bar, clip.size, clip.size)
        },
        onClear = onClearClipboard,
        onPaste = onPasteAlbums.takeIf { albums }
    )
}

/**
 * *New album* (spec §4.7), "Add Drive folder" and the account button (spec §4.1: the header's
 * account avatar).
 */
@Composable
private fun HeaderActions(
    onNewAlbum: () -> Unit,
    onAddDriveFolder: () -> Unit,
    onAccounts: () -> Unit,
    order: AlbumsOrderCallbacks,
    state: AlbumsUiState
) {
    IconButton(onClick = order.onSort) {
        Icon(
            painterResource(DesignR.drawable.ic_sort),
            contentDescription = stringResource(R.string.action_sort)
        )
    }
    val canEdit =
        state.sort.field == AlbumSortField.CUSTOM || state.albums.orEmpty().any { it.pinned }
    if (canEdit) {
        IconButton(onClick = order.onEditOrder) {
            Icon(
                painterResource(DesignR.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.action_edit_order)
            )
        }
    }
    IconButton(onClick = onNewAlbum) {
        Icon(
            painterResource(DesignR.drawable.ic_create_new_folder),
            contentDescription = stringResource(R.string.action_new_album)
        )
    }
    IconButton(onClick = onAddDriveFolder) {
        Icon(
            painterResource(DesignR.drawable.ic_add),
            contentDescription = stringResource(R.string.action_add_drive_folder)
        )
    }
    IconButton(onClick = onAccounts) {
        Icon(
            painterResource(DesignR.drawable.ic_account_circle),
            contentDescription = stringResource(R.string.action_accounts)
        )
    }
}

/** "Google Drive needs your permission again" for [account], with Reconnect (spec §2). */
@Composable
fun ReconnectBanner(
    account: AccountEntity,
    onReconnect: (accountId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.reconnect_banner, account.email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onReconnect(account.id) }) {
                Text(stringResource(R.string.action_reconnect))
            }
        }
    }
}

@Composable
internal fun AlbumTile.displayName(): String = if (kind !=
    TileKind.FOLDER
) {
    title
} else {
    when (folder) {
        DeviceFolder.CAMERA -> stringResource(R.string.album_camera)

        DeviceFolder.SCREENSHOTS -> stringResource(R.string.album_screenshots)

        DeviceFolder.DOWNLOADS -> stringResource(R.string.album_downloads)

        DeviceFolder.WHATSAPP, DeviceFolder.OTHER ->
            title.ifEmpty { stringResource(R.string.album_phone_storage) }
    }
}

/** One album tile; a Drive album carries a Drive badge and its sync status (spec §4.1). */
@Composable
fun AlbumTileView(
    album: AlbumTile,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val count = if (album.kind == TileKind.GROUP) {
        pluralStringResource(R.plurals.group_album_count, album.members, album.members)
    } else {
        pluralStringResource(R.plurals.album_item_count, album.count, album.count)
    }
    val status = album.drive?.label()?.let { stringResource(it) }
    val badge = when {
        album.drive != null -> stringResource(R.string.drive_badge)
        album.kind == TileKind.VIRTUAL -> stringResource(R.string.virtual_badge)
        album.kind == TileKind.SMART -> stringResource(R.string.smart_badge)
        album.kind == TileKind.GROUP -> stringResource(R.string.group_badge)
        else -> null
    }
    val pinned = if (album.pinned) stringResource(R.string.pinned_badge) else null
    Column(
        modifier = modifier
            .clip(TileShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    listOfNotNull(name, badge, pinned, count, status).joinToString(", ")
            }
    ) {
        TileCover(album)
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp)
        )
        Text(
            text = listOfNotNull(count, status).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (album.drive == DriveStatus.NEEDS_ATTENTION) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private fun AlbumTile.kindIcon(): Int? = when (kind) {
    TileKind.VIRTUAL -> DesignR.drawable.ic_photo_library
    TileKind.SMART -> DesignR.drawable.ic_auto_awesome
    TileKind.GROUP -> DesignR.drawable.ic_folder
    TileKind.FOLDER -> null
}

private fun DriveStatus.label(): Int? = when (this) {
    DriveStatus.SYNCED -> null
    DriveStatus.SYNCING -> R.string.drive_status_syncing
    DriveStatus.OFFLINE -> R.string.drive_status_offline
    DriveStatus.NEEDS_ATTENTION -> R.string.drive_status_attention
}

private fun DriveStatus.icon(): Int = when (this) {
    DriveStatus.SYNCED -> DesignR.drawable.ic_cloud
    DriveStatus.SYNCING -> DesignR.drawable.ic_sync
    DriveStatus.OFFLINE -> DesignR.drawable.ic_cloud_off
    DriveStatus.NEEDS_ATTENTION -> DesignR.drawable.ic_error
}

private val TileMinSize = 100.dp
private val TileShape = RoundedCornerShape(12.dp)
private val AccessHeight = 360.dp
