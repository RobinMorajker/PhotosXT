package com.photoxt.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.ContinueRow
import com.photoxt.core.database.HistoryRow
import com.photoxt.core.datastore.HistorySettings
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.organize.History
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The viewing history (spec §4.9) and its controls. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: History,
    private val settings: SettingsStore
) : ViewModel() {
    val recent: StateFlow<List<HistoryRow>?> = history.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val continueWatching: StateFlow<List<ContinueRow>> = history.continueWatching
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val historySettings: StateFlow<HistorySettings> = settings.settings.map { it.history }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), HistorySettings())

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { settings.updateHistory { it.copy(paused = paused) } }
    }

    fun setKeepMonths(months: Int) {
        viewModelScope.launch {
            settings.updateHistory { it.copy(keepMonths = months) }
            history.trim()
        }
    }

    fun clearAll() {
        viewModelScope.launch { history.clearAll() }
    }

    /** Clears [from] to [until], both included, as days on this phone's calendar. */
    fun clearDays(from: LocalDate, until: LocalDate) {
        viewModelScope.launch { history.clearDays(from, until, ZoneId.systemDefault()) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
