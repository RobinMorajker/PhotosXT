package com.photoxt.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.R as DesignR

/** A date header; while selecting, the whole row is the group's select-all checkbox. */
@Composable
internal fun GroupHeader(
    label: String,
    selecting: Boolean,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit
) {
    val toggle = if (selecting) {
        Modifier.toggleable(value = allSelected, role = Role.Checkbox, onValueChange = onSelectAll)
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = toggle
            .fillMaxWidth()
            .heightIn(min = HeaderHeight)
            .padding(horizontal = 12.dp)
    ) {
        if (selecting) {
            SelectionMark(selected = allSelected, size = 24.dp, overImage = false)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
    }
}

/**
 * A photo or video in the grid. The grid handles touches; this only declares what TalkBack can
 * do with the tile: open it, select it, or toggle it while selecting.
 */
@Composable
internal fun MediaTile(
    item: GridCell.Item,
    compact: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onSelect: () -> Unit,
    open: (() -> Unit)?
) {
    val context = LocalContext.current
    val toggleLabel = stringResource(R.string.media_toggle_selection)
    val selectLabel = stringResource(R.string.media_select)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics(mergeDescendants = true) {
                contentDescription = describeMedia(context, item)
                if (selecting) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(selected)
                    onClick(toggleLabel) {
                        onTap()
                        true
                    }
                } else {
                    if (open != null) {
                        onClick {
                            open()
                            true
                        }
                    }
                    onLongClick(selectLabel) {
                        onSelect()
                        true
                    }
                }
            }
    ) {
        // A selected tile shrinks inside its cell, as in Samsung Gallery and Google Photos.
        val inset = if (selected && !compact) {
            Modifier.padding(SelectedInset).clip(TileShape)
        } else {
            Modifier
        }
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(inset)
        )
        TileBadges(item, compact, selecting, selected)
    }
}

/** Video length and favorite heart (not on year and month tiles), and the selection mark. */
@Composable
private fun BoxScope.TileBadges(
    item: GridCell.Item,
    compact: Boolean,
    selecting: Boolean,
    selected: Boolean
) {
    if (!compact) {
        if (item.isVideo) VideoBadge(item.durationMs, Modifier.align(Alignment.BottomEnd))
        if (item.favorite) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_favorite_filled),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }
    }
    if (selecting) {
        SelectionMark(
            selected = selected,
            size = if (compact) 14.dp else 22.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 2.dp else 6.dp)
        )
    }
}

@Composable
private fun VideoBadge(durationMs: Long?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(4.dp)
            .background(BadgeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text = formatDuration(durationMs), color = Color.White, fontSize = 11.sp)
        Icon(
            painter = painterResource(DesignR.drawable.ic_play),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** A filled check when [selected], else an empty ring; over a photo the ring gets a scrim. */
@Composable
private fun SelectionMark(
    selected: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    overImage: Boolean = true
) {
    if (selected) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_check_circle_filled),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .size(size)
                .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
        )
    } else {
        Icon(
            painter = painterResource(DesignR.drawable.ic_circle),
            contentDescription = null,
            tint = if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .size(size)
                .then(if (overImage) Modifier.background(BadgeScrim, CircleShape) else Modifier)
        )
    }
}

private val TileShape = RoundedCornerShape(8.dp)
private val SelectedInset = 8.dp
private val BadgeScrim = Color(0x66000000)
