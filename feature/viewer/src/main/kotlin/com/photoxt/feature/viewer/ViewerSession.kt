package com.photoxt.feature.viewer

import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.model.DisplayMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Viewer choices (spec §4.5): the display mode, which the slideshow also uses, zoom lock, and the
 * slideshow's options, kept in the settings.
 */
@Singleton
class ViewerSession @Inject constructor(private val settings: SettingsStore) {
    val displayMode = MutableStateFlow(DisplayMode.FIT)
    val zoomLock = MutableStateFlow(false)
    val slideshow = MutableStateFlow(SlideshowSettings())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The choices persist with the settings (spec §4.5, §4.21), and sync with them (§4.8).
        scope.launch {
            val view = settings.current().view
            displayMode.value = view.displayMode
            zoomLock.value = view.zoomLock
            slideshow.value = SlideshowSettings(
                intervalSeconds = view.slideshowSeconds,
                transition = SlideshowTransition.entries.firstOrNull {
                    it.name == view.slideshowTransition
                } ?: SlideshowTransition.FADE,
                shuffle = view.slideshowShuffle,
                loop = view.slideshowLoop
            )
            combine(
                displayMode,
                zoomLock,
                slideshow,
                ::Triple
            ).drop(1).collect { (mode, lock, show) ->
                settings.updateView {
                    it.copy(
                        displayMode = mode,
                        zoomLock = lock,
                        slideshowSeconds = show.intervalSeconds,
                        slideshowTransition = show.transition.name,
                        slideshowShuffle = show.shuffle,
                        slideshowLoop = show.loop
                    )
                }
            }
        }
    }
}

/** Slideshow options (spec §4.5). */
data class SlideshowSettings(
    val intervalSeconds: Int = 3,
    val transition: SlideshowTransition = SlideshowTransition.FADE,
    val shuffle: Boolean = false,
    val loop: Boolean = true
)

enum class SlideshowTransition { FADE, SLIDE, KEN_BURNS }
