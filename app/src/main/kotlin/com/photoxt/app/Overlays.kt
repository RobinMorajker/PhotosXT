package com.photoxt.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.photoxt.feature.player.PlayerScreen
import com.photoxt.feature.viewer.CompareScreen
import com.photoxt.feature.viewer.PanoramaScreen
import com.photoxt.feature.viewer.SlideshowScreen
import com.photoxt.feature.viewer.ViewerNavigation
import com.photoxt.feature.viewer.ViewerScreen

/**
 * The full-screen destinations, stacked over the tabs. Each takes every touch in its area, so
 * nothing underneath reacts; one covered by another (the viewer under the player) is not drawn
 * and is out of TalkBack's reach, but keeps its state for the way back.
 */
@Composable
internal fun Overlays(navigation: NavigationState) {
    val entries = navigation.overlayEntries(overlayEntries(navigation))
    entries.forEachIndexed { index, entry ->
        val covered = index < entries.lastIndex
        key(entry.contentKey) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (covered) {
                            Modifier
                                .graphicsLayer { alpha = 0f }
                                .clearAndSetSemantics {}
                        } else {
                            Modifier
                        }
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent()
                        }
                    }
            ) {
                entry.Content()
            }
        }
    }
}

private fun overlayEntries(navigation: NavigationState): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        entry<ViewerKey> { key ->
            ViewerScreen(
                start = key.start,
                navigation = ViewerNavigation(
                    onClose = { navigation.closeOverlay(key) },
                    onPlay = { navigation.openOverlay(PlayerKey(it)) },
                    onSlideshow = { navigation.openOverlay(SlideshowKey(it)) },
                    onCompare = { first, second ->
                        navigation.openOverlay(CompareKey(first, second))
                    },
                    onPanorama = { uri, crop -> navigation.openOverlay(PanoramaKey(uri, crop)) }
                )
            )
        }
        entry<PlayerKey> { key ->
            PlayerScreen(mediaId = key.mediaId, onClose = { navigation.closeOverlay(key) })
        }
        entry<SlideshowKey> { key ->
            SlideshowScreen(start = key.start, onClose = { navigation.closeOverlay(key) })
        }
        entry<CompareKey> { key ->
            CompareScreen(
                firstId = key.firstId,
                secondId = key.secondId,
                onClose = { navigation.closeOverlay(key) }
            )
        }
        entry<PanoramaKey> { key ->
            PanoramaScreen(
                contentUri = key.contentUri,
                panorama = key.crop,
                onClose = { navigation.closeOverlay(key) }
            )
        }
    }
