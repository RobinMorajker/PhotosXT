@file:OptIn(UnstableApi::class)

package com.photoxt.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.photoxt.core.designsystem.R as DesignR
import kotlin.math.roundToInt

/** Whether the player is playing right now. */
@Composable
internal fun rememberIsPlaying(player: Player): Boolean {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return playing
}

/**
 * Subtitle text (spec §4.6), drawn by Compose from the player's cues: white with a soft shadow on
 * a dark band, so it reads on any picture.
 */
@Composable
internal fun Captions(player: Player, modifier: Modifier = Modifier) {
    var lines by remember(player) { mutableStateOf(emptyList<String>()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                lines = cueGroup.cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    if (lines.isEmpty()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 96.dp)
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(shadow = CaptionShadow),
                modifier = Modifier
                    .background(Color(CAPTION_BAND), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** Turns the side-loaded subtitles on and off. */
@Composable
internal fun SubtitlesToggle(player: Player) {
    var on by remember(player) {
        mutableStateOf(C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes)
    }
    IconToggleButton(checked = on, onCheckedChange = { enabled ->
        on = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_subtitles),
            contentDescription = stringResource(R.string.player_subtitles)
        )
    }
}

/** Picks one of several audio tracks; shown only when a video has more than one. */
@Composable
internal fun AudioTrackMenu(player: Player) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(changed: Tracks) {
                tracks = changed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(DesignR.drawable.ic_music, R.string.player_audio_track) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            audio.forEachIndexed { index, group ->
                val format = group.getTrackFormat(0)
                val name = format.label
                    ?: format.language
                    ?: stringResource(R.string.player_track, index + 1)
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, 0)
                                )
                                .build()
                    }
                )
            }
        }
    }
}

/** What a drag or double tap is doing, in the middle of the video. */
@Composable
internal fun FeedbackPill(feedback: GestureFeedback, modifier: Modifier = Modifier) {
    val (icon, text) = when (feedback) {
        is GestureFeedback.Seek ->
            DesignR.drawable.ic_speed to
                "${formatTime(feedback.positionMs)} / ${formatTime(feedback.durationMs)}"

        is GestureFeedback.Brightness -> DesignR.drawable.ic_brightness to percent(feedback.level)

        is GestureFeedback.Volume -> DesignR.drawable.ic_volume to percent(feedback.level)

        is GestureFeedback.Skip -> if (feedback.forward) {
            DesignR.drawable.ic_forward_10 to stringResource(R.string.player_skipped_forward)
        } else {
            DesignR.drawable.ic_replay_10 to stringResource(R.string.player_skipped_back)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Scrim, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** "Resumed at 1:23", with a way to start over (spec §4.6). */
@Composable
internal fun ResumedNote(positionMs: Long, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 112.dp)
            .background(Scrim, RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 4.dp)
    ) {
        Text(
            stringResource(R.string.player_resumed, formatTime(positionMs)),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onStartOver) { Text(stringResource(R.string.player_start_over)) }
    }
}

@Composable
private fun percent(level: Float): String =
    stringResource(R.string.player_percent, (level * PERCENT).roundToInt())

private val CaptionShadow = Shadow(Color.Black, blurRadius = 4f)
private const val CAPTION_BAND = 0x80000000
private const val PERCENT = 100
