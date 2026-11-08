package com.photoxt.feature.albums

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.R as DesignR
import kotlinx.coroutines.launch

/**
 * "Add Drive folder" (spec §4.2): browse [accountId]'s My Drive, Shared with me, Shared drives,
 * Starred, or search, then add the open folder as an album, its subfolders nested or merged.
 * [onAdded] gets the new album's id and title. With [relinkAlbumId], the open folder becomes the
 * one that unavailable album follows instead (Relink, spec §4.3).
 */
@Composable
fun DriveBrowserScreen(
    accountId: String,
    onBack: () -> Unit,
    onAdded: (albumId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    relinkAlbumId: String? = null
) {
    val viewModel = hiltViewModel<DriveBrowserViewModel, DriveBrowserViewModel.Factory>(
        key = "$accountId/$relinkAlbumId"
    ) { it.create(accountId, relinkAlbumId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val taken = stringResource(R.string.browser_already_album)
    var asked by rememberSaveable { mutableStateOf(false) }
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { viewModel.consentResult(it.data) }
    // Show Google's consent screen once by itself; "Allow" asks again after a refusal.
    val pending = state.consent
    LaunchedEffect(pending) {
        if (pending != null && !asked) {
            asked = true
            consent.launch(IntentSenderRequest.Builder(pending).build())
        }
    }
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }
    DriveBrowserContent(
        state = state,
        actions = object : BrowserActions {
            override fun onBack() {
                if (!viewModel.up()) onBack()
            }

            override fun onAllow() {
                val intent = state.consent
                if (intent != null) {
                    consent.launch(IntentSenderRequest.Builder(intent).build())
                } else {
                    viewModel.requestAccess()
                }
            }

            override fun onRoot(root: DriveRoot) = viewModel.selectRoot(root)

            override fun onSearch(query: String) = viewModel.search(query)

            override fun onOpen(folder: BrowserFolder) = viewModel.open(folder)

            override fun onMerge(merge: Boolean) = viewModel.setMerge(merge)

            override fun onRetry() = viewModel.reload()

            override fun onMore() = viewModel.loadMore()

            override fun onAdd() {
                scope.launch {
                    val title = state.current?.name.orEmpty()
                    val albumId = viewModel.addCurrent()
                    if (albumId != null) {
                        onAdded(albumId, title)
                    } else {
                        Toast.makeText(context, taken, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        modifier = modifier
    )
}

/** What the browser can be asked to do; previews and tests override only what they use. */
interface BrowserActions {
    fun onBack() = Unit

    fun onAllow() = Unit

    fun onRoot(root: DriveRoot) = Unit

    fun onSearch(query: String) = Unit

    fun onOpen(folder: BrowserFolder) = Unit

    fun onMerge(merge: Boolean) = Unit

    fun onRetry() = Unit

    fun onMore() = Unit

    fun onAdd() = Unit
}

/** The browser without its ViewModel, for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowserContent(
    state: DriveBrowserUiState,
    actions: BrowserActions,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(
            title = state.current?.name ?: stringResource(
                if (state.relinking) R.string.browser_relink_title else R.string.browser_title
            ),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = actions::onBack) {
                    Icon(
                        painterResource(DesignR.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.action_cancel)
                    )
                }
            }
        )
        when {
            !state.granted -> AccessNeeded(state.error, actions::onAllow, Modifier.weight(1f))

            else -> {
                if (state.path.isEmpty()) RootPicker(state, actions)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                FolderList(state, actions, Modifier.weight(1f))
                AddBar(state, actions)
            }
        }
    }
}

@Composable
private fun AccessNeeded(error: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(if (error) R.string.browser_error else R.string.browser_consent),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onAllow, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(if (error) R.string.action_retry else R.string.action_allow))
        }
    }
}

@Composable
private fun RootPicker(state: DriveBrowserUiState, actions: BrowserActions) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.browser_search)) },
        leadingIcon = {
            Icon(painterResource(DesignR.drawable.ic_search), contentDescription = null)
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                // The results and the Add bar sit under the keyboard otherwise.
                keyboard?.hide()
                actions.onSearch(query)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        DriveRoot.entries.forEach { root ->
            FilterChip(
                selected = state.query.isBlank() && state.root == root,
                onClick = {
                    query = ""
                    actions.onRoot(root)
                },
                label = { Text(stringResource(root.label)) },
                leadingIcon = {
                    Icon(painterResource(root.icon), contentDescription = null)
                }
            )
        }
    }
}

@Composable
private fun FolderList(state: DriveBrowserUiState, actions: BrowserActions, modifier: Modifier) {
    val list = rememberLazyListState()
    // Near the end of what is loaded: load the next page.
    LaunchedEffect(list) {
        snapshotFlow {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - LOAD_AHEAD
        }.collect { near -> if (near) actions.onMore() }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(state.folders, key = { it.id }) { folder ->
                ListItem(
                    headlineContent = {
                        Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Icon(
                            painterResource(
                                if (folder.driveId == folder.id) {
                                    DesignR.drawable.ic_hard_drive
                                } else {
                                    DesignR.drawable.ic_folder
                                }
                            ),
                            contentDescription = null
                        )
                    },
                    trailingContent = {
                        Icon(
                            painterResource(DesignR.drawable.ic_chevron_right),
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable(role = Role.Button) { actions.onOpen(folder) }
                )
            }
        }
        if (!state.loading && state.folders.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(
                        if (state.error) R.string.browser_error else R.string.browser_empty
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (state.error) {
                    TextButton(onClick = actions::onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddBar(state: DriveBrowserUiState, actions: BrowserActions) {
    val current = state.current
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            HorizontalDivider()
            // A relinked album keeps its own subfolder setting.
            if (!state.relinking) MergeSwitch(state, actions)
            Button(
                onClick = actions::onAdd,
                enabled = current != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        current == null && state.relinking ->
                            stringResource(R.string.browser_pick_relink)

                        current == null -> stringResource(R.string.browser_pick_folder)

                        state.relinking -> stringResource(R.string.browser_relink, current.name)

                        else -> stringResource(R.string.browser_add_folder, current.name)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MergeSwitch(state: DriveBrowserUiState, actions: BrowserActions) {
    val current = state.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = current != null, role = Role.Switch) {
                actions.onMerge(!state.merge)
            }
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.browser_merge),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = state.merge,
            onCheckedChange = null,
            enabled = current != null
        )
    }
}

private val DriveRoot.label: Int
    get() = when (this) {
        DriveRoot.MY_DRIVE -> R.string.browser_my_drive
        DriveRoot.SHARED_WITH_ME -> R.string.browser_shared_with_me
        DriveRoot.SHARED_DRIVES -> R.string.browser_shared_drives
        DriveRoot.STARRED -> R.string.browser_starred
    }

private val DriveRoot.icon: Int
    get() = when (this) {
        DriveRoot.MY_DRIVE -> DesignR.drawable.ic_folder
        DriveRoot.SHARED_WITH_ME -> DesignR.drawable.ic_folder_shared
        DriveRoot.SHARED_DRIVES -> DesignR.drawable.ic_hard_drive
        DriveRoot.STARRED -> DesignR.drawable.ic_star
    }

private const val LOAD_AHEAD = 20
