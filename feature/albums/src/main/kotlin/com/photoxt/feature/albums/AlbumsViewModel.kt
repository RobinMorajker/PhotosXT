package com.photoxt.feature.albums

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Immutable
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
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import com.photoxt.core.sync.DriveSync
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    val drive: DriveStatus? = null
)

@Immutable
data class AlbumsUiState(
    val access: MediaAccess,
    val albums: List<AlbumTile>? = null,
    val accounts: List<AccountEntity> = emptyList()
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
    private val driveSync: DriveSync
) : ViewModel() {
    val permissions: Array<String> get() = mediaAccess.permissions

    val uiState: StateFlow<AlbumsUiState> =
        combine(
            mediaAccess.state,
            albumDao.albums(),
            googleAccounts.accounts,
            driveSync.online
        ) { access, albums, accounts, online ->
            AlbumsUiState(access, arrange(albums, accounts, online), accounts)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AlbumsUiState(mediaAccess.state.value)
        )

    /** After a permission answer: re-read access and index whatever became visible. */
    fun refreshAccess() = deviceMediaSync.refresh()

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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Camera, then Screenshots, then every other album with the newest first. */
internal fun arrange(
    albums: List<AlbumSummary>,
    accounts: List<AccountEntity> = emptyList(),
    online: Boolean = true
): List<AlbumTile> {
    val needsConsent = accounts.filter { it.needsConsent }.mapTo(HashSet()) { it.id }
    return albums
        .map { album ->
            val drive = album.type == AlbumType.DRIVE_FOLDER
            AlbumTile(
                id = album.id,
                title = album.title,
                folder = if (drive) {
                    DeviceFolder.OTHER
                } else {
                    recogniseFolder(album.sourceRef.substringAfter(':'))
                },
                count = album.count,
                cover = album.cover(),
                drive = if (drive) album.status(album.accountId in needsConsent, online) else null
            )
        }
        .sortedBy { tile ->
            when (tile.folder) {
                DeviceFolder.CAMERA -> 0
                DeviceFolder.SCREENSHOTS -> 1
                else -> 2
            }
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

private fun AlbumSummary.status(needsConsent: Boolean, online: Boolean): DriveStatus = when {
    needsConsent || state == TrackedFolderState.UNAVAILABLE -> DriveStatus.NEEDS_ATTENTION
    !online -> DriveStatus.OFFLINE
    state == TrackedFolderState.SCANNING -> DriveStatus.SYNCING
    else -> DriveStatus.SYNCED
}
