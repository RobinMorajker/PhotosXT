package com.photoxt.core.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot

/**
 * A grid's side of [MediaHeroState]: it records which tile an open started from, and finds (and
 * scrolls to) the tile a closing viewer flies back into. Indices are the lazy grid's own.
 */
internal class HeroTiles(private val state: LazyGridState, private val hero: MediaHeroState?) :
    HeroAnchor {
    var coordinates: LayoutCoordinates? = null
    var layout: TimelineLayout = TimelineLayout.EMPTY
    var leadingCount = 0

    /** Opens an item tapped at [row] (a grid row, after any leading item) via [open]. */
    fun opener(open: (GridCell.Item, ordinal: Int) -> Unit): (GridCell.Item, Int) -> Unit =
        { item, row ->
            launchFrom(item, leadingCount + row)
            open(item, layout.itemOrdinalAt(row))
        }

    private fun launchFrom(item: GridCell.Item, index: Int) {
        hero?.launch = boundsOf(index)?.let { bounds ->
            val size = Size(item.width.toFloat(), item.height.toFloat())
            HeroLaunch(item.id, bounds, item.thumbnail, size, item.isVideo)
        }
    }

    override suspend fun reveal(ordinal: Int): Rect? {
        if (layout.itemCount == 0) return null
        val index = leadingCount + layout.rowOfItem(ordinal)
        if (!fullyVisible(index)) {
            val info = state.layoutInfo
            val tile = info.visibleItemsInfo.lastOrNull()?.size?.height ?: 0
            state.scrollToItem(index)
            // Centre the tile, as far as the grid's ends allow.
            state.scrollBy(-(info.viewportSize.height - tile) / 2f)
            // The grid lays out its new position in the frame after next.
            repeat(2) { withFrameNanos { } }
        }
        return boundsOf(index)
    }

    private fun fullyVisible(index: Int): Boolean {
        val info = state.layoutInfo
        val tile = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        return tile.offset.y >= info.viewportStartOffset &&
            tile.offset.y + tile.size.height <= info.viewportEndOffset
    }

    private fun boundsOf(index: Int): Rect? {
        val grid = coordinates?.takeIf { it.isAttached } ?: return null
        val tile =
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val origin =
            grid.positionInRoot() + Offset(tile.offset.x.toFloat(), tile.offset.y.toFloat())
        return Rect(origin, Size(tile.size.width.toFloat(), tile.size.height.toFloat()))
    }
}

/** The grid's [HeroTiles], and [onOpen] wrapped to record the tile each open starts from. */
@Composable
internal fun rememberHeroTiles(
    state: LazyGridState,
    layout: TimelineLayout,
    leadingCount: Int,
    sourceKey: String?,
    onOpen: ((GridCell.Item, ordinal: Int) -> Unit)?
): Pair<HeroTiles, ((GridCell.Item, row: Int) -> Unit)?> {
    val hero = LocalMediaHero.current
    val tiles = remember(state, hero) { HeroTiles(state, hero) }
    SideEffect {
        tiles.layout = layout
        tiles.leadingCount = leadingCount
    }
    if (hero != null && sourceKey != null) {
        DisposableEffect(hero, sourceKey, tiles) {
            hero.register(sourceKey, tiles)
            onDispose { hero.unregister(sourceKey, tiles) }
        }
    }
    val open = remember(onOpen, tiles) { onOpen?.let(tiles::opener) }
    return tiles to open
}
