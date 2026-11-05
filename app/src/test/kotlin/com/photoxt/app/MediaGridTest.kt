package com.photoxt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.ui.GridDensity
import com.photoxt.core.ui.MEDIA_GRID_TEST_TAG
import com.photoxt.core.ui.MediaGrid
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Selecting and pinching on the media grid (spec §4.1, §4.2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_QUALIFIERS)
class MediaGridTest {
    @get:Rule
    val compose = createComposeRule()

    private var selection by mutableStateOf(emptyMap<String, Long>())
    private var density by mutableStateOf(GridDensity.Default)
    private val groupsSelected = mutableListOf<Pair<Int, Boolean>>()

    @Before
    fun show() {
        compose.setContent {
            WithFakeThumbnails {
                val rows = remember { flowOf(PagingData.from(GalleryFakes.cells)) }
                    .collectAsLazyPagingItems()
                MediaGrid(
                    rows = rows,
                    layout = GalleryFakes.layout,
                    density = density,
                    onDensityChange = { density = it },
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onSelectGroup = { key, select -> groupsSelected += key to select }
                )
            }
        }
    }

    private fun tile(index: Int) =
        compose.onAllNodesWithContentDescription("Photo", substring = true)[index]

    @Test
    fun longPressStartsSelectingAndTapsThenToggle() {
        tile(0).performTouchInput { longClick() }
        assertEquals(setOf("a1"), selection.keys)

        tile(2).performTouchInput { longClick(center) }
        tile(1).performClick()
        tile(0).performClick()
        assertEquals(setOf("a3", "a4"), selection.keys)
    }

    @Test
    fun talkBackCanSelectWithoutGestures() {
        tile(0).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(setOf("a1"), selection.keys)
        // While selecting, a date header is its group's select-all checkbox.
        compose.onAllNodesWithText("2024", substring = true)[0].performClick()
        assertEquals(listOf(GalleryFakes.layout.keys[0] to true), groupsSelected)
    }

    @Test
    fun pinchingChangesTheDensityOneStepAtATime() {
        compose.onNodeWithTag(MEDIA_GRID_TEST_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f),
                end0 = center - Offset(200f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(200f, 0f)
            )
        }
        assertEquals(GridDensity.DAY_3, density)
        compose.onNodeWithTag(MEDIA_GRID_TEST_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(200f, 0f),
                end0 = center - Offset(40f, 0f),
                start1 = center + Offset(200f, 0f),
                end1 = center + Offset(40f, 0f)
            )
        }
        assertEquals(GridDensity.DAY_4, density)
    }
}
