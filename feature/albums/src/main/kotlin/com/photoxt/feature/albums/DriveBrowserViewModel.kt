package com.photoxt.feature.albums

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.auth.DriveScope
import com.photoxt.core.common.PxtLog
import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.FOLDER_LIST_FIELDS
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.drive.SHORTCUT_MIME
import com.photoxt.core.drive.driveLiteral
import com.photoxt.core.sync.DriveSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Drive browser starts (spec §4.2). */
enum class DriveRoot { MY_DRIVE, SHARED_WITH_ME, SHARED_DRIVES, STARRED }

/** A folder in the browser. A shared drive is a folder whose id is its [driveId]. */
@Immutable
data class BrowserFolder(val id: String, val name: String, val driveId: String? = null)

@Immutable
data class DriveBrowserUiState(
    /** Drive access was asked and Google's consent screen must be shown with this. */
    val consent: PendingIntent? = null,
    val granted: Boolean = false,
    val root: DriveRoot = DriveRoot.MY_DRIVE,
    /** The folders opened below [root], outermost first. */
    val path: List<BrowserFolder> = emptyList(),
    val query: String = "",
    val folders: List<BrowserFolder> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val merge: Boolean = false,
    /** Picking the folder an unavailable album follows from now on (Relink), not adding one. */
    val relinking: Boolean = false
) {
    /** The folder that "Add" would add: the innermost opened one. */
    val current: BrowserFolder? get() = path.lastOrNull()
}

/**
 * The in-app Drive folder browser of one account: My Drive, shared, starred and search. With
 * [relinkAlbumId] it picks the folder that unavailable album follows from now on (spec §4.3).
 */
@HiltViewModel(assistedFactory = DriveBrowserViewModel.Factory::class)
class DriveBrowserViewModel @AssistedInject constructor(
    @Assisted(ACCOUNT) val accountId: String,
    @Assisted(RELINK) private val relinkAlbumId: String?,
    private val auth: DriveAuth,
    private val http: DriveHttp,
    private val sync: DriveSync
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted(ACCOUNT) accountId: String,
            @Assisted(RELINK) relinkAlbumId: String?
        ): DriveBrowserViewModel
    }

    private val state = MutableStateFlow(DriveBrowserUiState(relinking = relinkAlbumId != null))
    val uiState: StateFlow<DriveBrowserUiState> = state.asStateFlow()
    private var nextPage: String? = null
    private var loadJob: Job? = null
    private val account = DriveAccount(accountId)

    init {
        requestAccess()
    }

    /** Asks for read access to Drive (spec §2: `drive.readonly` with the first folder). */
    fun requestAccess() {
        viewModelScope.launch {
            val result = runCatching { auth.authorize(accountId, DriveScope.READONLY) }
                .onFailure { PxtLog.w(TAG, "Drive authorization failed", it) }
                .getOrNull()
            when (result) {
                is Authorization.Granted -> {
                    state.update { it.copy(granted = true, consent = null) }
                    reload()
                }

                is Authorization.NeedsConsent -> state.update { it.copy(consent = result.intent) }

                null -> state.update { it.copy(error = true) }
            }
        }
    }

    fun consentResult(data: Intent?) {
        viewModelScope.launch {
            if (auth.completeConsent(accountId, data)) {
                state.update { it.copy(granted = true, consent = null) }
                reload()
            }
        }
    }

    fun selectRoot(root: DriveRoot) {
        state.update { it.copy(root = root, path = emptyList(), query = "") }
        reload()
    }

    fun search(query: String) {
        state.update { it.copy(query = query, path = emptyList()) }
        reload()
    }

    fun open(folder: BrowserFolder) {
        state.update { it.copy(path = it.path + folder, query = "") }
        reload()
    }

    /** Goes up one folder; false when already at the top. */
    fun up(): Boolean {
        if (state.value.path.isEmpty()) return false
        state.update { it.copy(path = it.path.dropLast(1)) }
        reload()
        return true
    }

    fun setMerge(merge: Boolean) = state.update { it.copy(merge = merge) }

    /**
     * Tracks the current folder, or relinks the album to it; returns the album id. Null when
     * there is no folder open, or the folder is another album already.
     */
    suspend fun addCurrent(): String? {
        val folder = state.value.current ?: return null
        val relink = relinkAlbumId
        return if (relink != null) {
            relink.takeIf { sync.relink(it, folder.id, folder.driveId) }
        } else {
            sync.addFolder(accountId, folder.id, folder.name, folder.driveId, state.value.merge)
        }
    }

    fun reload() {
        nextPage = null
        state.update { it.copy(folders = emptyList()) }
        loadMore()
    }

    /** Loads the next page of folders, if there is one and none is loading. */
    fun loadMore() {
        if (loadJob?.isActive == true) return
        val first = state.value.folders.isEmpty()
        if (!first && nextPage == null) return
        loadJob = viewModelScope.launch {
            state.update { it.copy(loading = true, error = false) }
            runCatching { http.folderPage(account, state.value, nextPage) }
                .onSuccess { (folders, next) ->
                    nextPage = next
                    state.update { it.copy(folders = it.folders + folders, loading = false) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    PxtLog.w(TAG, "Listing Drive folders failed", error)
                    state.update { it.copy(loading = false, error = true) }
                }
        }
    }

    private companion object {
        const val TAG = "DriveBrowser"
        const val ACCOUNT = "account"
        const val RELINK = "relink"
    }
}

/** One page of the folders the browser shows at [at]. */
private suspend fun DriveHttp.folderPage(
    account: DriveAccount,
    at: DriveBrowserUiState,
    pageToken: String?
): Pair<List<BrowserFolder>, String?> {
    val current = at.current
    if (current == null && at.query.isBlank() && at.root == DriveRoot.SHARED_DRIVES) {
        val drives = api.sharedDrives(account, pageToken)
        return drives.drives.map { BrowserFolder(it.id, it.name, it.id) } to
            drives.nextPageToken
    }
    val folders = "mimeType = '$FOLDER_MIME'"
    val query = when {
        current != null ->
            "${driveLiteral(current.id)} in parents and ($folders or " +
                "mimeType = '$SHORTCUT_MIME') and trashed = false"

        at.query.isNotBlank() ->
            "name contains ${driveLiteral(at.query.trim())} and $folders and trashed = false"

        at.root == DriveRoot.SHARED_WITH_ME -> "sharedWithMe and $folders and trashed = false"

        at.root == DriveRoot.STARRED -> "starred and $folders and trashed = false"

        else ->
            "'root' in parents and ($folders or mimeType = '$SHORTCUT_MIME') and " +
                "trashed = false"
    }
    val inDrive = current?.driveId
    val page = api.list(
        account,
        query,
        FOLDER_LIST_FIELDS,
        pageToken,
        pageSize = PAGE_SIZE,
        orderBy = "name",
        corpora = inDrive?.let { "drive" },
        driveId = inDrive
    )
    return page.files.mapNotNull { it.asFolder(inDrive) } to page.nextPageToken
}

private fun DriveFile.asFolder(inDrive: String?): BrowserFolder? = when {
    isFolder -> BrowserFolder(id, name, driveId ?: inDrive)

    // A shortcut to a folder opens the folder it points to (spec §4.2).
    isShortcut && shortcutDetails?.targetMimeType == FOLDER_MIME ->
        shortcutDetails?.targetId?.let { BrowserFolder(it, name, driveId ?: inDrive) }

    else -> null
}

private const val PAGE_SIZE = 200
