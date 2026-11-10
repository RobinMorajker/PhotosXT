package com.photoxt.feature.viewer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.DisplayMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Back, the item's date, display mode, zoom lock and the overflow menu, over a scrim. */
@Composable
internal fun ViewerTopBar(
    item: ViewerItem?,
    mode: DisplayMode,
    zoomLock: Boolean,
    actions: ChromeActions,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            ChromeIcon(DesignR.drawable.ic_arrow_back, R.string.action_back, actions.onBack)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (item != null) {
                    val taken = Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault())
                    Text(
                        text = DateFormat.format(taken),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = TimeFormat.format(taken),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            DisplayModeMenu(mode, actions.onDisplayMode)
            IconToggleButton(checked = zoomLock, onCheckedChange = actions.onZoomLock) {
                Icon(
                    painter = painterResource(
                        if (zoomLock) DesignR.drawable.ic_lock else DesignR.drawable.ic_lock_open
                    ),
                    contentDescription = stringResource(R.string.action_zoom_lock)
                )
            }
            OverflowMenu(actions)
        }
    }
}

/** Share, delete and details, plus Play, Motion or 360° when the item has them. */
@Composable
internal fun ViewerBottomBar(actions: ChromeActions, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                .navigationBarsPadding()
                .padding(vertical = 8.dp)
        ) {
            actions.onPlay?.let { BarAction(DesignR.drawable.ic_play, R.string.action_play, it) }
            actions.onMotion?.let {
                BarAction(DesignR.drawable.ic_motion_photo, R.string.action_motion, it)
            }
            actions.onPanorama?.let {
                BarAction(DesignR.drawable.ic_360, R.string.action_panorama, it)
            }
            BarAction(DesignR.drawable.ic_share, R.string.action_share, actions.onShare)
            BarAction(DesignR.drawable.ic_delete, R.string.action_delete, actions.onDelete)
            BarAction(DesignR.drawable.ic_info, R.string.action_details, actions.onDetails)
        }
    }
}

@Composable
private fun DisplayModeMenu(mode: DisplayMode, onSelect: (DisplayMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChromeIcon(DesignR.drawable.ic_fit_screen, R.string.action_display_mode) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DisplayMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = { RadioButton(selected = option == mode, onClick = null) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = option == mode
                    }
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(actions: ChromeActions) {
    var open by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    Box {
        ChromeIcon(DesignR.drawable.ic_more_vert, R.string.action_more) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuEntry(R.string.action_slideshow, pick(actions.onSlideshow))
            actions.onCompare?.let { MenuEntry(R.string.action_compare, pick(it)) }
            MenuEntry(R.string.action_open_with, pick(actions.onOpenWith))
            MenuEntry(R.string.action_set_as, pick(actions.onSetAs))
            MenuEntry(R.string.action_copy, pick(actions.onCopy))
            actions.offline?.let { pinned ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_keep_offline)) },
                    trailingIcon = { Checkbox(checked = pinned, onCheckedChange = null) },
                    onClick = pick { actions.onOffline(!pinned) }
                )
            }
        }
    }
}

@Composable
private fun MenuEntry(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun ChromeIcon(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun BarAction(@DrawableRes icon: Int, label: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

private val Scrim = Color(0x99000000)
private val DateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
