package com.photoxt.feature.library

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.datastore.StorageSettings
import com.photoxt.core.media.AccountStorage
import com.photoxt.core.media.AlbumStorage
import com.photoxt.core.media.StorageBreakdown
import com.photoxt.core.media.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Storage screen's actions reach the right album and setting (spec §4.4). */
@RunWith(AndroidJUnit4::class)
class StorageContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clearsTheExpandedAlbumAndChangesARule() {
        var cleared: String? = null
        var settings = StorageSettings()
        compose.setContent {
            StorageContent(
                state = StorageUiState(
                    usage = StorageUsage(
                        listOf(
                            AccountStorage(
                                "a",
                                "a@example.com",
                                listOf(AlbumStorage("drive:a:1", "Holidays", 3, StorageBreakdown()))
                            )
                        ),
                        removed = 0
                    )
                ),
                actions = StorageActions(
                    onClearAlbum = { cleared = it },
                    onSettings = { settings = it(settings) }
                )
            )
        }

        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Holidays"))
        compose.onNodeWithText("Holidays").performClick()
        list.performScrollToNode(hasText("Clear cache for this album"))
        compose.onNodeWithText("Clear cache for this album").performClick()
        list.performScrollToNode(hasText("Keep downloaded originals"))
        compose.onNodeWithText("Keep downloaded originals").performClick()
        compose.onNodeWithText("7 days after last opened").performClick()

        assertEquals("drive:a:1", cleared)
        assertEquals(7, settings.retentionDays)
    }
}
