package com.photoxt.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * The flight of a photo between its grid tile and the viewer (spec §4.5): the viewer opens out of
 * the tapped tile, and on closing it asks the grid that opened it to bring the current item's tile
 * into view and flies the photo back into it. While a photo is in flight its tile is hidden, so it
 * never shows twice.
 *
 * This is plain bounds and an overlay rather than Compose's SharedTransitionLayout, which would
 * put the whole app, every grid scroll included, inside a lookahead scope.
 */
@Stable
class MediaHeroState {
    /** The tile the last open started from. Not saved: after process death the viewer fades in. */
    var launch: HeroLaunch? = null

    /** The item whose tile is hidden while its photo is in flight. */
    var hiddenId: String? by mutableStateOf(null)

    private val anchors = HashMap<String, HeroAnchor>()

    /** The grid showing [sourceKey]'s items, if it is still on screen. */
    fun anchor(sourceKey: String): HeroAnchor? = anchors[sourceKey]

    internal fun register(sourceKey: String, anchor: HeroAnchor) {
        anchors[sourceKey] = anchor
    }

    internal fun unregister(sourceKey: String, anchor: HeroAnchor) {
        if (anchors[sourceKey] === anchor) anchors.remove(sourceKey)
    }
}

/**
 * Where a flight starts: the tapped tile in root coordinates, the thumbnail it showed, and the
 * item's displayed pixel size (zero when unknown), which tells the viewer where the photo lands.
 */
@Immutable
data class HeroLaunch(
    val id: String,
    val bounds: Rect,
    val thumbnail: Any?,
    val imageSize: Size,
    val isVideo: Boolean
)

/** A grid that can tell where an item's tile is, scrolling to it first when it is off screen. */
fun interface HeroAnchor {
    /** The tile of the item at [ordinal] (0 = newest), in root coordinates, or null. */
    suspend fun reveal(ordinal: Int): Rect?
}

/** The app's one [MediaHeroState]; null in previews and tests that do not provide one. */
val LocalMediaHero = staticCompositionLocalOf<MediaHeroState?> { null }

/** The key a grid and its viewer share: one album (or everything) under one filter. */
fun heroSourceKey(albumId: String?, filter: Any): String = "${albumId ?: "*"}|$filter"
