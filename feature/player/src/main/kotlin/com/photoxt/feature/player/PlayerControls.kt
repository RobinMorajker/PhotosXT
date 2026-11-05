@file:OptIn(UnstableApi::class)

package com.photoxt.feature.player

import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.photoxt.core.designsystem.R as DesignR

/** The player's own settings shown in its bars. */
internal data class ControlsState(
    val aspect: VideoAspect,
    val rotationLocked: Boolean,
    val hasSubtitles: Boolean
)

internal class ControlsActions(
    val onBack: () -> Unit,
    val onAspect: (VideoAspect) -> Unit,
    val onRotationLock: (Boolean) -> Unit,
    val onPictureInPicture: () -> Unit
)

/** Top bar with the options, centre play controls, and the seek bar. */
@Composable
internal fun PlayerControls(
    player: Player,
    title: String,
    state: ControlsState,
    actions: ControlsActions
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize()) {
            TopControls(player, title, state, actions, Modifier.align(Alignment.TopCenter))
            CentreControls(player, Modifier.align(Alignment.Center))
            SeekBar(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TopControls(
    player: Player,
    title: String,
    state: ControlsState,
    actions: ControlsActions,
    modifier: Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
            .statusBarsPadding()
            .padding(4.dp)
    ) {
        BarIcon(DesignR.drawable.ic_arrow_back, R.string.player_back, actions.onBack)
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (state.hasSubtitles) SubtitlesToggle(player)
        AudioTrackMenu(player)
        SpeedMenu(player)
        ChoiceMenu(
            icon = DesignR.drawable.ic_aspect_ratio,
            label = R.string.player_aspect,
            options = VideoAspect.entries,
            selected = state.aspect,
            text = { stringResource(it.label) },
            onSelect = actions.onAspect
        )
        Toggle(
            player.repeatMode == Player.REPEAT_MODE_ONE,
            DesignR.drawable.ic_repeat,
            R.string.player_loop
        ) {
            player.repeatMode = if (it) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
        Toggle(
            state.rotationLocked,
            if (state.rotationLocked) {
                DesignR.drawable.ic_screen_lock_rotation
            } else {
                DesignR.drawable.ic_screen_rotation
            },
            R.string.player_rotation_lock,
            actions.onRotationLock
        )
        BarIcon(DesignR.drawable.ic_pip, R.string.player_pip, actions.onPictureInPicture)
    }
}

@Composable
private fun CentreControls(player: Player, modifier: Modifier) {
    val playPause = rememberPlayPauseButtonState(player)
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        BarIcon(DesignR.drawable.ic_replay_10, R.string.player_back_10) { player.seekBack() }
        IconButton(
            onClick = playPause::onClick,
            enabled = playPause.isEnabled,
            modifier = Modifier
                .size(72.dp)
                .background(Scrim, androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(
                painter = painterResource(
                    if (playPause.showPlay) DesignR.drawable.ic_play else DesignR.drawable.ic_pause
                ),
                contentDescription = stringResource(
                    if (playPause.showPlay) R.string.player_play else R.string.player_pause
                ),
                modifier = Modifier.size(40.dp)
            )
        }
        BarIcon(DesignR.drawable.ic_forward_10, R.string.player_forward_10) { player.seekForward() }
    }
}

@Composable
private fun SeekBar(player: Player, modifier: Modifier) {
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = TICK_MS)
    val duration = progress.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) {
        dragged
    } else if (duration >
        0
    ) {
        progress.currentPositionMs.toFloat() / duration
    } else {
        0f
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime((fraction * duration).toLong()),
                style = MaterialTheme.typography.labelMedium
            )
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragged = it
            },
            onValueChangeFinished = {
                player.seekTo((dragged * duration).toLong())
                dragging = false
            },
            enabled = duration > 0
        )
    }
}

@Composable
private fun SpeedMenu(player: Player) {
    val speed = rememberPlaybackSpeedState(player)
    ChoiceMenu(
        icon = DesignR.drawable.ic_speed,
        label = R.string.player_speed,
        options = SPEEDS,
        selected = SPEEDS.minByOrNull { kotlin.math.abs(it - speed.playbackSpeed) } ?: 1f,
        text = { stringResource(R.string.player_speed_value, it) },
        onSelect = speed::updatePlaybackSpeed
    )
}

/** A bar button that opens a single-choice menu. */
@Composable
private fun <T> ChoiceMenu(
    @DrawableRes icon: Int,
    label: Int,
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(icon, label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    leadingIcon = { RadioButton(selected = option == selected, onClick = null) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        this.selected = option == selected
                    }
                )
            }
        }
    }
}

@Composable
internal fun BarIcon(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun Toggle(
    checked: Boolean,
    @DrawableRes icon: Int,
    label: Int,
    onChange: (Boolean) -> Unit
) {
    IconToggleButton(checked = checked, onCheckedChange = onChange) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

private val VideoAspect.label: Int
    get() = when (this) {
        VideoAspect.FIT -> R.string.aspect_fit
        VideoAspect.FILL -> R.string.aspect_fill
        VideoAspect.STRETCH -> R.string.aspect_stretch
        VideoAspect.WIDE -> R.string.aspect_16_9
        VideoAspect.STANDARD -> R.string.aspect_4_3
    }

/** 1:05 or 1:02:05. */
internal fun formatTime(ms: Long): String {
    val seconds = (ms / MILLIS).coerceAtLeast(0)
    val hours = seconds / HOUR
    val minutes = seconds % HOUR / MINUTE
    val rest = seconds % MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

internal val Scrim = Color(0x99000000)
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
private const val TICK_MS = 250L
private const val MILLIS = 1_000L
private const val MINUTE = 60L
private const val HOUR = 3_600L
