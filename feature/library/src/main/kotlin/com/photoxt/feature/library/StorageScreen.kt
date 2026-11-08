package com.photoxt.feature.library

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.datastore.MobileOriginals
import com.photoxt.core.datastore.StorageSettings
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.AlbumStorage
import com.photoxt.core.media.StorageBreakdown
import kotlinx.coroutines.launch

/**
 * The Storage screen (spec §4.4): per account and per album, the thumbnails, previews, cached
 * originals, offline pins and video cache on the phone, with *Free up now* and *Clear cache for
 * this album*, and the cache settings of spec §4.21 (retention, limits, prefetch, data saver).
 */
@Composable
fun StorageScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<StorageViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Measured each time the screen shows: files change while it waits behind another tab.
    LifecycleStartEffect(viewModel) {
        viewModel.measure()
        onStopOrDispose {}
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    StorageContent(
        state = state,
        actions = StorageActions(
            onBack = onBack,
            onFreeUp = {
                scope.launch { context.showFreed(viewModel.freeUpNow()) }
            },
            onClearAlbum = { albumId ->
                scope.launch { context.showFreed(viewModel.clearAlbum(albumId)) }
            },
            onSettings = viewModel::update
        ),
        modifier = modifier
    )
}

private fun Context.showFreed(bytes: Long) {
    val text = getString(R.string.storage_freed, Formatter.formatShortFileSize(this, bytes))
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** What the Storage screen can be asked to do. */
class StorageActions(
    val onBack: () -> Unit = {},
    val onFreeUp: () -> Unit = {},
    val onClearAlbum: (albumId: String) -> Unit = {},
    val onSettings: ((StorageSettings) -> StorageSettings) -> Unit = {}
)

/** The Storage screen without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageContent(state: StorageUiState, actions: StorageActions, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = stringResource(R.string.storage_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(
                        painterResource(DesignR.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item(key = "summary") { Summary(state, actions) }
            usageSections(state, expanded, { expanded = it }, actions)
            item(key = "settings") { Settings(state.settings, actions.onSettings) }
        }
    }
}

@Composable
private fun Summary(state: StorageUiState, actions: StorageActions) {
    val context = LocalContext.current
    val usage = state.usage
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (usage == null) {
                    stringResource(R.string.storage_measuring)
                } else {
                    stringResource(R.string.storage_total, context.size(usage.total))
                },
                style = MaterialTheme.typography.titleLarge
            )
            if (usage != null) {
                Breakdown(usage.usage)
                if (usage.removed > 0) SizeLine(R.string.storage_removed, usage.removed)
            }
            Text(
                text = stringResource(R.string.storage_free_up_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = actions.onFreeUp,
                enabled = !state.busy,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.storage_free_up))
            }
        }
    }
}

@Composable
private fun Breakdown(usage: StorageBreakdown) {
    SizeLine(R.string.storage_thumbnails, usage.thumbnails)
    SizeLine(R.string.storage_previews, usage.previews)
    SizeLine(R.string.storage_originals, usage.originals)
    SizeLine(R.string.storage_offline, usage.offline)
    SizeLine(R.string.storage_video, usage.video)
}

@Composable
private fun SizeLine(label: Int, bytes: Long) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = context.size(bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Per account, its tracked folders, each opening to its breakdown and *Clear cache*. */
private fun LazyListScope.usageSections(
    state: StorageUiState,
    expanded: String?,
    onExpand: (String?) -> Unit,
    actions: StorageActions
) {
    val usage = state.usage ?: return
    if (usage.accounts.all { it.albums.isEmpty() }) {
        item(key = "none") {
            Text(
                text = stringResource(R.string.storage_no_drive),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        return
    }
    usage.accounts.filter { it.albums.isNotEmpty() }.forEach { account ->
        item(key = "account:${account.accountId}") {
            val context = LocalContext.current
            SectionTitle("${account.email} · ${context.size(account.usage.total)}")
        }
        items(account.albums, key = { "album:${it.albumId}" }) { album ->
            AlbumRow(
                album = album,
                expanded = expanded == album.albumId,
                busy = state.busy,
                onToggle = { onExpand(album.albumId.takeIf { it != expanded }) },
                onClear = { actions.onClearAlbum(album.albumId) }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun AlbumRow(
    album: AlbumStorage,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val shown = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column {
        ListItem(
            headlineContent = {
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    pluralStringResource(
                        R.plurals.storage_album_line,
                        album.items,
                        album.items,
                        context.size(album.usage.total)
                    )
                )
            },
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = shown }
        )
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Breakdown(album.usage)
                TextButton(
                    onClick = onClear,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.storage_clear_album))
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

/** The cache rules (spec §4.21, accounts and storage), each a choice from a short list. */
@Composable
private fun Settings(
    settings: StorageSettings,
    onChange: ((StorageSettings) -> StorageSettings) -> Unit
) {
    val context = LocalContext.current
    Column {
        SectionTitle(stringResource(R.string.storage_settings))
        Choice(
            label = stringResource(R.string.storage_retention),
            value = settings.retentionDays,
            choices = StorageSettings.RETENTION_DAYS,
            text = { days ->
                if (days == 0) {
                    stringResource(R.string.storage_retention_always)
                } else {
                    pluralStringResource(R.plurals.storage_retention_days, days, days)
                }
            },
            onChoose = { days -> onChange { it.copy(retentionDays = days) } }
        )
        Choice(
            label = stringResource(R.string.storage_cache_cap),
            value = settings.cacheCapMb,
            choices = StorageSettings.CACHE_CAPS_MB,
            text = { context.size(it * StorageSettings.MB) },
            onChoose = { cap -> onChange { it.copy(cacheCapMb = cap) } }
        )
        Choice(
            label = stringResource(R.string.storage_preview_cap),
            value = settings.previewCapMb,
            choices = StorageSettings.PREVIEW_CAPS_MB,
            text = { context.size(it * StorageSettings.MB) },
            onChoose = { cap -> onChange { it.copy(previewCapMb = cap) } }
        )
        Choice(
            label = stringResource(R.string.storage_prefetch),
            value = settings.prefetch,
            choices = StorageSettings.PREFETCH,
            text = { count ->
                if (count == 0) {
                    stringResource(R.string.storage_prefetch_off)
                } else {
                    pluralStringResource(R.plurals.storage_prefetch_count, count, count)
                }
            },
            onChoose = { count -> onChange { it.copy(prefetch = count) } }
        )
        Choice(
            label = stringResource(R.string.storage_mobile),
            value = settings.mobileOriginals,
            choices = MobileOriginals.entries,
            text = { stringResource(it.label) },
            onChoose = { rule -> onChange { it.copy(mobileOriginals = rule) } }
        )
    }
}

/** A setting showing its value; tapping it lists the [choices]. */
@Composable
private fun <T> Choice(
    label: String,
    value: T,
    choices: List<T>,
    text: @Composable (T) -> String,
    onChoose: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(text(value)) },
            modifier = Modifier.clickable(role = Role.DropdownList) { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(text(choice)) },
                    trailingIcon = if (choice == value) {
                        { Icon(painterResource(DesignR.drawable.ic_check), null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onChoose(choice)
                    }
                )
            }
        }
    }
}

private val MobileOriginals.label: Int
    get() = when (this) {
        MobileOriginals.ALWAYS -> R.string.storage_mobile_always
        MobileOriginals.WIFI_ONLY -> R.string.storage_mobile_wifi
        MobileOriginals.ASK -> R.string.storage_mobile_ask
    }

private fun Context.size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)
