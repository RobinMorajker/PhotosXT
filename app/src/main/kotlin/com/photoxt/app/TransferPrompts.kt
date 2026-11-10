package com.photoxt.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.auth.Authorization
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.WaitFor
import com.photoxt.core.transfer.consentUri
import com.photoxt.core.transfer.mediaStoreRequest
import com.photoxt.core.transfer.source
import com.photoxt.core.transfer.target
import kotlinx.coroutines.launch

/**
 * Answers for waiting transfer jobs while PhotoXT is on screen (spec §4.7): MediaStore's own
 * request screens, Google's full-Drive consent, name conflicts and Drive files the user can't
 * bin; the notification permission once there is something to show; and a snackbar with Undo
 * when a move or delete finishes.
 */
@Composable
fun TransferPrompts(
    snackbar: SnackbarHostState,
    onOpenTransfers: () -> Unit,
    viewModel: TransferPromptsViewModel = hiltViewModel()
) {
    val waiting by viewModel.waiting.collectAsStateWithLifecycle()
    MediaStorePrompt(waiting, viewModel)
    DriveScopePrompt(waiting, viewModel)
    NotificationPermission(viewModel)
    waiting.firstOrNull { it.waitFor == WaitFor.CONFLICT }?.let { job ->
        ConflictDialog(
            job,
            waiting.count {
                it.waitFor == WaitFor.CONFLICT &&
                    it.batchId == job.batchId
            }
        ) { policy, all ->
            viewModel.resolveConflict(job.id, policy, all)
        }
    }
    val notOwned = waiting.filter { it.waitFor == WaitFor.NOT_OWNED }
    if (notOwned.isNotEmpty()) {
        NotOwnedDialog(notOwned.size) { remove ->
            viewModel.resolveNotOwned(notOwned.map { it.id }, remove)
        }
    }
    FinishedSnackbars(snackbar, viewModel, onOpenTransfers)
}

/** MediaStore's request screen for one kind of waiting job at a time (all of them at once). */
@Composable
private fun MediaStorePrompt(
    waiting: List<TransferJobEntity>,
    viewModel: TransferPromptsViewModel
) {
    val context = LocalContext.current
    // What is being asked, as "KIND|id:updatedAt,…". It outlives a rotation, and stays until
    // those waits have moved on, so an answer still being saved isn't asked for a second time.
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        asking?.let { request ->
            val kind = WaitFor.valueOf(request.substringBefore('|'))
            val ids = request.substringAfter('|').split(',')
                .mapNotNull { it.substringBefore(':').toLongOrNull() }
            viewModel.deviceResult(kind, ids, result.resultCode == Activity.RESULT_OK)
        }
    }
    LaunchedEffect(waiting, asking) {
        val pending = waiting.filter { it.waitFor in DEVICE_WAITS && it.consentUri != null }
        val asked = asking?.substringAfter('|')?.split(',')?.toSet()
        if (asked != null) {
            if (pending.none { it.askKey in asked }) asking = null
            return@LaunchedEffect
        }
        val group = pending.groupBy { it.waitFor }.entries.firstOrNull() ?: return@LaunchedEffect
        val kind = group.key ?: return@LaunchedEffect
        val jobs = group.value.take(MAX_URIS)
        val intent = mediaStoreRequest(context, kind, jobs.mapNotNull { it.consentUri }.distinct())
            ?: return@LaunchedEffect
        asking = "${kind.name}|${jobs.joinToString(",") { it.askKey }}"
        launcher.launch(IntentSenderRequest.Builder(intent).build())
    }
}

/** One wait of one job: the same job waiting again later is a new question. */
private val TransferJobEntity.askKey: String get() = "$id:$updatedAt"

/** Google's consent for full Drive access, one account at a time (spec §2, scope upgrade). */
@Composable
private fun DriveScopePrompt(
    waiting: List<TransferJobEntity>,
    viewModel: TransferPromptsViewModel
) {
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        asking?.let { viewModel.consentResult(it, result.data) }
    }
    val account = waiting.firstOrNull { it.waitFor == WaitFor.DRIVE_SCOPE }?.let { job ->
        job.target?.account?.takeIf { job.target?.isDrive == true } ?: job.source.account
    }
    // Keyed on the account, not the whole list: a change while Google answers mustn't cancel it.
    LaunchedEffect(account, asking) {
        if (asking != null) {
            // Answered once that account's jobs have moved on; not asked again before.
            if (account != asking) asking = null
            return@LaunchedEffect
        }
        if (account == null) return@LaunchedEffect
        // Granted already, or refused: the view model moves the jobs on.
        val result = viewModel.authorize(account)
        if (result is Authorization.NeedsConsent) {
            asking = account
            launcher.launch(IntentSenderRequest.Builder(result.intent).build())
        }
    }
}

/** Android 13+: asks once for notifications when there is a transfer to show. */
@Composable
private fun NotificationPermission(viewModel: TransferPromptsViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(busy) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (busy && !granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** "An item named a.jpg is already in Trip": Keep both, Replace or Skip, with Apply to all. */
@Composable
private fun ConflictDialog(
    job: TransferJobEntity,
    count: Int,
    onAnswer: (ConflictPolicy, Boolean) -> Unit
) {
    var all by rememberSaveable(job.id) { mutableStateOf(false) }
    val name = job.target?.name ?: job.source.name
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.conflict_title, name, job.target?.title.orEmpty()))
        },
        text = {
            Column {
                Text(stringResource(R.string.conflict_body))
                if (count > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = all, role = Role.Checkbox, onValueChange = {
                                all =
                                    it
                            })
                    ) {
                        Checkbox(checked = all, onCheckedChange = null)
                        Text(pluralStringResource(R.plurals.conflict_apply_all, count, count))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onAnswer(ConflictPolicy.SKIP, all) }) {
                    Text(stringResource(R.string.conflict_skip))
                }
                TextButton(onClick = { onAnswer(ConflictPolicy.REPLACE, all) }) {
                    Text(stringResource(R.string.conflict_replace))
                }
                TextButton(onClick = { onAnswer(ConflictPolicy.KEEP_BOTH, all) }) {
                    Text(stringResource(R.string.conflict_keep_both))
                }
            }
        }
    )
}

/** Drive files shared with the user can't go to the bin: take them out of the album instead? */
@Composable
private fun NotOwnedDialog(count: Int, onAnswer: (remove: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(pluralStringResource(R.plurals.not_owned_title, count, count)) },
        text = { Text(stringResource(R.string.not_owned_body)) },
        confirmButton = {
            TextButton(onClick = {
                onAnswer(true)
            }) { Text(stringResource(R.string.not_owned_remove)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onAnswer(false)
            }) { Text(stringResource(R.string.not_owned_keep)) }
        }
    )
}

/** What a finished batch did, with Undo for moves and deletes (spec §4.7, Undo). */
@Composable
private fun FinishedSnackbars(
    snackbar: SnackbarHostState,
    viewModel: TransferPromptsViewModel,
    onOpenTransfers: () -> Unit
) {
    val resources = LocalResources.current
    val undo = stringResource(R.string.transfer_undo)
    val details = stringResource(R.string.transfer_details)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when {
                event.failed > 0 ->
                    resources.getQuantityString(
                        R.plurals.transfer_failed_message,
                        event.failed,
                        event.failed
                    )

                event.done == 0 -> return@collect

                event.op == TransferOp.MKDIR -> resources.getString(
                    R.string.transfer_folder_made,
                    event.title
                )

                else -> resources.getQuantityString(
                    event.op.message,
                    event.done,
                    event.done,
                    event.title
                )
            }
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = when {
                    event.failed > 0 -> details
                    event.undoable -> undo
                    else -> null
                },
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (event.failed > 0) onOpenTransfers() else viewModel.undo(event.batchId)
            }
        }
    }
}

private val TransferOp.message: Int
    get() = when (this) {
        TransferOp.COPY -> R.plurals.transfer_copied
        TransferOp.MOVE -> R.plurals.transfer_moved
        TransferOp.TRASH -> R.plurals.transfer_trashed
        TransferOp.RESTORE -> R.plurals.transfer_restored
        TransferOp.DELETE -> R.plurals.transfer_deleted
        TransferOp.RENAME -> R.plurals.transfer_renamed
        TransferOp.CONVERT -> R.plurals.transfer_converted
        TransferOp.MKDIR, TransferOp.REMOVE_FROM_ALBUM -> R.plurals.transfer_removed
    }

private val DEVICE_WAITS = setOf(
    WaitFor.DEVICE_WRITE,
    WaitFor.DEVICE_TRASH,
    WaitFor.DEVICE_RESTORE,
    WaitFor.DEVICE_DELETE
)

/** MediaStore takes a few hundred items per request comfortably. */
private const val MAX_URIS = 500
