package com.photoxt.feature.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import kotlin.math.roundToInt

/** Whether the activity is showing as a picture-in-picture window. */
@Composable
internal fun rememberInPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> {
            inPip = it.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * Picture-in-picture for the player (spec §4.6). While a video plays, leaving the app shrinks it
 * into a floating window: on its own from Android 12, through the user-leave hint before that.
 * Returns the action that enters picture-in-picture from the player's own button.
 */
@Composable
internal fun pictureInPicture(
    playing: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    bounds: Rect?
): () -> Unit {
    val activity = LocalActivity.current as? ComponentActivity ?: return {}
    val params = pipParams(playing, videoWidth, videoHeight, bounds)
    val current by rememberUpdatedState(params)
    val isPlaying by rememberUpdatedState(playing)
    DisposableEffect(activity, params) {
        activity.setPictureInPictureParams(params)
        onDispose { }
    }
    DisposableEffect(activity) {
        val leaving = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isPlaying) {
                activity.enterPictureInPictureMode(current)
            }
        }
        activity.addOnUserLeaveHintListener(leaving)
        onDispose {
            activity.removeOnUserLeaveHintListener(leaving)
            activity.setPictureInPictureParams(pipParams(false, videoWidth, videoHeight, null))
        }
    }
    return { activity.enterPictureInPictureMode(current) }
}

private fun pipParams(
    playing: Boolean,
    width: Int,
    height: Int,
    bounds: Rect?
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
    if (width > 0 && height > 0) builder.setAspectRatio(clampedRatio(width, height))
    bounds?.let {
        builder.setSourceRectHint(
            android.graphics.Rect(
                it.left.roundToInt(),
                it.top.roundToInt(),
                it.right.roundToInt(),
                it.bottom.roundToInt()
            )
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(playing)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

/** Android accepts picture-in-picture ratios between 1:2.39 and 2.39:1. */
internal fun clampedRatio(width: Int, height: Int): Rational {
    val ratio = width.toFloat() / height
    return when {
        ratio > MAX_RATIO -> Rational(MAX_RATIO_NUMERATOR, MAX_RATIO_DENOMINATOR)
        ratio < 1f / MAX_RATIO -> Rational(MAX_RATIO_DENOMINATOR, MAX_RATIO_NUMERATOR)
        else -> Rational(width, height)
    }
}

private const val MAX_RATIO = 2.39f
private const val MAX_RATIO_NUMERATOR = 239
private const val MAX_RATIO_DENOMINATOR = 100
