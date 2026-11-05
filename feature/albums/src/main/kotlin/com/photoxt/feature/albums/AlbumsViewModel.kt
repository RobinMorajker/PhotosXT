package com.photoxt.feature.albums

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.DeviceThumbnail
import com.photoxt.core.media.MediaAccessMonitor
import com.photoxt.core.model.MediaAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

@Immutable
data class AlbumTile(
    val id: String,
    /** The folder's own name; screens show a translated name for recognised folders. */
    val title: String,
    val folder: DeviceFolder,
    val count: Int,
    val cover: Any?
)

@Immutable
data class AlbumsUiState(val access: MediaAccess, val albums: List<AlbumTile>? = null)

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    albumDao: AlbumDao,
    private val mediaAccess: MediaAccessMonitor,
    private val deviceMediaSync: DeviceMediaSync
) : ViewModel() {
    val permissions: Array<String> get() = mediaAccess.permissions

    val uiState: StateFlow<AlbumsUiState> =
        combine(mediaAccess.state, albumDao.albums()) { access, albums ->
            AlbumsUiState(access, arrange(albums))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AlbumsUiState(mediaAccess.state.value)
        )

    /** After a permission answer: re-read access and index whatever became visible. */
    fun refreshAccess() = deviceMediaSync.refresh()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Camera, then Screenshots, then every other album with the newest first. */
internal fun arrange(albums: List<AlbumSummary>): List<AlbumTile> = albums
    .map { album ->
        AlbumTile(
            id = album.id,
            title = album.title,
            folder = recogniseFolder(album.sourceRef.substringAfter(':')),
            count = album.count,
            cover = album.coverUri?.let { DeviceThumbnail(it, album.coverModifiedAt) }
        )
    }
    .sortedBy { tile ->
        when (tile.folder) {
            DeviceFolder.CAMERA -> 0
            DeviceFolder.SCREENSHOTS -> 1
            else -> 2
        }
    }
