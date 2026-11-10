package com.photoxt.core.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.auth.DriveScope
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.TransferOp
import com.photoxt.core.sync.DeviceConditions
import com.photoxt.core.sync.DriveSync
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts the queue (spec §4.7, §5.3). While PhotoXT is on screen on Android 14+, as a
 * user-initiated data transfer job, which the 6-hour `dataSync` limit of Android 15 doesn't
 * touch; otherwise as WorkManager work in a foreground service. Either one drains the whole
 * queue, so kicking a running queue only makes sure it looks again before it stops.
 */
@Singleton
class TransferScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val conditions: DeviceConditions
) : TransferKick {
    private val kicks = AtomicLong()

    /** Rises with every kick; a drain that saw it change while running goes round again. */
    val kickCount: Long get() = kicks.get()

    override fun kick(visible: Boolean) {
        kicks.incrementAndGet()
        val userInitiated = visible && conditions.online.value &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scheduleJob()
        if (!userInitiated) enqueueWork(delayMs = 0)
    }

    /** Runs the queue again at [at] (a retry's backoff), once there is a network. */
    fun wakeAt(at: Long) = enqueueWork(delayMs = (at - System.currentTimeMillis()).coerceAtLeast(0))

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleJob(): Boolean {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return false
        // Scheduling it again would stop the running one mid-transfer.
        if (jobs.getPendingJob(JOB_ID) != null) return true
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        return runCatching { jobs.schedule(info) == JobScheduler.RESULT_SUCCESS }
            .onFailure { PxtLog.w(TAG, "No user-initiated job; WorkManager instead", it) }
            .getOrDefault(false)
    }

    private fun enqueueWork(delayMs: Long) {
        val later = delayMs > 0
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .apply {
                if (later) {
                    setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                }
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (later) WAKE else RUN,
            if (later) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    companion object {
        /** Below the JobScheduler ids WorkManager may use (`PhotoXtApplication`). */
        const val JOB_ID = 5_005
        private const val TAG = "TransferScheduler"
        private const val RUN = "transfers"
        private const val WAKE = "transfers-wake"
    }
}

/** Starts the queue; [visible] when PhotoXT is on screen. Tests replace it. */
fun interface TransferKick {
    fun kick(visible: Boolean)
}

/** Drains the queue for whichever job runs it, and books the next wake-up for retries. */
@Singleton
class TransferRunner @Inject constructor(
    private val engine: TransferEngine,
    private val scheduler: TransferScheduler,
    private val database: PxtDatabase,
    private val clock: Clock
) {
    suspend fun runAll(onProgress: suspend (TransferProgress?) -> Unit) {
        do {
            val kicks = scheduler.kickCount
            engine.drain { onProgress(it) }
        } while (kicks != scheduler.kickCount)
        database.transferDao().nextDueAt()?.let {
            scheduler.wakeAt(maxOf(it, clock.millis() + MIN_WAKE_MS))
        }
    }

    private companion object {
        const val MIN_WAKE_MS = 1_000L
    }
}

/**
 * The queue as a user-initiated data transfer job (Android 14+, spec §4.7). Its notification
 * shows the progress; if the system stops the job, it is rescheduled and resumes where the jobs
 * saved their progress.
 */
// Its id stays out of the range the app gives WorkManager (`PhotoXtApplication`), which a
// library module's lint can't see.
@SuppressLint("SpecifyJobSchedulerIdRange")
class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val entry = transferEntry()
        val notifications = entry.notifications()
        show(params, notifications.progress(null))
        stopped = false
        work = scope.launch {
            try {
                entry.runner().runAll { show(params, notifications.progress(it)) }
            } finally {
                if (!stopped) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        work?.cancel()
        return true
    }

    private fun show(params: JobParameters, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(
                params,
                TransferNotifications.PROGRESS_ID,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE
            )
        }
    }
}

/** The queue as WorkManager work, in a `dataSync` foreground service when Android allows one. */
class TransferWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = applicationContext.transferEntry()
        val notifications = entry.notifications()
        // Android 12+ may refuse a foreground service started from the background: run anyway.
        runCatching { setForeground(notifications.foreground(null)) }
        entry.runner().runAll { progress ->
            runCatching { setForeground(notifications.foreground(progress)) }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        applicationContext.transferEntry().notifications().foreground(null)
}

/** The transfer notifications (spec §4.7): progress, and "needs you" while PhotoXT is hidden. */
@Singleton
class TransferNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val manager = NotificationManagerCompat.from(context)

    fun progress(progress: TransferProgress?): Notification {
        ensureChannel()
        val title = progress?.let { context.getString(titleOf(it.op), it.title) }
            ?: context.getString(R.string.transfer_notification_working)
        val total = progress?.bytesTotal ?: 0
        val done = progress?.bytesDone ?: 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(
                PROGRESS_MAX,
                if (total > 0) (done * PROGRESS_MAX / total).toInt() else 0,
                total <= 0
            )
            .build()
    }

    fun foreground(progress: TransferProgress?): ForegroundInfo = ForegroundInfo(
        PROGRESS_ID,
        progress(progress),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    /** Some transfers wait for an answer (a permission, a conflict) while PhotoXT is hidden. */
    @SuppressLint("MissingPermission")
    fun needsYou(count: Int) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle(context.getString(R.string.transfer_notification_waiting_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.transfer_notification_waiting,
                    count,
                    count
                )
            )
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(WAITING_ID, notification)
    }

    fun clearNeedsYou() = manager.cancel(WAITING_ID)

    private fun openApp(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.putExtra(EXTRA_OPEN, OPEN_TRANSFERS)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.transfer_channel))
                .build()
        )
    }

    private fun titleOf(op: TransferOp): Int = when (op) {
        TransferOp.COPY -> R.string.transfer_notification_copying
        TransferOp.MOVE -> R.string.transfer_notification_moving
        TransferOp.CONVERT -> R.string.transfer_notification_converting
        else -> R.string.transfer_notification_updating
    }

    companion object {
        const val PROGRESS_ID = 5_005
        const val WAITING_ID = 5_006
        const val CHANNEL = "transfers"

        /** The launch intent's extra that opens the Transfers screen. */
        const val EXTRA_OPEN = "com.photoxt.open"
        const val OPEN_TRANSFERS = "transfers"
        private const val PROGRESS_MAX = 1000
    }
}

/** The app's side of [TransferEnvironment]: Drive scopes, the network, sync after writes. */
class AppTransferEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: DriveAuth,
    private val conditions: DeviceConditions,
    private val sync: DriveSync
) : TransferEnvironment {
    override suspend fun canWriteDrive(account: String): Boolean =
        auth.hasScope(account, DriveScope.FULL)

    override val online: Boolean get() = conditions.online.value

    override val workDir: File get() = File(context.filesDir, "transfers")

    override suspend fun driveWritten(account: String) {
        sync.syncAccount(account)
    }

    override suspend fun trackFolder(account: String, folderId: String, name: String) {
        sync.addFolder(account, folderId, name, driveId = null, merge = false)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface TransferModule {
    @Binds
    fun environment(environment: AppTransferEnvironment): TransferEnvironment

    @Binds
    fun kick(scheduler: TransferScheduler): TransferKick
}

/** How the job service and the worker, made by Android, reach PhotoXT's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TransferEntryPoint {
    fun runner(): TransferRunner

    fun notifications(): TransferNotifications
}

private fun Context.transferEntry(): TransferEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, TransferEntryPoint::class.java)
