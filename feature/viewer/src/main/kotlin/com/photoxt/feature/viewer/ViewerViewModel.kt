package com.photoxt.feature.viewer

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
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.media.ImageTraitsReader
import com.photoxt.core.media.MediaDetails
import com.photoxt.core.media.MediaDetailsReader
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.ui.heroSourceKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the details sheet shows for one item. */
data class ViewerDetails(val item: ViewerItem, val folder: String?, val media: MediaDetails)

/** The viewer of one grid's items, starting at [start] (spec §4.5). */
@HiltViewModel(assistedFactory = ViewerViewModel.Factory::class)
class ViewerViewModel @AssistedInject constructor(
    @Assisted val start: ViewerStart,
    private val mediaDao: MediaDao,
    private val traitsReader: ImageTraitsReader,
    private val detailsReader: MediaDetailsReader,
    private val session: ViewerSession
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

    /** Motion photo, photo sphere and Ultra HDR facts about a photo, read once per page. */
    fun traitsOf(item: ViewerItem): StateFlow<ImageTraits> {
        traits[item.id]?.let { return it }
        val flow = MutableStateFlow(ImageTraits.NONE)
        traits[item.id] = flow
        val uri = item.contentUri
        if (item.kind == MediaKind.IMAGE && uri != null) {
            viewModelScope.launch { flow.value = traitsReader.read(uri, item.mime) }
        }
        return flow
    }

    /** Where the item [id] is now among this viewer's items, or null if it is gone. */
    suspend fun indexOf(id: String): Int? =
        mediaDao.viewerItem(id)?.let { mediaDao.positionOf(query, it) }

    suspend fun details(item: ViewerItem): ViewerDetails {
        val media = item.contentUri?.let { detailsReader.read(it, item.kind) } ?: MediaDetails()
        return ViewerDetails(item, mediaDao.folderOf(item.id), media)
    }

    private companion object {
        const val TRAIT_PAGES = 24
        const val LOAD_FACTOR = 0.75f
        val pagingConfig = PagingConfig(
            pageSize = 30,
            initialLoadSize = 60,
            enablePlaceholders = true,
            jumpThreshold = 90
        )
    }
}
