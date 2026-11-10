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
import com.photoxt.core.datastore.MobileOriginals
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.BlurHash
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.DriveThumbnails
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
import com.photoxt.core.sync.DeviceConditions
import com.photoxt.core.sync.OfflinePins
import com.photoxt.core.transfer.SystemClipboard
import com.photoxt.core.transfer.Transfers
import com.photoxt.core.ui.heroSourceKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A BlurHash decoded to [pixels] (ARGB) of [width] × [height]. */
class Blur(val width: Int, val height: Int, val pixels: IntArray)

/** What the details sheet shows for one item. */
data class ViewerDetails(val item: ViewerItem, val folder: String?, val media: MediaDetails)

/**
 * The viewer of one grid's items, starting at [start] (spec §4.5). Drive photos follow the cache
 * rules of spec §4.4: data saver on mobile data, prefetch of the neighbours, offline pins.
 */
@HiltViewModel(assistedFactory = ViewerViewModel.Factory::class)
class ViewerViewModel @AssistedInject constructor(
    @Assisted val start: ViewerStart,
    private val mediaDao: MediaDao,
    private val traitsReader: ImageTraitsReader,
    private val detailsReader: MediaDetailsReader,
    private val session: ViewerSession,
    private val originals: DriveOriginals,
    private val thumbnails: DriveThumbnails,
    private val pins: OfflinePins,
    private val settings: SettingsStore,
    private val conditions: DeviceConditions,
    private val transfers: Transfers,
    private val systemClipboard: SystemClipboard
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
    suspend fun previewOf(item: ViewerItem): File? =
        item.drive { fileId, version -> originals.preview(fileId, version) }

    /**
     * Data saver (spec §4.4): on a metered network, unless originals always load, the rule that
     * holds Drive originals back; null when they load as on Wi-Fi.
     */
    val dataSaver: StateFlow<MobileOriginals?> =
        combine(conditions.metered, settings.settings) { metered, all ->
            all.storage.mobileOriginals.takeIf { metered && it != MobileOriginals.ALWAYS }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_MS),
            // Until the settings are read: the default rule, so nothing loads early.
            MobileOriginals.WIFI_ONLY.takeIf { conditions.metered.value }
        )

    private val requested = MutableStateFlow<Set<String>>(emptySet())

    /** Items whose original the user asked for over mobile data (*Load original*). */
    val loadRequested: StateFlow<Set<String>> = requested.asStateFlow()

    /**
     * *Load original* for [item]; with [always] ("Always" in data saver's question), originals
     * load over mobile data from now on.
     */
    fun loadOriginal(item: ViewerItem, always: Boolean = false) {
        requested.update { it + item.id }
        if (always) {
            viewModelScope.launch {
                settings.updateStorage { it.copy(mobileOriginals = MobileOriginals.ALWAYS) }
            }
        }
    }

    /** Whether [item]'s full file is on the phone already, so data saver does not apply. */
    suspend fun hasLocalOriginal(item: ViewerItem): Boolean = originals.localFile(item.id) != null

    /** The preview data saver shows: one made before, else Drive's own at about 1,600 px. */
    suspend fun savingPreviewOf(item: ViewerItem): File? = previewOf(item)
        ?: runCatching { thumbnails.preview(item.id) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    /**
     * Fetches the originals of the Drive photos within the prefetch count of [page] (spec §4.4):
     * on Wi-Fi, or on mobile data only when originals always load. [itemAt] reads the pager.
     */
    fun prefetchAround(page: Int, itemAt: (Int) -> ViewerItem?) {
        viewModelScope.launch {
            val storage = settings.current().storage
            if (storage.prefetch <= 0 || dataSaver.value != null) return@launch
            val metered = conditions.metered.value
            if (metered && storage.mobileOriginals != MobileOriginals.ALWAYS) return@launch
            val ids = (1..storage.prefetch).flatMap { listOf(page + it, page - it) }
                .mapNotNull(itemAt)
                .filter { it.source == SourceType.DRIVE && it.kind == MediaKind.IMAGE }
                .map { it.id }
            originals.prefetch(ids)
        }
    }

    /** Whether Drive item [id] is kept offline (spec §4.4, offline pins). */
    fun pinned(id: String): Flow<Boolean> = pins.pinned(id)

    fun setPinned(item: ViewerItem, pinned: Boolean) {
        viewModelScope.launch { pins.setPinned(listOf(item.id), pinned) }
    }

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

    /** Delete, and Copy for a Drive photo (spec §4.7). */
    val fileOps = ViewerFileOps(viewModelScope, transfers, systemClipboard)

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

/** The viewer's file operations (spec §4.7). */
class ViewerFileOps internal constructor(
    private val scope: CoroutineScope,
    private val transfers: Transfers,
    private val systemClipboard: SystemClipboard
) {
    /** Moves [item] to the trash, the phone's or Drive's; Undo follows. */
    fun delete(item: ViewerItem) {
        scope.launch { transfers.trash(listOf(item.id), item.name) }
    }

    /** A Drive photo onto the system clipboard, downloaded first. */
    fun copyToClipboard(item: ViewerItem) {
        scope.launch { systemClipboard.copy(item.id, item.contentUri, item.name) }
    }
}
