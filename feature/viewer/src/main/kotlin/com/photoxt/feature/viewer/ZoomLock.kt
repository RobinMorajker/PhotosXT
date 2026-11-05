package com.photoxt.feature.viewer

import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.first
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomFocalPoint
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset

/**
 * Zoom lock (spec §4.5): the zoom and the spot in view carry over from photo to photo, to compare
 * sharpness across a burst. The zoom is relative to each photo's display mode, and the spot is the
 * image point at the centre of the screen, as fractions of the image's width and height.
 */
@Stable
internal class ZoomLock {
    var zoom by mutableFloatStateOf(1f)
    var centre by mutableStateOf(Offset(HALF, HALF))
}

/**
 * Keeps one page in step with [lock]: the [current] page writes its zoom into the lock, the pages
 * beside it follow, so the next photo is already zoomed to the same spot when swiped in.
 */
@OptIn(ExperimentalTelephotoApi::class)
@Composable
internal fun ZoomLockEffect(lock: ZoomLock?, state: ZoomableState, current: Boolean) {
    if (lock == null) return
    LaunchedEffect(lock, state, current) {
        snapshotFlow { state.contentTransformation }.first { it.isSpecified }
        if (current) {
            snapshotFlow { state.contentTransformation }.collect { transformation ->
                lock.zoom = transformation.scaleMetadata.userZoom
                lock.centre = state.centreFraction()
            }
        } else {
            snapshotFlow { lock.zoom to lock.centre }.collect { (zoom, centre) ->
                val base = state.contentTransformation.scaleMetadata.initialScale.let {
                    maxOf(it.scaleX, it.scaleY)
                }
                val size = state.unscaledContentSize()
                val spot = Offset(centre.x * size.width, centre.y * size.height)
                state.zoomTo(
                    zoomFactor = base * zoom,
                    focal = ZoomFocalPoint.moveToViewportCenter(
                        SpatialOffset(spot, CoordinateSpace.ZoomableContent)
                    ),
                    animationSpec = snap()
                )
            }
        }
    }
}

/** The image point at the centre of the screen, as fractions of the image's size. */
@OptIn(ExperimentalTelephotoApi::class)
private fun ZoomableState.centreFraction(): Offset = with(coordinateSystem) {
    val size = unscaledContentSize()
    if (size.isEmpty()) return Offset(HALF, HALF)
    val viewportCentre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    val spot = SpatialOffset(viewportCentre, CoordinateSpace.Viewport)
        .offsetIn(CoordinateSpace.ZoomableContent)
    Offset(spot.x / size.width, spot.y / size.height)
}

private const val HALF = 0.5f
