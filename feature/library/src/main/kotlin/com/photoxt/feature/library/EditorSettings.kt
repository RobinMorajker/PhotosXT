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
import com.photoxt.core.ml.EraserModel

/** The editor's settings (spec §4.10): the object eraser's model, and the space models take. */
@Composable
internal fun EditorSettings(
    model: EraserModel,
    onDisk: Long,
    onModel: (EraserModel) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.settings_editor))
    Choice(
        label = stringResource(R.string.settings_eraser_model),
        value = model,
        choices = EraserModel.entries,
        text = { m ->
            val size = Formatter.formatShortFileSize(context, m.bytes)
            stringResource(
                if (m ==
                    EraserModel.MIGAN
                ) {
                    R.string.settings_model_migan
                } else {
                    R.string.settings_model_lama
                },
                size
            )
        },
        onChoose = onModel
    )
    if (onDisk > 0) {
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
}
