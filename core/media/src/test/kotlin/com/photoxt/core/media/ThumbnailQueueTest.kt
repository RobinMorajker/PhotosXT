package com.photoxt.core.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailQueueTest {
    @Test
    fun visibleTilesJumpTheBackgroundFillAndRepeatsShareOneJob() = runTest {
        val workScope = TestScope(StandardTestDispatcher(testScheduler))
        val made = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val queue = ThumbnailQueue(workScope, workers = 1) { id ->
            if (id == "first") gate.await()
            made += id
        }
        queue.enqueue("first", ThumbnailPriority.BACKGROUND)
        workScope.advanceUntilIdle() // the one worker now waits inside "first"
        val a = queue.enqueue("a", ThumbnailPriority.BACKGROUND)
        queue.enqueue("b", ThumbnailPriority.ALBUM)
        queue.enqueue("c", ThumbnailPriority.BACKGROUND)
        // Scrolled into view while queued: same job, moved to the front.
        assertSame(a, queue.enqueue("a", ThumbnailPriority.VISIBLE))
        gate.complete(Unit)
        workScope.advanceUntilIdle()
        assertEquals(listOf("first", "a", "b", "c"), made)
        workScope.cancel()
    }

    @Test
    fun aFailedThumbnailFailsItsJobAndTheQueueGoesOn() = runTest {
        val workScope = TestScope(StandardTestDispatcher(testScheduler))
        val queue = ThumbnailQueue(workScope, workers = 2) { id ->
            check(id != "bad") { "no source" }
        }
        val bad = queue.enqueue("bad", ThumbnailPriority.VISIBLE)
        val good = queue.enqueue("good", ThumbnailPriority.VISIBLE)
        workScope.advanceUntilIdle()
        assertEquals(true, bad.isCompleted && bad.getCompletionExceptionOrNull() != null)
        assertEquals(true, good.isCompleted && good.getCompletionExceptionOrNull() == null)
        workScope.cancel()
    }
}
