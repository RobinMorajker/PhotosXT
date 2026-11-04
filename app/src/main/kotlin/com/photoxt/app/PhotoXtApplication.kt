package com.photoxt.app

import android.app.Application
import android.os.StrictMode
import com.photoxt.core.common.PxtLog
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executors

@HiltAndroidApp
class PhotoXtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
    }

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
