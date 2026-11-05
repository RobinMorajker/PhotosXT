package com.photoxt.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.photoxt.core.model.MediaAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of the phone's photos and videos PhotoXT may read. Permissions change outside the app,
 * so call [refresh] after a permission request and whenever the app returns to the foreground.
 */
@Singleton
class MediaAccessMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** The runtime permissions to request on this Android version. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val current = MutableStateFlow(read())
    val state: StateFlow<MediaAccess> = current.asStateFlow()

    fun refresh() {
        current.value = read()
    }

    private fun read(): MediaAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.LIMITED

            else -> MediaAccess.DENIED
        }

        granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL

        else -> MediaAccess.DENIED
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
