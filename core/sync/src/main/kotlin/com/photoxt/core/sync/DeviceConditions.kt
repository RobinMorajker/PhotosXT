package com.photoxt.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The network and battery facts Drive work depends on (spec §4.1 status chips, §4.3). */
@Singleton
class DeviceConditions @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val connected = MutableStateFlow(true)

    /** Whether the phone has a network with internet. */
    val online: StateFlow<Boolean> = connected.asStateFlow()

    /** Call once, from `Application.onCreate` (through [DriveSync.start]). */
    fun start() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connected.value = connectivity.activeNetwork != null
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    connected.value =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    connected.value = false
                }
            }
        )
    }

    /**
     * Whether background thumbnail work should wait: on a metered network or in battery saver.
     * Visible thumbnails never wait.
     */
    fun pauseBackgroundWork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        // ponytail: fixed defaults; the setting that changes them comes with Settings (Phase 6).
        return connectivity?.isActiveNetworkMetered == true || power?.isPowerSaveMode == true
    }
}
