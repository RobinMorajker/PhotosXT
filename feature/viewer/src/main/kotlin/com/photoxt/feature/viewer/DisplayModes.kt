package com.photoxt.feature.viewer

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import com.photoxt.core.model.DisplayMode
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/** How each display mode lays out a photo before any zoom. */
internal val DisplayMode.contentScale: ContentScale
    get() = when (this) {
        DisplayMode.FIT -> ContentScale.Fit
        DisplayMode.FILL -> ContentScale.Crop
        DisplayMode.STRETCH -> ContentScale.FillBounds
        DisplayMode.FIT_WIDTH -> ContentScale.FillWidth
        DisplayMode.FIT_HEIGHT -> ContentScale.FillHeight
        DisplayMode.ONE_TO_ONE -> ContentScale.None
    }

@get:StringRes
internal val DisplayMode.label: Int
    get() = when (this) {
        DisplayMode.FIT -> R.string.mode_fit
        DisplayMode.FILL -> R.string.mode_fill
        DisplayMode.STRETCH -> R.string.mode_stretch
        DisplayMode.FIT_WIDTH -> R.string.mode_fit_width
        DisplayMode.FIT_HEIGHT -> R.string.mode_fit_height
        DisplayMode.ONE_TO_ONE -> R.string.mode_one_to_one
    }

/** Where [mode] puts an image of size [image] in a [viewport], centred, before any zoom. */
internal fun displayedBounds(mode: DisplayMode, viewport: Size, image: Size): Rect {
    if (image.isEmpty() || viewport.isEmpty()) return Rect(Offset.Zero, viewport)
    val scale = mode.contentScale.computeScaleFactor(image, viewport)
    val size = Size(image.width * scale.scaleX, image.height * scale.scaleY)
    val corner = Offset((viewport.width - size.width) / 2f, (viewport.height - size.height) / 2f)
    return Rect(corner, size)
}

/**
 * Where a double tap zooms to (spec §4.5): Fit, then Fill, then 1:1 pixels, then back to the
 * start. Zooms are absolute, 1 being one image pixel per screen pixel. Levels below [min] (the
 * display mode's own zoom) or above [max] are skipped; null means back to the start.
 */
internal fun nextDoubleTapZoom(
    current: Float,
    fit: Float,
    fill: Float,
    min: Float,
    max: Float
): Float? = listOf(fit, fill, 1f)
    .filter { it > min * NEAR && it <= max }
    .sorted()
    .firstOrNull { it > current * NEAR }

/** Double tap on a photo: [nextDoubleTapZoom] around the tapped point. */
@OptIn(ExperimentalTelephotoApi::class)
internal object CycleFitFillPixels : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        val transformation = state.contentTransformation
        val viewport = state.coordinateSystem.viewportSize
        val content = state.unscaledContentSize()
        if (content.isEmpty() || viewport.isEmpty()) return
        val fit = minOf(viewport.width / content.width, viewport.height / content.height)
        val fill = maxOf(viewport.width / content.width, viewport.height / content.height)
        val min = transformation.scaleMetadata.initialScale.let { maxOf(it.scaleX, it.scaleY) }
        val next = nextDoubleTapZoom(
            current = transformation.scale.let { maxOf(it.scaleX, it.scaleY) },
            fit = fit,
            fill = fill,
            min = min,
            max = MAX_ZOOM * maxOf(1f, min)
        )
        if (next == null) state.resetZoom() else state.zoomTo(next, centroid)
    }
}

/**
 * The photo's own size in its pixels, which every zoom here is relative to: its bounds on screen
 * before any zoom, over the zoom that fits it there. (Converting those bounds into content
 * coordinates instead would divide by the current zoom, so every double tap would step up again.)
 * Zero until the photo is laid out.
 */
@OptIn(ExperimentalTelephotoApi::class)
internal fun ZoomableState.unscaledContentSize(): Size {
    val base = contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.initialScale
    if (base == null || base.scaleX <= 0f || base.scaleY <= 0f) return Size.Zero
    val shown = with(coordinateSystem) {
        unscaledContentBounds(clipToViewport = false).rectIn(CoordinateSpace.Viewport).size
    }
    return Size(shown.width / base.scaleX, shown.height / base.scaleY)
}

/** How far a photo zooms: four times beyond one image pixel per screen pixel (spec §4.5). */
internal const val MAX_ZOOM = 4f

private const val NEAR = 1.02f
