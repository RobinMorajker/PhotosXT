package com.photoxt.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.transfer.TransferQueue
import com.photoxt.core.transfer.Transfers
import com.photoxt.core.transfer.TrashEntry
import com.photoxt.core.transfer.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TrashUiState(
    val loading: Boolean = true,
    val entries: List<TrashEntry> = emptyList(),
    /** Accounts whose Drive bin couldn't be read (offline, or Drive access lapsed). */
    val failedAccounts: List<String> = emptyList(),
    /** Selected entries' keys. */
    val selection: Set<String> = emptySet()
)

/**
 * The unified Trash (spec §4.7): the phone's trash and every Drive bin, read each time the screen
 * shows. Restore, delete forever and empty go through the transfer queue; what they took leaves
 * the list at once, and the list is read again once the queue is done with it.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: TrashRepository,
    private val transfers: Transfers,
    private val queue: TransferQueue
) : ViewModel() {
    private val state = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            queue.events.collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            runCatching { trash.load() }
                .onSuccess { contents ->
                    state.update { current ->
                        val keys = contents.entries.mapTo(HashSet()) { it.key }
                        current.copy(
                            loading = false,
                            entries = contents.entries,
                            failedAccounts = contents.failedAccounts,
                            selection = current.selection.filterTo(HashSet()) { it in keys }
                        )
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    state.update { current -> current.copy(loading = false) }
                }
        }
    }

    fun toggle(key: String) = state.update {
        it.copy(selection = if (key in it.selection) it.selection - key else it.selection + key)
    }

    fun clearSelection() = state.update { it.copy(selection = emptySet()) }

    fun restore(title: String) = act(selectedOnly = true) { refs -> transfers.restore(refs, title) }

    fun deleteForever(title: String) =
        act(selectedOnly = true) { refs -> transfers.deleteForever(refs, title) }

    /** Deletes every entry shown for good (only what PhotoXT shows: photos and videos). */
    fun empty(title: String) =
        act(selectedOnly = false) { refs -> transfers.deleteForever(refs, title) }

    private fun act(
        selectedOnly: Boolean,
        action: suspend (List<com.photoxt.core.transfer.FileRef>) -> Long?
    ) {
        val current = state.value
        val chosen = current.entries.filter { !selectedOnly || it.key in current.selection }
            .filter { it.owned }
        if (chosen.isEmpty()) return
        val keys = chosen.mapTo(HashSet()) { it.key }
        state.update {
            it.copy(
                entries = it.entries.filterNot { entry ->
                    entry.key in keys
                },
                selection = emptySet()
            )
        }
        viewModelScope.launch { action(chosen.map { it.ref }) }
    }
}
