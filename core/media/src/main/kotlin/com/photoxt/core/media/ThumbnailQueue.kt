package com.photoxt.core.media

import java.util.PriorityQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Thumbnail work order (spec §4.3): items on screen, then the album, then everything else. */
enum class ThumbnailPriority { VISIBLE, ALBUM, BACKGROUND }

/**
 * A priority queue of thumbnail jobs run by [workers] coroutines in [scope]. Asking again for a
 * queued item returns the same job and raises its priority if the new one is higher, so a tile
 * scrolled into view jumps ahead of the background fill.
 */
class ThumbnailQueue(
    scope: CoroutineScope,
    workers: Int,
    private val make: suspend (id: String) -> Unit
) {
    private class Job(val id: String, var priority: ThumbnailPriority, var order: Long) {
        val done = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val queue = PriorityQueue<Job>(compareBy<Job>({ it.priority }, { it.order }))
    private val jobs = HashMap<String, Job>()
    private val signals = Channel<Unit>(Channel.UNLIMITED)
    private var order = 0L

    init {
        repeat(workers) { scope.launch { work() } }
    }

    /** Queues [id] at [priority]; the result completes when its thumbnail is made or failed. */
    fun enqueue(id: String, priority: ThumbnailPriority): Deferred<Unit> = synchronized(lock) {
        val known = jobs[id]
        if (known != null) {
            // Still waiting (not running yet) and now wanted sooner: move it up.
            if (priority < known.priority && queue.remove(known)) {
                known.priority = priority
                known.order = order++
                queue.add(known)
            }
            return known.done
        }
        val job = Job(id, priority, order++)
        jobs[id] = job
        queue.add(job)
        signals.trySend(Unit)
        job.done
    }

    private suspend fun work() {
        while (true) {
            signals.receive()
            val job = synchronized(lock) { queue.poll() } ?: continue
            val result = runCatching { make(job.id) }
            synchronized(lock) { jobs.remove(job.id) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.fold({ job.done.complete(Unit) }, { job.done.completeExceptionally(it) })
        }
    }
}
