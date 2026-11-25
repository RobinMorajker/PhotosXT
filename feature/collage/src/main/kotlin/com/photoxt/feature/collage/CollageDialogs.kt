package com.photoxt.feature.collage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.render.CollageSizing
import com.photoxt.core.transfer.SourceCounts

/**
 * Export (spec §4.11): JPEG, PNG or WebP at a quality, a size up to 8K, the album (the first
 * photo's, [start], unless another of [albums] is picked), and *delete sources*, off by
 * default, saying how many of the photos are on this phone and how many in Drive ([counts]).
 */
@Composable
internal fun ExportDialog(
    ui: CollageUi,
    albums: List<AlbumSummary>,
    start: Pair<String, String>?,
    counts: SourceCounts?,
    onExport: (CollageOutput, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sizes = sizesFor(ui)
    var format by rememberSaveable { mutableStateOf(ExportFormat.JPEG) }
    var quality by rememberSaveable { mutableStateOf(QUALITY.toFloat()) }
    // The canvas's own size when it has one, else 4K.
    var size by rememberSaveable {
        mutableStateOf(sizes.firstOrNull { it == 0 } ?: CollageOutput.LONG_EDGE_4K)
    }
    var album by rememberSaveable { mutableStateOf(start) }
    var delete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(start) { if (album == null) album = start }
    val output = CollageOutput(format, quality.toInt(), size.takeIf { it > 0 })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collage_export_title)) },
        text = {
            Column(
                Modifier.heightIn(max = DIALOG_MAX_DP.dp).verticalScroll(rememberScrollState())
            ) {
                Heading(R.string.collage_format)
                ChipRow(CollageOutput.FORMATS, format, { it.name }) { format = it }
                if (format != ExportFormat.PNG) {
                    SliderRow(
                        R.string.collage_quality,
                        quality,
                        { quality = it },
                        {},
                        MIN_QUALITY..MAX_QUALITY,
                        zero = QUALITY.toFloat(),
                        shown = {
                            "%d".format(it.toInt())
                        }
                    )
                }
                Heading(R.string.collage_size)
                ChipRow(sizes, size, { sizeLabel(ui, it) }) { size = it }
                Heading(R.string.collage_save_to)
                AlbumPicker(albums, album) { album = it }
                DeleteSources(counts, delete) { delete = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    album?.let { (id, title) -> onExport(output, id, title, delete) }
                },
                enabled = album != null
            ) { Text(stringResource(R.string.collage_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.collage_cancel)) }
        }
    )
}

/**
 * The long edges on offer, smallest first; 0 is the canvas's own size (print sizes, custom
 * pixels, a stitch).
 */
private fun sizesFor(ui: CollageUi): List<Int> = when {
    ui.mode == CollageMode.STITCH ||
        ui.project.canvas.aspect == CanvasAspect.AUTO ||
        ui.project.canvas.aspect.isPixels ->
        listOf(0, CollageOutput.LONG_EDGE_HD, CollageOutput.LONG_EDGE_4K)

    else -> listOf(
        CollageOutput.LONG_EDGE_HD,
        CollageOutput.LONG_EDGE_2K,
        CollageOutput.LONG_EDGE_4K,
        CollageOutput.LONG_EDGE_8K
    )
}

@Composable
private fun sizeLabel(ui: CollageUi, longEdge: Int): String {
    val px = CollageSizing.size(
        ui.project,
        CollageOutput(
            longEdge = longEdge.takeIf {
                it > 0
            }
        )
    ) { ui.size(it) }
    val name = when (longEdge) {
        0 -> stringResource(R.string.collage_size_own)
        CollageOutput.LONG_EDGE_8K -> "8K"
        CollageOutput.LONG_EDGE_4K -> "4K"
        CollageOutput.LONG_EDGE_2K -> "2K"
        else -> "HD"
    }
    return stringResource(R.string.collage_size_choice, name, px.width, px.height)
}

@Composable
private fun AlbumPicker(
    albums: List<AlbumSummary>,
    picked: Pair<String, String>?,
    onPick: (Pair<String, String>) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(picked?.second ?: stringResource(R.string.collage_pick_album), maxLines = 1)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        albums.forEach { album ->
            val drive = album.type == AlbumType.DRIVE_FOLDER
            DropdownMenuItem(
                text = {
                    Text(
                        if (drive) {
                            stringResource(
                                R.string.collage_album_drive,
                                album.title
                            )
                        } else {
                            album.title
                        }
                    )
                },
                onClick = {
                    open = false
                    onPick(album.id to album.title)
                }
            )
        }
    }
}

@Composable
private fun DeleteSources(counts: SourceCounts?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val total = (counts?.phone ?: 0) + (counts?.drive ?: 0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 8.dp, top = 2.dp)
        )
        Column {
            Text(
                pluralStringResource(R.plurals.collage_delete_sources, total, total),
                style = MaterialTheme.typography.bodyLarge
            )
            if (counts != null) {
                Text(
                    stringResource(
                        R.string.collage_delete_sources_where,
                        counts.phone,
                        counts.drive
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                stringResource(R.string.collage_delete_sources_after),
                style = MaterialTheme.typography.bodySmall
            )
            if ((counts?.drive ?: 0) > 0) {
                Text(
                    stringResource(R.string.collage_delete_sources_drive),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Text to add or change (spec §4.11, Extras: text). */
@Composable
internal fun TextDialog(
    initial: String,
    title: Int,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.collage_text)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(text)
            }, enabled = text.isNotBlank()) { Text(stringResource(R.string.collage_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.collage_cancel)) }
        }
    )
}

/** Custom pixels (spec §4.11, Canvas): width and height, up to 8K each. */
@Composable
internal fun CustomSizeDialog(
    width: Int,
    height: Int,
    onDone: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var w by rememberSaveable { mutableStateOf(width.toString()) }
    var h by rememberSaveable { mutableStateOf(height.toString()) }
    val pw = w.toIntOrNull()?.takeIf { it in MIN_PIXELS..CollageOutput.LONG_EDGE_8K }
    val ph = h.toIntOrNull()?.takeIf { it in MIN_PIXELS..CollageOutput.LONG_EDGE_8K }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collage_custom_title)) },
        text = {
            Column {
                Hint(R.string.collage_custom_hint)
                PixelField(R.string.collage_width, w) { w = it }
                PixelField(R.string.collage_height, h) { h = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pw != null &&
                    ph != null
                ) {
                    onDone(pw, ph)
                }
            }, enabled = pw != null && ph != null) {
                Text(stringResource(R.string.collage_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.collage_cancel)) }
        }
    )
}

@Composable
private fun PixelField(label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(DIGITS)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

private const val QUALITY = 92
private const val MIN_QUALITY = 50f
private const val MAX_QUALITY = 100f
private const val DIALOG_MAX_DP = 480
private const val MIN_PIXELS = 64
private const val DIGITS = 4
