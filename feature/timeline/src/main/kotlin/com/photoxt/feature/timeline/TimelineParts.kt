package com.photoxt.feature.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
        MediaFilter.OFFLINE -> R.string.filter_offline
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
