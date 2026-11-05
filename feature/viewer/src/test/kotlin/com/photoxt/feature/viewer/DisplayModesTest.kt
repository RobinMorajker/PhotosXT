package com.photoxt.feature.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.photoxt.core.model.DisplayMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Display modes, the double-tap zoom cycle and slideshow order. */
class DisplayModesTest {
    private val screen = Size(1080f, 2340f)

    @Test
    fun displayModesPlaceAPhotoAsNamed() {
        val photo = Size(4080f, 3060f)
        assertEquals(Rect(0f, 765f, 1080f, 1575f), displayedBounds(DisplayMode.FIT, screen, photo))
        assertEquals(
            Rect(0f, 0f, 1080f, 2340f),
            displayedBounds(DisplayMode.STRETCH, screen, photo)
        )
        val fill = displayedBounds(DisplayMode.FILL, screen, photo)
        assertEquals(2340f, fill.height, 0.5f)
        assertEquals(3120f, fill.width, 0.5f)
        val pixels = displayedBounds(DisplayMode.ONE_TO_ONE, screen, photo)
        assertEquals(photo, pixels.size)
        assertEquals(
            Rect(0f, 0f, 1080f, 2340f),
            displayedBounds(DisplayMode.FIT, screen, Size.Zero)
        )
    }

    @Test
    fun doubleTapGoesFitFillOneToOneThenBack() {
        // A 200 MP photo on a phone: fit is tiny, fill bigger, then 1:1 pixels.
        val fit = 1080f / 16320f
        val fill = 2340f / 12240f
        val max = MAX_ZOOM
        assertEquals(fill, checkNotNull(nextDoubleTapZoom(fit, fit, fill, fit, max)), 1e-6f)
        assertEquals(1f, checkNotNull(nextDoubleTapZoom(fill, fit, fill, fit, max)), 1e-6f)
        assertNull(nextDoubleTapZoom(1f, fit, fill, fit, max))
        // Zoomed in by hand between fit and fill: the next tap still goes to fill.
        assertEquals(fill, checkNotNull(nextDoubleTapZoom(fit * 1.5f, fit, fill, fit, max)), 1e-6f)
    }

    @Test
    fun aSmallPhotoSkipsOneToOne() {
        // 800 x 600 on the same phone: 1:1 is smaller than fit, so taps go fit, fill, back.
        val fit = 1080f / 800f
        val fill = 2340f / 600f
        val max = MAX_ZOOM * fit
        assertEquals(fill, checkNotNull(nextDoubleTapZoom(fit, fit, fill, fit, max)), 1e-6f)
        assertNull(nextDoubleTapZoom(fill, fit, fill, fit, max))
    }

    @Test
    fun slideshowStartsAtTheTappedItemAndCoversEveryItemOnce() {
        assertEquals(listOf(2, 3, 4, 0, 1), slideOrder(5, 2, shuffle = false, random = Random(1)))
        val shuffled = slideOrder(5, 2, shuffle = true, random = Random(1))
        assertEquals(2, shuffled.first())
        assertEquals((0 until 5).toSet(), shuffled.toSet())
        assertEquals(emptyList<Int>(), slideOrder(0, 0, shuffle = false, random = Random(1)))
    }
}
