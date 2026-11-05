package com.photoxt.feature.viewer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.photoxt.core.media.GeoPoint

/**
 * A small, static map of where a photo was taken (spec §4.5), in Google Maps' lite mode. It needs
 * a Maps API key in the app's manifest; without one (see the README) nothing is drawn and the
 * details sheet offers its "Open in Maps" button alone.
 */
@Composable
internal fun MiniMap(point: GeoPoint, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasKey = remember { context.hasMapsKey() }
    if (!hasKey) return
    val position = LatLng(point.latitude, point.longitude)
    val camera = rememberCameraPositionState {
        this.position = CameraPosition.fromLatLngZoom(position, ZOOM)
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false)
    ) {
        Marker(state = rememberUpdatedMarkerState(position = position))
    }
}

private fun Context.hasMapsKey(): Boolean {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }
    return !info.metaData?.getString(MAPS_KEY).isNullOrBlank()
}

private const val MAPS_KEY = "com.google.android.geo.API_KEY"
private const val ZOOM = 14f
