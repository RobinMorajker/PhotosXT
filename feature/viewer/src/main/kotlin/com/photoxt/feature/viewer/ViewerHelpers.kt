package com.photoxt.feature.viewer

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.media.Panorama
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.ui.HdrWindowEffect
import com.photoxt.core.ui.ImmersiveEffect
import com.photoxt.core.ui.LocalMediaHero
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** If the grid changed between the tap and now, find the tapped item again. */
@Composable
internal fun FixStartPage(
    start: ViewerStart,
    items: LazyPagingItems<ViewerItem>,
    pager: PagerState,
    viewModel: ViewerViewModel
) {
    LaunchedEffect(start) {
        val first = snapshotFlow { items.peekOrNull(start.index) }.first { it != null }
        if (first?.id != start.id) viewModel.indexOf(start.id)?.let { pager.scrollToPage(it) }
    }
}

/** Predictive back (spec §4.5): the photo shrinks with the gesture, then flies home. */
@Composable
internal fun PredictiveBack(
    transition: ViewerTransition,
    frame: ViewerFrame,
    items: LazyPagingItems<ViewerItem>,
    pager: PagerState,
    viewModel: ViewerViewModel,
    closeNow: suspend () -> Unit
) {
    val context = LocalContext.current
    PredictiveBackHandler(enabled = transition.phase == ViewerTransition.Phase.SHOWN) { events ->
        val page = pager.currentPage
        val item = items.peekOrNull(page)
        val from = frame.handles[page]?.bounds?.invoke()?.translate(-frame.origin)
        if (item != null && from != null) {
            transition.beginGesture(
                from,
                closingModel(context, item, frame.size.toIntSize()),
                viewModel.displayMode.value == DisplayMode.STRETCH
            )
        }
        try {
            events.collect { event ->
                transition.backProgress(
                    event.progress,
                    fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT,
                    width = frame.size.width
                )
            }
            closeNow()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { transition.cancelGesture() }
            throw cancelled
        }
    }
}

/** The photo in flight, drawn over everything at the transition's bounds. */
@Composable
internal fun HeroLayer(transition: ViewerTransition) {
    if (!transition.flying) return
    AsyncImage(
        model = transition.model,
        contentDescription = null,
        contentScale = if (transition.stretch) ContentScale.FillBounds else ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                val bounds = transition.heroBounds
                val width = bounds.width.roundToInt().coerceAtLeast(0)
                val height = bounds.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                }
            }
            .graphicsLayer { alpha = transition.heroAlpha }
    )
}
