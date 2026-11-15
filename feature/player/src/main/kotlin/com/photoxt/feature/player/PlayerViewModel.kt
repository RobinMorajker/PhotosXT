@file:OptIn(UnstableApi::class)

package com.photoxt.feature.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.DriveVideo
import com.photoxt.core.media.SubtitleFile
import com.photoxt.core.media.SubtitleFinder
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.organize.Bookmarks
import com.photoxt.core.organize.History
import com.photoxt.core.ui.NamedChoice
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One video in the player (spec §4.6). The player lives here, so it keeps playing through
 * rotation and picture-in-picture. Where playback stopped is saved on every pause, every few
 * seconds while playing, and on leaving; the next open resumes there. Opened from album
 * [albumId], the view and the time watched go to the history (spec §4.9); [startAt] opens a
 * bookmarked moment (spec §4.8).
 */
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted("media") private val mediaId: String,
    @Assisted("album") private val albumId: String?,
    @Assisted private val startAt: Long?,
    @ApplicationContext context: Context,
    private val mediaDao: MediaDao,
    private val history: History,
    private val bookmarks: Bookmarks,
    private val subtitleFinder: SubtitleFinder,
    private val driveVideo: DriveVideo,
    private val originals: DriveOriginals
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("media") mediaId: String,
            @Assisted("album") albumId: String?,
            startAt: Long?
        ): PlayerViewModel
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

    val collections: Flow<List<NamedChoice>> =
        bookmarks.collections.map { list -> list.map { NamedChoice(it.id, it.name) } }

    /** This open's history entry, once logged; null while history is paused (spec §4.9). */
    private var viewId: Long? = null
    private var playingSince: Long? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playingSince = SystemClock.elapsedRealtime()
                } else {
                    logWatched()
                    savePosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) history.finished(mediaId)
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
        // A Drive video kept offline or downloaded plays from the phone (spec §4.4).
        val local = video.driveFile()?.let { originals.localFile(mediaId) }
        val drive = video.driveFile().takeIf { local == null }
        val uri = local?.toUri()?.toString()
            ?: drive?.let { driveVideo.uri(it.fileId) }
            ?: video.contentUri
            ?: return
        loaded.value = video
        // Side-loaded subtitles are files beside the video on the phone (spec §4.6).
        val found = if (video.source == SourceType.DRIVE) emptyList() else subtitlesOf(video)
        subtitleFiles.value = found
        val mediaItem = MediaItem.Builder()
            .setUri(uri.toUri())
            .setCustomCacheKey(drive?.let { driveVideo.cacheKey(it.fileId, it.version) })
            .setSubtitleConfigurations(found.mapIndexed(::subtitleConfiguration))
            .build()
        viewId = history.viewed(mediaId, albumId)
        val resumeAt = startAt ?: history.resumeAt(mediaId)
        if (drive != null) {
            // Streamed from Drive with Range requests through the video cache (spec §4.4).
            val source = DefaultMediaSourceFactory(driveVideo.dataSourceFactory(drive.accountId))
                .createMediaSource(mediaItem)
            player.setMediaSource(source, resumeAt ?: 0L)
        } else {
            player.setMediaItem(mediaItem, resumeAt ?: 0L)
        }
        // A bookmarked moment is where the user asked to start, not a resume to undo.
        resumed.value = resumeAt.takeIf { startAt == null }
        player.prepare()
        player.playWhenReady = true
    }

    private suspend fun subtitlesOf(video: ViewerItem): List<SubtitleFile> =
        mediaDao.folderOf(mediaId)?.let { subtitleFinder.find(it, video.name) }.orEmpty()

    private fun subtitleConfiguration(index: Int, file: SubtitleFile) =
        MediaItem.SubtitleConfiguration.Builder(file.contentUri.toUri())
            .setMimeType(file.mime)
            .setLanguage(file.language)
            .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

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
        history.savePosition(mediaId, player.currentPosition, duration)
    }

    /** Bookmarks this video at [positionMs] (spec §4.8). */
    fun bookmarkMoment(
        positionMs: Long,
        collectionId: String?,
        newCollection: String?,
        note: String
    ) {
        if (loaded.value == null) return
        viewModelScope.launch {
            val collection = bookmarks.collection(collectionId, newCollection)
            bookmarks.add(collection, TargetType.ITEM, mediaId, note, positionMs)
        }
    }

    private fun logWatched() {
        val since = playingSince ?: return
        playingSince = null
        viewId?.let { history.watched(it, SystemClock.elapsedRealtime() - since) }
    }

    override fun onCleared() {
        logWatched()
        savePosition()
        player.release()
    }

    private companion object {
        const val SKIP_MS = 10_000L
        const val SAVE_EVERY_MS = 5_000L
    }
}

/** A Drive video's account, file and version. */
private class DriveFileRef(val accountId: String, val fileId: String, val version: Long)

private fun ViewerItem.driveFile(): DriveFileRef? {
    if (source != SourceType.DRIVE) return null
    val account = accountId ?: return null
    val file = remoteId ?: return null
    val fileVersion = remoteVersion ?: return null
    return DriveFileRef(account, file, fileVersion)
}
