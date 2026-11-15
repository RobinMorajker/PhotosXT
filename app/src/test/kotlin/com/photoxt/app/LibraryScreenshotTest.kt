package com.photoxt.app

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.feature.library.R as LibraryR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library pages of Phase 6 (spec §4.8, §4.9, §4.21), opened as the user opens them, at
 * normal and at 200% text size (spec §7). A fresh install: nothing bookmarked or viewed yet,
 * Hidden and the vault locked, Settings at their defaults.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class LibraryScreenshotTest(private val page: Page) {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun page() {
        openLibraryPage(compose, page.title)
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/library_${page.name.lowercase()}.png"
        )
    }

    /** A page and the title of its Library row. */
    enum class Page(@param:StringRes val title: Int) {
        BOOKMARKS(LibraryR.string.bookmarks_title),
        HISTORY(LibraryR.string.history_title),
        HIDDEN(LibraryR.string.hidden_title),
        VAULT(LibraryR.string.vault_title),
        SETTINGS(LibraryR.string.settings_title)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun pages() = Page.entries.map { arrayOf(it) }
    }
}

/** The same pages at 200% text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
class LibraryLargeTextScreenshotTest(private val page: LibraryScreenshotTest.Page) {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun page() {
        openLibraryPage(compose, page.title)
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/library_${page.name.lowercase()}_200.png"
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun pages() = LibraryScreenshotTest.Page.entries.map { arrayOf(it) }
    }
}

/** Library tab, then the row titled [title], scrolled to first: semantic clicks, no ripples. */
private fun openLibraryPage(
    compose: AndroidComposeTestRule<*, MainActivity>,
    @StringRes title: Int
) {
    val activity = compose.activity
    compose.onNode(hasText(activity.getString(TopLevelTab.Library.label)) and isSelectable())
        .performSemanticsAction(SemanticsActions.OnClick)
    val row = hasText(activity.getString(title)) and hasClickAction()
    compose.onNode(hasScrollAction()).performScrollToNode(row)
    compose.onNode(row).performSemanticsAction(SemanticsActions.OnClick)
    compose.waitForIdle()
}
