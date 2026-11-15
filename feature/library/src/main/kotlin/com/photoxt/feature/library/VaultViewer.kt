@file:OptIn(UnstableApi::class)

package com.photoxt.feature.library

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaKind
import com.photoxt.core.ui.ConfirmDialog
import com.photoxt.core.ui.RequireUnlocked
import com.photoxt.core.ui.SecureWindow
import com.photoxt.core.vault.Vault
import com.photoxt.core.vault.VaultDataSource
import com.photoxt.core.vault.VaultEntry
import com.photoxt.core.vault.VaultImage

/**
 * A vault item full screen (spec §4.8), swiping through the vault: photos decrypted in memory,
 * videos played as they decrypt, screenshots blocked. Restored after the app was away, it closes
 * until the vault is unlocked again.
 */
@Composable
fun VaultViewerScreen(startIndex: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    RequireUnlocked(onLocked = onClose) {
        SecureWindow()
        BackHandler(onBack = onClose)
        val viewModel = hiltViewModel<VaultViewModel>()
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val list = entries
            when {
                list == null -> Unit
                list.isEmpty() -> LaunchedEffect(Unit) { onClose() }
                else -> VaultPager(list, startIndex, viewModel, onClose)
            }
        }
    }
}

@Composable
private fun VaultPager(
    list: List<VaultEntry>,
    startIndex: Int,
    viewModel: VaultViewModel,
    onClose: () -> Unit
) {
    val pager =
        rememberPagerState(initialPage = startIndex.coerceIn(0, list.lastIndex)) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf<VaultEntry?>(null) }
    HorizontalPager(pager, key = { list[it].id }, modifier = Modifier.fillMaxSize()) { page ->
        val entry = list[page]
        if (entry.meta.kind == MediaKind.VIDEO) {
            VaultVideo(viewModel.vault, entry, current = pager.settledPage == page)
        } else {
            AsyncImage(
                model = VaultImage(entry.id, thumbnail = false),
                contentDescription = entry.meta.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { chrome = !chrome }
            )
        }
    }
    val current = list.getOrNull(pager.currentPage)
    if (chrome && current != null) {
        VaultBar(
            current,
            onClose = onClose,
            onMoveOut = { title -> viewModel.moveOut(current, title) },
            onDelete = { deleting = current }
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_title),
            body = stringResource(R.string.vault_delete_body),
            confirm = stringResource(R.string.vault_delete),
            onConfirm = {
                deleting = null
                viewModel.delete(entry)
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun VaultBar(
    entry: VaultEntry,
    onClose: () -> Unit,
    onMoveOut: (title: String) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .statusBarsPadding()
        ) {
            BackButton(onClose)
            Text(
                entry.meta.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painterResource(DesignR.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.vault_actions)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vault_move_out)) },
                        onClick = {
                            menu = false
                            onMoveOut(resources.getQuantityString(R.plurals.vault_items, 1, 1))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vault_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** A vault video, decrypted as it plays; it pauses when swiped away. */
@Composable
private fun VaultVideo(vault: Vault, entry: VaultEntry, current: Boolean) {
    val context = LocalContext.current
    val player = remember(entry.id) {
        ExoPlayer.Builder(context).build().apply {
            val source = ProgressiveMediaSource.Factory { VaultDataSource(vault, entry.id) }
                .createMediaSource(MediaItem.fromUri("vault:" + entry.id))
            setMediaSource(source)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(current) { if (!current) player.pause() }
    val playPause = rememberPlayPauseButtonState(player)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContentFrame(player = player, modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = playPause::onClick,
            enabled = playPause.isEnabled,
            modifier = Modifier
                .size(72.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                painterResource(
                    if (playPause.showPlay) DesignR.drawable.ic_play else DesignR.drawable.ic_pause
                ),
                contentDescription = stringResource(
                    if (playPause.showPlay) R.string.vault_play else R.string.vault_pause
                ),
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}
