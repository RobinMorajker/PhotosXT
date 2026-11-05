package com.photoxt.feature.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.photoxt.core.common.PxtLog
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.Panorama
import com.photoxt.core.ui.ImmersiveEffect
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 360° view of a photo sphere (spec §4.5): drag to look around, pinch to zoom, double-tap to
 * face forward again.
 */
@Composable
fun PanoramaScreen(
    contentUri: String,
    panorama: Panorama,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(contentUri) { PanoramaRenderer(panorama) }
    val view = remember(contentUri) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    LaunchedEffect(contentUri) {
        decodePanorama(context, contentUri)?.let { bitmap ->
            renderer.setImage(bitmap)
            view.requestRender()
        }
    }
    DisposableEffect(view) {
        view.onResume()
        onDispose { view.onPause() }
    }
    BackHandler(onBack = onClose)
    ImmersiveEffect(barsVisible = false)
    val description = stringResource(R.string.panorama_description)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = description }
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier
                .fillMaxSize()
                .lookAround(renderer, view)
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp)
                .background(Color(SCRIM), CircleShape)
        ) {
            Icon(
                painterResource(DesignR.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White
            )
        }
    }
}

/** Drag to turn, pinch to zoom, double-tap to face forward again. */
private fun Modifier.lookAround(renderer: PanoramaRenderer, view: GLSurfaceView) = this
    .pointerInput(renderer) {
        detectTransformGestures { _, pan, zoom, _ ->
            val degreesPerPixel = renderer.fieldOfView / size.height
            renderer.yaw -= pan.x * degreesPerPixel
            renderer.pitch = (renderer.pitch + pan.y * degreesPerPixel)
                .coerceIn(-PanoramaRenderer.MAX_PITCH, PanoramaRenderer.MAX_PITCH)
            renderer.fieldOfView = (renderer.fieldOfView / zoom)
                .coerceIn(PanoramaRenderer.MIN_FOV, PanoramaRenderer.MAX_FOV)
            view.requestRender()
        }
    }
    .pointerInput(renderer) {
        detectTapGestures(onDoubleTap = {
            renderer.yaw = 0f
            renderer.pitch = 0f
            renderer.fieldOfView = PanoramaRenderer.DEFAULT_FOV
            view.requestRender()
        })
    }

/** The panorama, sampled down to fit one GPU texture. */
private suspend fun decodePanorama(context: android.content.Context, contentUri: String): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(contentUri.toUri())?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > PanoramaRenderer.MAX_TEXTURE ||
                bounds.outHeight / sample > PanoramaRenderer.MAX_TEXTURE
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(contentUri.toUri())?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: IOException) {
            PxtLog.w(TAG, "Could not open the panorama", e)
            null
        }
    }

private const val TAG = "Panorama"
private const val SCRIM = 0x99000000
