package com.photoxt.feature.timeline

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaFilter

// The pieces a timeline screen is built from.

@Composable
internal fun FilterChips(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    filters: List<MediaFilter> = MediaFilter.entries
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.label)) }
            )
        }
    }
}

private val MediaFilter.label: Int
    get() = when (this) {
        MediaFilter.ALL -> R.string.filter_all
        MediaFilter.PHOTOS -> R.string.filter_photos
        MediaFilter.VIDEOS -> R.string.filter_videos
        MediaFilter.FAVORITES -> R.string.filter_favorites
        MediaFilter.DEVICE -> R.string.filter_device
        MediaFilter.DRIVE -> R.string.filter_drive
    }

@Composable
internal fun FilteredEmpty() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.filter_empty_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.filter_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun CancelSelectionButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_close),
            contentDescription = stringResource(R.string.action_cancel_selection)
        )
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back)
        )
    }
}

/**
 * The selection's action bar, in place of the navigation bar (spec §4.1). [onCompare] is set when
 * exactly two items are selected. Cut, Copy, Add to album, Favorite and Delete join it with the
 * phases that build them (5 and 6).
 */
@Composable
internal fun SelectionActions(onShare: () -> Unit, onCompare: (() -> Unit)? = null) {
    Surface(tonalElevation = 3.dp) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp)
        ) {
            ActionButton(DesignR.drawable.ic_share, stringResource(R.string.action_share), onShare)
            if (onCompare != null) {
                ActionButton(
                    DesignR.drawable.ic_compare,
                    stringResource(R.string.action_compare),
                    onCompare
                )
            }
        }
    }
}

@Composable
private fun ActionButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
