package com.photoxt.feature.albums

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.transfer.Clip
import com.photoxt.core.ui.LimitedAccessBanner
import com.photoxt.core.ui.MediaAccessRequest
import com.photoxt.core.ui.PasteBar
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
    viewModel: AlbumsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<AccountsSheetMode?>(null) }
    var placeDialog by rememberSaveable { mutableStateOf<PlaceDialog?>(null) }
    val actions = rememberAccountActions(viewModel, onBrowseDrive, closeSheet = { sheet = null })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unreachable = stringResource(R.string.refresh_failed)
    AlbumsContent(
        title = title,
        state = state,
        permissions = viewModel.permissions,
        onAccessResult = viewModel::refreshAccess,
        onOpenAlbum = onOpenAlbum,
        onAddDriveFolder = {
            when (state.accounts.size) {
                0 -> actions.onAddAccount(true)
                1 -> onBrowseDrive(state.accounts.single().id)
                else -> sheet = AccountsSheetMode.PICK
            }
        },
        onAccounts = { sheet = AccountsSheetMode.MANAGE },
        onNewAlbum = { placeDialog = PlaceDialog.NEW_ALBUM },
        onPasteAlbums = { placeDialog = PlaceDialog.PASTE },
        onClearClipboard = viewModel::clearClipboard,
        onReconnect = actions.onReconnect,
        onRefresh = {
            scope.launch {
                if (!viewModel.refresh()) {
                    Toast.makeText(context, unreachable, Toast.LENGTH_SHORT).show()
                }
            }
        },
        empty = empty,
        modifier = modifier
    )
    sheet?.let { mode ->
        AccountsSheet(mode, state.accounts, actions, onDismiss = { sheet = null })
    }
    placeDialog?.let { dialog ->
        // The screen's scope: the dialog's own would end as it closes, before the album is made.
        PlaceDialogFor(dialog, state, viewModel, scope, onOpenAlbum) { placeDialog = null }
    }
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
    onRefresh: () -> Unit = {}
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
            onReconnect = onReconnect
        )
    }
}

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
    onReconnect: (accountId: String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = title,
            scrollBehavior = scrollBehavior,
            actions = { HeaderActions(onNewAlbum, onAddDriveFolder, onAccounts) }
        )
        val albums = state.albums
        // Without phone access and without Drive albums there is nothing else to show.
        if (state.access == MediaAccess.DENIED && albums.isNullOrEmpty()) {
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
            if (albums != null && albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { empty() }
            }
            items(albums.orEmpty(), key = { it.id }) { album ->
                val name = album.displayName()
                AlbumTileView(album, name, onClick = { onOpenAlbum(album.id, name) })
            }
        }
        state.clip?.let { AlbumsPasteBar(it, onPasteAlbums, onClearClipboard) }
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
    onAccounts: () -> Unit
) {
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
private fun AlbumTile.displayName(): String = when (folder) {
    DeviceFolder.CAMERA -> stringResource(R.string.album_camera)

    DeviceFolder.SCREENSHOTS -> stringResource(R.string.album_screenshots)

    DeviceFolder.DOWNLOADS -> stringResource(R.string.album_downloads)

    DeviceFolder.WHATSAPP, DeviceFolder.OTHER ->
        title.ifEmpty { stringResource(R.string.album_phone_storage) }
}

/** One album tile; a Drive album carries a Drive badge and its sync status (spec §4.1). */
@Composable
fun AlbumTileView(
    album: AlbumTile,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val count = pluralStringResource(R.plurals.album_item_count, album.count, album.count)
    val status = album.drive?.label()?.let { stringResource(it) }
    val badge = if (album.drive != null) stringResource(R.string.drive_badge) else null
    Column(
        modifier = modifier
            .clip(TileShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(name, badge, count, status).joinToString(", ")
            }
    ) {
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
            if (album.drive != null) {
                Icon(
                    painterResource(album.drive.icon()),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(4.dp)
                        .size(16.dp)
                )
            }
        }
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
