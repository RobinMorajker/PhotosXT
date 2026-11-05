package com.photoxt.core.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full screen while viewing: the system bars hide with the viewer's own bars (a swipe brings them
 * back for a moment), and their icons stay light over the dark viewer. Restored on leaving.
 */
@Composable
fun ImmersiveEffect(barsVisible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view, barsVisible) {
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (barsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}

/**
 * Ultra HDR (spec §4.5): while a photo with an HDR gain map is on screen, the window asks for HDR
 * output, so an HDR display shows its highlights brighter. Other screens ignore the request.
 */
@Composable
fun HdrWindowEffect(hdr: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, hdr) {
        if (hdr) window.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }
}
