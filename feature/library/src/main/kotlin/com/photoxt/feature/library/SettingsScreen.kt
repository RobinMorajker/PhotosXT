package com.photoxt.feature.library

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.GoogleAccounts
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.datastore.SyncSettings
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.ml.EraserModels
import com.photoxt.core.organize.SettingsSync
import com.photoxt.core.organize.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Settings screen shows. */
data class SettingsUiState(
    val sync: SyncSettings = SyncSettings(),
    val status: SyncStatus? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val modelsOnDisk: Long = 0
)

/** Settings (spec §4.21): Drive sync's rules and settings sync (spec §4.8). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val settingsSync: SettingsSync,
    private val models: EraserModels,
    googleAccounts: GoogleAccounts
) : ViewModel() {
    private val onDisk = MutableStateFlow(0L)

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.settings,
        settingsSync.status,
        googleAccounts.accounts,
        onDisk
    ) { all, status, accounts, bytes ->
        SettingsUiState(all.sync, status, accounts, bytes)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SettingsUiState())

    init {
        viewModelScope.launch { onDisk.value = withContext(Dispatchers.IO) { models.sizeOnDisk() } }
    }

    fun deleteModels() {
        viewModelScope.launch {
            onDisk.value = withContext(Dispatchers.IO) {
                models.deleteAll()
                models.sizeOnDisk()
            }
        }
    }

    fun updateSync(transform: (SyncSettings) -> SyncSettings) {
        viewModelScope.launch { settings.updateSync(transform) }
    }

    fun setBackupAccount(accountId: String) {
        viewModelScope.launch { settingsSync.setAccount(accountId) }
    }

    fun syncNow() = settingsSync.schedule()

    /** Google's consent for the app-data folder, when one is needed. */
    suspend fun authorize(): Authorization? = settingsSync.authorize()

    fun consentResult(data: android.content.Intent?) {
        viewModelScope.launch { settingsSync.consentResult(data) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/**
 * Settings (spec §4.21, minimum set so far): accounts and storage (the cache, Drive sync's
 * interval and mobile data), history, and settings sync through the user's own Drive.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenStorage: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<SettingsViewModel>()
    val historyViewModel = hiltViewModel<HistoryViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.consentResult(result.data)
    }
    val allow: () -> Unit = {
        scope.launch {
            val result = viewModel.authorize()
            if (result is Authorization.NeedsConsent) {
                consent.launch(IntentSenderRequest.Builder(result.intent.intentSender).build())
            }
        }
    }
    SubScreen(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        modifier = modifier
    ) {
        item(key = "accounts") {
            Column {
                SectionTitle(stringResource(R.string.settings_accounts_storage))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_storage)) },
                    supportingContent = { Text(stringResource(R.string.library_storage_body)) },
                    leadingContent = {
                        Icon(painterResource(DesignR.drawable.ic_hard_drive), null)
                    },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenStorage)
                )
                DriveSyncSettings(state.sync, viewModel::updateSync)
            }
        }
        item(key = "history") {
            Column {
                SectionTitle(stringResource(R.string.history_title))
                HistoryControls(historyViewModel)
            }
        }
        item(key = "editor") {
            Column {
                EditorSettings(state.modelsOnDisk, viewModel::deleteModels)
            }
        }
        item(key = "settings-sync") {
            Column { SettingsSyncSection(state, viewModel, allow) }
        }
    }
}

@Composable
private fun DriveSyncSettings(
    sync: SyncSettings,
    onChange: ((SyncSettings) -> SyncSettings) -> Unit
) {
    Choice(
        label = stringResource(R.string.settings_drive_interval),
        value = sync.driveHours,
        choices = SyncSettings.DRIVE_HOURS,
        text = { hours -> pluralStringResource(R.plurals.settings_every_hours, hours, hours) },
        onChoose = { hours -> onChange { it.copy(driveHours = hours) } }
    )
    SwitchRow(
        title = stringResource(R.string.settings_drive_mobile),
        body = stringResource(
            if (sync.driveOnMobile) {
                R.string.settings_drive_mobile_on
            } else {
                R.string.settings_drive_mobile_off
            }
        ),
        checked = sync.driveOnMobile,
        onChange = { on -> onChange { it.copy(driveOnMobile = on) } }
    )
}

@Composable
private fun SettingsSyncSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    allow: () -> Unit
) {
    val status = state.status
    SectionTitle(stringResource(R.string.settings_sync))
    SwitchRow(
        title = stringResource(R.string.settings_sync_switch),
        body = stringResource(R.string.settings_sync_body),
        checked = state.sync.enabled,
        onChange = { on -> viewModel.updateSync { it.copy(enabled = on) } }
    )
    if (!state.sync.enabled || status == null) return
    if (state.accounts.size > 1) {
        Choice(
            label = stringResource(R.string.settings_sync_account),
            value = status.accountId,
            choices = state.accounts.map { it.id },
            text = { id -> state.accounts.firstOrNull { it.id == id }?.email.orEmpty() },
            onChoose = { id -> id?.let(viewModel::setBackupAccount) }
        )
    }
    ListItem(
        headlineContent = { Text(syncLine(status)) },
        trailingContent = {
            when {
                status.accountId == null -> Unit

                status.needsConsent -> TextButton(onClick = allow) {
                    Text(stringResource(R.string.settings_sync_allow))
                }

                else -> TextButton(onClick = viewModel::syncNow, enabled = !status.running) {
                    Text(stringResource(R.string.settings_sync_now))
                }
            }
        }
    )
}

@Composable
private fun syncLine(status: SyncStatus): String = when {
    status.accountId == null -> stringResource(R.string.settings_sync_no_account)

    status.needsConsent -> stringResource(
        R.string.settings_sync_needs_consent,
        status.email.orEmpty()
    )

    status.running -> stringResource(R.string.settings_sync_running)

    status.failed -> stringResource(R.string.settings_sync_failed)

    status.syncedAt != null -> stringResource(
        R.string.settings_sync_done,
        status.email.orEmpty(),
        DateUtils.getRelativeTimeSpanString(status.syncedAt ?: 0).toString()
    )

    else -> stringResource(R.string.settings_sync_waiting, status.email.orEmpty())
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onChange
        )
    )
}
