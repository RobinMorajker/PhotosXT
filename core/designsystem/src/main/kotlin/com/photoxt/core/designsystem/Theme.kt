package com.photoxt.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * PhotoXT theme: light, dark, or dark with pure-black surfaces for AMOLED screens.
 *
 * Dynamic colour (Android 12+) is the default. Without it, the Material 3 baseline scheme is used;
 * a PhotoXT accent scheme arrives with the theme settings (spec §4.20, §4.21).
 *
 * @param amoledBlack only applies when [darkTheme] is true.
 */
@Composable
fun PxtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledBlack: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()

        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme && amoledBlack) scheme.withAmoledBlack() else scheme,
        content = content
    )
}

// AMOLED containers lift from black in small steps, so cards and bars stay distinct.
private val AmoledContainerLow = Color(0xFF0B0B0B)
private val AmoledContainer = Color(0xFF121212)
private val AmoledContainerHigh = Color(0xFF1A1A1A)
private val AmoledContainerHighest = Color(0xFF222222)

private fun ColorScheme.withAmoledBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = AmoledContainerLow,
    surfaceContainer = AmoledContainer,
    surfaceContainerHigh = AmoledContainerHigh,
    surfaceContainerHighest = AmoledContainerHighest
)
