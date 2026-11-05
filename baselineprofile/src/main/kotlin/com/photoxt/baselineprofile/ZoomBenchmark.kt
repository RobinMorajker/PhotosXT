package com.photoxt.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while a 200 MP photo zooms to 1:1 and is panned there (Phase 2 "Done when"). The
 * phone needs an album named [ZOOM_ALBUM] whose newest photo is the 200 MP test photo.
 */
@RunWith(AndroidJUnit4::class)
class ZoomBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun zoomBigPhotoToPixels() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            grantMediaAccess()
            pressHome()
            startActivityAndWait()
            openNewestPhoto(ZOOM_ALBUM)
        }
    ) {
        zoomToPixelsAndPan()
    }

    private companion object {
        const val ZOOM_ALBUM = "PhotoXT test"
    }
}
