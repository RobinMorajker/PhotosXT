package com.photoxt.core.transfer

import com.photoxt.core.auth.ConsentRequiredException
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.drive.driveStatus
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The transfer engine (spec §4.7): runs the jobs queued in Room one at a time, oldest batch first.
 * Copies, cuts, renames and conversions go through the operation matrix ([Copier]); trash,
 * restore, delete and new folders are one call each.
 *
 * Jobs save how far they got ([JobProgress]) as they go, and Drive copies, uploads and folders
 * carry ids made in advance, so a job killed at any point resumes without a duplicate. A job that
 * needs the user (a name conflict, a permission screen) waits in [TransferState.WAITING] while
 * the rest of the queue carries on.
 */
@Singleton
class TransferEngine @Inject constructor(
    database: PxtDatabase,
    private val device: DeviceStore,
    private val drive: DriveFiles,
    private val env: TransferEnvironment,
    private val clock: Clock
) {
    private val dao = database.transferDao()
    private val copier = Copier(device, drive, env)
    private val mutex = Mutex()
    private val finishedBatches = MutableSharedFlow<Long>(extraBufferCapacity = EVENTS)

    /** Batches that just finished, for Undo and the last notification. */
    val finished: SharedFlow<Long> = finishedBatches.asSharedFlow()

    /**
     * Runs every job that is due, until none is left (waiting and backing-off jobs stay). One
     * drain at a time; a stopped drain leaves its job to resume at the next.
     */
    suspend fun drain(onProgress: suspend (TransferProgress) -> Unit = {}) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Running jobs belong to a drain that was stopped or a process that died.
            dao.requeueRunning()
            var next = dao.nextRunnable(clock.millis())
            while (next != null) {
                currentCoroutineContext().ensureActive()
                runJob(next, onProgress)
                next = dao.nextRunnable(clock.millis())
            }
        }
    }

    /** Marks [batchId] finished once nothing of it can run any more, and says so. */
    suspend fun settleBatch(batchId: Long) {
        if (dao.finishIfDone(batchId, clock.millis()) == 0) return
        dao.jobs(batchId).flatMap { listOfNotNull(it.source.account, it.target?.account) }
            .distinct()
            .forEach { account ->
                runCatching { env.driveWritten(account) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        finishedBatches.emit(batchId)
    }

    /** Claims [job] and runs it; one cancelled meanwhile stays cancelled. */
    private suspend fun runJob(
        job: TransferJobEntity,
        onProgress: suspend (TransferProgress) -> Unit
    ) {
        if (dao.claim(job.id, clock.millis()) == 0) return
        val batch = dao.batch(job.batchId) ?: return
        val run = JobRun(job.copy(state = TransferState.RUNNING), batch, dao, clock, onProgress)
        settle(run, outcomeOf(run))
        settleBatch(batch.id)
    }

    /** Runs [run]: whatever goes wrong becomes that job's outcome, never the whole queue's. */
    @Suppress("TooGenericExceptionCaught") // The boundary of one job: every failure settles it.
    private suspend fun outcomeOf(run: JobRun): Outcome = try {
        perform(run)
    } catch (stopped: CancellationException) {
        // The runner stopped: the job resumes from its saved progress at the next drain.
        throw stopped
    } catch (error: Exception) {
        failureOf(run, error)
    }

    private suspend fun perform(run: JobRun): Outcome = when (run.job.op) {
        TransferOp.COPY ->
            if (run.src.isFolder) {
                throw PermanentException("Folders copy item by item")
            } else {
                copier.transfer(run)
            }

        TransferOp.MOVE -> if (run.src.isFolder) copier.moveFolder(run) else copier.transfer(run)

        TransferOp.TRASH -> trash(run)

        TransferOp.RESTORE -> restore(run)

        TransferOp.DELETE -> delete(run)

        TransferOp.RENAME -> copier.rename(run)

        TransferOp.CONVERT -> copier.convert(run)

        TransferOp.MKDIR -> mkdir(run)

        TransferOp.REMOVE_FROM_ALBUM -> removeFromAlbum(run)
    }

    // --- Trash, restore, delete, folders -------------------------------------------------------

    private suspend fun trash(run: JobRun): Outcome {
        val src = run.src
        if (src.isDrive) {
            env.requireDriveWrite(checkNotNull(src.account))
            copier.binInDrive(run, src)
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_TRASH, uri) { device.trash(uri) }
        }
        return Outcome.Done(src)
    }

    private suspend fun restore(run: JobRun): Outcome {
        val src = run.src
        if (src.isDrive) {
            val account = checkNotNull(src.account)
            env.requireDriveWrite(account)
            drive.setTrashed(account, checkNotNull(src.fileId), trashed = false)
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_RESTORE, uri) { device.restore(uri) }
        }
        return Outcome.Done(src)
    }

    private suspend fun delete(run: JobRun): Outcome {
        val src = run.src
        if (src.isDrive) {
            val account = checkNotNull(src.account)
            env.requireDriveWrite(account)
            drive.delete(account, checkNotNull(src.fileId))
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_DELETE, uri) { device.delete(uri) }
        }
        return Outcome.Done(null)
    }

    /** A Drive folder, made with an id from [JobProgress.generatedId] so a retry finds it. */
    private suspend fun mkdir(run: JobRun): Outcome {
        val parent = run.destination()
        val account = checkNotNull(parent.account)
        env.requireDriveWrite(account)
        val id = run.driveId { drive.newId(account) }
        val parentId = parent.folderId ?: ROOT
        val folder = drive.find(account, id)?.takeUnless { it.trashed }
            ?: drive.createFolder(account, id, run.src.name, parentId)
        if (parent.track) env.trackFolder(account, folder.id, folder.name)
        val made = folder.toRef(account)
        return Outcome.Done(made.copy(mime = FOLDER_MIME, parentId = parentId, isFolder = true))
    }

    private suspend fun removeFromAlbum(run: JobRun): Outcome {
        val src = run.src
        val account = checkNotNull(src.account)
        env.requireDriveWrite(account)
        drive.removeFrom(account, checkNotNull(src.fileId), checkNotNull(src.parentId))
        return Outcome.Done(src)
    }

    // --- Outcomes ------------------------------------------------------------------------------

    private fun failureOf(run: JobRun, error: Exception): Outcome = when (error) {
        is JobCancelledException -> Outcome.Cancelled
        is WaitException -> Outcome.Wait(error.waitFor)
        is RetryLaterException -> Outcome.Later(error.at)
        is PermanentException -> Outcome.Fail(error.message.orEmpty())
        is RetryException, is VerificationException -> Outcome.Retry(error.message.orEmpty())
        is ConsentRequiredException -> Outcome.Wait(WaitFor.DRIVE_SCOPE)
        is SecurityException -> Outcome.Wait(run.deviceWait())
        else -> error.driveStatus?.let { driveFailure(it, error.refusal) } ?: otherFailure(error)
    }

    /** What an HTTP [status] from Drive means for the job. */
    private fun driveFailure(status: Int, refusal: Refusal?): Outcome = when {
        status == HTTP_UNAUTHORIZED || refusal == Refusal.SCOPE -> Outcome.Wait(WaitFor.DRIVE_SCOPE)

        refusal == Refusal.RATE -> Outcome.Retry("Google Drive is busy")

        status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERROR ->
            Outcome.Retry("Google Drive answered HTTP $status")

        status == HTTP_NOT_FOUND -> Outcome.Fail("It's no longer in Google Drive")

        else -> Outcome.Fail("Google Drive answered HTTP $status")
    }

    private fun otherFailure(error: Exception): Outcome = when {
        error is IOException && !env.online -> Outcome.Offline

        error is IOException -> Outcome.Retry(error.javaClass.simpleName)

        else -> {
            PxtLog.w(TAG, "A transfer job failed", error)
            Outcome.Fail(error.javaClass.simpleName)
        }
    }

    /** Saves where the run left its job. A cancel that came first wins: its row isn't touched. */
    private suspend fun settle(run: JobRun, outcome: Outcome) {
        val job = run.job
        val now = clock.millis()
        val end = when (outcome) {
            is Outcome.Done -> Ending(TransferState.DONE, result = outcome.result)

            Outcome.Skipped -> Ending(TransferState.SKIPPED)

            is Outcome.Wait -> Ending(TransferState.WAITING, waitFor = outcome.waitFor)

            is Outcome.Later ->
                Ending(TransferState.QUEUED, next = maxOf(outcome.at, now + LATER_MS))

            Outcome.Offline ->
                Ending(TransferState.QUEUED, error = OFFLINE, next = now + OFFLINE_RETRY_MS)

            is Outcome.Retry -> retried(job.attempts + 1, outcome.error, now)

            is Outcome.Fail -> Ending(TransferState.FAILED, error = outcome.error)

            Outcome.Cancelled -> Ending(TransferState.CANCELLED)
        }
        if (end.state == TransferState.FAILED || end.state == TransferState.CANCELLED) {
            copier.discardPartial(run)
        }
        dao.settle(
            job.id,
            end.state,
            end.waitFor,
            end.error,
            end.result?.encode(),
            end.attempts ?: job.attempts,
            end.next,
            now
        )
    }

    /** Another attempt after 5 s, doubling up to 15 min; the sixth failed attempt is the last. */
    private fun retried(attempts: Int, error: String, now: Long): Ending =
        if (attempts >= MAX_ATTEMPTS) {
            Ending(TransferState.FAILED, attempts = attempts, error = error)
        } else {
            val backoff = minOf(BACKOFF_MS shl (attempts - 1), MAX_BACKOFF_MS)
            Ending(TransferState.QUEUED, attempts = attempts, error = error, next = now + backoff)
        }

    /** A job's state once a run of it ends; [attempts] stays as it was when null. */
    private data class Ending(
        val state: TransferState,
        val attempts: Int? = null,
        val waitFor: WaitFor? = null,
        val error: String? = null,
        val result: FileRef? = null,
        val next: Long = 0
    )

    private companion object {
        const val TAG = "TransferEngine"
        const val EVENTS = 16
        const val MAX_ATTEMPTS = 6
        const val BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L
        const val LATER_MS = 1_000L
        const val OFFLINE_RETRY_MS = 60_000L
        const val OFFLINE = "Offline"
        const val ROOT = "root"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
