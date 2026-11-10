package com.photoxt.core.transfer

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TransferBatchEntity
import com.photoxt.core.database.TransferBatchRow
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A batch that finished, for the Undo snackbar (spec §4.7, Undo). */
data class TransferEvent(
    val batchId: Long,
    val op: TransferOp,
    val title: String,
    val done: Int,
    val failed: Int,
    /** A move or a trash the user can take back. */
    val undoable: Boolean
)

/**
 * The transfer queue for screens (spec §4.7): its batches and the jobs waiting for the user, the
 * controls (pause, cancel, retry, Undo) and the user's answers. Everything is queued in Room
 * first, then run by [TransferEngine] in a user-initiated job or WorkManager
 * ([TransferScheduler]); nothing here waits for the work itself.
 */
@Singleton
class TransferQueue @Inject constructor(
    private val database: PxtDatabase,
    private val engine: TransferEngine,
    private val scheduler: TransferKick,
    private val notifications: TransferNotifications,
    private val clock: Clock
) {
    private val dao = database.transferDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visibleState = MutableStateFlow(false)
    private val eventFlow = MutableSharedFlow<TransferEvent>(extraBufferCapacity = EVENTS)

    val batches: Flow<List<TransferBatchRow>> = dao.batches()

    /** Jobs waiting for the user: conflicts, permission screens, Drive files not theirs. */
    val waiting: Flow<List<TransferJobEntity>> = dao.waiting()

    /** Whether anything is queued or running. */
    val busy: Flow<Boolean> = dao.busy()

    val events: SharedFlow<TransferEvent> = eventFlow.asSharedFlow()

    /** Whether PhotoXT is on screen, so prompts and user-initiated jobs can start. */
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    /** Call once, from `Application.onCreate`: resumes what a killed process left. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    visibleState.value = true
                    scope.launch { if (busy.first()) scheduler.kick(visible = true) }
                }

                override fun onStop(owner: LifecycleOwner) {
                    visibleState.value = false
                }
            }
        )
        scope.launch {
            // A job a killed process left running counts: the drain requeues it (spec §0 rule 4).
            if (busy.first()) scheduler.kick(visible = false)
            engine.finished.collect { id -> eventOf(id)?.let { eventFlow.emit(it) } }
        }
        // Jobs that need an answer while PhotoXT is hidden say so in a notification.
        scope.launch {
            combine(waiting, visibleState) { jobs, shown -> if (shown) 0 else jobs.size }
                .distinctUntilChanged()
                .collect { count ->
                    if (count > 0) notifications.needsYou(count) else notifications.clearNeedsYou()
                }
        }
    }

    /** Queues [plan] as one batch and starts the queue; returns the batch's id. */
    internal suspend fun enqueue(
        op: TransferOp,
        title: String,
        plan: List<Planned>,
        undoOf: Long? = null,
        policy: ConflictPolicy = ConflictPolicy.ASK
    ): Long {
        val now = clock.millis()
        val id = database.withTransaction {
            val batchId = dao.insertBatch(
                TransferBatchEntity(
                    op = op,
                    title = title,
                    conflictPolicy = policy,
                    createdAt = now,
                    undoOf = undoOf
                )
            )
            val ids = LongArray(plan.size)
            plan.forEachIndexed { index, planned ->
                // A folder made by an earlier job of the plan: that job's id, now that it has one.
                val dst = planned.dst?.let { target ->
                    val placeholder = target.folderJob
                    if (placeholder != null && placeholder < 0) {
                        target.copy(folderJob = ids[(-placeholder - 1).toInt()])
                    } else {
                        target
                    }
                }
                ids[index] = dao.insertJob(
                    TransferJobEntity(
                        batchId = batchId,
                        op = planned.op,
                        src = planned.src.encode(),
                        dst = dst?.encode(),
                        state = TransferState.QUEUED,
                        bytesTotal = planned.src.bytes,
                        updatedAt = now
                    )
                )
            }
            batchId
        }
        kick()
        return id
    }

    suspend fun pause(batchId: Long, paused: Boolean) {
        dao.setPaused(batchId, paused)
        if (!paused) kick()
    }

    suspend fun cancel(batchId: Long) {
        dao.cancel(batchId, clock.millis())
        engine.settleBatch(batchId)
    }

    suspend fun retry(batchId: Long) {
        dao.retryFailed(batchId, clock.millis())
        dao.reopen(batchId)
        kick()
    }

    suspend fun clearFinished() = dao.clearFinished()

    /**
     * Undo (spec §4.7): queues the reverse of finished batch [batchId]. A move goes back where it
     * came from; a cut across sources restores its source from the trash and bins the copy; a
     * trash restores. Null when there is nothing to take back.
     */
    suspend fun undo(batchId: Long): Long? {
        val batch = dao.batch(batchId)?.takeIf { !it.undone && it.undoOf == null } ?: return null
        val plan = dao.jobs(batchId).filter { it.state == TransferState.DONE }.flatMap(::reverseOf)
        if (plan.isEmpty()) return null
        val id = enqueue(
            batch.op,
            batch.title,
            plan,
            undoOf = batchId,
            policy = ConflictPolicy.KEEP_BOTH
        )
        dao.updateBatch(batch.copy(undone = true))
        return id
    }

    // --- Answers to waiting jobs ---------------------------------------------------------------

    /** The user's answer to a name conflict; with [applyToAll] the batch's later ones too. */
    suspend fun resolveConflict(jobId: Long, policy: ConflictPolicy, applyToAll: Boolean) {
        val job = dao.job(jobId) ?: return
        val now = clock.millis()
        if (applyToAll) {
            dao.setBatchPolicy(job.batchId, policy)
            dao.resolve(dao.waitingIn(job.batchId, WaitFor.CONFLICT), policy, now)
        } else {
            dao.resolve(listOf(jobId), policy, now)
        }
        kick()
    }

    /**
     * Drive files the user can't bin (spec §4.7): take them out of their album instead, or leave
     * them (a cut then keeps its source, a delete skips).
     */
    suspend fun resolveNotOwned(jobIds: List<Long>, removeFromAlbum: Boolean) {
        val jobs = jobIds.mapNotNull { dao.job(it) }.filter { it.waitFor == WaitFor.NOT_OWNED }
        val now = clock.millis()
        jobs.forEach { job ->
            val progress = job.saved
            when {
                removeFromAlbum -> dao.updateJob(
                    job.copy(
                        state = TransferState.QUEUED,
                        waitFor = null,
                        progress = progress.copy(removeInstead = true).encode(),
                        nextAttemptAt = 0,
                        updatedAt = now
                    )
                )

                progress.copy != null -> complete(job, progress.copy, KEPT_SOURCE)

                else -> dao.settleWaiting(listOf(job.id), TransferState.SKIPPED, null, now)
            }
        }
        settle(jobs)
        kick()
    }

    /**
     * MediaStore's answer for jobs waiting on [kind]. A trash, restore or delete request does
     * the change itself, so those jobs are done; a write grant lets the jobs run again.
     */
    suspend fun deviceConsent(kind: WaitFor, jobIds: List<Long>, granted: Boolean) {
        val jobs = jobIds.mapNotNull { dao.job(it) }
            .filter { it.state == TransferState.WAITING && it.waitFor == kind }
        val now = clock.millis()
        when {
            !granted -> jobs.forEach { job ->
                val copy = job.saved.copy
                if (copy != null) {
                    complete(job, copy, KEPT_SOURCE)
                } else {
                    dao.settleWaiting(listOf(job.id), TransferState.FAILED, NOT_ALLOWED, now)
                }
            }

            kind == WaitFor.DEVICE_WRITE -> dao.release(jobs.map { it.id }, now)

            else -> jobs.forEach { job -> confirmed(job, now) }
        }
        settle(jobs)
        kick()
    }

    /** Google's answer to the full-Drive request for [account] (spec §2, scope upgrade). */
    suspend fun driveAccess(account: String, granted: Boolean) {
        val jobs = dao.jobsWaiting(WaitFor.DRIVE_SCOPE)
            .filter { it.source.account == account || it.target?.account == account }
        val now = clock.millis()
        if (granted) {
            dao.release(jobs.map { it.id }, now)
        } else {
            dao.settleWaiting(jobs.map { it.id }, TransferState.FAILED, NOT_ALLOWED, now)
        }
        settle(jobs)
        kick()
    }

    /** MediaStore made the change [job] waited for itself: a trash, restore or delete. */
    private suspend fun confirmed(job: TransferJobEntity, now: Long) {
        val copy = job.saved.copy
        when {
            job.op in SELF_DONE ->
                complete(job, job.source.takeIf { job.op != TransferOp.DELETE }, null)

            copy != null -> complete(job, copy, null)

            // A Replace binned the old item; the copy can go ahead now.
            else -> dao.release(listOf(job.id), now)
        }
    }

    private suspend fun complete(job: TransferJobEntity, result: FileRef?, note: String?) {
        dao.updateJob(
            job.copy(
                state = TransferState.DONE,
                waitFor = null,
                error = note,
                result = result?.encode() ?: job.result,
                updatedAt = clock.millis()
            )
        )
    }

    private suspend fun settle(jobs: List<TransferJobEntity>) {
        jobs.map { it.batchId }.distinct().forEach { engine.settleBatch(it) }
    }

    private fun kick() = scheduler.kick(visibleState.value)

    private fun reverseOf(job: TransferJobEntity): List<Planned> {
        val was = job.source
        val now = job.outcome ?: return emptyList()
        return when (job.op) {
            TransferOp.TRASH ->
                if (job.saved.removeInstead) {
                    emptyList()
                } else {
                    listOf(Planned(TransferOp.RESTORE, was, null))
                }

            TransferOp.MOVE -> {
                val sameAccount = was.isDrive && now.isDrive && was.account == now.account
                val sameVolume = !was.isDrive && !now.isDrive && was.volume == now.volume
                if (sameAccount || sameVolume) {
                    val back = Destination(
                        was.source,
                        was.name,
                        volume = was.volume,
                        path = was.path,
                        account = was.account,
                        folderId = job.saved.fromParent ?: was.parentId,
                        name = was.name
                    )
                    listOf(Planned(TransferOp.MOVE, now, back))
                } else {
                    listOf(
                        Planned(TransferOp.RESTORE, was, null),
                        Planned(TransferOp.TRASH, now, null)
                    )
                }
            }

            else -> emptyList()
        }
    }

    private suspend fun eventOf(batchId: Long): TransferEvent? {
        val batch = dao.batch(batchId) ?: return null
        val jobs = dao.jobs(batchId)
        val done = jobs.count { it.state == TransferState.DONE }
        val failed = jobs.count { it.state == TransferState.FAILED }
        return TransferEvent(
            batchId = batchId,
            op = batch.op,
            title = batch.title,
            done = done,
            failed = failed,
            undoable = done > 0 && batch.undoOf == null && batch.op in UNDOABLE
        )
    }

    private companion object {
        const val EVENTS = 8
        const val NOT_ALLOWED = "Not allowed"
        const val KEPT_SOURCE = "Copied; the original stays where it was"
        val SELF_DONE = setOf(TransferOp.TRASH, TransferOp.RESTORE, TransferOp.DELETE)
        val UNDOABLE = setOf(TransferOp.MOVE, TransferOp.TRASH)
    }
}
