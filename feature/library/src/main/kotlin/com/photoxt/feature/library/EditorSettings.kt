package com.photoxt.feature.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/** The editor's settings (spec §4.10): the space the object eraser's model takes. */
@Composable
internal fun EditorSettings(onDisk: Long, onDelete: () -> Unit) {
    if (onDisk <= 0) return
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.settings_editor))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_models_delete)) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.settings_models_size,
                    Formatter.formatShortFileSize(context, onDisk)
                )
            )
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onDelete)
    )
}
