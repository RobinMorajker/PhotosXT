package com.photoxt.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photoxt.core.common.PxtLog
import com.photoxt.core.datastore.MobileOriginals
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.media.DriveCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * PhotoXT's background work (spec §5.3) through WorkManager: Drive sync every few hours (6 unless
 * the settings say otherwise, spec §4.21) on a network, or only Wi-Fi, with the battery not low
 * (spec §4.3), the cache cleanup once a day (spec §4.4), and fetching offline pins whenever pins
 * change. Each job is idempotent; WorkManager retries and resumes them.
 */
@Singleton
class SyncWork @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore
) {
    private val manager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Call once, from `Application.onCreate` (through [DriveSync.start]); it runs as long as the
     * app does, so the Drive sync follows its settings as they change.
     */
    suspend fun schedule() {
        manager.enqueueUniquePeriodicWork(
            CACHE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
        )
        settings.settings.map { it.sync.driveHours to it.sync.driveOnMobile }
            .distinctUntilChanged()
            .collect { (hours, onMobile) ->
                manager.enqueueUniquePeriodicWork(
                    SYNC,
                    // A new interval or network rule applies from the next run on.
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<DriveSyncWorker>(hours.toLong(), TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(
                                    if (onMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
                                )
                                .setRequiresBatteryNotLow(true)
                                .build()
                        )
                        .build()
                )
            }
    }

    /**
     * Fetches what offline pins still lack: on Wi-Fi, or on any network when originals may load
     * over mobile data (spec §4.4, data saver). Queued after any running pass.
     */
    suspend fun fetchOffline() {
        val anyNetwork = settings.current().storage.mobileOriginals == MobileOriginals.ALWAYS
        manager.enqueueUniqueWork(
            OFFLINE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OfflineWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED
                        )
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
        )
    }

    private companion object {
        const val SYNC = "drive-sync"
        const val CACHE = "drive-cache-cleanup"
        const val OFFLINE = "drive-offline-pins"
    }
}

/** Every few hours: each account's Drive changes. A failure waits for the next trigger. */
class DriveSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.syncEntry().driveSync().syncAll()
        return Result.success()
    }
}

/** Once a day: retention, caps and orphans (spec §4.4). */
class CacheWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = applicationContext.syncEntry()
        val storage = entry.settings().current().storage
        val freed = entry.driveCache().cleanUp(
            storage.retentionMs,
            storage.cacheCapBytes,
            storage.previewCapBytes
        )
        PxtLog.i(TAG, "Cache cleanup freed ${freed / KIB} KiB")
        return Result.success()
    }

    private companion object {
        const val TAG = "CacheWorker"
        const val KIB = 1024
    }
}

/** Fetches offline pins' files; a failed file is retried later with backoff. */
class OfflineWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.syncEntry().offlinePins().fetchPending()) {
            Result.success()
        } else {
            Result.retry()
        }
}

/** How WorkManager's workers, made by reflection, reach PhotoXT's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncEntryPoint {
    fun driveSync(): DriveSync

    fun driveCache(): DriveCache

    fun offlinePins(): OfflinePins

    fun settings(): SettingsStore
}

private fun Context.syncEntry(): SyncEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
