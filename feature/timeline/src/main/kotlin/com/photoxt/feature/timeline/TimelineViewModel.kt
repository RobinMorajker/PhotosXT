package com.photoxt.feature.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.ShareTarget
import com.photoxt.core.database.TimelineQuery
import com.photoxt.core.database.TimelineRow
import com.photoxt.core.database.groupCounts
import com.photoxt.core.database.stampsInGroup
import com.photoxt.core.database.timeline
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.MediaAccessMonitor
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.GridDensity
import com.photoxt.core.ui.TimelineLayout
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TimelineUiState(
    val access: MediaAccess,
    val filter: MediaFilter = MediaFilter.ALL,
    val density: GridDensity = GridDensity.Default,
    val layout: TimelineLayout = TimelineLayout.EMPTY,
    /** Selected item ids, each with its capture time. */
    val selection: Map<String, Long> = emptyMap(),
    /** A Drive album's subfolders and sync state; null for Pictures and phone albums. */
    val drive: DriveAlbumInfo? = null,
    /** Pull-to-refresh is running. */
    val refreshing: Boolean = false
)

/** What a Drive album shows besides its items (spec §4.2, §4.3). */
@Immutable
data class DriveAlbumInfo(
    val accountId: String,
    /** The tracked folder itself, which can merge its subfolders, go offline or be removed. */
    val isRoot: Boolean,
    val merge: Boolean,
    /** "Make available offline" is on (spec §4.4). */
    val offline: Boolean = false,
    val syncing: Boolean,
    /** Binned, deleted or unshared in Drive: the Unavailable banner (spec §4.3). */
    val unavailable: Boolean = false,
    val needsConsent: Boolean,
    /** Subfolders shown as nested albums. */
    val children: List<ChildAlbum>
)

@Immutable
data class ChildAlbum(val id: String, val title: String, val count: Int, val cover: Any?)

/** Pictures when [albumId] is null, otherwise the grid of that one album. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TimelineViewModel.Factory::class)
class TimelineViewModel @AssistedInject constructor(
    @Assisted val albumId: String?,
    private val mediaDao: MediaDao,
    private val mediaAccess: MediaAccessMonitor,
    private val deviceMediaSync: DeviceMediaSync,
    private val savedState: SavedStateHandle,
    private val driveAlbums: DriveAlbums
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(albumId: String?): TimelineViewModel
    }

    val permissions: Array<String> get() = mediaAccess.permissions

    private val filter = savedState.getStateFlow(KEY_FILTER, MediaFilter.ALL)
    private val density = savedState.getStateFlow(KEY_DENSITY, GridDensity.Default)
    private val selection = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val refreshing = MutableStateFlow(false)

    // Column changes keep the query (and so the loaded rows); only a new grouping replaces them.
    private val query: Flow<TimelineQuery> =
        combine(filter, density) { filter, density ->
            TimelineQuery(filter, density.grouping, albumId)
        }.distinctUntilChanged()

    val rows: Flow<PagingData<GridCell>> = query
        .flatMapLatest { query ->
            Pager(pagingConfig) { mediaDao.timeline(query) }.flow
        }
        .map { rows -> rows.map { it.toCell() } }
        .cachedIn(viewModelScope)

    private val layout: Flow<TimelineLayout> = query.flatMapLatest { query ->
        mediaDao.groupCounts(query).map { groups ->
            TimelineLayout(
                query.grouping,
                IntArray(groups.size) { groups[it].groupKey },
                IntArray(groups.size) { groups[it].count }
            )
        }
    }

    /** Whether this is a Drive album, which needs no access to the phone's media. */
    private val isDrive = albumId?.let(DriveAlbums::isDrive) == true

    private val access: Flow<MediaAccess> =
        if (isDrive) flowOf(MediaAccess.FULL) else mediaAccess.state

    private val driveInfo: Flow<DriveAlbumInfo?> =
        if (albumId != null && isDrive) driveAlbums.info(albumId) else flowOf(null)

    val uiState: StateFlow<TimelineUiState> =
        combine(
            combine(access, filter, density, layout, selection, ::TimelineUiState),
            driveInfo,
            refreshing
        ) { state, drive, refreshing -> state.copy(drive = drive, refreshing = refreshing) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                TimelineUiState(
                    if (isDrive) MediaAccess.FULL else mediaAccess.state.value,
                    filter.value,
                    density.value
                )
            )

    /** After a permission answer: re-read access and index whatever became visible. */
    fun refreshAccess() = deviceMediaSync.refresh()

    /**
     * Pull-to-refresh (spec §4.3): Drive changes for this album's account, or for Pictures every
     * account and the phone. False when Drive could not be reached.
     */
    suspend fun refresh(): Boolean {
        if (refreshing.value) return true
        refreshing.value = true
        return try {
            driveAlbums.refresh(albumId)
        } finally {
            refreshing.value = false
        }
    }

    /** "Make available offline" for this tracked Drive folder (spec §4.4). */
    fun setOffline(offline: Boolean) {
        val id = albumId ?: return
        viewModelScope.launch { driveAlbums.setOffline(id, offline) }
    }
    fun setFilter(value: MediaFilter) {
        savedState[KEY_FILTER] = value
    }

    fun setDensity(value: GridDensity) {
        savedState[KEY_DENSITY] = value
    }

    fun setSelection(value: Map<String, Long>) {
        selection.value = value
    }

    fun clearSelection() {
        selection.value = emptyMap()
    }

    /** Selects or unselects every item of one date group, loaded or not. */
    fun selectGroup(groupKey: Int, select: Boolean) {
        viewModelScope.launch {
            val query = TimelineQuery(filter.value, density.value.grouping, albumId)
            val stamps = mediaDao.stampsInGroup(query, groupKey)
            selection.update { selected ->
                if (select) {
                    selected + stamps.associate { it.id to it.takenAt }
                } else {
                    selected - stamps.mapTo(HashSet()) { it.id }
                }
            }
        }
    }

    /** Nests this Drive album's subfolders as albums, or merges them into it. */
    fun setMerge(merge: Boolean) {
        val id = albumId ?: return
        viewModelScope.launch { driveAlbums.setMerge(id, merge) }
    }

    /** Stops showing this tracked Drive folder; nothing changes in Drive. */
    suspend fun removeDriveFolder() {
        albumId?.let { driveAlbums.remove(it) }
    }

    /** The two selected items for Compare, the newer first, or null unless exactly two. */
    fun comparePair(): Pair<String, String>? {
        val selected = selection.value.entries.sortedByDescending { it.value }
        return if (selected.size == 2) selected[0].key to selected[1].key else null
    }

    /** What to hand to the share sheet, or null when more than [MAX_SHARE] items are selected. */
    suspend fun shareTargets(): List<ShareTarget>? {
        val ids = selection.value.keys.toList()
        if (ids.size > MAX_SHARE) return null
        return ids.chunked(MAX_SQL_VARIABLES).flatMap { mediaDao.shareTargets(it) }
    }

    companion object {
        /** Every shared item travels in the share intent, which Binder caps at about 1 MB. */
        const val MAX_SHARE = 500

        private const val PAGE_SIZE = 150

        // Placeholders and jumps let the fast scroller land anywhere without loading every page.
        private val pagingConfig = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE * 3,
            enablePlaceholders = true,
            jumpThreshold = PAGE_SIZE * 3
        )
        private const val MAX_SQL_VARIABLES = 900
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KEY_FILTER = "filter"
        private const val KEY_DENSITY = "density"
    }
}

internal fun TimelineRow.toCell(): GridCell {
    val itemId = id
    return if (isHeader || itemId == null) {
        GridCell.Header(groupKey)
    } else {
        val sideways = rotation % HALF_TURN != 0
        GridCell.Item(
            id = itemId,
            thumbnail = thumbnailOf(
                MediaRef(
                    id = itemId,
                    source = source ?: SourceType.DEVICE,
                    contentUri = contentUri,
                    modifiedAt = modifiedAt,
                    remoteId = itemId.substringAfterLast(':'),
                    remoteVersion = remoteVersion
                )
            ),
            isVideo = kind == MediaKind.VIDEO,
            durationMs = durationMs,
            favorite = favorite,
            takenAt = takenAt,
            width = if (sideways) height else width,
            height = if (sideways) width else height
        )
    }
}

private const val HALF_TURN = 180
