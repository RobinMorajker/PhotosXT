package com.photoxt.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaList
import com.photoxt.core.organize.SettingsSync
import com.photoxt.core.ui.EmptyState
import com.photoxt.core.ui.LocalMediaHero
import com.photoxt.core.ui.MediaHeroState
import com.photoxt.core.ui.PrivateLock
import com.photoxt.feature.albums.AlbumGroupScreen
import com.photoxt.feature.albums.AlbumsScreen
import com.photoxt.feature.albums.DriveBrowserScreen
import com.photoxt.feature.library.BookmarkNavigation
import com.photoxt.feature.library.BookmarksScreen
import com.photoxt.feature.library.HiddenScreen
import com.photoxt.feature.library.HistoryScreen
import com.photoxt.feature.library.LibraryPage
import com.photoxt.feature.library.LibraryScreen
import com.photoxt.feature.library.SettingsScreen
import com.photoxt.feature.library.StorageScreen
import com.photoxt.feature.library.TransfersScreen
import com.photoxt.feature.library.TrashScreen
import com.photoxt.feature.library.VaultScreen
import com.photoxt.feature.timeline.AlbumScreen
import com.photoxt.feature.timeline.MediaListScreen
import com.photoxt.feature.timeline.PicturesScreen
import com.photoxt.feature.timeline.TimelineNavigation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * App shell: bottom bar on phones, rail from 600 dp wide (spec §4.1), one back stack per tab, and
 * full-screen destinations (viewer, player, slideshow, compare, 360°) over the tabs, so the grid
 * a photo came from stays underneath for its flight back.
 */
@Composable
fun PhotoXtApp(
    modifier: Modifier = Modifier,
    open: AppOpen = AppOpen(),
    onOpenHandled: () -> Unit = {}
) {
    val navigation = rememberNavigationState()
    val hero = remember { MediaHeroState() }
    val snackbar = remember { SnackbarHostState() }
    // A transfer notification's tap opens Transfers; an album shortcut's, its album.
    LaunchedEffect(open) {
        if (open.transfers) navigation.openIn(TopLevelTab.Library, TransfersKey)
        open.album?.let { (id, title) ->
            navigation.openIn(TopLevelTab.Albums, AlbumKey(id, title))
        }
        if (open != AppOpen()) onOpenHandled()
    }
    RestoredSnackbar(snackbar)
    CompositionLocalProvider(LocalMediaHero provides hero) {
        Box(modifier.fillMaxSize()) {
            Tabs(
                navigation,
                // Covered by a full-screen destination: out of TalkBack's reach.
                if (navigation.overlayOpen) Modifier.clearAndSetSemantics {} else Modifier
            )
            Overlays(navigation)
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = SnackbarLift)
            )
        }
    }
    TransferPrompts(snackbar, onOpenTransfers = {
        navigation.openIn(TopLevelTab.Library, TransfersKey)
    })
}

/** What the app was opened to show: Transfers, or an album as `(id, title)`. */
data class AppOpen(val transfers: Boolean = false, val album: Pair<String, String>? = null)

/** Says once that settings sync restored a reinstall's albums and settings (spec §4.8). */
@HiltViewModel
class AppEventsViewModel @Inject constructor(settingsSync: SettingsSync) : ViewModel() {
    val restored: Flow<Unit> = settingsSync.restored
}

@Composable
private fun RestoredSnackbar(snackbar: SnackbarHostState) {
    val viewModel = hiltViewModel<AppEventsViewModel>()
    val message = stringResource(R.string.settings_restored)
    LaunchedEffect(viewModel) {
        viewModel.restored.collect {
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }
}

/** Above the bottom navigation bar, where a snackbar can't hide the tabs. */
private val SnackbarLift = 88.dp

@Composable
private fun Tabs(navigation: NavigationState, modifier: Modifier = Modifier) {
    // Hidden and the vault lock again whenever the Library list shows (spec §4.8). Decided here:
    // NavDisplay can keep the list composed under the pages it opened, so the list can't tell.
    val libraryShows = navigation.currentTab == TopLevelTab.Library && navigation.atTabRoot
    LaunchedEffect(libraryShows) { if (libraryShows) PrivateLock.lock() }
    // While a grid is in multi-select, its action bar takes the navigation bar's place.
    val navigationSuite = rememberNavigationSuiteScaffoldState()
    val scope = rememberCoroutineScope()
    val onSelectionModeChange: (Boolean) -> Unit = remember(navigationSuite, scope) {
        { selecting ->
            scope.launch { if (selecting) navigationSuite.hide() else navigationSuite.show() }
        }
    }
    NavigationSuiteScaffold(
        modifier = modifier,
        state = navigationSuite,
        navigationSuiteItems = {
            TopLevelTab.entries.forEach { tab ->
                val selected = tab == navigation.currentTab
                item(
                    selected = selected,
                    onClick = { navigation.select(tab) },
                    icon = {
                        Icon(
                            painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(tab.label)) }
                )
            }
        }
    ) {
        NavDisplay(
            entries = navigation.entries(appEntries(navigation, onSelectionModeChange)),
            onBack = navigation::goBack
        )
    }
}

/** What each back-stack key shows. */
private fun appEntries(
    navigation: NavigationState,
    onSelectionModeChange: (Boolean) -> Unit
): (NavKey) -> NavEntry<NavKey> = entryProvider {
    val timelineNavigation = TimelineNavigation(
        onOpen = { start -> navigation.openOverlay(ViewerKey(start)) },
        onCompare = { first, second -> navigation.openOverlay(CompareKey(first, second)) },
        onOpenAlbum = { id, title -> navigation.open(AlbumKey(id, title)) },
        onRelink = { albumId, accountId -> navigation.open(DriveBrowserKey(accountId, albumId)) }
    )
    entry(TopLevelTab.Pictures) {
        PicturesScreen(
            title = stringResource(TopLevelTab.Pictures.title),
            empty = { TabEmptyState(TopLevelTab.Pictures) },
            onSelectionModeChange = onSelectionModeChange,
            navigation = timelineNavigation
        )
    }
    entry(TopLevelTab.Albums) {
        AlbumsScreen(
            title = stringResource(TopLevelTab.Albums.title),
            empty = { TabEmptyState(TopLevelTab.Albums) },
            onOpenAlbum = { id, title -> navigation.open(AlbumKey(id, title)) },
            onBrowseDrive = { accountId -> navigation.open(DriveBrowserKey(accountId)) },
            onOpenGroup = { id, title -> navigation.open(AlbumGroupKey(id, title)) }
        )
    }
    albumEntries(navigation, onSelectionModeChange, timelineNavigation)
    entry(TopLevelTab.Library) {
        LibraryScreen(
            title = stringResource(TopLevelTab.Library.title),
            onOpen = { page -> navigation.open(page.key) }
        )
    }
    entry<StorageKey> { StorageScreen(onBack = navigation::goBack) }
    entry<TrashKey> { TrashScreen(onBack = navigation::goBack) }
    entry<TransfersKey> { TransfersScreen(onBack = navigation::goBack) }
    libraryEntries(navigation, onSelectionModeChange, timelineNavigation)
    PlaceholderTabs.forEach { tab -> entry(tab) { TabScreen(tab) } }
}

/** What opens from the Albums tab: a group, the Drive folder browser, an album. */
private fun EntryProviderScope<NavKey>.albumEntries(
    navigation: NavigationState,
    onSelectionModeChange: (Boolean) -> Unit,
    timelineNavigation: TimelineNavigation
) {
    entry<AlbumGroupKey> { key ->
        AlbumGroupScreen(
            groupId = key.groupId,
            title = key.title,
            onBack = navigation::goBack,
            onOpenAlbum = { id, title -> navigation.open(AlbumKey(id, title)) }
        )
    }
    entry<DriveBrowserKey> { key ->
        DriveBrowserScreen(
            accountId = key.accountId,
            relinkAlbumId = key.relinkAlbumId,
            onBack = navigation::goBack,
            onAdded = { id, title ->
                navigation.goBack()
                // A relinked album is the one underneath already.
                if (key.relinkAlbumId == null) navigation.open(AlbumKey(id, title))
            }
        )
    }
    entry<AlbumKey> { key ->
        AlbumScreen(
            albumId = key.albumId,
            title = key.title,
            empty = { TabEmptyState(TopLevelTab.Pictures) },
            onSelectionModeChange = onSelectionModeChange,
            onBack = navigation::goBack,
            navigation = timelineNavigation
        )
    }
}

/** The Library's pages of spec §4.8 and §4.9, and Settings. */
private fun EntryProviderScope<NavKey>.libraryEntries(
    navigation: NavigationState,
    onSelectionModeChange: (Boolean) -> Unit,
    timelineNavigation: TimelineNavigation
) {
    entry<MediaListKey> { key ->
        PrivateIf(key.list == MediaList.HIDDEN, onLocked = navigation::goBack) {
            MediaListScreen(
                list = key.list,
                title = stringResource(key.list.title),
                empty = { ListEmptyState(key.list) },
                onSelectionModeChange = onSelectionModeChange,
                onBack = navigation::goBack,
                navigation = timelineNavigation
            )
        }
    }
    entry<HiddenAlbumKey> { key ->
        PrivateIf(true, onLocked = navigation::goBack) {
            AlbumScreen(
                albumId = key.albumId,
                title = key.title,
                empty = { TabEmptyState(TopLevelTab.Pictures) },
                onSelectionModeChange = onSelectionModeChange,
                onBack = navigation::goBack,
                navigation = timelineNavigation
            )
        }
    }
    entry<BookmarksKey> {
        BookmarksScreen(
            onBack = navigation::goBack,
            navigation = BookmarkNavigation(
                onOpen = { navigation.openOverlay(ViewerKey(it)) },
                onPlay = { id, at -> navigation.openOverlay(PlayerKey(id, startAt = at)) },
                onOpenAlbum = { id, title -> navigation.open(AlbumKey(id, title)) }
            )
        )
    }
    entry<HistoryKey> {
        HistoryScreen(
            onBack = navigation::goBack,
            onOpen = { navigation.openOverlay(ViewerKey(it)) },
            onPlay = { navigation.openOverlay(PlayerKey(it)) }
        )
    }
    entry<HiddenKey> {
        HiddenScreen(
            onBack = navigation::goBack,
            onOpenItems = { navigation.open(MediaListKey(MediaList.HIDDEN)) },
            onOpenAlbum = { id, title -> navigation.open(HiddenAlbumKey(id, title)) }
        )
    }
    entry<VaultKey> {
        VaultScreen(
            onBack = navigation::goBack,
            onOpen = { navigation.openOverlay(VaultViewerKey(it)) }
        )
    }
    entry<SettingsKey> {
        SettingsScreen(onBack = navigation::goBack, onOpenStorage = { navigation.open(StorageKey) })
    }
}

/** Where each Library row leads. */
private val LibraryPage.key: NavKey
    get() = when (this) {
        LibraryPage.FAVORITES -> MediaListKey(MediaList.FAVORITES)
        LibraryPage.BOOKMARKS -> BookmarksKey
        LibraryPage.HISTORY -> HistoryKey
        LibraryPage.VIDEOS -> MediaListKey(MediaList.VIDEOS)
        LibraryPage.SCREENSHOTS -> MediaListKey(MediaList.SCREENSHOTS)
        LibraryPage.TRASH -> TrashKey
        LibraryPage.HIDDEN -> HiddenKey
        LibraryPage.VAULT -> VaultKey
        LibraryPage.OFFLINE -> MediaListKey(MediaList.OFFLINE)
        LibraryPage.TRANSFERS -> TransfersKey
        LibraryPage.STORAGE -> StorageKey
        LibraryPage.SETTINGS -> SettingsKey
    }

private val MediaList.title: Int
    get() = when (this) {
        MediaList.FAVORITES -> R.string.list_favorites
        MediaList.VIDEOS -> R.string.list_videos
        MediaList.SCREENSHOTS -> R.string.list_screenshots
        MediaList.OFFLINE -> R.string.list_offline
        MediaList.HIDDEN -> R.string.list_hidden
    }

@Composable
private fun ListEmptyState(list: MediaList) {
    val (icon, title, body) = when (list) {
        MediaList.FAVORITES ->
            Triple(
                DesignR.drawable.ic_favorite,
                R.string.list_favorites_empty,
                R.string.list_favorites_empty_body
            )

        MediaList.VIDEOS ->
            Triple(
                DesignR.drawable.ic_play,
                R.string.list_videos_empty,
                R.string.list_videos_empty_body
            )

        MediaList.SCREENSHOTS ->
            Triple(
                DesignR.drawable.ic_image,
                R.string.list_screenshots_empty,
                R.string.list_screenshots_empty_body
            )

        MediaList.OFFLINE ->
            Triple(
                DesignR.drawable.ic_cloud_off,
                R.string.list_offline_empty,
                R.string.list_offline_empty_body
            )

        MediaList.HIDDEN ->
            Triple(
                DesignR.drawable.ic_visibility_off,
                R.string.list_hidden_empty,
                R.string.list_hidden_empty_body
            )
    }
    EmptyState(
        icon = painterResource(icon),
        title = stringResource(title),
        body = stringResource(body)
    )
}

/** Tabs whose content arrives in later phases; until then they show their empty state. */
private val PlaceholderTabs = listOf(TopLevelTab.Shared, TopLevelTab.Public)

@Composable
private fun TabEmptyState(tab: TopLevelTab, modifier: Modifier = Modifier) {
    EmptyState(
        icon = painterResource(tab.icon),
        title = stringResource(tab.emptyTitle),
        body = stringResource(tab.emptyBody),
        modifier = modifier
    )
}

/** A tab that has no content yet: the One UI header over the tab's empty state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(tab: TopLevelTab, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(title = stringResource(tab.title), scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { TabEmptyState(tab, Modifier.fillParentMaxSize()) }
        }
    }
}

@PreviewLightDark
@Composable
private fun TabScreenPreview() {
    PxtTheme {
        Surface {
            TabScreen(TopLevelTab.Library)
        }
    }
}
