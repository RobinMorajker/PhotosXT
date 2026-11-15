package com.photoxt.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.HiddenAlbumRow
import com.photoxt.core.organize.Hiding
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Hidden items and albums (spec §4.8). */
@HiltViewModel
class HiddenViewModel @Inject constructor(private val hiding: Hiding) : ViewModel() {
    val count: StateFlow<Int> = hiding.count
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), 0)

    val albums: StateFlow<List<HiddenAlbumRow>> = hiding.hiddenAlbums
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun unhide(albumId: String) {
        viewModelScope.launch { hiding.setAlbumHidden(albumId, false) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
