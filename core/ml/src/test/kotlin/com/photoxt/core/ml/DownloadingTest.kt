package com.photoxt.core.ml

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A model Play services is still fetching is told apart from a real failure. */
@RunWith(RobolectricTestRunner::class)
class DownloadingTest {
    @Test
    fun aModelStillDownloadingIsToldApart() {
        val waiting = MlKitException("Waiting for the module", MlKitException.UNAVAILABLE)
        assertTrue(waiting.downloading() is ModelsDownloadingException)
        val other = MlKitException("Bad image", MlKitException.INVALID_ARGUMENT)
        assertSame(other, other.downloading())
    }
}
