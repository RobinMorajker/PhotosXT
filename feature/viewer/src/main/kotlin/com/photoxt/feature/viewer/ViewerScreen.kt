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
import androidx.compose.runtime.produceState
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
import com.photoxt.core.model.SourceType
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

/** Where the viewer sends the user. */
class ViewerNavigation(
    /** The viewer has flown back into its grid; remove it. */
    val onClose: () -> Unit,
    val onPlay: (mediaId: String) -> Unit,
    val onSlideshow: (ViewerStart) -> Unit,
    val onCompare: (firstId: String, secondId: String) -> Unit,
    val onPanorama: (contentUri: String, Panorama) -> Unit
)

/**
 * The viewer (spec §4.5): the items of the grid it was opened from, in the same order, one per
 * page. It flies out of the tapped tile and, on back or a swipe down, back into the tile of the
 * item it is showing, with the grid visible underneath.
 */
@Composable
fun ViewerScreen(start: ViewerStart, navigation: ViewerNavigation, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ViewerViewModel, ViewerViewModel.Factory> { it.create(start) }
    val items = viewModel.items.collectAsLazyPagingItems()
    val hero = LocalMediaHero.current
    val transition = remember { ViewerTransition(hero) }
    val pager = rememberPagerState(initialPage = start.index) {
        maxOf(items.itemCount, start.index + 1)
    }
    val frame = remember { ViewerFrame() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val closeNow: suspend () -> Unit = {
        val page = pager.currentPage
        val item = items.peekOrNull(page)
        val from = frame.handles[page]?.bounds?.invoke()?.translate(-frame.origin)
            ?: Rect(Offset.Zero, frame.size)
        transition.close(
            from = from,
            model = item?.let { closingModel(context, it, frame.size.toIntSize()) },
            stretch = viewModel.displayMode.value == DisplayMode.STRETCH,
            itemId = item?.id,
            target = { hero?.anchor(viewModel.sourceKey)?.reveal(page)?.translate(-frame.origin) }
        )
        navigation.onClose()
    }
    OpeningFlight(start, viewModel, transition, frame)
    FixStartPage(start, items, pager, viewModel)
    BackHandler(enabled = transition.phase != ViewerTransition.Phase.SHOWN) {}
    PredictiveBack(transition, frame, items, pager, viewModel, closeNow)
    LaunchedEffect(scope) { frame.scope = scope }
    Box(
        modifier
            .fillMaxSize()
            .onPlaced {
                frame.origin = it.positionInRoot()
                frame.size = it.size.toSize()
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = transition.backdropAlpha }
                .background(Color.Black)
        )
        ViewerBody(
            ViewerParts(viewModel, items, pager, transition, frame),
            close = { scope.launch { closeNow() } },
            navigation = navigation
        )
        HeroLayer(transition)
    }
}

/** What the viewer's parts share. */
internal class ViewerParts(
    val viewModel: ViewerViewModel,
    val items: LazyPagingItems<ViewerItem>,
    val pager: PagerState,
    val transition: ViewerTransition,
    val frame: ViewerFrame
)

/** Bars, sheets and the playing motion photo, which the pages and the bars both change. */
@Stable
internal class ViewerUi {
    var chrome by mutableStateOf(true)
    var details by mutableStateOf<ViewerItem?>(null)
    var motion by mutableStateOf<String?>(null)
}

/** Flies the tapped tile's photo into place, once the viewer knows its size. */
@Composable
private fun OpeningFlight(
    start: ViewerStart,
    viewModel: ViewerViewModel,
    transition: ViewerTransition,
    frame: ViewerFrame
) {
    val hero = LocalMediaHero.current
    val context = LocalContext.current
    LaunchedEffect(transition) {
        val launch = hero?.launch?.takeIf { it.id == start.id }
        hero?.launch = null
        val viewport = snapshotFlow { frame.sizeState }.first { !it.isEmpty() }
        val mode = viewModel.displayMode.value
        transition.open(
            from = launch?.copy(bounds = launch.bounds.translate(-frame.origin)),
            target = displayedBounds(mode, viewport, launch?.imageSize ?: Size.Zero),
            model = openingModel(context, launch, viewport.toIntSize()),
            stretch = mode == DisplayMode.STRETCH
        )
    }
}

/** The pager, its bars and the details sheet. */
@Composable
private fun ViewerBody(parts: ViewerParts, close: () -> Unit, navigation: ViewerNavigation) {
    val viewModel = parts.viewModel
    val ui = remember { ViewerUi() }
    val transition = parts.transition
    LaunchedEffect(parts.pager.settledPage) { ui.motion = null }
    val current = parts.items.peekOrNull(parts.pager.currentPage)
    val traits = current?.let { viewModel.traitsOf(it).collectAsStateWithLifecycle().value }
    HdrWindowEffect(traits?.ultraHdr == true && !transition.flying)
    // The system bars follow the viewer's own bars only: hiding them for a flight would move
    // the grid underneath while it shows through.
    ImmersiveEffect(barsVisible = ui.chrome)
    ViewerPages(parts, ui, close, navigation)
    AnimatedVisibility(
        visible = ui.chrome && !transition.flying,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        ViewerBars(parts, ui, current, traits, close, navigation)
    }
    ui.details?.let { item -> DetailsSheet(item, viewModel::details) { ui.details = null } }
}

@Composable
private fun ViewerBars(
    parts: ViewerParts,
    ui: ViewerUi,
    current: ViewerItem?,
    traits: ImageTraits?,
    close: () -> Unit,
    navigation: ViewerNavigation
) {
    val mode by parts.viewModel.displayMode.collectAsStateWithLifecycle()
    val zoomLock by parts.viewModel.zoomLock.collectAsStateWithLifecycle()
    val page = parts.pager.currentPage
    val actions = chromeActions(
        context = LocalContext.current,
        viewModel = parts.viewModel,
        page = page,
        current = current,
        other = parts.items.peekOrNull(page + 1) ?: parts.items.peekOrNull(page - 1),
        traits = traits,
        close = close,
        navigation = navigation,
        onDetails = { ui.details = it },
        onMotion = { ui.motion = it }
    )
    Box(Modifier.fillMaxSize()) {
        ViewerTopBar(current, mode, zoomLock, actions, Modifier.align(Alignment.TopCenter))
        ViewerBottomBar(actions, Modifier.align(Alignment.BottomCenter))
    }
}

/** One page per item; a page's vertical swipes close the viewer or open its details. */
@Composable
private fun ViewerPages(
    parts: ViewerParts,
    ui: ViewerUi,
    close: () -> Unit,
    navigation: ViewerNavigation
) {
    val viewModel = parts.viewModel
    val transition = parts.transition
    val mode by viewModel.displayMode.collectAsStateWithLifecycle()
    val zoomLockOn by viewModel.zoomLock.collectAsStateWithLifecycle()
    val zoomLock = remember { ZoomLock() }
    HorizontalPager(
        state = parts.pager,
        key = { page -> parts.items.peekOrNull(page)?.id ?: "page:$page" },
        beyondViewportPageCount = 1,
        pageSpacing = PageGap,
        userScrollEnabled = transition.phase == ViewerTransition.Phase.SHOWN,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (transition.flying) 0f else 1f }
    ) { page ->
        val item = parts.items.getOrNull(page) ?: return@HorizontalPager
        val handle = rememberPageHandle(parts.frame, page)
        val actions = PageActions(
            onTap = { ui.chrome = !ui.chrome },
            onLongPress = {
                if (viewModel.traitsOf(item).value.motionVideo != null) ui.motion = item.id
            },
            onMotionEnded = { ui.motion = null },
            onPlay = { navigation.onPlay(item.id) }
        )
        val description = describeItem(item)
        val pageModifier = Modifier
            .verticalSwipes(pageSwipes(parts, item, handle, mode, ui, close))
            .semantics { contentDescription = description }
        when (item.kind) {
            MediaKind.IMAGE -> ImagePage(
                item = item,
                traits = viewModel.traitsOf(item).collectAsStateWithLifecycle().value,
                mode = mode,
                lock = zoomLock.takeIf { zoomLockOn },
                current = page == parts.pager.settledPage,
                motionPlaying = ui.motion == item.id,
                handle = handle,
                actions = actions,
                modifier = pageModifier,
                drive = if (item.source == SourceType.DRIVE) {
                    val original by viewModel.originalOf(item).collectAsStateWithLifecycle()
                    val blur by produceState<Blur?>(null, item.id) {
                        value = viewModel.blurOf(item)
                    }
                    val preview by produceState<java.io.File?>(null, item.id) {
                        value = viewModel.previewOf(item)
                    }
                    DrivePage(original, preview, blur)
                } else {
                    null
                }
            )

            MediaKind.VIDEO -> VideoPage(item, mode, handle, actions, pageModifier)
        }
    }
}

/** Down closes the viewer once far or fast enough, otherwise springs back; up opens details. */
@Composable
private fun pageSwipes(
    parts: ViewerParts,
    item: ViewerItem,
    handle: PageHandle,
    mode: DisplayMode,
    ui: ViewerUi,
    close: () -> Unit
): VerticalSwipes {
    val context = LocalContext.current
    val transition = parts.transition
    val frame = parts.frame
    return VerticalSwipes(
        enabled = { transition.phase == ViewerTransition.Phase.SHOWN && !handle.zoomed() },
        onStart = {
            val from = handle.bounds()?.translate(-frame.origin) ?: Rect(Offset.Zero, frame.size)
            transition.beginGesture(
                from,
                closingModel(context, item, frame.size.toIntSize()),
                mode == DisplayMode.STRETCH
            )
        },
        onDrag = { transition.dragBy(it, frame.size.height) },
        onEnd = { velocity ->
            if (transition.shouldClose(velocity, frame.size.height)) {
                close()
            } else {
                frame.scope?.launch { transition.cancelGesture() }
            }
        },
        onSwipeUp = { ui.details = item }
    )
}

/** Everything the viewer's bars can do; null actions do not apply to the current item. */
internal data class ChromeActions(
    val onBack: () -> Unit,
    val onDisplayMode: (DisplayMode) -> Unit,
    val onZoomLock: (Boolean) -> Unit,
    val onShare: () -> Unit,
    val onDetails: () -> Unit,
    val onSlideshow: () -> Unit,
    val onCompare: (() -> Unit)?,
    val onOpenWith: () -> Unit,
    val onSetAs: () -> Unit,
    val onCopy: () -> Unit,
    val onPlay: (() -> Unit)?,
    val onMotion: (() -> Unit)?,
    val onPanorama: (() -> Unit)?
)

/** Where the viewer sits and its pages' reports, read by gestures and flights. */
internal class ViewerFrame {
    var origin = Offset.Zero
    val handles = HashMap<Int, PageHandle>()
    var scope: CoroutineScope? = null

    /** The viewer's size, as state so the opening flight can wait for the first layout. */
    var sizeState by mutableStateOf(Size.Zero)
    var size: Size
        get() = sizeState
        set(value) {
            sizeState = value
        }
}

@Composable
private fun rememberPageHandle(frame: ViewerFrame, page: Int): PageHandle {
    val handle = remember(page) { PageHandle() }
    DisposableEffect(frame, page, handle) {
        frame.handles[page] = handle
        onDispose { if (frame.handles[page] === handle) frame.handles.remove(page) }
    }
    return handle
}

internal fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

/**
 * The item at [index], asking Paging to load around it; null outside the loaded list. The pager
 * has the start page before the first page loads, and Paging throws for an index past its end.
 */
internal fun <T : Any> LazyPagingItems<T>.getOrNull(index: Int): T? =
    if (index in 0 until itemCount) get(index) else null

internal fun Size.toIntSize() = IntSize(width.roundToInt(), height.roundToInt())

private val PageGap = 16.dp
