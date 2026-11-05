package com.photoxt.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR

/**
 * Shown instead of a grid while PhotoXT may not read the phone's media (spec §4.3). It asks for
 * access; once the system stops showing the dialog (the user refused twice), it opens the app's
 * settings instead. [onResult] runs after every answer, so the caller can re-read access.
 */
@Composable
fun MediaAccessRequest(
    permissions: Array<String>,
    onResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onResult()
        val refused = results.values.none { it }
        blocked = refused &&
            activity != null &&
            permissions.none { activity.shouldShowRequestPermissionRationale(it) }
    }
    // Scrollable, so the screen's collapsing header still collapses over it.
    LazyColumn(modifier) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_tab_pictures),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = stringResource(R.string.access_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(
                        if (blocked) R.string.access_body_blocked else R.string.access_body
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        if (blocked) context.openAppSettings() else launcher.launch(permissions)
                    }
                ) {
                    Text(
                        stringResource(
                            if (blocked) R.string.access_settings else R.string.access_allow
                        )
                    )
                }
            }
        }
    }
}

/**
 * Android 14+ "selected photos only" access: a banner that stays above the grid (spec §4.3).
 * Manage reopens the system picker to change the selection; Allow all opens the app's settings,
 * because asking again only offers the picker on some phones (seen on One UI 8).
 */
@Composable
fun LimitedAccessBanner(
    permissions: Array<String>,
    onResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.access_limited),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            TextButton(onClick = { launcher.launch(permissions) }) {
                Text(stringResource(R.string.access_limited_manage))
            }
            TextButton(onClick = { context.openAppSettings() }) {
                Text(stringResource(R.string.access_limited_allow_all))
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
