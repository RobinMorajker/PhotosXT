package com.photoxt.app

import android.app.Application
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.allowHardware
import com.photoxt.core.common.PxtLog
import com.photoxt.core.media.DeviceMediaSync
import com.photoxt.core.media.DeviceThumbnailFetcher
import com.photoxt.core.media.DeviceThumbnailKeyer
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class PhotoXtApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var deviceMediaSync: DeviceMediaSync

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        deviceMediaSync.start()
    }

    /**
     * The one Coil image loader (spec §5.3). Device thumbnails come from MediaStore's cache.
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
    }
}
