package com.photoxt.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.R as DesignR

/**
 * A tracked Drive folder's menu: merge its subfolders, keep it available offline (spec §4.4), or
 * remove it from PhotoXT.
 */
@Composable
internal fun DriveAlbumMenu(
    info: DriveAlbumInfo,
    onMerge: (Boolean) -> Unit,
    onOffline: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painterResource(DesignR.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.action_more)
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_merge_subfolders)) },
            trailingIcon = { Checkbox(checked = info.merge, onCheckedChange = null) },
            onClick = {
                open = false
                onMerge(!info.merge)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_keep_album_offline)) },
            trailingIcon = { Checkbox(checked = info.offline, onCheckedChange = null) },
            onClick = {
                open = false
                onOffline(!info.offline)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_remove_drive_folder)) },
            onClick = {
                open = false
                confirm = true
            }
        )
    }
    if (confirm) {
        RemoveDialog(
            onConfirm = {
                confirm = false
                onRemove()
            },
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun RemoveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_drive_folder_title)) },
        text = { Text(stringResource(R.string.remove_drive_folder_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Above a Drive album's items: the Unavailable banner with Relink and Remove (spec §4.3, lost
 * access), or its sync state, then its subfolders as nested albums.
 */
@Composable
internal fun DriveAlbumHeader(
    info: DriveAlbumInfo,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onRelink: () -> Unit = {},
    onRemove: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        val status = when {
            info.unavailable -> null
            info.needsConsent -> R.string.drive_needs_consent
            info.syncing -> R.string.drive_syncing
            else -> null
        }
        if (info.unavailable) UnavailableBanner(onRelink, onRemove)
        if (status != null) {
            Text(
                text = stringResource(status),
                style = MaterialTheme.typography.bodyMedium,
                color = if (info.needsConsent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (info.children.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(info.children, key = { it.id }) { child ->
                    ChildAlbumTile(child, onClick = { onOpenAlbum(child.id, child.title) })
                }
            }
        }
    }
}

/** The folder went from Drive: its items stay until the user relinks or removes it. */
@Composable
private fun UnavailableBanner(onRelink: () -> Unit, onRemove: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.drive_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = 8.dp)
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onRelink) { Text(stringResource(R.string.action_relink)) }
                TextButton(onClick = { confirm = true }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
    }
    if (confirm) {
        RemoveDialog(
            onConfirm = {
                confirm = false
                onRemove()
            },
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun ChildAlbumTile(album: ChildAlbum, onClick: () -> Unit) {
    val count = pluralStringResource(R.plurals.album_item_count, album.count, album.count)
    Column(
        Modifier
            .width(ChildSize)
            .clip(ChildShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${album.title}, $count" }
    ) {
        AsyncImage(
            model = album.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ChildSize)
                .clip(ChildShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
        Text(
            text = album.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val ChildSize = 96.dp
private val ChildShape = RoundedCornerShape(10.dp)
