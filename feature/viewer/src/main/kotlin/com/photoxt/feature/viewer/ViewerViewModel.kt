package com.photoxt.feature.viewer

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.database.positionOf
import com.photoxt.core.database.viewerItems
import com.photoxt.core.media.BlurHash
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.GeoPoint
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.media.ImageTraitsReader
import com.photoxt.core.media.MediaDetails
import com.photoxt.core.media.MediaDetailsReader
import com.photoxt.core.media.Original
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.ui.heroSourceKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A BlurHash decoded to [pixels] (ARGB) of [width] × [height]. */
class Blur(val width: Int, val height: Int, val pixels: IntArray)

/** What the details sheet shows for one item. */
data class ViewerDetails(val item: ViewerItem, val folder: String?, val media: MediaDetails)

/** The viewer of one grid's items, starting at [start] (spec §4.5). */
@HiltViewModel(assistedFactory = ViewerViewModel.Factory::class)
class ViewerViewModel @AssistedInject constructor(
    @Assisted val start: ViewerStart,
    private val mediaDao: MediaDao,
    private val traitsReader: ImageTraitsReader,
    private val detailsReader: MediaDetailsReader,
    private val session: ViewerSession,
    private val originals: DriveOriginals
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(start: ViewerStart): ViewerViewModel
    }

    /** The grid this viewer was opened from, for the flight back into it. */
    val sourceKey: String = heroSourceKey(start.albumId, start.filter)

    private val query = MediaListQuery(start.filter, start.albumId)

    /** The items, in the grid's order, loaded around the start so it shows at once. */
    val items: Flow<PagingData<ViewerItem>> =
        Pager(pagingConfig, initialKey = start.index) { mediaDao.viewerItems(query) }
            .flow
            .cachedIn(viewModelScope)

    val displayMode: StateFlow<DisplayMode> = session.displayMode.asStateFlow()
    val zoomLock: StateFlow<Boolean> = session.zoomLock.asStateFlow()

    fun setDisplayMode(mode: DisplayMode) {
        session.displayMode.value = mode
    }

    fun setZoomLock(on: Boolean) {
        session.zoomLock.value = on
    }

    // ponytail: a small LRU of the pages seen lately; a page further back reads its file again.
    private val traits = object : LinkedHashMap<String, MutableStateFlow<ImageTraits>>(
        TRAIT_PAGES,
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableStateFlow<ImageTraits>>) =
            size > TRAIT_PAGES
    }

    // ponytail: like traits, a small LRU; a page further back asks the cache again.
    private val originalFlows = object : LinkedHashMap<String, StateFlow<Original>>(
        TRAIT_PAGES,
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, StateFlow<Original>>) =
            size > TRAIT_PAGES
    }

    /** A Drive photo's full file: from the cache, or downloading (spec §4.4). */
    fun originalOf(item: ViewerItem): StateFlow<Original> = originalFlows.getOrPut(item.id) {
        // Leaving the page stops the download; its partial file resumes next time.
        originals.original(item.id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), Original.Loading(0f))
    }

    /** A Drive photo's preview from an earlier opening, shown until the full file is here. */
    suspend fun previewOf(item: ViewerItem): java.io.File? =
        item.drive { fileId, version -> originals.preview(fileId, version) }

    /**
     * A Drive item's BlurHash as tiny ARGB pixels at the photo's aspect ratio (width, height,
     * pixels): the first thing its page shows (spec §4.4).
     */
    suspend fun blurOf(item: ViewerItem): Blur? {
        if (item.source != SourceType.DRIVE) return null
        val hash = originals.blurhash(item.id) ?: return null
        val size = item.displaySize()
        val height = if (size.width > 0f) {
            (BLUR_PX * size.height / size.width).toInt().coerceIn(1, BLUR_PX * 2)
        } else {
            BLUR_PX
        }
        return BlurHash.decode(hash, BLUR_PX, height)?.let { Blur(BLUR_PX, height, it) }
    }

    /** The URI of the item's bytes on the phone, once there is one. */
    private suspend fun localUri(item: ViewerItem): String? = item.contentUri
        ?: if (item.source == SourceType.DRIVE) {
            originalOf(item).filterIsInstance<Original.Ready>().first().file.toUri().toString()
        } else {
            null
        }

    /** Motion photo, photo sphere and Ultra HDR facts about a photo, read once per page. */
    fun traitsOf(item: ViewerItem): StateFlow<ImageTraits> {
        traits[item.id]?.let { return it }
        val flow = MutableStateFlow(ImageTraits.NONE)
        traits[item.id] = flow
        if (item.kind == MediaKind.IMAGE) {
            viewModelScope.launch {
                localUri(item)?.let { flow.value = traitsReader.read(it, item.mime) }
            }
        }
        return flow
    }

    /** Where the item [id] is now among this viewer's items, or null if it is gone. */
    suspend fun indexOf(id: String): Int? =
        mediaDao.viewerItem(id)?.let { mediaDao.positionOf(query, it) }

    suspend fun details(item: ViewerItem): ViewerDetails {
        // A Drive item: what Drive said about it.
        val media = item.contentUri?.let { detailsReader.read(it, item.kind) } ?: MediaDetails(
            camera = item.camera,
            location = item.lat?.let { lat -> item.lng?.let { lng -> GeoPoint(lat, lng) } }
        )
        return ViewerDetails(item, mediaDao.folderOf(item.id), media)
    }

    private companion object {
        const val TRAIT_PAGES = 24
        const val LOAD_FACTOR = 0.75f
        const val BLUR_PX = 32
        const val STOP_MS = 2_000L
        val pagingConfig = PagingConfig(
            pageSize = 30,
            initialLoadSize = 60,
            enablePlaceholders = true,
            jumpThreshold = 90
        )
    }
}
