package com.photoxt.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.transfer.OrganizeOps
import com.photoxt.core.vault.Vault
import com.photoxt.core.vault.VaultEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The vault's items (spec §4.8), and moving them out or deleting them. */
@HiltViewModel
class VaultViewModel @Inject constructor(internal val vault: Vault, private val ops: OrganizeOps) :
    ViewModel() {
    val entries: StateFlow<List<VaultEntry>?> = vault.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** Back to the phone folder it came from; [title] names it in Transfers. */
    fun moveOut(entry: VaultEntry, title: String) {
        viewModelScope.launch { ops.moveOutOfVault(listOf(entry), title) }
    }

    fun delete(entry: VaultEntry) {
        viewModelScope.launch { vault.delete(entry.id) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
