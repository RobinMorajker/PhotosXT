package com.photoxt.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.transfer.TransferNotifications
import com.photoxt.core.ui.EXTRA_ALBUM_TITLE
import com.photoxt.core.ui.PrivateLock
import com.photoxt.core.ui.albumOfLink
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A transfer notification was tapped: show Transfers. */
    private var openTransfers by mutableStateOf(false)

    /** A home-screen album shortcut was tapped (spec §4.8): its album id and title. */
    private var openAlbum by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No scrim behind 3-button navigation: the bottom bar already gives the buttons contrast.
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) read(intent)
        setContent {
            PxtTheme {
                // Test tags double as resource ids, so benchmarks can find the grid.
                PhotoXtApp(
                    Modifier.semantics { testTagsAsResourceId = true },
                    open = AppOpen(openTransfers, openAlbum),
                    onOpenHandled = {
                        openTransfers = false
                        openAlbum = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** Leaving the app locks Hidden and the vault again (spec §4.8). */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) PrivateLock.lock()
    }

    private fun read(intent: Intent?) {
        val open = intent?.getStringExtra(TransferNotifications.EXTRA_OPEN)
        if (open == TransferNotifications.OPEN_TRANSFERS) openTransfers = true
        albumOfLink(intent?.data)?.let { albumId ->
            openAlbum = albumId to intent?.getStringExtra(EXTRA_ALBUM_TITLE).orEmpty()
        }
    }
}
