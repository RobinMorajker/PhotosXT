package com.photoxt.feature.library

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.TransferBatchRow
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.TransferOp
import com.photoxt.core.transfer.TransferQueue
import com.photoxt.core.ui.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One user action on the Transfers screen, with its jobs counted. */
@Immutable
data class TransferRowState(val batch: TransferBatchRow) {
    val finished: Boolean get() = batch.finishedAt != null

    /** How far the batch got: by bytes when they are known, else by items. */
    val fraction: Float
        get() = if (batch.bytesTotal > 0) {
            batch.bytesDone.toFloat() / batch.bytesTotal
        } else {
            (batch.done + batch.skipped).toFloat() / batch.total.coerceAtLeast(1)
        }

    /** Undo applies to a finished move or trash that moved something and wasn't undone. */
    val undoable: Boolean
        get() = finished && !batch.undone && batch.undoOf == null && batch.done > 0 &&
            batch.op in setOf(TransferOp.MOVE, TransferOp.TRASH)
}

@HiltViewModel
class TransfersViewModel @Inject constructor(private val queue: TransferQueue) : ViewModel() {
    val rows: StateFlow<List<TransferRowState>?> = queue.batches
        .map { batches -> batches.map(::TransferRowState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun pause(id: Long, paused: Boolean) = launch { queue.pause(id, paused) }

    fun cancel(id: Long) = launch { queue.cancel(id) }

    fun retry(id: Long) = launch { queue.retry(id) }

    fun undo(id: Long) = launch { queue.undo(id) }

    fun clearFinished() = launch { queue.clearFinished() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** What a transfer row can ask for. */
class TransferActions(
    val onPause: (Long, Boolean) -> Unit = { _, _ -> },
    val onCancel: (Long) -> Unit = {},
    val onRetry: (Long) -> Unit = {},
    val onUndo: (Long) -> Unit = {}
)

/**
 * Transfers (spec §4.7): every copy, move, upload, delete and conversion, the latest first, with
 * pause, resume, cancel, retry and Undo.
 */
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    TransfersContent(
        rows = rows,
        onBack = onBack,
        onClearFinished = viewModel::clearFinished,
        actions = TransferActions(
            viewModel::pause,
            viewModel::cancel,
            viewModel::retry,
            viewModel::undo
        ),
        modifier = modifier
    )
}

/** The Transfers screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersContent(
    rows: List<TransferRowState>?,
    onBack: () -> Unit,
    onClearFinished: () -> Unit,
    actions: TransferActions,
    modifier: Modifier = Modifier
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = stringResource(R.string.transfers_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(DesignR.drawable.ic_arrow_back),
                        stringResource(R.string.action_back)
                    )
                }
            },
            actions = {
                if (rows.orEmpty().any { it.finished }) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.transfers_clear))
                    }
                }
            }
        )
        val list = rememberLazyListState()
        // A new transfer joins at the top, where the list keeps the old first row in view: when
        // that row was at the top, follow the new one instead.
        val newest = rows?.firstOrNull()?.batch?.id
        LaunchedEffect(newest) { if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            if (rows != null && rows.isEmpty()) {
                item {
                    EmptyState(
                        icon = painterResource(DesignR.drawable.ic_swap_vert),
                        title = stringResource(R.string.transfers_empty_title),
                        body = stringResource(R.string.transfers_empty_body),
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            }
            items(rows.orEmpty(), key = { it.batch.id }) { row -> TransferRow(row, actions) }
        }
    }
}

@Composable
private fun TransferRow(row: TransferRowState, actions: TransferActions) {
    val batch = row.batch
    ListItem(
        leadingContent = { Icon(painterResource(batch.op.icon), contentDescription = null) },
        headlineContent = {
            val action = stringResource(batch.op.label, batch.title)
            // An Undo is named after what it takes back.
            val undo = batch.undoOf?.let { stringResource(R.string.transfer_undo_of, action) }
            Text(undo ?: action)
        },
        supportingContent = {
            Column {
                Text(listOfNotNull(statusOf(row), bytesOf(row)).joinToString(" · "))
                if (!row.finished && !batch.paused) {
                    LinearProgressIndicator(
                        progress = { row.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
        },
        trailingContent = { RowActions(row, actions) }
    )
}

/** Where the batch is: paused, waiting for the user, how it ended, or how far it got. */
@Composable
private fun statusOf(row: TransferRowState): String {
    val batch = row.batch
    return when {
        batch.paused && !row.finished -> stringResource(R.string.transfer_paused)

        batch.waiting > 0 -> stringResource(R.string.transfer_waiting)

        row.finished && batch.failed > 0 ->
            pluralStringResource(R.plurals.transfer_failed, batch.failed, batch.failed)

        row.finished && batch.cancelled > 0 && batch.done == 0 ->
            stringResource(R.string.transfer_cancelled)

        row.finished && batch.skipped > 0 && batch.done == 0 ->
            pluralStringResource(R.plurals.transfer_skipped, batch.skipped, batch.skipped)

        row.finished -> pluralStringResource(R.plurals.transfer_done, batch.done, batch.done)

        else -> stringResource(R.string.transfer_progress, batch.done + batch.skipped, batch.total)
    }
}

/** Bytes moved so far, while the batch runs and knows its size. */
@Composable
private fun bytesOf(row: TransferRowState): String? {
    val batch = row.batch
    if (row.finished || batch.bytesTotal <= 0) return null
    val context = LocalContext.current
    return stringResource(
        R.string.transfer_bytes,
        Formatter.formatShortFileSize(context, batch.bytesDone),
        Formatter.formatShortFileSize(context, batch.bytesTotal)
    )
}

@Composable
private fun RowActions(row: TransferRowState, actions: TransferActions) {
    val batch = row.batch
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!row.finished) {
            RowAction(
                if (batch.paused) DesignR.drawable.ic_play else DesignR.drawable.ic_pause,
                if (batch.paused) R.string.transfer_resume else R.string.transfer_pause
            ) { actions.onPause(batch.id, !batch.paused) }
            RowAction(DesignR.drawable.ic_close, R.string.transfer_cancel) {
                actions.onCancel(batch.id)
            }
        }
        if (row.finished && batch.failed > 0) {
            RowAction(DesignR.drawable.ic_repeat, R.string.transfer_retry) {
                actions.onRetry(batch.id)
            }
        }
        if (row.undoable) {
            RowAction(DesignR.drawable.ic_undo, R.string.transfer_undo) {
                actions.onUndo(batch.id)
            }
        }
    }
}

@Composable
private fun RowAction(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

private val TransferOp.label: Int
    get() = when (this) {
        TransferOp.COPY -> R.string.transfer_op_copy
        TransferOp.MOVE -> R.string.transfer_op_move
        TransferOp.TRASH -> R.string.transfer_op_trash
        TransferOp.RESTORE -> R.string.transfer_op_restore
        TransferOp.DELETE -> R.string.transfer_op_delete
        TransferOp.RENAME -> R.string.transfer_op_rename
        TransferOp.CONVERT -> R.string.transfer_op_convert
        TransferOp.MKDIR -> R.string.transfer_op_mkdir
        TransferOp.REMOVE_FROM_ALBUM -> R.string.transfer_op_remove
    }

private val TransferOp.icon: Int
    get() = when (this) {
        TransferOp.COPY -> DesignR.drawable.ic_content_copy

        TransferOp.MOVE -> DesignR.drawable.ic_content_cut

        TransferOp.TRASH, TransferOp.DELETE, TransferOp.REMOVE_FROM_ALBUM ->
            DesignR.drawable.ic_delete

        TransferOp.RESTORE -> DesignR.drawable.ic_restore_from_trash

        TransferOp.RENAME -> DesignR.drawable.ic_edit

        TransferOp.CONVERT -> DesignR.drawable.ic_swap_vert

        TransferOp.MKDIR -> DesignR.drawable.ic_create_new_folder
    }
