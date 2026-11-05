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
import com.photoxt.core.media.DeviceThumbnail
import com.photoxt.core.media.MediaAccessMonitor
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaKind
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
    val selection: Map<String, Long> = emptyMap()
)

/** Pictures when [albumId] is null, otherwise the grid of that one album. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TimelineViewModel.Factory::class)
class TimelineViewModel @AssistedInject constructor(
    @Assisted val albumId: String?,
    private val mediaDao: MediaDao,
    private val mediaAccess: MediaAccessMonitor,
    private val deviceMediaSync: DeviceMediaSync,
    private val savedState: SavedStateHandle
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(albumId: String?): TimelineViewModel
    }

    val permissions: Array<String> get() = mediaAccess.permissions

    private val filter = savedState.getStateFlow(KEY_FILTER, MediaFilter.ALL)
    private val density = savedState.getStateFlow(KEY_DENSITY, GridDensity.Default)
    private val selection = MutableStateFlow<Map<String, Long>>(emptyMap())

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

    val uiState: StateFlow<TimelineUiState> =
        combine(mediaAccess.state, filter, density, layout, selection, ::TimelineUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                TimelineUiState(mediaAccess.state.value, filter.value, density.value)
            )

    /** After a permission answer: re-read access and index whatever became visible. */
    fun refreshAccess() = deviceMediaSync.refresh()

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
            thumbnail = contentUri?.let { DeviceThumbnail(it, modifiedAt) },
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
