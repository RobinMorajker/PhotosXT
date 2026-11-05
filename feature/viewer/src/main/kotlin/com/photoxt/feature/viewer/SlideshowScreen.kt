@file:OptIn(UnstableApi::class)

package com.photoxt.feature.viewer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.ui.ImmersiveEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The slideshow (spec §4.5): photos in the grid's order or shuffled, every few seconds, with a
 * fade, a slide or a slow Ken Burns pan, optionally to music from an audio file. Tap for controls.
 */
@Composable
fun SlideshowScreen(start: ViewerStart, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<SlideshowViewModel, SlideshowViewModel.Factory> {
        it.create(start)
    }
    val item by viewModel.current.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mode by viewModel.displayMode.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val music by viewModel.music.collectAsStateWithLifecycle()
    var controls by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    ImmersiveEffect(barsVisible = controls)
    KeepScreenOn()
    MusicPlayer(music, playing)
    LaunchedEffect(item?.id, playing, settings.intervalSeconds) {
        if (!playing || item == null) return@LaunchedEffect
        delay(settings.intervalSeconds * MILLIS)
        if (!viewModel.next()) controls = true
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.setMusic(it.toString())
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) }
    ) {
        val size = with(LocalDensity.current) {
            IntSize(maxWidth.toPx().roundToInt(), maxHeight.toPx().roundToInt())
        }
        LaunchedEffect(upcoming?.id, size) {
            upcoming?.let {
                SingletonImageLoader.get(context).enqueue(slideRequest(context, it, size))
            }
        }
        Slide(item, settings, mode, size)
        if (controls) {
            SlideshowControls(
                settings = settings,
                playing = playing,
                hasMusic = music != null,
                actions = SlideshowControlActions(
                    onClose = onClose,
                    onPlaying = viewModel::setPlaying,
                    onNext = { viewModel.next() },
                    onPrevious = viewModel::previous,
                    onChange = viewModel::update,
                    onMusic = { pick ->
                        if (pick) pickMusic.launch(arrayOf("audio/*")) else viewModel.setMusic(null)
                    }
                )
            )
        }
    }
}

/** The current slide, brought in with the chosen transition. */
@Composable
private fun Slide(
    item: ViewerItem?,
    settings: SlideshowSettings,
    mode: DisplayMode,
    size: IntSize
) {
    val context = LocalContext.current
    val image: @Composable (ViewerItem?) -> Unit = { shown ->
        if (shown != null) {
            AsyncImage(
                model = remember(shown.id, size) { slideRequest(context, shown, size) },
                contentDescription = describeItem(shown),
                contentScale = mode.contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (settings.transition == SlideshowTransition.KEN_BURNS) {
                            Modifier.kenBurns(shown.id, settings.intervalSeconds)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
    when (settings.transition) {
        SlideshowTransition.SLIDE -> AnimatedContent(
            targetState = item,
            transitionSpec = {
                slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith
                    slideOutHorizontally(tween(SLIDE_MS)) { -it }
            },
            label = "slide"
        ) { image(it) }

        SlideshowTransition.FADE, SlideshowTransition.KEN_BURNS ->
            Crossfade(targetState = item, animationSpec = tween(FADE_MS), label = "fade") {
                image(it)
            }
    }
}

/** A slow zoom and drift across the slide's time on screen, alternating direction. */
@Composable
private fun Modifier.kenBurns(id: String, seconds: Int): Modifier {
    val progress = remember(id) { Animatable(0f) }
    LaunchedEffect(id) {
        progress.animateTo(1f, tween((seconds * MILLIS + FADE_MS).toInt(), easing = LinearEasing))
    }
    val direction = if (id.hashCode() % 2 == 0) 1f else -1f
    return graphicsLayer {
        val zoom = 1f + KEN_BURNS_ZOOM * progress.value
        scaleX = zoom
        scaleY = zoom
        translationX = direction * (progress.value - HALF) * KEN_BURNS_DRIFT * size.width
    }
}

/** Plays the picked audio file on a loop while the slideshow plays. */
@Composable
private fun MusicPlayer(uri: String?, playing: Boolean) {
    val context = LocalContext.current
    val player = remember(uri) { uri?.let { musicPlayer(context, it) } }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player, playing) { player?.playWhenReady = playing }
}

private fun musicPlayer(context: Context, uri: String): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(uri.toUri()))
        repeatMode = Player.REPEAT_MODE_ALL
        prepare()
    }

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A slide decoded at the screen's size; videos show their poster frame. */
// ponytail: videos show their poster for one interval; play them through when wanted.
private fun slideRequest(context: Context, item: ViewerItem, size: IntSize): ImageRequest =
    if (item.kind == MediaKind.VIDEO) {
        ImageRequest.Builder(context).data(item.poster()).size(size.width, size.height).build()
    } else {
        fullImageRequest(context, item, size)
    }

private const val MILLIS = 1_000L
private const val FADE_MS = 700
private const val SLIDE_MS = 500
private const val KEN_BURNS_ZOOM = 0.12f
private const val KEN_BURNS_DRIFT = 0.06f
private const val HALF = 0.5f
