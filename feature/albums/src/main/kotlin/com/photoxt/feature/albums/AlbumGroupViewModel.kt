package com.photoxt.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** One album group's albums (spec §4.8), in the Albums tab's sort. */
@HiltViewModel(assistedFactory = AlbumGroupViewModel.Factory::class)
class AlbumGroupViewModel @AssistedInject constructor(
    @Assisted val groupId: String,
    tiles: AlbumTiles,
    organizeParts: AlbumsOrganizeParts
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(groupId: String): AlbumGroupViewModel
    }

    val organize: AlbumsOrganize = organizeParts.forScreen(viewModelScope)

    val albums: StateFlow<List<AlbumTile>?> = tiles.tiles(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private companion object {
        const val STOP_MS = 5_000L
    }
}
