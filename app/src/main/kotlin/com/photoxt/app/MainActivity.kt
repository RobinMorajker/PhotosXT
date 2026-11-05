package com.photoxt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.photoxt.core.designsystem.PxtTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No scrim behind 3-button navigation: the bottom bar already gives the buttons contrast.
        window.isNavigationBarContrastEnforced = false
        setContent {
            PxtTheme {
                // Test tags double as resource ids, so benchmarks can find the grid.
                PhotoXtApp(Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }
}
