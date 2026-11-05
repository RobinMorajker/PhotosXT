package com.photoxt.app

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.designsystem.PXT_HEADER_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Phase 0 "Done when": the app launches to Pictures, and switching tabs keeps each tab's state. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_QUALIFIERS)
class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(tab: TopLevelTab): SemanticsNodeInteraction =
        compose.onNode(hasText(compose.activity.getString(tab.label)) and isSelectable())

    private fun headerHeight(): Dp =
        compose.onNodeWithTag(PXT_HEADER_TEST_TAG).getBoundsInRoot().height

    @Test
    fun launchesToPicturesAndEachTabKeepsItsState() {
        tab(TopLevelTab.Pictures).assertIsSelected()
        val expanded = headerHeight()
        assertTrue("header starts expanded, was $expanded", expanded > 200.dp)

        // Drag the content up to collapse the Pictures header; another tab then starts fresh.
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        val collapsed = headerHeight()
        assertTrue("header collapses, was $collapsed", collapsed < expanded / 2)
        tab(TopLevelTab.Albums).performClick()
        tab(TopLevelTab.Albums).assertIsSelected()
        assertEquals(expanded, headerHeight())

        // Pictures comes back as it was left, also after the activity is recreated.
        tab(TopLevelTab.Pictures).performClick()
        assertEquals(collapsed, headerHeight())
        compose.activityRule.scenario.recreate()
        assertEquals(collapsed, headerHeight())
    }

    @Test
    fun backFromAnotherTabReturnsToPictures() {
        tab(TopLevelTab.Library).performClick()
        tab(TopLevelTab.Library).assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        tab(TopLevelTab.Pictures).assertIsSelected()
    }
}

/** A typical phone: 360 x 800 dp at xhdpi. */
const val PHONE_QUALIFIERS = "w360dp-h800dp-xhdpi"
