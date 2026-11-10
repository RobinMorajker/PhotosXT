package com.photoxt.app

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.allowHardware
import com.photoxt.core.common.PxtLog
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.DeviceThumbnailFetcher
import com.photoxt.core.media.DeviceThumbnailKeyer
import com.photoxt.core.media.DriveOriginalFetcher
import com.photoxt.core.media.DriveOriginalKeyer
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.DriveThumbnailKeyer
import com.photoxt.core.media.DriveThumbnails
import com.photoxt.core.sync.DriveSync
import com.photoxt.core.transfer.DriveLinkThumbnailFetcher
import com.photoxt.core.transfer.DriveLinkThumbnailKeyer
import com.photoxt.core.transfer.TransferQueue
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class PhotoXtApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    @Inject
    lateinit var deviceMediaSync: DeviceMediaSync

    @Inject
    lateinit var driveSync: DriveSync

    @Inject
    lateinit var driveThumbnails: DriveThumbnails

    @Inject
    lateinit var driveOriginals: DriveOriginals

    @Inject
    lateinit var transferQueue: TransferQueue

    @Inject
    lateinit var driveHttp: DriveHttp

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        deviceMediaSync.start()
        driveSync.start()
        // Resumes transfers a killed process left half done (spec §4.7).
        transferQueue.start()
    }

    /**
     * WorkManager starts on first use with this configuration (its startup initializer is off in
     * the manifest), so tests and early callers never meet an uninitialized WorkManager. Workers
     * reach their dependencies through a Hilt entry point (core/sync `SyncWork`).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            // Clear of the transfer queue's own JobScheduler job (TransferScheduler.JOB_ID).
            .setJobSchedulerJobIdRange(WORK_JOB_IDS_FROM, WORK_JOB_IDS_TO)
            .build()

    /**
     * The one Coil image loader (spec §5.3). Device thumbnails come from MediaStore's cache; Drive
     * thumbnails and full files from PhotoXT's own store, made or downloaded on first use.
     * Hardware bitmaps are off: a fling through the grid creates and frees hundreds of them, and
     * on the Galaxy A15 freeing their graphic buffers stalled the render thread. A full-size
     * image can opt back in on its own request.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .allowHardware(false)
            .components {
                add(DeviceThumbnailKeyer())
                add(DeviceThumbnailFetcher.Factory())
                add(DriveThumbnailKeyer())
                add(DriveThumbnails.Fetcher.Factory(driveThumbnails))
                add(DriveOriginalKeyer())
                add(DriveOriginalFetcher.Factory(driveOriginals))
                // Binned Drive files in Trash (spec §4.7).
                add(DriveLinkThumbnailKeyer())
                add(DriveLinkThumbnailFetcher.Factory(driveHttp))
                // Animated GIF, WebP and HEIF in the viewer (spec §4.5); stills decode as before.
                add(AnimatedImageDecoder.Factory())
            }
            .build()

    /** Debug builds only. Violations go through [PxtLog], which redacts paths and URIs. */
    private fun enableStrictMode() {
        val executor = Executors.newSingleThreadExecutor()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyListener(executor) { PxtLog.w(TAG, "Thread policy violation", it) }
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyListener(executor) { PxtLog.w(TAG, "VM policy violation", it) }
                .build()
        )
    }

    private companion object {
        const val TAG = "StrictMode"

        /** JobScheduler ids WorkManager may use; the transfer job's (5005) is below them. */
        const val WORK_JOB_IDS_FROM = 10_000
        const val WORK_JOB_IDS_TO = 100_000
    }
}
