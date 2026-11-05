package com.photoxt.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A draggable thumb on the right edge of a long grid, with a bubble that names the date under it
 * (spec §4.2). It shows while the grid scrolls and fades out shortly after. TalkBack can adjust it
 * like a slider. [itemCount] is every item of the grid; [labelAt] names the item at an index.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    labelAt: (index: Int) -> String?,
    modifier: Modifier = Modifier
) {
    if (itemCount < MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    val drag = remember(state, scope) { ScrollerDrag(state, scope) }
    val visible = rememberShownWhile(state.isScrollInProgress || drag.dragging)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "fastScrollerAlpha")
    // The thumb follows every row in the layout phase; composition only sees 1% steps, so a
    // fling does not recompose the scroller on every frame.
    val position = remember(state, itemCount) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (itemCount - 1) }
    }
    val percent by remember(position) {
        derivedStateOf { (position.value.coerceIn(0f, 1f) * PERCENT).roundToInt() }
    }
    val shown = { (if (drag.dragging) drag.position else position.value).coerceIn(0f, 1f) }
    val description = stringResource(R.string.fast_scroll)

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val thumbHeight = with(LocalDensity.current) { ThumbHeight.toPx() }
        val track = (constraints.maxHeight - thumbHeight).coerceAtLeast(1f)
        val thumbOffset = { IntOffset(0, (shown() * track).roundToInt()) }
        if (drag.dragging) {
            labelAt((drag.position * (itemCount - 1)).roundToInt())?.let { label ->
                DateBubble(label, Modifier.align(Alignment.TopEnd).offset { thumbOffset() })
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { thumbOffset() }
                .size(TouchWidth, ThumbHeight)
                .graphicsLayer { this.alpha = alpha }
                // Only a visible thumb can be grabbed, so a touch at the edge still scrolls.
                .pointerInput(itemCount, track, visible) {
                    if (!visible) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { drag.start(position.value) },
                        onDragEnd = drag::end,
                        onDragCancel = drag::end
                    ) { change, dragAmount ->
                        change.consume()
                        drag.moveBy(dragAmount / track, itemCount)
                    }
                }
                .semantics {
                    contentDescription = description
                    stateDescription = labelAt(percent * (itemCount - 1) / PERCENT).orEmpty()
                    progressBarRangeInfo = ProgressBarRangeInfo(percent / PERCENT.toFloat(), 0f..1f)
                    setProgress { value ->
                        drag.scrollTo(value, itemCount)
                        true
                    }
                }
        ) {
            ThumbMark(Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** The visible pill; the thumb around it is wider, so it is easy to grab. */
@Composable
private fun ThumbMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = 4.dp)
            .size(width = 6.dp, height = ThumbHeight)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
    )
}

/** Where a dragged thumb is, as a fraction of the grid, and scrolling the grid to match. */
private class ScrollerDrag(private val state: LazyGridState, private val scope: CoroutineScope) {
    var dragging by mutableStateOf(false)
        private set
    var position by mutableFloatStateOf(0f)
        private set

    fun start(from: Float) {
        position = from
        dragging = true
    }

    fun moveBy(fraction: Float, itemCount: Int) {
        position = (position + fraction).coerceIn(0f, 1f)
        scrollTo(position, itemCount)
    }

    fun end() {
        dragging = false
    }

    fun scrollTo(fraction: Float, itemCount: Int) {
        scope.launch { state.scrollToItem((fraction * (itemCount - 1)).roundToInt()) }
    }
}

/** True while [active], and for a moment after, so the thumb can still be grabbed. */
@Composable
private fun rememberShownWhile(active: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) delay(HIDE_DELAY_MS)
        shown = active
    }
    return shown
}

@Composable
private fun DateBubble(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 2.dp,
        modifier = modifier.padding(end = TouchWidth)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

private const val MIN_ITEMS = 200
private const val PERCENT = 100
private const val HIDE_DELAY_MS = 1500L
private val ThumbHeight = 48.dp
private val TouchWidth = 48.dp
