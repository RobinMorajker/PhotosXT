package com.photoxt.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.ui.EmptyState
import com.photoxt.core.ui.LocalMediaHero
import com.photoxt.core.ui.MediaHeroState
import com.photoxt.feature.albums.AlbumsScreen
import com.photoxt.feature.albums.DriveBrowserScreen
import com.photoxt.feature.library.LibraryScreen
import com.photoxt.feature.library.StorageScreen
import com.photoxt.feature.timeline.AlbumScreen
import com.photoxt.feature.timeline.PicturesScreen
import com.photoxt.feature.timeline.TimelineNavigation
import kotlinx.coroutines.launch

/**
 * App shell: bottom bar on phones, rail from 600 dp wide (spec §4.1), one back stack per tab, and
 * full-screen destinations (viewer, player, slideshow, compare, 360°) over the tabs, so the grid
 * a photo came from stays underneath for its flight back.
 */
@Composable
fun PhotoXtApp(modifier: Modifier = Modifier) {
    val navigation = rememberNavigationState()
    val hero = remember { MediaHeroState() }
    CompositionLocalProvider(LocalMediaHero provides hero) {
        Box(modifier.fillMaxSize()) {
            Tabs(
                navigation,
                // Covered by a full-screen destination: out of TalkBack's reach.
                if (navigation.overlayOpen) Modifier.clearAndSetSemantics {} else Modifier
            )
            Overlays(navigation)
        }
    }
}

@Composable
private fun Tabs(navigation: NavigationState, modifier: Modifier = Modifier) {
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
            onBrowseDrive = { accountId -> navigation.open(DriveBrowserKey(accountId)) }
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
    entry(TopLevelTab.Library) {
        LibraryScreen(
            title = stringResource(TopLevelTab.Library.title),
            onOpenStorage = { navigation.open(StorageKey) }
        )
    }
    entry<StorageKey> { StorageScreen(onBack = navigation::goBack) }
    PlaceholderTabs.forEach { tab -> entry(tab) { TabScreen(tab) } }
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
