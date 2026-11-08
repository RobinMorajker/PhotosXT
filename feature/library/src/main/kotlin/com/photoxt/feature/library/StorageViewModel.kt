package com.photoxt.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.datastore.StorageSettings
import com.photoxt.core.media.DriveCache
import com.photoxt.core.media.StorageUsage
import com.photoxt.core.sync.OfflinePins
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class StorageUiState(
    /** Null while the files are measured. */
    val usage: StorageUsage? = null,
    val settings: StorageSettings = StorageSettings(),
    /** Freeing up space: the actions wait. */
    val busy: Boolean = false
)

/** The Storage screen (spec §4.4): what Drive items keep on the phone, and the cache rules. */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val cache: DriveCache,
    private val settings: SettingsStore,
    private val pins: OfflinePins
) : ViewModel() {
    private val usage = MutableStateFlow<StorageUsage?>(null)
    private val busy = MutableStateFlow(false)

    val uiState: StateFlow<StorageUiState> =
        combine(usage, settings.settings, busy) { usage, all, busy ->
            StorageUiState(usage, all.storage, busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StorageUiState())

    /** Measures the files on the phone again. */
    fun measure() {
        viewModelScope.launch { usage.value = cache.usage() }
    }

    /** *Free up now*; returns the bytes freed. */
    suspend fun freeUpNow(): Long = freeing { cache.freeUpNow() }

    /** *Clear cache for this album*; returns the bytes freed. */
    suspend fun clearAlbum(albumId: String): Long = freeing { cache.clearAlbum(albumId) }

    /** Changes a cache rule; a lower limit or shorter retention applies at once. */
    fun update(transform: (StorageSettings) -> StorageSettings) {
        viewModelScope.launch {
            settings.updateStorage(transform)
            val rules = settings.current().storage
            freeing {
                cache.cleanUp(rules.retentionMs, rules.cacheCapBytes, rules.previewCapBytes)
            }
            // Offline downloads wait for Wi-Fi unless originals may use mobile data.
            pins.refresh()
        }
    }

    private suspend fun freeing(block: suspend () -> Long): Long {
        busy.value = true
        return try {
            block()
        } finally {
            busy.value = false
            measure()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
