package com.photoxt.feature.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.DisplayMode

/**
 * A video in the pager: its poster frame and a play button. Playing opens the player (spec §4.6),
 * where horizontal swipes seek instead of turning pages.
 */
@Composable
internal fun VideoPage(
    item: ViewerItem,
    mode: DisplayMode,
    handle: PageHandle,
    actions: PageActions,
    modifier: Modifier = Modifier
) {
    val place = remember { PagePlace() }
    DisposableEffect(handle, item.id, mode) {
        handle.bounds = {
            displayedBounds(mode, place.size, item.displaySize()).translate(place.origin)
        }
        handle.zoomed = { false }
        onDispose { handle.bounds = { null } }
    }
    Box(
        modifier
            .fillMaxSize()
            .onPlaced {
                place.origin = it.positionInRoot()
                place.size = it.size.toSize()
            }
            .pointerInput(actions) { detectTapGestures(onTap = { actions.onTap() }) }
    ) {
        AsyncImage(
            model = item.poster(),
            contentDescription = null,
            contentScale = mode.contentScale,
            modifier = Modifier.fillMaxSize()
        )
        FilledIconButton(
            onClick = actions.onPlay,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(SCRIM),
                contentColor = Color.White
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .size(PlaySize)
        ) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_play),
                contentDescription = stringResource(R.string.action_play),
                modifier = Modifier.size(PlayIconSize)
            )
        }
    }
}

private const val SCRIM = 0x99000000
private val PlaySize = 72.dp
private val PlayIconSize = 40.dp
