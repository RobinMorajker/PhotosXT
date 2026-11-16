package com.photoxt.feature.albums

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.auth.DriveScope
import com.photoxt.core.auth.GoogleAccounts
import com.photoxt.core.auth.SignInResult
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.MediaAccessMonitor
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.TrackedFolderState
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.organize.Pins
import com.photoxt.core.organize.SettingsSync
import com.photoxt.core.sync.DriveSync
import com.photoxt.core.transfer.Clip
import com.photoxt.core.transfer.NewAlbumPlace
import com.photoxt.core.transfer.Transfers
import com.photoxt.core.ui.NamedChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Folders PhotoXT recognises on the phone (spec §4.2). They sort first or get a fixed name. */
enum class DeviceFolder { CAMERA, SCREENSHOTS, DOWNLOADS, WHATSAPP, OTHER }

/** Recognises a device folder from its MediaStore relative path, e.g. `DCIM/Camera/`. */
fun recogniseFolder(relativePath: String): DeviceFolder {
    val path = relativePath.trimEnd('/').lowercase()
    return when {
        path == "dcim/camera" -> DeviceFolder.CAMERA
        path.endsWith("/screenshots") || path == "screenshots" -> DeviceFolder.SCREENSHOTS
        path == "download" || path.startsWith("download/") -> DeviceFolder.DOWNLOADS
        "whatsapp" in path -> DeviceFolder.WHATSAPP
        else -> DeviceFolder.OTHER
    }
}

/** A Drive album's sync status chip (spec §4.1). */
enum class DriveStatus { SYNCED, SYNCING, OFFLINE, NEEDS_ATTENTION }

@Immutable
data class AlbumTile(
    val id: String,
    /** The folder's own name; screens show a translated name for recognised folders. */
    val title: String,
    val folder: DeviceFolder,
    val count: Int,
    val cover: Any?,
    /** Null for a device album. */
    val drive: DriveStatus? = null,
    val kind: TileKind = TileKind.FOLDER,
    /** Pinned to the top of Albums (spec §4.8). */
    val pinned: Boolean = false,
    val hideFromPictures: Boolean = false,
    val groupId: String? = null,
    /** The name it shows and sorts by; empty until placed. */
    val name: String = "",
    /** A group's albums. */
    val members: Int = 0
)

@Immutable
data class AlbumsUiState(
    val access: MediaAccess,
    val albums: List<AlbumTile>? = null,
    val accounts: List<AccountEntity> = emptyList(),
    /** Pull-to-refresh is running. */
    val refreshing: Boolean = false,
    /** What the clipboard holds (spec §4.7); clipped albums can be pasted at the top. */
    val clip: Clip? = null,
    val sort: AlbumSort = AlbumSort(),
    /** Custom order's *Edit order* (spec §4.2): dragging moves tiles. */
    val editingOrder: Boolean = false,
    val groups: List<NamedChoice> = emptyList()
) {
    /** Accounts whose Drive grant lapsed: the "Reconnect Google Drive" banner. */
    val reconnect: List<AccountEntity> get() = accounts.filter { it.needsConsent }
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    albumDao: AlbumDao,
    private val mediaAccess: MediaAccessMonitor,
    private val deviceMediaSync: DeviceMediaSync,
    private val googleAccounts: GoogleAccounts,
    private val driveAuth: DriveAuth,
    private val driveSync: DriveSync,
    private val transfers: Transfers,
    private val settingsSync: SettingsSync,
    private val tiles: AlbumTiles,
    private val pins: Pins,
    private val options: AlbumOptions,
    private val savedState: SavedStateHandle,
    organizeParts: AlbumsOrganizeParts
) : ViewModel() {
    val permissions: Array<String> get() = mediaAccess.permissions

    private val refreshing = MutableStateFlow(false)
    private val editingOrder = savedState.getStateFlow(KEY_EDITING, false)

    /** Pinning, hiding, groups, own albums and Custom order of albums (spec §4.2, §4.8). */
    val organize: AlbumsOrganize = organizeParts.forScreen(viewModelScope)

    init {
        // Camera and Screenshots are pinned the first time they appear (spec §4.2).
        viewModelScope.launch {
            albumDao.albums().collect { albums -> pins.autoPin(tiles.autoPinned(albums)) }
        }
    }

    val uiState: StateFlow<AlbumsUiState> =
        combine(
            combine(
                mediaAccess.state,
                tiles.tiles(groupId = null),
                googleAccounts.accounts,
                refreshing
            ) { access, albums, accounts, refreshing ->
                AlbumsUiState(access, albums, accounts, refreshing)
            },
            transfers.clipboard,
            options.albumsSort,
            editingOrder,
            options.groups
        ) { state, clip, sort, editing, groups ->
            state.copy(
                clip = clip,
                sort = sort,
                editingOrder = editing,
                groups = groups.map { NamedChoice(it.id, it.name) }
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AlbumsUiState(mediaAccess.state.value)
        )

    /** Account [accountId] was just added: settings sync may restore its albums (spec §4.8). */
    fun accountAdded(accountId: String) {
        viewModelScope.launch { settingsSync.accountAdded(accountId) }
    }

    fun setSort(sort: AlbumSort) {
        val onScreen = uiState.value.albums.orEmpty().filterNot { it.pinned }.map { it.id }
        viewModelScope.launch { options.setAlbumsSort(sort, onScreen) }
    }

    /** Custom order's *Edit order*: dragging an album moves it. */
    fun setEditingOrder(on: Boolean) {
        savedState[KEY_EDITING] = on
    }

    /** *Move up* or *Move down* (spec §4.2, for TalkBack and switch access). */
    fun move(albumId: String, up: Boolean) {
        val tiles = uiState.value.albums.orEmpty()
        val section = sectionOf(tiles, albumId) ?: return
        val index = section.indexOf(albumId)
        val other = section.getOrNull(if (up) index - 1 else index + 1) ?: return
        reorder(albumId, other, after = !up)
    }

    /** Drops [movedId] before or [after] [targetId]: among pins, or in Custom order. */
    fun reorder(movedId: String, targetId: String, after: Boolean) {
        val tiles = uiState.value.albums.orEmpty()
        val section = sectionOf(tiles, movedId) ?: return
        if (targetId !in section || movedId == targetId) return
        val rest = section - movedId
        val at = rest.indexOf(targetId) + if (after) 1 else 0
        val order = rest.subList(0, at) + movedId + rest.subList(at, rest.size)
        viewModelScope.launch {
            if (tiles.first { it.id == movedId }.pinned) {
                pins.reorder(TargetType.ALBUM, order)
            } else {
                options.setOrder(order)
            }
        }
    }

    /** The ids [albumId] can move among: the pins, or the rest in Custom order. */
    private fun sectionOf(tiles: List<AlbumTile>, albumId: String): List<String>? {
        val tile = tiles.firstOrNull { it.id == albumId } ?: return null
        return when {
            tile.pinned -> tiles.filter { it.pinned }.map { it.id }

            uiState.value.sort.field == AlbumSortField.CUSTOM ->
                tiles.filterNot { it.pinned }.map { it.id }

            else -> null
        }
    }

    /** After a permission answer: re-read access and index whatever became visible. */
    fun refreshAccess() = deviceMediaSync.refresh()

    /**
     * Pull-to-refresh (spec §4.3): the phone's folders and every account's Drive changes. False
     * when Drive could not be reached.
     */
    suspend fun refresh(): Boolean {
        if (refreshing.value) return true
        refreshing.value = true
        return try {
            deviceMediaSync.refresh()
            driveSync.refresh()
        } finally {
            refreshing.value = false
        }
    }

    /** Google's account chooser over [activity]; the added account, or why there is none. */
    suspend fun signIn(activity: Activity): SignInResult = googleAccounts.signIn(activity)

    fun removeAccount(accountId: String) {
        viewModelScope.launch { driveSync.removeAccount(accountId) }
    }

    /** Asks Drive again for [accountId]; the screen shows the consent screen if needed. */
    suspend fun reconnect(accountId: String): Authorization {
        val result = driveAuth.authorize(accountId, DriveScope.READONLY)
        if (result is Authorization.Granted) driveSync.reconnected(accountId)
        return result
    }

    /** The consent screen's answer for [accountId]. */
    fun consentResult(accountId: String, data: Intent?) {
        viewModelScope.launch {
            if (driveAuth.completeConsent(accountId, data)) driveSync.reconnected(accountId)
        }
    }

    /**
     * *New album* (spec §4.7): a phone album exists at once (its id, to open it); a Drive folder
     * is made by the queue (null). Throws when the phone's storage isn't there.
     */
    suspend fun newAlbum(name: String, place: NewAlbumPlace): String? =
        transfers.newAlbum(place, name)

    /** Pastes clipped albums at the top of the phone or of a My Drive. */
    suspend fun pasteAlbums(place: NewAlbumPlace): Result<Long> = transfers.pasteAlbums(place)

    fun clearClipboard() {
        viewModelScope.launch { transfers.clearClipboard() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_EDITING = "editingOrder"
    }
}

/** The album's newest item as a thumbnail model, from whichever source it is in. */
fun AlbumSummary.cover(): Any? {
    val id = coverId ?: return null
    val drive = type == AlbumType.DRIVE_FOLDER
    return thumbnailOf(
        MediaRef(
            id = id,
            source = if (drive) SourceType.DRIVE else SourceType.DEVICE,
            contentUri = coverUri,
            modifiedAt = coverModifiedAt,
            accountId = accountId,
            remoteId = if (drive) id.substringAfterLast(':') else null,
            remoteVersion = coverVersion
        )
    )
}

internal fun AlbumSummary.status(needsConsent: Boolean, online: Boolean): DriveStatus = when {
    needsConsent || state == TrackedFolderState.UNAVAILABLE -> DriveStatus.NEEDS_ATTENTION
    !online -> DriveStatus.OFFLINE
    state == TrackedFolderState.SCANNING -> DriveStatus.SYNCING
    else -> DriveStatus.SYNCED
}
