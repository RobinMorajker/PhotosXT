package com.photoxt.app

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.core.designsystem.PxtTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One screenshot per tab, compared with src/test/screenshots on every test run.
 * Re-record after an intended UI change with ./gradlew recordRoborazziDebug.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class TabScreenshotTest(private val tab: TopLevelTab) {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tab() {
        // A semantic click, not a touch: a touch leaves a ripple whose frame differs between runs.
        compose.onNode(hasText(compose.activity.getString(tab.label)) and isSelectable())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onRoot().captureRoboImage("src/test/screenshots/tab_${tab.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun tabs() = TopLevelTab.entries.map { arrayOf(it) }
    }
}

/** Dark and AMOLED-black variants of the theme, on the Pictures screen. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, amoledBlack: Boolean) {
        compose.setContent {
            PxtTheme(darkTheme = true, amoledBlack = amoledBlack) {
                Surface { TabScreen(TopLevelTab.Pictures) }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/theme_$name.png")
    }

    @Test
    fun dark() = capture("dark", amoledBlack = false)

    @Test
    fun amoled() = capture("amoled", amoledBlack = true)
}

/** Spec §7: layouts must hold at 200% text size. Shared has the longest title. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
class LargeTextScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun sharedAt200Percent() {
        compose.onNode(hasText(compose.activity.getString(R.string.tab_shared)) and isSelectable())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onRoot().captureRoboImage("src/test/screenshots/text_200_percent.png")
    }
}
