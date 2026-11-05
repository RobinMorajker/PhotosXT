@file:OptIn(UnstableApi::class)

package com.photoxt.feature.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberPresentationState
import com.photoxt.core.ui.ImmersiveEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The video player (spec §4.6): swipe to seek, swipe on the left for brightness and on the right
 * for volume, double-tap for ±10 s, pinch to zoom; speed, aspect modes, loop, rotation lock,
 * subtitles, audio tracks and picture-in-picture. It resumes where the video was left.
 */
@Composable
fun PlayerScreen(mediaId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<PlayerViewModel, PlayerViewModel.Factory> { it.create(mediaId) }
    val player = viewModel.player
    val inPip = rememberInPictureInPicture()
    val playing = rememberIsPlaying(player)
    val ui = remember { PlayerUi() }
    val presentation = rememberPresentationState(player)
    val video = presentation.videoSizeDp
    val enterPip = pictureInPicture(
        playing = playing,
        videoWidth = video?.width?.roundToInt() ?: 0,
        videoHeight = video?.height?.roundToInt() ?: 0,
        bounds = ui.videoBounds
    )
    MediaSessionEffect(player)
    PauseWhenStopped(player, viewModel::savePosition)
    RotationLockEffect(ui.rotationLocked)
    ImmersiveEffect(barsVisible = ui.controls && !inPip)
    BackHandler(onBack = onClose)
    LaunchedEffect(ui.controls, playing) {
        if (ui.controls && playing) {
            delay(HIDE_CONTROLS_MS)
            ui.controls = false
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VideoFrame(player, ui.aspect, ui.gestures) { ui.videoBounds = it }
        Captions(player, Modifier.align(Alignment.BottomCenter))
        if (!inPip) PlayerOverlay(viewModel, ui, onClose, enterPip)
    }
}

/** The player's on-screen state. */
@Stable
internal class PlayerUi {
    var controls by mutableStateOf(true)
    var aspect by mutableStateOf(VideoAspect.FIT)
    var rotationLocked by mutableStateOf(false)
    var videoBounds by mutableStateOf<Rect?>(null)
    val gestures = PlayerGestureState()
}

/** Gestures, their feedback, the controls and the resume note, all hidden in picture-in-picture. */
@Composable
private fun BoxScope.PlayerOverlay(
    viewModel: PlayerViewModel,
    ui: PlayerUi,
    onClose: () -> Unit,
    enterPip: () -> Unit
) {
    val player = viewModel.player
    val item by viewModel.item.collectAsStateWithLifecycle()
    val resumedFrom by viewModel.resumedFrom.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    LaunchedEffect(resumedFrom) {
        if (resumedFrom != null) {
            delay(RESUMED_NOTE_MS)
            viewModel.dismissResumed()
        }
    }
    GestureLayer(player, ui.gestures, onTap = { ui.controls = !ui.controls })
    ui.gestures.feedback?.let { FeedbackPill(it, Modifier.align(Alignment.Center)) }
    AnimatedVisibility(
        visible = ui.controls,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        PlayerControls(
            player = player,
            title = item?.name.orEmpty(),
            state = ControlsState(ui.aspect, ui.rotationLocked, subtitles.isNotEmpty()),
            actions = ControlsActions(
                onBack = onClose,
                onAspect = { ui.aspect = it },
                onRotationLock = { ui.rotationLocked = it },
                onPictureInPicture = enterPip
            )
        )
    }
    resumedFrom?.let {
        ResumedNote(it, viewModel::startOver, Modifier.align(Alignment.BottomCenter))
    }
}

/** The video at its aspect mode, enlarged and moved by a pinch. */
@Composable
private fun VideoFrame(
    player: Player,
    aspect: VideoAspect,
    gestures: PlayerGestureState,
    onBounds: (Rect) -> Unit
) {
    val forced = aspect.forcedRatio
    Box(
        Modifier
            .fillMaxSize()
            // Laid out larger rather than scaled: a SurfaceView ignores Compose's layer scaling.
            .layout { measurable, constraints ->
                val width = (constraints.maxWidth * gestures.zoom).roundToInt()
                val height = (constraints.maxHeight * gestures.zoom).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (constraints.maxWidth - width) / 2 + gestures.pan.x.roundToInt(),
                        (constraints.maxHeight - height) / 2 + gestures.pan.y.roundToInt()
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        ContentFrame(
            player = player,
            contentScale = aspect.contentScale,
            modifier = (
                if (forced !=
                    null
                ) {
                    Modifier.wrapContentSize().aspectRatio(forced)
                } else {
                    Modifier.fillMaxSize()
                }
                )
                .onGloballyPositioned { onBounds(it.boundsInWindow()) }
        )
    }
}

/** Where taps, double-taps and drags on the video land. */
@Composable
private fun GestureLayer(player: Player, gestures: PlayerGestureState, onTap: () -> Unit) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
    val targets = remember(player, activity, audio) {
        GestureTargets(
            position = { player.currentPosition },
            duration = { player.duration.coerceAtLeast(0L) },
            seekTo = { position, exact -> player.seekToPosition(position, exact) },
            brightness = {
                activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                    ?: HALF
            },
            setBrightness = { level ->
                activity?.window?.let {
                    it.attributes =
                        it.attributes.apply { screenBrightness = level }
                }
            },
            volume = { audio.levelOf(AudioManager.STREAM_MUSIC) },
            setVolume = { audio.setLevel(AudioManager.STREAM_MUSIC, it) }
        )
    }
    LaunchedEffect(gestures.feedback) {
        if (gestures.feedback is GestureFeedback.Skip) {
            delay(SKIP_NOTE_MS)
            gestures.feedback = null
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .playerDrags(gestures, targets)
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        val forward = at.x > size.width / 2f
                        if (forward) player.seekForward() else player.seekBack()
                        gestures.feedback = GestureFeedback.Skip(forward)
                    }
                )
            }
    )
}

/** Media buttons, the lock screen and the picture-in-picture window control playback. */
@Composable
private fun MediaSessionEffect(player: Player) {
    val context = LocalContext.current
    DisposableEffect(player) {
        val session = MediaSession.Builder(context, player).build()
        onDispose { session.release() }
    }
}

/** Leaving the app (unless into picture-in-picture) pauses the video and saves its position. */
@Composable
private fun PauseWhenStopped(player: Player, save: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                save()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            save()
        }
    }
}

@Composable
private fun RotationLockEffect(locked: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, locked) {
        activity.requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

private fun AudioManager.levelOf(stream: Int): Float {
    val max = getStreamMaxVolume(stream).coerceAtLeast(1)
    return getStreamVolume(stream).toFloat() / max
}

private fun AudioManager.setLevel(stream: Int, level: Float) {
    val max = getStreamMaxVolume(stream)
    setStreamVolume(stream, (level * max).roundToInt(), 0)
}

private fun Player.seekToPosition(position: Long, exact: Boolean) {
    if (this is androidx.media3.exoplayer.ExoPlayer) {
        setSeekParameters(
            if (exact) {
                androidx.media3.exoplayer.SeekParameters.EXACT
            } else {
                androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
            }
        )
    }
    seekTo(position)
}

private const val HIDE_CONTROLS_MS = 3_000L
private const val RESUMED_NOTE_MS = 5_000L
private const val SKIP_NOTE_MS = 600L
private const val HALF = 0.5f

/** Fit, Fill and Stretch fill the screen differently; 16:9 and 4:3 force that shape. */
internal enum class VideoAspect(val contentScale: ContentScale, val forcedRatio: Float? = null) {
    FIT(ContentScale.Fit),
    FILL(ContentScale.Crop),
    STRETCH(ContentScale.FillBounds),
    WIDE(ContentScale.FillBounds, 16f / 9f),
    STANDARD(ContentScale.FillBounds, 4f / 3f)
}
