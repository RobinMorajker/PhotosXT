package com.photoxt.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Resize
import kotlin.math.roundToInt

/**
 * *Save as copy* (spec §4.10, Output): the format and its quality, a file size to stay under,
 * the picture's size (a preset, a percentage or pixels) and whether the metadata comes along.
 * A photo of type [source] that PhotoXT can't write, a GIF say, isn't offered "same as original".
 */
@Composable
internal fun ExportDialog(
    start: ExportOptions,
    source: String,
    onSave: (ExportOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val own = ExportFormat.ofMime(source)
    val formats = ExportFormat.entries.filter { it != ExportFormat.SAME || own != null }
    val first = start.format.takeIf { it in formats } ?: ExportFormat.JPEG
    var format by rememberSaveable { mutableStateOf(first) }
    var quality by rememberSaveable { mutableIntStateOf(start.quality) }
    var maxBytes by rememberSaveable { mutableStateOf(start.maxBytes) }
    var resize by rememberSaveable(stateSaver = ResizeSaver) { mutableStateOf(start.resize) }
    var custom by rememberSaveable { mutableStateOf(start.resize !in RESIZES) }
    var keep by rememberSaveable { mutableStateOf(start.keepMetadata) }
    // PNG has no quality to trade for size, whether asked for or the photo's own.
    val lossy = ExportFormat.written(format, source) != ExportFormat.PNG
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_save_copy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section(R.string.editor_format)
                ChipRow(formats, format, { stringResource(formatLabel(it)) }) { format = it }
                if (lossy) {
                    Quality(quality) { quality = it }
                    Section(R.string.editor_file_size)
                    ChipRow(SIZES, maxBytes, { sizeLabel(it) }) { maxBytes = it }
                }
                Section(R.string.editor_resize)
                SizeChoice(resize, custom) { picked, typed ->
                    resize = picked
                    custom = typed
                }
                KeepMetadata(keep) { keep = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ExportOptions(format, quality, resize, maxBytes.takeIf { lossy }, keep))
                onDismiss()
            }) { Text(stringResource(R.string.editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        }
    )
}

@Composable
private fun Section(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun Quality(quality: Int, onChange: (Int) -> Unit) {
    val name = stringResource(R.string.editor_quality)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        Text("$quality")
    }
    Slider(
        value = quality.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = MIN_QUALITY..MAX_QUALITY,
        modifier = Modifier.semantics { contentDescription = name }
    )
}

/** Full size, a preset, or a size typed in; [onChange] hears the size and whether it was typed. */
@Composable
private fun SizeChoice(resize: Resize, custom: Boolean, onChange: (Resize, Boolean) -> Unit) {
    // The last chip, null, is "Custom".
    ChipRow<Resize?>(RESIZES + null, resize.takeUnless { custom }, { resizeLabel(it) }) {
        if (it == null) onChange(resize, true) else onChange(it, false)
    }
    if (custom) CustomSize(resize) { onChange(it, true) }
}

/** A size typed in: the long edge in pixels, or a percentage. Photos are never enlarged. */
@Composable
private fun CustomSize(start: Resize, onChange: (Resize) -> Unit) {
    var text by rememberSaveable {
        mutableStateOf((start.percent ?: start.longEdge)?.toString().orEmpty())
    }
    var percent by rememberSaveable { mutableStateOf(start.percent != null) }
    fun report() {
        val number = text.toIntOrNull()?.takeIf { it > 0 }
        onChange(
            when {
                number == null -> Resize()
                percent -> Resize(percent = number.coerceAtMost(FULL_PERCENT))
                else -> Resize(longEdge = number)
            }
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter(Char::isDigit).take(MAX_DIGITS)
                report()
            },
            label = { Text(stringResource(R.string.editor_resize)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        listOf(false, true).forEach { unit ->
            FilterChip(
                selected = percent == unit,
                onClick = {
                    percent = unit
                    report()
                },
                label = { Text(stringResource(unitLabel(unit))) }
            )
        }
    }
    Text(
        stringResource(R.string.editor_resize_hint),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun KeepMetadata(keep: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = keep, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = keep, onCheckedChange = null)
        Text(
            stringResource(R.string.editor_keep_metadata),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    if (!keep) {
        Text(
            stringResource(R.string.editor_metadata_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatLabel(format: ExportFormat) = when (format) {
    ExportFormat.SAME -> R.string.format_same
    ExportFormat.JPEG -> R.string.format_jpeg
    ExportFormat.PNG -> R.string.format_png
    ExportFormat.WEBP -> R.string.format_webp
    ExportFormat.HEIC -> R.string.format_heic
    ExportFormat.AVIF -> R.string.format_avif
}

private fun unitLabel(percent: Boolean) =
    if (percent) R.string.editor_unit_percent else R.string.editor_unit_px

@Composable
private fun sizeLabel(bytes: Long?): String = when {
    bytes == null -> stringResource(R.string.editor_size_any)
    bytes < MB -> stringResource(R.string.editor_size_kb, bytes / KB)
    else -> stringResource(R.string.editor_size_mb, bytes / MB)
}

@Composable
private fun resizeLabel(resize: Resize?): String {
    val percent = resize?.percent
    val edge = resize?.longEdge
    return when {
        resize == null -> stringResource(R.string.editor_resize_custom)
        percent != null -> stringResource(R.string.editor_resize_percent, percent)
        edge != null -> stringResource(R.string.editor_resize_edge, edge)
        else -> stringResource(R.string.editor_resize_full)
    }
}

/** A [Resize] kept across a rotation as its two numbers, −1 for neither. */
private val ResizeSaver = Saver<Resize, List<Int>>(
    save = { listOf(it.percent ?: NONE, it.longEdge ?: NONE) },
    restore = { (percent, edge) ->
        Resize(percent.takeIf { it != NONE }, edge.takeIf { it != NONE })
    }
)

private const val NONE = -1
private const val KB = 1_000L
private const val MB = 1_000_000L
private const val FULL_PERCENT = 100
private const val MAX_DIGITS = 5
private const val MIN_QUALITY = 10f
private const val MAX_QUALITY = 100f
private val SIZES = listOf(null, 500 * KB, MB, 2 * MB, 5 * MB)
private val RESIZES = listOf(
    Resize(),
    Resize(percent = 75),
    Resize(percent = 50),
    Resize(percent = 25),
    Resize(longEdge = 4096),
    Resize(longEdge = 2048),
    Resize(longEdge = 1080)
)
