package com.photoxt.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.model.DisplayMode
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/**
 * What one page tells the viewer: where its photo is on screen (root coordinates) and whether it
 * is zoomed or turned, in which case swiping down pans instead of closing.
 */
internal class PageHandle {
    var bounds: () -> Rect? = { null }
    var zoomed: () -> Boolean = { false }
}

/** Callbacks a page needs. */
internal class PageActions(
    val onTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onMotionEnded: () -> Unit,
    val onPlay: () -> Unit
)

/**
 * One photo (spec §4.5): pinch, pan, fling and double-tap zoom up to [MAX_ZOOM] beyond 1:1, with
 * tiled decoding for huge images; two-finger rotation; the display [mode]; zoom [lock] when on;
 * and a motion photo's video while [motionPlaying].
 */
@Composable
internal fun ImagePage(
    item: ViewerItem,
    traits: ImageTraits,
    mode: DisplayMode,
    lock: ZoomLock?,
    current: Boolean,
    motionPlaying: Boolean,
    handle: PageHandle,
    actions: PageActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomState)
    val rotation = rememberPageRotation()
    val place = remember { PagePlace() }
    var pageSize by remember { mutableStateOf(Size.Zero) }
    DisposableEffect(handle, zoomState, rotation, mode) {
        handle.bounds = { zoomState.shownBounds()?.translate(place.origin) }
        handle.zoomed = { (zoomState.zoomFraction ?: 0f) > ZOOMED || rotation.turned }
        onDispose { handle.bounds = { null } }
    }
    ZoomLockEffect(lock, zoomState, current)
    val image = item.displaySize()
    Box(
        modifier
            .fillMaxSize()
            .onPlaced { place.origin = it.positionInRoot() }
            .onSizeChanged { pageSize = it.toSize() }
    ) {
        ZoomableAsyncImage(
            model = remember(item.id, item.modifiedAt) { fullImageRequest(context, item) },
            contentDescription = null,
            state = imageState,
            contentScale = mode.contentScale,
            gestures = when {
                rotation.active -> EnabledZoomGestures.None

                // With zoom lock, one finger pages between photos; two fingers zoom and pan.
                lock != null -> EnabledZoomGestures.ZoomOnly

                else -> EnabledZoomGestures.ZoomAndPan
            },
            onClick = { actions.onTap() },
            onLongClick = { actions.onLongPress() },
            onDoubleClick = CycleFitFillPixels,
            modifier = Modifier
                .fillMaxSize()
                .rotatable(rotation, displayedBounds(mode, pageSize, image).size)
        )
        val video = traits.motionVideo
        val uri = item.contentUri
        if (motionPlaying && video != null && uri != null) {
            MotionVideo(
                uri,
                video,
                mode.contentScale,
                actions.onMotionEnded,
                Modifier.fillMaxSize()
            )
        }
    }
}

/** Where a page sits, read by the flight rather than by composition. */
internal class PagePlace {
    var origin = Offset.Zero
    var size = Size.Zero
}

/** The photo's bounds on the page, zoom and pan included, before clipping to the page. */
@OptIn(ExperimentalTelephotoApi::class)
private fun ZoomableState.shownBounds(): Rect? {
    if (!contentTransformation.isSpecified) return null
    return with(coordinateSystem) {
        contentBounds(clipToViewport = false).rectIn(CoordinateSpace.Viewport)
    }
}

private const val ZOOMED = 0.01f
