package com.photoxt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.compose.LifecycleStartEffect
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.transfer.canManageMedia
import com.photoxt.core.transfer.manageMediaIntent

/**
 * The Library tab (spec §4.1): its pages, and media management. Pinned, Recent, Places, People,
 * Things and Cleanup join with their phases. The app locks Hidden and the vault again whenever
 * this list shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(title: String, onOpen: (LibraryPage) -> Unit, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    val context = LocalContext.current
    // Granted in system settings, so read again each time the tab shows.
    var manageMedia by remember { mutableStateOf(canManageMedia(context)) }
    LifecycleStartEffect(Unit) {
        manageMedia = canManageMedia(context)
        onStopOrDispose { }
    }
    val manageIntent = remember { manageMediaIntent(context) }
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(title = title, scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(LibraryPage.entries, key = { it.name }) { page ->
                LibraryRow(page.title, page.body, page.icon) { onOpen(page) }
            }
            if (manageIntent != null) {
                item(key = "manage-media") {
                    LibraryRow(
                        R.string.library_manage_media,
                        if (manageMedia) {
                            R.string.library_manage_media_on
                        } else {
                            R.string.library_manage_media_off
                        },
                        DesignR.drawable.ic_lock_open
                    ) { runCatching { context.startActivity(manageIntent) } }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: Int, body: Int, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
        leadingContent = { Icon(painterResource(icon), null) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    )
}

@PreviewLightDark
@Composable
private fun LibraryScreenPreview() {
    PxtTheme {
        Surface { LibraryScreen(title = "Library", onOpen = {}) }
    }
}
