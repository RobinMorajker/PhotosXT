package com.photoxt.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.ContinueRow
import com.photoxt.core.database.HistoryRow
import com.photoxt.core.datastore.HistorySettings
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.organize.History
import com.photoxt.core.ui.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History (spec §4.9): *Continue watching*, *Recently viewed*, pause, auto-delete and clear. */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpen: (ViewerStart) -> Unit,
    onPlay: (mediaId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<HistoryViewModel>()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val watching by viewModel.continueWatching.collectAsStateWithLifecycle()
    var clearing by rememberSaveable { mutableStateOf<ClearChoice?>(null) }
    SubScreen(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        modifier = modifier
    ) {
        item(key = "controls") {
            HistoryControls(viewModel, onClear = { clearing = ClearChoice.ALL })
        }
        if (watching.isNotEmpty()) {
            item(key = "continue") { ContinueWatching(watching, onPlay) }
        }
        item(key = "recent-title") { SectionTitle(stringResource(R.string.history_recent)) }
        val rows = recent.orEmpty()
        if (recent != null && rows.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        items(rows, key = { it.id }) { row -> RecentRow(row, onOpen) }
    }
    clearing?.let { choice ->
        ClearDialogs(choice, viewModel, onChoice = {
            clearing = it
        }, onDismiss = { clearing = null })
    }
}

/** Pause, auto-delete and clear: also in Settings (spec §4.21, history on/off and auto-delete). */
@Composable
internal fun HistoryControls(viewModel: HistoryViewModel, onClear: (() -> Unit)? = null) {
    val settings by viewModel.historySettings.collectAsStateWithLifecycle()
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.history_pause)) },
            supportingContent = { Text(stringResource(R.string.history_pause_body)) },
            trailingContent = { Switch(checked = settings.paused, onCheckedChange = null) },
            modifier = Modifier.toggleable(
                value = settings.paused,
                role = Role.Switch,
                onValueChange = viewModel::setPaused
            )
        )
        Choice(
            label = stringResource(R.string.history_keep),
            value = settings.keepMonths,
            choices = HistorySettings.KEEP_MONTHS,
            text = { months ->
                if (months == 0) {
                    stringResource(R.string.history_keep_always)
                } else {
                    pluralStringResource(R.plurals.history_keep_months, months, months)
                }
            },
            onChoose = viewModel::setKeepMonths
        )
        onClear?.let { clear ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.history_clear)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = clear)
            )
        }
    }
}

@Composable
private fun ContinueWatching(rows: List<ContinueRow>, onPlay: (String) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.history_continue))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rows, key = { it.id }) { row ->
                Column(
                    Modifier
                        .width(ContinueWidth)
                        .clickable(role = Role.Button) { onPlay(row.id) }
                ) {
                    Thumb(
                        thumbnailOf(
                            MediaRef(
                                row.id,
                                row.source,
                                row.contentUri,
                                row.modifiedAt,
                                row.accountId,
                                remoteVersion = row.remoteVersion
                            )
                        ),
                        size = ContinueWidth
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (row.durationMs >
                                0
                            ) {
                                row.positionMs.toFloat() / row.durationMs
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    Text(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(row: HistoryRow, onOpen: (ViewerStart) -> Unit) {
    val ago = DateUtils.getRelativeTimeSpanString(row.viewedAt).toString()
    ListItem(
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.history_row, row.views, row.views, ago))
        },
        leadingContent = {
            Thumb(
                thumbnailOf(
                    MediaRef(
                        row.id,
                        row.source,
                        row.contentUri,
                        row.modifiedAt,
                        row.accountId,
                        remoteVersion = row.remoteVersion
                    )
                )
            )
        },
        modifier = Modifier.clickable(role = Role.Button) {
            // The viewer pages through the item's own album, finding the item there.
            onOpen(ViewerStart(row.albumId, MediaFilter.ALL, 0, row.id))
        }
    )
}

private enum class ClearChoice { ALL, CONFIRM_ALL, RANGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearDialogs(
    choice: ClearChoice,
    viewModel: HistoryViewModel,
    onChoice: (ClearChoice) -> Unit,
    onDismiss: () -> Unit
) {
    when (choice) {
        ClearChoice.ALL -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = { onChoice(ClearChoice.CONFIRM_ALL) }) {
                    Text(stringResource(R.string.history_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { onChoice(ClearChoice.RANGE) }) {
                    Text(stringResource(R.string.history_clear_range))
                }
            }
        )

        ClearChoice.CONFIRM_ALL -> ConfirmDialog(
            title = stringResource(R.string.history_clear_all_title),
            body = stringResource(R.string.history_clear_all_body),
            confirm = stringResource(R.string.history_clear_all),
            onConfirm = {
                onDismiss()
                viewModel.clearAll()
            },
            onDismiss = onDismiss
        )

        ClearChoice.RANGE -> {
            val state = rememberDateRangePickerState()
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        enabled = state.selectedEndDateMillis != null,
                        onClick = {
                            val from = state.selectedStartDateMillis
                            val until = state.selectedEndDateMillis
                            onDismiss()
                            if (from != null && until != null) {
                                viewModel.clearDays(from.utcDate(), until.utcDate())
                            }
                        }
                    ) { Text(stringResource(R.string.history_clear_range_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            ) {
                DateRangePicker(state = state, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A date picker's selection: midnight UTC of the picked day. */
private fun Long.utcDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val ContinueWidth = 136.dp
