package com.photoxt.app

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.auth.DriveScope
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.WaitFor
import com.photoxt.core.transfer.TransferEvent
import com.photoxt.core.transfer.TransferQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The app's side of jobs that wait for the user, and of finished ones (spec §4.7). */
@HiltViewModel
class TransferPromptsViewModel @Inject constructor(
    private val queue: TransferQueue,
    private val driveAuth: DriveAuth
) : ViewModel() {
    val waiting: StateFlow<List<TransferJobEntity>> =
        queue.waiting.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_MS),
            emptyList()
        )

    val busy: StateFlow<Boolean> =
        queue.busy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val events: SharedFlow<TransferEvent> = queue.events

    fun resolveConflict(jobId: Long, policy: ConflictPolicy, all: Boolean) =
        launch { queue.resolveConflict(jobId, policy, all) }

    fun resolveNotOwned(ids: List<Long>, remove: Boolean) = launch {
        queue.resolveNotOwned(ids, remove)
    }

    fun deviceResult(kind: WaitFor, ids: List<Long>, granted: Boolean) =
        launch { queue.deviceConsent(kind, ids, granted) }

    /**
     * Asks Google for full Drive access for [account]; the screen launches the consent screen when
     * one is needed. Granted, or failed (the jobs then fail and can be retried), the jobs move on.
     */
    suspend fun authorize(account: String): Authorization? {
        val result = runCatching { driveAuth.authorize(account, DriveScope.FULL) }
            .onFailure {
                if (it is CancellationException) throw it
                PxtLog.w(TAG, "Couldn't ask for Drive access", it)
            }
            .getOrNull()
        when (result) {
            is Authorization.Granted -> queue.driveAccess(account, true)
            is Authorization.NeedsConsent -> Unit
            null -> queue.driveAccess(account, false)
        }
        return result
    }

    fun consentResult(account: String, data: Intent?) = launch {
        queue.driveAccess(account, driveAuth.completeConsent(account, data))
    }

    fun undo(batchId: Long) = launch { queue.undo(batchId) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val TAG = "TransferPrompts"
        const val STOP_MS = 5_000L
    }
}
