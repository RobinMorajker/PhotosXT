package com.photoxt.core.transfer

import com.photoxt.core.database.TransferBatchEntity
import com.photoxt.core.database.TransferDao
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.driveReason
import com.photoxt.core.drive.driveStatus
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock

/** What the engine needs from the rest of the app; tests fake it. */
interface TransferEnvironment {
    /** Whether [account] granted full Drive access (spec §2: upgraded at the first write). */
    suspend fun canWriteDrive(account: String): Boolean

    /** Whether the phone has a network now. */
    val online: Boolean

    /** Where downloads and conversions wait while a job runs. */
    val workDir: File

    /** [account]'s Drive changed through PhotoXT: sync it (spec §4.3, "after any write"). */
    suspend fun driveWritten(account: String)

    /** A Drive folder made by *New album* becomes a tracked album. */
    suspend fun trackFolder(account: String, folderId: String, name: String)
}

/** What a running job is doing, for the progress notification. */
data class TransferProgress(
    val batchId: Long,
    val op: TransferOp,
    val title: String,
    val bytesDone: Long,
    val bytesTotal: Long
)

/**
 * One run of one job: its parts decoded, and its progress saved as it goes, so a job killed at
 * any point resumes from there (spec §0 rule 4).
 */
internal class JobRun(
    val job: TransferJobEntity,
    val batch: TransferBatchEntity,
    private val dao: TransferDao,
    private val clock: Clock,
    private val report: suspend (TransferProgress) -> Unit
) {
    val src: FileRef = job.source
    val isMove: Boolean get() = job.op == TransferOp.MOVE
    var progress: JobProgress = job.saved
    var bytesTotal: Long = job.bytesTotal.takeIf { it > 0 } ?: src.bytes
    private var bytesDone = job.bytesDone
    private var lastSave = 0L

    /** Saves [update]; throws [JobCancelledException] once the user cancelled the job. */
    suspend fun save(update: JobProgress = progress) {
        progress = update
        val saved =
            dao.saveProgress(job.id, bytesDone, bytesTotal, progress.encode(), clock.millis())
        if (saved == 0) throw JobCancelledException()
    }

    /** [done] bytes so far: saved and reported about once a second. */
    suspend fun advance(done: Long) {
        bytesDone = done
        val now = System.nanoTime()
        if (now - lastSave >= SAVE_INTERVAL_NS) {
            lastSave = now
            save()
            report(TransferProgress(batch.id, job.op, batch.title, done, bytesTotal))
        }
    }

    /**
     * The folder to put the item in, once the job making it has run: a recursive paste's items
     * wait for their folder.
     */
    suspend fun destination(): Destination {
        val target = checkNotNull(job.target) { "A job without a destination" }
        val folderJob = target.folderJob ?: return target
        val made = dao.job(folderJob)
        if (made?.state == TransferState.DONE) {
            return target.copy(folderId = made.outcome?.fileId, folderJob = null)
        }
        throw when (made?.state) {
            TransferState.WAITING -> WaitException(made.waitFor ?: WaitFor.DRIVE_SCOPE)

            TransferState.QUEUED, TransferState.RUNNING ->
                RetryLaterException(made.nextAttemptAt)

            else -> PermanentException("Its folder couldn't be made")
        }
    }

    /** Which MediaStore screen this job's refused change needs. */
    fun deviceWait(): WaitFor = when (job.op) {
        TransferOp.TRASH -> WaitFor.DEVICE_TRASH
        TransferOp.RESTORE -> WaitFor.DEVICE_RESTORE
        TransferOp.DELETE -> WaitFor.DEVICE_DELETE
        else -> WaitFor.DEVICE_WRITE
    }

    /** The Drive id of the file or folder this job makes: made once and kept, for retries. */
    suspend fun driveId(newId: suspend () -> String): String =
        progress.generatedId ?: newId().also { save(progress.copy(generatedId = it)) }

    /**
     * Runs a MediaStore change that another app's item may refuse until the user allows it: then
     * the job waits for [kind], MediaStore's matching request screen, about [uri].
     */
    suspend fun deviceWrite(kind: WaitFor, uri: String?, change: () -> Unit) {
        try {
            change()
        } catch (refused: SecurityException) {
            save(progress.copy(consentUri = uri))
            throw WaitException(kind, refused)
        }
    }

    private companion object {
        const val SAVE_INTERVAL_NS = 1_000_000_000L
    }
}

/** Waits for the `drive` scope unless [account] granted it (spec §2: asked at the first write). */
internal suspend fun TransferEnvironment.requireDriveWrite(account: String) {
    if (!canWriteDrive(account)) throw WaitException(WaitFor.DRIVE_SCOPE)
}

/** What a 403 from Drive is about. */
internal enum class Refusal {
    /** The token lacks the `drive` scope (spec §2). */
    SCOPE,

    /** Too many requests: it passes with time. */
    RATE,

    /** The file isn't the user's to change, e.g. one shared with them. */
    FILE
}

/** Why Drive refused the call that threw this, when it answered 403; else null. */
internal val Throwable.refusal: Refusal?
    get() = if (driveStatus != HTTP_FORBIDDEN) {
        null
    } else {
        when (driveReason()) {
            "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> Refusal.SCOPE
            "rateLimitExceeded", "userRateLimitExceeded" -> Refusal.RATE
            else -> Refusal.FILE
        }
    }

/** How one run of a job ended. */
internal sealed interface Outcome {
    data class Done(val result: FileRef?) : Outcome

    data object Skipped : Outcome

    data class Wait(val waitFor: WaitFor) : Outcome

    /** Not yet: something it depends on is due at [at]. */
    data class Later(val at: Long) : Outcome

    /** No network: try again later without counting an attempt. */
    data object Offline : Outcome

    data class Retry(val error: String) : Outcome

    data class Fail(val error: String) : Outcome

    /** The user cancelled it while it ran: its row says so already; only leftovers go. */
    data object Cancelled : Outcome
}

/** The job waits for the user ([waitFor]). */
internal class WaitException(val waitFor: WaitFor, cause: Throwable? = null) : Exception(cause)

/** Not yet: something the job needs is due at [at]. */
internal class RetryLaterException(val at: Long) : Exception()

/** Retrying won't help. */
internal class PermanentException(message: String) : Exception(message)

/** Worth another attempt after a backoff. */
internal class RetryException(message: String) : Exception(message)

/** The user cancelled the job while it ran. */
internal class JobCancelledException : Exception()

/** A copy didn't match its source: it was deleted and the source kept (spec §0 rule 4). */
class VerificationException(message: String) : IOException(message)

/**
 * A source's bytes: its [size] and [md5] (computed only when asked), read at any offset. Reads
 * go on from one open stream while they follow each other, and reopen at a new offset after a
 * jump (an upload that Drive acknowledged short).
 */
internal class Input(
    val size: suspend () -> Long,
    val md5: suspend () -> String?,
    val open: suspend (Long) -> InputStream
) : AutoCloseable {
    private var stream: InputStream? = null
    private var position = 0L

    /** Fills [buffer] from [offset], up to its end or the source's; returns how many bytes. */
    suspend fun read(offset: Long, buffer: ByteArray): Int {
        if (stream == null || position != offset) {
            stream?.close()
            stream = open(offset)
            position = offset
        }
        val input = checkNotNull(stream)
        var filled = 0
        var count = input.read(buffer, 0, buffer.size)
        while (count >= 0) {
            filled += count
            val room = buffer.size - filled
            count = if (room > 0) input.read(buffer, filled, room) else -1
        }
        position += filled
        return filled
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}

internal fun md5Of(input: InputStream): String = sizeAndMd5Of(input).second

/** How many bytes [input] holds, and their MD5. */
internal fun sizeAndMd5Of(input: InputStream): Pair<Long, String> {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(DIGEST_BUFFER)
    var size = 0L
    var count = input.read(buffer)
    while (count >= 0) {
        digest.update(buffer, 0, count)
        size += count
        count = input.read(buffer)
    }
    return size to digest.hex()
}

internal fun File.md5(): String = inputStream().use(::md5Of)

internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

internal fun DriveFile.toRef(account: String) = FileRef(
    source = SourceType.DRIVE,
    name = name,
    mime = mimeType,
    bytes = size?.toLongOrNull() ?: 0,
    account = account,
    fileId = id,
    parentId = parents.firstOrNull(),
    md5 = md5Checksum,
    isFolder = isFolder
)

internal fun DeviceFile.toRef() = FileRef(
    source = SourceType.DEVICE,
    name = name,
    mime = mime,
    bytes = bytes,
    uri = uri,
    volume = volume,
    path = path,
    takenAt = takenAt,
    modifiedAt = modifiedAt
)

private const val DIGEST_BUFFER = 64 * 1024
private const val HTTP_FORBIDDEN = 403
