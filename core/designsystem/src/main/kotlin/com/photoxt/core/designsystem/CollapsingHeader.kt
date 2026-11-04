package com.photoxt.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt

private val CollapsedHeight = 64.dp
private val MinWindowHeightToExpand = 480.dp
private const val EXPANDED_WINDOW_FRACTION = 0.4f
private const val TITLE_SWAP_FRACTION = 0.5f

/**
 * One UI-style header (spec §4.1). At rest it fills the top 40% of the window with a large centred
 * title, so content starts in the lower, thumb-reachable part of the screen. As content scrolls it
 * collapses into a toolbar-height title. Short windows (landscape phones) start collapsed.
 *
 * Pair it with [androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior] and
 * put that behaviour's nested-scroll connection on the screen. The behaviour's state is saveable,
 * so the collapse position survives tab switches and configuration changes.
 */
@ExperimentalMaterial3Api
@Composable
fun PxtCollapsingHeader(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    val expandedHeight = expandedHeight()
    val canExpand = expandedHeight > CollapsedHeight
    val state = scrollBehavior.state
    val collapseRangePx = with(LocalDensity.current) { (expandedHeight - CollapsedHeight).toPx() }
    SideEffect { state.heightOffsetLimit = -collapseRangePx }
    // Swap titles halfway, where both are fully transparent, so only one is in the semantics tree.
    val showLargeTitle by remember(state, canExpand) {
        derivedStateOf { canExpand && state.collapsedFraction < TITLE_SWAP_FRACTION }
    }

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .layout { measurable, constraints ->
                // Read the offset during layout, not composition, so scrolling never recomposes.
                val height = (expandedHeight.toPx() + state.heightOffset).roundToInt()
                    .coerceAtLeast(CollapsedHeight.roundToPx())
                val placeable = measurable.measure(
                    constraints.copy(minHeight = height, maxHeight = height)
                )
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .clipToBounds()
    ) {
        if (showLargeTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = 1f - state.collapsedFraction * 2f }
                    .semantics { heading() }
            )
        } else {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .height(CollapsedHeight)
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha =
                                if (canExpand) state.collapsedFraction * 2f - 1f else 1f
                        }
                        .semantics { heading() }
                )
            }
        }
    }
}

/** 40% of the window height, or just the toolbar height on windows too short to spare that. */
@Composable
private fun expandedHeight(): Dp {
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return if (window < MinWindowHeightToExpand) {
        CollapsedHeight
    } else {
        max(CollapsedHeight, window * EXPANDED_WINDOW_FRACTION)
    }
}
