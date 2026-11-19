package com.photoxt.feature.editor

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.ui.ConfirmDialog
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The controls the tool panels share: sliders, chips, colours, switches and buttons.
 */

/** One choice of several, as chips in a row that scrolls. */
@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                enabled = enabled
            )
        }
    }
}

/**
 * Things of the user's own (presets, LUTs, stickers) as chips: tap one to use it, tap its cross
 * to take it off the list, after asking.
 */
@Composable
internal fun <T> RemovableChips(
    items: List<T>,
    selected: T?,
    name: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onRemove: (T) -> Unit
) {
    var removing by remember { mutableStateOf<T?>(null) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val label = name(item)
            val remove = stringResource(R.string.editor_remove_named, label)
            InputChip(
                selected = item == selected,
                onClick = { onPick(item) },
                label = { Text(label) },
                trailingIcon = {
                    Icon(
                        painterResource(DesignR.drawable.ic_close),
                        contentDescription = remove,
                        modifier = Modifier
                            .size(REMOVE_ICON.dp)
                            .clickable(role = Role.Button) { removing = item }
                    )
                }
            )
        }
    }
    removing?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.editor_remove_named, name(item)),
            body = stringResource(R.string.editor_remove_text),
            confirm = stringResource(R.string.editor_remove),
            onConfirm = {
                removing = null
                onRemove(item)
            },
            onDismiss = { removing = null }
        )
    }
}

/**
 * Colour swatches and a picker (spec §4.10, Add): from anywhere on screen with Android 17's
 * eye dropper, from the photo itself on older versions. A picked colour goes to
 * [DrawActions.picked], which knows what the tool shown colours.
 */
@Composable
internal fun ColorRow(selected: Int?, tools: EditorTools, onSelect: (Int) -> Unit) {
    val eyeDropper = rememberEyeDropper(tools.draw::picked)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SWATCHES.forEach { (color, name) ->
            Swatch(color, stringResource(name), color == selected) { onSelect(color) }
        }
        BarIcon(DesignR.drawable.ic_colorize, R.string.editor_pick_color) {
            // No eye dropper on this phone, or it wouldn't open: pick from the photo.
            if (eyeDropper == null || runCatching(eyeDropper).isFailure) tools.draw.startPicking()
        }
    }
}

@Composable
private fun Swatch(color: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Box(
        Modifier
            .size(TOUCH.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(SWATCH.dp)
                .background(Color(color), CircleShape)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
        )
    }
}

/** Opens Android 17's eye dropper, which hands back a colour; null on older versions. */
@Composable
private fun rememberEyeDropper(onPicked: (Int) -> Unit): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return null
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val picked = result.resultCode == Activity.RESULT_OK && data != null &&
            data.hasExtra(Intent.EXTRA_COLOR)
        if (picked) onPicked(checkNotNull(data).getIntExtra(Intent.EXTRA_COLOR, 0))
    }
    return { launcher.launch(Intent(Intent.ACTION_OPEN_EYE_DROPPER)) }
}

@Composable
internal fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** An icon that stays lit while [checked]: bold, or one of the text alignments. */
@Composable
internal fun ToggleIcon(@DrawableRes icon: Int, label: Int, checked: Boolean, onClick: () -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
internal fun ActionButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(end = 8.dp, top = 4.dp)
    ) { Text(stringResource(label)) }
}

/** Buttons side by side; the row scrolls when large text makes them too wide. */
@Composable
internal fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
internal fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = Color.LightGray,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
internal fun Heading(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

private const val TOUCH = 48
private const val SWATCH = 32
private const val REMOVE_ICON = 24

private val SWATCHES = listOf(
    -1 to R.string.color_white,
    -0x1000000 to R.string.color_black,
    -0xbbbbbc to R.string.color_grey,
    -0x10000 to R.string.color_red,
    -0x6800 to R.string.color_orange,
    -0x100 to R.string.color_yellow,
    -0xff3a00 to R.string.color_green,
    -0xff5501 to R.string.color_blue,
    -0x80ff01 to R.string.color_purple,
    -0x16b8f to R.string.color_pink
)
