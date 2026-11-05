@file:OptIn(UnstableApi::class)

package com.photoxt.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.PlaybackPositions
import com.photoxt.core.media.SubtitleFile
import com.photoxt.core.media.SubtitleFinder
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One video in the player (spec §4.6). The player lives here, so it keeps playing through
 * rotation and picture-in-picture. Where playback stopped is saved on every pause, every few
 * seconds while playing, and on leaving; the next open resumes there.
 */
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val mediaId: String,
    @ApplicationContext context: Context,
    private val mediaDao: MediaDao,
    private val positions: PlaybackPositions,
    private val subtitleFinder: SubtitleFinder
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(mediaId: String): PlayerViewModel
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SKIP_MS)
        .setSeekForwardIncrementMs(SKIP_MS)
        .build()

    private val loaded = MutableStateFlow<ViewerItem?>(null)
    val item: StateFlow<ViewerItem?> = loaded.asStateFlow()

    /** Set when playback resumed a saved position, so the screen can offer to start over. */
    private val resumed = MutableStateFlow<Long?>(null)
    val resumedFrom: StateFlow<Long?> = resumed.asStateFlow()

    private val subtitleFiles = MutableStateFlow<List<SubtitleFile>>(emptyList())
    val subtitles: StateFlow<List<SubtitleFile>> = subtitleFiles.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) positions.clear(mediaId)
            }
        })
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (player.isPlaying) savePosition()
            }
        }
    }

    private suspend fun load() {
        val video = mediaDao.viewerItem(mediaId) ?: return
        val uri = video.contentUri ?: return
        loaded.value = video
        val found = mediaDao.folderOf(mediaId)?.let {
            subtitleFinder.find(it, video.name)
        }.orEmpty()
        subtitleFiles.value = found
        val mediaItem = MediaItem.Builder()
            .setUri(uri.toUri())
            .setSubtitleConfigurations(
                found.mapIndexed { index, file ->
                    MediaItem.SubtitleConfiguration.Builder(file.contentUri.toUri())
                        .setMimeType(file.mime)
                        .setLanguage(file.language)
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
            )
            .build()
        val resumeAt = positions.resumeAt(mediaId)
        if (resumeAt != null) {
            player.setMediaItem(mediaItem, resumeAt)
            resumed.value = resumeAt
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun startOver() {
        resumed.value = null
        player.seekTo(0)
    }

    fun dismissResumed() {
        resumed.value = null
    }

    fun savePosition() {
        if (loaded.value == null || player.playbackState == Player.STATE_ENDED) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        positions.save(mediaId, player.currentPosition, duration)
    }

    override fun onCleared() {
        savePosition()
        player.release()
    }

    private companion object {
        const val SKIP_MS = 10_000L
        const val SAVE_EVERY_MS = 5_000L
    }
}
