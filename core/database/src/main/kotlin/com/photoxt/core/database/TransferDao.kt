package com.photoxt.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import kotlinx.coroutines.flow.Flow

/** The transfer queue and the clipboard (spec §4.7, §5.4). */
@Dao
interface TransferDao {
    @Insert
    suspend fun insertBatch(batch: TransferBatchEntity): Long

    @Insert
    suspend fun insertJobs(jobs: List<TransferJobEntity>)

    @Insert
    suspend fun insertJob(job: TransferJobEntity): Long

    /** Queues [jobs] as one new batch; returns its id. */
    @Transaction
    suspend fun enqueue(batch: TransferBatchEntity, jobs: List<TransferJobEntity>): Long {
        val id = insertBatch(batch)
        insertJobs(jobs.map { it.copy(batchId = id) })
        return id
    }

    @Query("SELECT * FROM transfer_batch WHERE id = :id")
    suspend fun batch(id: Long): TransferBatchEntity?

    @Update
    suspend fun updateBatch(batch: TransferBatchEntity)

    @Query("SELECT * FROM transfer_job WHERE id = :id")
    suspend fun job(id: Long): TransferJobEntity?

    @Query("SELECT * FROM transfer_job WHERE batchId = :batchId ORDER BY id")
    suspend fun jobs(batchId: Long): List<TransferJobEntity>

    @Query("SELECT * FROM transfer_job WHERE batchId = :batchId ORDER BY id")
    fun jobsFlow(batchId: Long): Flow<List<TransferJobEntity>>

    @Update
    suspend fun updateJob(job: TransferJobEntity)

    /** Claims queued job [id] for running; 0 when it was cancelled or claimed meanwhile. */
    @Query(
        "UPDATE transfer_job SET state = 'RUNNING', updatedAt = :at " +
            "WHERE id = :id AND state = 'QUEUED'"
    )
    suspend fun claim(id: Long, at: Long): Int

    /** Saves a running job's progress; 0 when it was cancelled meanwhile, so it stops. */
    @Query(
        "UPDATE transfer_job SET bytesDone = :bytesDone, bytesTotal = :bytesTotal, " +
            "progress = :progress, updatedAt = :at WHERE id = :id AND state = 'RUNNING'"
    )
    suspend fun saveProgress(
        id: Long,
        bytesDone: Long,
        bytesTotal: Long,
        progress: String?,
        at: Long
    ): Int

    /** Where a running job ended up; a cancel that came first wins. */
    @Query(
        """
        UPDATE transfer_job SET state = :state, waitFor = :waitFor, error = :error,
            result = COALESCE(:result, result), attempts = :attempts,
            nextAttemptAt = :nextAttemptAt, updatedAt = :at
        WHERE id = :id AND state = 'RUNNING'
        """
    )
    suspend fun settle(
        id: Long,
        state: TransferState,
        waitFor: WaitFor?,
        error: String?,
        result: String?,
        attempts: Int,
        nextAttemptAt: Long,
        at: Long
    ): Int

    /** The next job to run: queued, due, in a batch that isn't paused; oldest batch first. */
    @Query(
        """
        SELECT j.* FROM transfer_job j JOIN transfer_batch b ON b.id = j.batchId
        WHERE j.state = 'QUEUED' AND b.paused = 0 AND j.nextAttemptAt <= :now
        ORDER BY j.batchId, j.id LIMIT 1
        """
    )
    suspend fun nextRunnable(now: Long): TransferJobEntity?

    /** When the next queued job is due, or null when none is queued. */
    @Query(
        """
        SELECT MIN(j.nextAttemptAt) FROM transfer_job j JOIN transfer_batch b ON b.id = j.batchId
        WHERE j.state = 'QUEUED' AND b.paused = 0
        """
    )
    suspend fun nextDueAt(): Long?

    /** Whether any job is queued or running, paused batches aside. */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM transfer_job j JOIN transfer_batch b ON b.id = j.batchId
            WHERE j.state IN ('QUEUED', 'RUNNING') AND b.paused = 0)
        """
    )
    fun busy(): Flow<Boolean>

    /** Jobs that were running when the process died: they resume from their saved progress. */
    @Query("UPDATE transfer_job SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun requeueRunning(): Int

    /** Every job waiting for the user, oldest first: the app asks for them while visible. */
    @Query("SELECT * FROM transfer_job WHERE state = 'WAITING' ORDER BY batchId, id")
    fun waiting(): Flow<List<TransferJobEntity>>

    @Query(
        "UPDATE transfer_job SET state = 'QUEUED', waitFor = NULL, nextAttemptAt = 0, " +
            "updatedAt = :at WHERE id IN (:ids) AND state = 'WAITING'"
    )
    suspend fun release(ids: List<Long>, at: Long)

    @Query(
        "UPDATE transfer_job SET state = :state, waitFor = NULL, error = :error, " +
            "updatedAt = :at WHERE id IN (:ids) AND state = 'WAITING'"
    )
    suspend fun settleWaiting(ids: List<Long>, state: TransferState, error: String?, at: Long)

    /** The user's answer to a name conflict for [ids]; they queue again. */
    @Query(
        "UPDATE transfer_job SET conflictPolicy = :policy, state = 'QUEUED', waitFor = NULL, " +
            "nextAttemptAt = 0, updatedAt = :at WHERE id IN (:ids) AND state = 'WAITING'"
    )
    suspend fun resolve(ids: List<Long>, policy: ConflictPolicy, at: Long)

    /** *Apply to all*: the batch's later conflicts take [policy] without asking. */
    @Query("UPDATE transfer_batch SET conflictPolicy = :policy WHERE id = :batchId")
    suspend fun setBatchPolicy(batchId: Long, policy: ConflictPolicy)

    @Query(
        "SELECT id FROM transfer_job WHERE batchId = :batchId AND state = 'WAITING' " +
            "AND waitFor = :waitFor"
    )
    suspend fun waitingIn(batchId: Long, waitFor: WaitFor): List<Long>

    @Query("SELECT * FROM transfer_job WHERE state = 'WAITING' AND waitFor = :waitFor")
    suspend fun jobsWaiting(waitFor: WaitFor): List<TransferJobEntity>

    @Query("UPDATE transfer_batch SET paused = :paused WHERE id = :batchId")
    suspend fun setPaused(batchId: Long, paused: Boolean)

    /** Cancels what hasn't run yet; a running job notices at its next step. */
    @Query(
        "UPDATE transfer_job SET state = 'CANCELLED', waitFor = NULL, updatedAt = :at " +
            "WHERE batchId = :batchId AND state IN ('QUEUED', 'WAITING', 'RUNNING')"
    )
    suspend fun cancel(batchId: Long, at: Long)

    @Query(
        "UPDATE transfer_job SET state = 'QUEUED', attempts = 0, error = NULL, " +
            "nextAttemptAt = 0, updatedAt = :at WHERE batchId = :batchId AND state = 'FAILED'"
    )
    suspend fun retryFailed(batchId: Long, at: Long)

    /** Marks [batchId] finished once none of its jobs can still run. */
    @Query(
        """
        UPDATE transfer_batch SET finishedAt = :at WHERE id = :batchId AND finishedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM transfer_job WHERE batchId = :batchId
                AND state IN ('QUEUED', 'RUNNING', 'WAITING'))
        """
    )
    suspend fun finishIfDone(batchId: Long, at: Long): Int

    /** A batch that took jobs back (a retry) is no longer finished. */
    @Query("UPDATE transfer_batch SET finishedAt = NULL WHERE id = :batchId")
    suspend fun reopen(batchId: Long)

    /** The Transfers screen: the latest batches with their jobs counted. */
    @Query(
        """
        SELECT b.id, b.op, b.title, b.paused, b.createdAt, b.finishedAt, b.undoOf, b.undone,
            COUNT(j.id) AS total,
            COALESCE(SUM(j.state = 'DONE'), 0) AS done,
            COALESCE(SUM(j.state = 'FAILED'), 0) AS failed,
            COALESCE(SUM(j.state = 'SKIPPED'), 0) AS skipped,
            COALESCE(SUM(j.state = 'WAITING'), 0) AS waiting,
            COALESCE(SUM(j.state = 'CANCELLED'), 0) AS cancelled,
            COALESCE(SUM(j.bytesDone), 0) AS bytesDone,
            COALESCE(SUM(j.bytesTotal), 0) AS bytesTotal
        FROM transfer_batch b LEFT JOIN transfer_job j ON j.batchId = b.id
        GROUP BY b.id ORDER BY b.createdAt DESC, b.id DESC LIMIT :limit
        """
    )
    fun batches(limit: Int = BATCH_LIMIT): Flow<List<TransferBatchRow>>

    @Query("DELETE FROM transfer_batch WHERE finishedAt IS NOT NULL")
    suspend fun clearFinished()

    @Query("SELECT * FROM clipboard WHERE id = 0")
    fun clipboard(): Flow<ClipboardEntity?>

    @Query("SELECT * FROM clipboard WHERE id = 0")
    suspend fun currentClipboard(): ClipboardEntity?

    @Upsert
    suspend fun setClipboard(clipboard: ClipboardEntity)

    @Query("DELETE FROM clipboard")
    suspend fun clearClipboard()

    companion object {
        const val BATCH_LIMIT = 100
    }
}

/**
 * What file operations read about albums and items (spec §4.7). Paths are compared with `substr`,
 * not `LIKE`, because folder names may hold `%` and `_`.
 */
@Dao
interface FileOpsDao {
    @Query("SELECT * FROM album WHERE id = :id")
    suspend fun album(id: String): AlbumEntity?

    /** At most 999 ids per call. */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun media(ids: List<String>): List<MediaEntity>

    /** Device folders at or below `<volume>:<path>` ([sourceRef]), the folder itself first. */
    @Query(
        """
        SELECT * FROM album WHERE type = 'DEVICE_FOLDER'
            AND substr(sourceRef, 1, length(:sourceRef)) = :sourceRef
        ORDER BY length(sourceRef), sourceRef
        """
    )
    suspend fun deviceAlbumsUnder(sourceRef: String): List<AlbumEntity>

    /** At most 999 ids per call. */
    @Query("SELECT * FROM media WHERE albumId IN (:albumIds) ORDER BY takenAt DESC, id DESC")
    suspend fun mediaIn(albumIds: List<String>): List<MediaEntity>

    /** Drive items of [accountId] in the folders [folderIds]. At most 900 ids per call. */
    @Query(
        "SELECT * FROM media WHERE accountId = :accountId AND remoteParentId IN (:folderIds) " +
            "ORDER BY takenAt DESC, id DESC"
    )
    suspend fun driveMediaIn(accountId: String, folderIds: List<String>): List<MediaEntity>

    /** The tracked Drive folder of album [albumId], if it is one. */
    @Query("SELECT * FROM tracked_folder WHERE albumId = :albumId")
    suspend fun tracked(albumId: String): TrackedFolderEntity?

    @Query("SELECT * FROM drive_folder WHERE accountId = :accountId")
    suspend fun driveFolders(accountId: String): List<DriveFolderEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Query("SELECT * FROM account ORDER BY addedAt")
    suspend fun accounts(): List<AccountEntity>

    /**
     * A new, still empty phone album (spec §4.7, *New album*): MediaStore shows a folder only once
     * it holds an item, so PhotoXT keeps the row ([AlbumEntity.pending]) until then.
     */
    @Upsert
    suspend fun savePendingAlbum(album: AlbumEntity)
}
