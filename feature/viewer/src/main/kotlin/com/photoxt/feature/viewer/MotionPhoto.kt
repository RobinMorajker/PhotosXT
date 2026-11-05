@file:OptIn(UnstableApi::class)

package com.photoxt.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.photoxt.core.media.EmbeddedVideo

/**
 * Plays the video inside a motion photo (spec §4.5), once, over the photo, then reports
 * [onEnded]. The photo's file is read from [video]'s offset, so nothing is copied out.
 */
@Composable
internal fun MotionVideo(
    contentUri: String,
    video: EmbeddedVideo,
    contentScale: ContentScale,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val player = remember(contentUri, video) { motionPlayer(context, contentUri.toUri(), video) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = ended()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // A texture view, so the video can sit over the photo and fade with the page.
    ContentFrame(
        player = player,
        modifier = modifier,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = contentScale,
        keepContentOnReset = true,
        shutter = {}
    )
}

private fun motionPlayer(context: Context, uri: Uri, video: EmbeddedVideo): ExoPlayer {
    val source = ProgressiveMediaSource.Factory {
        EmbeddedVideoDataSource(DefaultDataSource(context, false), video)
    }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(source)
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
}

/** Reads only the embedded video's bytes of a motion photo's file, as if they were a file. */
internal class EmbeddedVideoDataSource(
    private val upstream: DataSource,
    private val video: EmbeddedVideo
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val remaining = video.length - dataSpec.position
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            remaining
        } else {
            minOf(dataSpec.length, remaining)
        }
        val inFile = dataSpec.buildUpon()
            .setPosition(video.offset + dataSpec.position)
            .setLength(length)
            .build()
        upstream.open(inFile)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()
}
