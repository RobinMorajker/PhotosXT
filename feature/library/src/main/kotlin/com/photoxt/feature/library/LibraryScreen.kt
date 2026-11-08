package com.photoxt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.photoxt.core.designsystem.PxtCollapsingHeader
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.designsystem.R as DesignR

/**
 * The Library tab (spec §4.1). For now it holds Storage (spec §4.4); Favorites, Pinned,
 * Bookmarks, History, Trash, Offline, Transfers, Settings and the rest join with their phases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(title: String, onOpenStorage: () -> Unit, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )
    Column(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        PxtCollapsingHeader(title = title, scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "storage") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_storage)) },
                    supportingContent = { Text(stringResource(R.string.library_storage_body)) },
                    leadingContent = {
                        Icon(painterResource(DesignR.drawable.ic_hard_drive), null)
                    },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenStorage)
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun LibraryScreenPreview() {
    PxtTheme {
        Surface { LibraryScreen(title = "Library", onOpenStorage = {}) }
    }
}
