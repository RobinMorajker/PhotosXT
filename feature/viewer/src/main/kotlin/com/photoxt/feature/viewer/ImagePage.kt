package com.photoxt.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.net.toUri
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.datastore.MobileOriginals
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.media.Original
import com.photoxt.core.model.DisplayMode
import java.io.File
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
    val onPlay: () -> Unit,
    /** Data saver: load this Drive photo's original over mobile data. */
    val onLoadOriginal: () -> Unit = {},
    /** Data saver's question answered "Always". */
    val onAlwaysOriginals: () -> Unit = {}
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
    modifier: Modifier = Modifier,
    drive: DrivePage? = null
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
        val file = (drive?.original as? Original.Ready)?.file
        if (drive != null &&
            file == null
        ) {
            DriveLoading(drive, mode, actions, Modifier.fillMaxSize())
        }
        ZoomableAsyncImage(
            // ponytail: software tiles; on a 200 MP photo each 2040 × 1530 tile uploads for
            // 10–18 ms on the render thread (ZoomBenchmark), but hardware tiles measured no better
            // on the A15. Smaller tiles or upload pacing in the Phase 15 performance pass.
            model = remember(item.id, item.modifiedAt, item.remoteVersion, file, drive?.preview) {
                pageModel(context, item, drive, file)
            },
            contentDescription = null,
            state = imageState,
            contentScale = mode.contentScale,
            gestures = gesturesFor(rotation.active, lock != null),
            onClick = { actions.onTap() },
            onLongClick = { actions.onLongPress() },
            onDoubleClick = CycleFitFillPixels,
            modifier = Modifier
                .fillMaxSize()
                .rotatable(rotation, displayedBounds(mode, pageSize, image).size)
        )
        val video = traits.motionVideo
        val uri = item.contentUri ?: file?.toUri()?.toString()
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

/** The full photo; a Drive photo's preview or grid thumbnail until its [file] is here. */
private fun pageModel(context: Context, item: ViewerItem, drive: DrivePage?, file: File?): Any? =
    if (drive == null || file != null) {
        fullImageRequest(context, item, file = file)
    } else {
        drive.preview ?: item.poster()
    }

/** No gestures while turning; with zoom lock, one finger pages and two fingers zoom and pan. */
private fun gesturesFor(rotating: Boolean, locked: Boolean): EnabledZoomGestures = when {
    rotating -> EnabledZoomGestures.None
    locked -> EnabledZoomGestures.ZoomOnly
    else -> EnabledZoomGestures.ZoomAndPan
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

/**
 * What a Drive photo's page needs while its full file comes (spec §4.4). With [saver] set, data
 * saver holds the original back: the preview shows with *Load original*.
 */
internal class DrivePage(
    val original: Original,
    val preview: File?,
    val blur: Blur?,
    val saver: MobileOriginals? = null
)

/**
 * BlurHash under the thumbnail, and download progress, until the Drive file is here; or data
 * saver's *Load original* (spec §4.4).
 */
@Composable
private fun DriveLoading(
    page: DrivePage,
    mode: DisplayMode,
    actions: PageActions,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        val blur = page.blur
        if (blur != null) {
            val bitmap = remember(blur) {
                Bitmap.createBitmap(
                    blur.pixels,
                    blur.width,
                    blur.height,
                    Bitmap.Config.ARGB_8888
                ).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = mode.contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
        val saver = page.saver
        if (saver != null) {
            SaverPrompt(
                ask = saver == MobileOriginals.ASK,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = PromptAboveBar)
            )
            return@Box
        }
        when (val original = page.original) {
            is Original.Loading -> LinearProgressIndicator(
                progress = { original.fraction },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )

            is Original.Failed -> Text(
                text = stringResource(R.string.drive_open_failed),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
            )

            is Original.Ready -> Unit
        }
    }
}

/** On mobile data: the original loads on request; with [ask], "Always" stops asking. */
@Composable
private fun SaverPrompt(ask: Boolean, actions: PageActions, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = PROMPT_ALPHA),
        contentColor = Color.White,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            if (ask) {
                Text(
                    text = stringResource(R.string.saver_question),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            TextButton(onClick = actions.onLoadOriginal) {
                Text(stringResource(R.string.action_load_original), color = Color.White)
            }
            if (ask) {
                TextButton(onClick = actions.onAlwaysOriginals) {
                    Text(stringResource(R.string.action_always), color = Color.White)
                }
            }
        }
    }
}

private val PromptAboveBar = 88.dp
private const val PROMPT_ALPHA = 0.6f
