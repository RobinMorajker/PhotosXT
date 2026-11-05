package com.photoxt.feature.viewer

import com.photoxt.core.model.DisplayMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Viewer choices kept for the app session (spec §4.5): the display mode, which the slideshow also
 * uses, zoom lock, and the slideshow's options. They reset when the app's process ends; Settings
 * (Phase 6) will keep the ones worth keeping.
 */
@Singleton
class ViewerSession @Inject constructor() {
    val displayMode = MutableStateFlow(DisplayMode.FIT)
    val zoomLock = MutableStateFlow(false)
    val slideshow = MutableStateFlow(SlideshowSettings())
}

/** Slideshow options (spec §4.5). */
data class SlideshowSettings(
    val intervalSeconds: Int = 3,
    val transition: SlideshowTransition = SlideshowTransition.FADE,
    val shuffle: Boolean = false,
    val loop: Boolean = true
)

enum class SlideshowTransition { FADE, SLIDE, KEN_BURNS }
