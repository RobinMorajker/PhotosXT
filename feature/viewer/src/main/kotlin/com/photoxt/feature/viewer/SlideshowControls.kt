package com.photoxt.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR

/** Close, play and pause, skipping, and the options: interval, transition, shuffle, loop, music. */
@Composable
internal fun SlideshowControls(
    settings: SlideshowSettings,
    playing: Boolean,
    hasMusic: Boolean,
    actions: SlideshowControlActions
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize()) {
            IconButton(
                onClick = actions.onClose,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(4.dp)
                    .background(Color(SCRIM), CircleShape)
            ) {
                Icon(
                    painterResource(DesignR.drawable.ic_close),
                    contentDescription = stringResource(R.string.slideshow_close)
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(SCRIM))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp)
            ) {
                PlaybackRow(playing, actions)
                TimingRows(settings, actions)
                SwitchRow(settings, hasMusic, actions)
            }
        }
    }
}

/** How long each slide shows, and how the next one comes in. */
@Composable
private fun TimingRows(settings: SlideshowSettings, actions: SlideshowControlActions) {
    OptionRow {
        INTERVALS.forEach { seconds ->
            FilterChip(
                selected = settings.intervalSeconds == seconds,
                onClick = { actions.onChange { it.copy(intervalSeconds = seconds) } },
                label = {
                    Text(pluralStringResource(R.plurals.slideshow_seconds, seconds, seconds))
                }
            )
        }
    }
    OptionRow {
        SlideshowTransition.entries.forEach { transition ->
            FilterChip(
                selected = settings.transition == transition,
                onClick = { actions.onChange { it.copy(transition = transition) } },
                label = { Text(stringResource(transition.label)) }
            )
        }
    }
}

@Composable
private fun SwitchRow(
    settings: SlideshowSettings,
    hasMusic: Boolean,
    actions: SlideshowControlActions
) {
    OptionRow {
        FilterChip(
            selected = settings.shuffle,
            onClick = { actions.onChange { it.copy(shuffle = !it.shuffle) } },
            label = { Text(stringResource(R.string.slideshow_shuffle)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_shuffle), null) }
        )
        FilterChip(
            selected = settings.loop,
            onClick = { actions.onChange { it.copy(loop = !it.loop) } },
            label = { Text(stringResource(R.string.slideshow_loop)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_repeat), null) }
        )
        FilterChip(
            selected = hasMusic,
            onClick = { actions.onMusic(!hasMusic) },
            label = { Text(stringResource(R.string.slideshow_music)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_music), null) }
        )
    }
}

@Composable
private fun PlaybackRow(playing: Boolean, actions: SlideshowControlActions) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = actions.onPrevious) {
            Icon(
                painterResource(DesignR.drawable.ic_replay_10),
                contentDescription = stringResource(R.string.slideshow_previous)
            )
        }
        IconButton(onClick = { actions.onPlaying(!playing) }) {
            Icon(
                painterResource(
                    if (playing) DesignR.drawable.ic_pause else DesignR.drawable.ic_play
                ),
                contentDescription = stringResource(
                    if (playing) R.string.slideshow_pause else R.string.slideshow_play
                )
            )
        }
        IconButton(onClick = actions.onNext) {
            Icon(
                painterResource(DesignR.drawable.ic_forward_10),
                contentDescription = stringResource(R.string.slideshow_next)
            )
        }
    }
}

@Composable
private fun OptionRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) { content() }
}

private val SlideshowTransition.label: Int
    get() = when (this) {
        SlideshowTransition.FADE -> R.string.slideshow_fade
        SlideshowTransition.SLIDE -> R.string.slideshow_slide
        SlideshowTransition.KEN_BURNS -> R.string.slideshow_ken_burns
    }

private val INTERVALS = listOf(2, 3, 5, 10)
private const val SCRIM = 0x99000000
