package com.photoxt.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.Panorama
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.ViewerStart
import kotlinx.serialization.Serializable

/** The five bottom-navigation tabs (spec §4.1). Each is also the root key of its back stack. */
@Serializable
enum class TopLevelTab(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
    @param:StringRes val emptyTitle: Int,
    @param:StringRes val emptyBody: Int,
    @param:StringRes val title: Int = label
) : NavKey {
    Pictures(
        R.string.tab_pictures,
        DesignR.drawable.ic_tab_pictures,
        DesignR.drawable.ic_tab_pictures_selected,
        R.string.empty_pictures_title,
        R.string.empty_pictures_body
    ),
    Albums(
        R.string.tab_albums,
        DesignR.drawable.ic_tab_albums,
        DesignR.drawable.ic_tab_albums_selected,
        R.string.empty_albums_title,
        R.string.empty_albums_body
    ),
    Shared(
        R.string.tab_shared,
        DesignR.drawable.ic_tab_shared,
        DesignR.drawable.ic_tab_shared_selected,
        R.string.empty_shared_title,
        R.string.empty_shared_body,
        title = R.string.title_shared
    ),
    Public(
        R.string.tab_public,
        DesignR.drawable.ic_tab_public,
        DesignR.drawable.ic_tab_public_selected,
        R.string.empty_public_title,
        R.string.empty_public_body,
        title = R.string.title_public
    ),
    Library(
        R.string.tab_library,
        DesignR.drawable.ic_tab_library,
        DesignR.drawable.ic_tab_library_selected,
        R.string.empty_library_title,
        R.string.empty_library_body
    )
}

/** One album, opened from the Albums tab on top of its back stack. */
@Serializable
data class AlbumKey(val albumId: String, val title: String) : NavKey

// Full-screen destinations shown over the tabs, so the grid stays underneath (spec §4.5): the
// viewer flies back into it, and closing any of them returns to exactly where the user was.

/** The viewer over one grid's items, starting at item [id], the [index]th of them. */
@Serializable
data class ViewerKey(
    val albumId: String?,
    val filter: MediaFilter,
    val index: Int,
    val id: String
) : NavKey {
    constructor(start: ViewerStart) : this(start.albumId, start.filter, start.index, start.id)

    val start: ViewerStart get() = ViewerStart(albumId, filter, index, id)
}

@Serializable
data class PlayerKey(val mediaId: String) : NavKey

@Serializable
data class SlideshowKey(
    val albumId: String?,
    val filter: MediaFilter,
    val index: Int,
    val id: String
) : NavKey {
    constructor(start: ViewerStart) : this(start.albumId, start.filter, start.index, start.id)

    val start: ViewerStart get() = ViewerStart(albumId, filter, index, id)
}

@Serializable
data class CompareKey(val firstId: String, val secondId: String) : NavKey

@Serializable
data class PanoramaKey(
    val contentUri: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) : NavKey {
    constructor(contentUri: String, crop: Panorama) :
        this(contentUri, crop.left, crop.top, crop.width, crop.height)

    val crop: Panorama get() = Panorama(left, top, width, height)
}

private val StartTab = TopLevelTab.Pictures

/**
 * One back stack per tab, so every tab keeps its own screens and saved UI state while another tab
 * is showing. Back follows "exit through home": it pops the current tab's stack, then returns to
 * Pictures, and only Pictures exits the app. Pattern from the official nav3-recipes "multiple
 * back stacks" recipe.
 */
class NavigationState internal constructor(
    currentTab: MutableState<TopLevelTab>,
    private val backStacks: Map<TopLevelTab, NavBackStack<NavKey>>,
    private val overlays: NavBackStack<NavKey>
) {
    var currentTab: TopLevelTab by currentTab
        private set

    fun select(tab: TopLevelTab) {
        currentTab = tab
    }

    /** Opens [key] on top of the current tab's stack. */
    fun open(key: NavKey) {
        backStacks.getValue(currentTab).add(key)
    }

    fun goBack() {
        val stack = backStacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else currentTab = StartTab
    }

    /** Whether a full-screen destination covers the tabs. */
    val overlayOpen: Boolean get() = overlays.isNotEmpty()

    /** Shows [key] full screen, over the tabs and any other full-screen destination. */
    fun openOverlay(key: NavKey) {
        overlays.add(key)
    }

    /** Removes [key], normally the topmost full-screen destination. */
    fun closeOverlay(key: NavKey) {
        val index = overlays.lastIndexOf(key)
        if (index >= 0) overlays.removeAt(index)
    }

    /** The full-screen destinations, bottom first, each with its own state and ViewModels. */
    @Composable
    fun overlayEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> =
        rememberDecoratedNavEntries(
            backStack = overlays,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider
        )

    /** The entries to display: Pictures' stack, then the current tab's stack on top of it. */
    @Composable
    fun entries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        // Each stack gets its own saveable-state and ViewModel decorators, so state and
        // ViewModels belong to one entry and outlive its tab being hidden.
        val decorated = backStacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider
            )
        }
        val visibleTabs = listOf(StartTab, currentTab).distinct()
        return visibleTabs.flatMap { decorated.getValue(it) }
    }
}

@Composable
fun rememberNavigationState(): NavigationState {
    val currentTab = rememberSaveable { mutableStateOf(StartTab) }
    val backStacks = TopLevelTab.entries.associateWith { rememberNavBackStack(it) }
    val overlays = rememberNavBackStack()
    return remember { NavigationState(currentTab, backStacks, overlays) }
}
