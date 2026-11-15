package com.photoxt.core.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SmartRule
import com.photoxt.core.model.SourceType
import java.time.LocalDate
import java.time.ZoneId

/**
 * *Add to album* (spec §4.8, virtual albums): the virtual albums to put the selection in, and a
 * new one. Nothing is moved.
 */
@Composable
fun PickAlbumDialog(
    albums: List<NamedChoice>,
    onPick: (albumId: String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_album_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item(key = "new") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.add_to_new_album)) },
                        leadingContent = {
                            Icon(
                                painterResource(DesignR.drawable.ic_add),
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onNew)
                    )
                }
                items(albums, key = { it.id }) { album ->
                    ListItem(
                        headlineContent = { Text(album.name) },
                        leadingContent = {
                            Icon(
                                painterResource(DesignR.drawable.ic_photo_library),
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable(role = Role.Button) { onPick(album.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * *Bookmark* (spec §4.8): which collection, a new one or [defaultName] when there is none yet,
 * and a note. [moment] names the video time being bookmarked, if any.
 */
@Composable
fun BookmarkDialog(
    collections: List<NamedChoice>,
    defaultName: String,
    onSave: (collectionId: String?, newCollection: String?, note: String) -> Unit,
    onDismiss: () -> Unit,
    moment: String? = null
) {
    var picked by rememberSaveable { mutableStateOf(collections.firstOrNull()?.id) }
    var newName by rememberSaveable {
        mutableStateOf(if (collections.isEmpty()) defaultName else "")
    }
    var note by rememberSaveable { mutableStateOf("") }
    val makingNew = picked == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (moment != null) {
                    stringResource(R.string.bookmark_moment_title, moment)
                } else {
                    stringResource(R.string.bookmark_title)
                }
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                collections.forEach { collection ->
                    ChoiceRow(collection.name, picked == collection.id) { picked = collection.id }
                }
                ChoiceRow(stringResource(R.string.bookmark_new_collection), makingNew) {
                    picked = null
                }
                if (makingNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.bookmark_collection_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.bookmark_note)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !makingNew || newName.isNotBlank(),
                onClick = {
                    onDismiss()
                    onSave(picked, newName.takeIf { makingNew }, note)
                }
            ) { Text(stringResource(R.string.bookmark_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

/**
 * A smart album's name and rule (spec §4.8): type, years taken, source, camera, size, video
 * length, favorites and having a location. Every condition set must hold.
 */
@Composable
fun SmartAlbumDialog(
    initialName: String,
    initialRule: SmartRule,
    onSave: (name: String, rule: SmartRule) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    var fields by rememberSaveable(stateSaver = SmartFields.Saver) {
        mutableStateOf(SmartFields.of(initialName, initialRule, zone))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart_album_title)) },
        text = { SmartForm(fields) { fields = it } },
        confirmButton = {
            TextButton(
                enabled = fields.name.isNotBlank(),
                onClick = {
                    onDismiss()
                    onSave(fields.name, fields.rule(initialRule.albumIds, zone))
                }
            ) { Text(stringResource(R.string.smart_album_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** The smart album dialog's fields as typed: numbers stay text until saved. */
private data class SmartFields(
    val name: String,
    val kind: MediaKind?,
    val source: SourceType?,
    val fromYear: String,
    val toYear: String,
    val camera: String,
    val minMb: String,
    val minMinutes: String,
    val favorite: Boolean,
    val located: Boolean
) {
    fun rule(albumIds: List<String>, zone: ZoneId) = SmartRule(
        kind = kind,
        source = source,
        from = fromYear.toIntOrNull()?.let { startOfYear(it, zone) },
        until = toYear.toIntOrNull()?.let { startOfYear(it + 1, zone) },
        camera = camera.trim().ifEmpty { null },
        minBytes = minMb.toLongOrNull()?.times(MB),
        minDurationMs = minMinutes.toLongOrNull()?.times(MINUTE_MS),
        favorite = favorite,
        hasLocation = located,
        albumIds = albumIds
    )

    companion object {
        fun of(name: String, rule: SmartRule, zone: ZoneId) = SmartFields(
            name = name,
            kind = rule.kind,
            source = rule.source,
            fromYear = yearOf(rule.from, zone),
            toYear = yearOf(rule.until?.minus(1), zone),
            camera = rule.camera.orEmpty(),
            minMb = rule.minBytes?.let { (it / MB).toString() }.orEmpty(),
            minMinutes = rule.minDurationMs?.let { (it / MINUTE_MS).toString() }.orEmpty(),
            favorite = rule.favorite,
            located = rule.hasLocation
        )

        /** Kept through a rotation as plain values. */
        val Saver: Saver<SmartFields, Any> = listSaver(
            save = {
                listOf(
                    it.name, it.kind?.name, it.source?.name, it.fromYear, it.toYear,
                    it.camera, it.minMb, it.minMinutes, it.favorite, it.located
                )
            },
            restore = {
                SmartFields(
                    name = it[0] as String,
                    kind = (it[1] as String?)?.let(MediaKind::valueOf),
                    source = (it[2] as String?)?.let(SourceType::valueOf),
                    fromYear = it[3] as String,
                    toYear = it[4] as String,
                    camera = it[5] as String,
                    minMb = it[6] as String,
                    minMinutes = it[7] as String,
                    favorite = it[8] as Boolean,
                    located = it[9] as Boolean
                )
            }
        )
    }
}

/** Type, source, years taken, size, video length, camera, favorites and a location. */
@Composable
private fun SmartForm(fields: SmartFields, onChange: (SmartFields) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = fields.name,
            onValueChange = { onChange(fields.copy(name = it)) },
            label = { Text(stringResource(R.string.smart_album_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Segments(
            listOf(null, MediaKind.IMAGE, MediaKind.VIDEO),
            fields.kind,
            { onChange(fields.copy(kind = it)) },
            listOf(R.string.smart_any, R.string.smart_photos, R.string.smart_videos)
        )
        Segments(
            listOf(null, SourceType.DEVICE, SourceType.DRIVE),
            fields.source,
            { onChange(fields.copy(source = it)) },
            listOf(R.string.smart_anywhere, R.string.smart_phone, R.string.smart_drive)
        )
        SmartNumbers(fields, onChange)
        OutlinedTextField(
            value = fields.camera,
            onValueChange = { onChange(fields.copy(camera = it)) },
            label = { Text(stringResource(R.string.smart_camera)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        CheckRow(stringResource(R.string.smart_favorites), fields.favorite) {
            onChange(fields.copy(favorite = it))
        }
        CheckRow(stringResource(R.string.smart_located), fields.located) {
            onChange(fields.copy(located = it))
        }
    }
}

/** Years taken, smallest size and shortest video, typed as numbers. */
@Composable
private fun SmartNumbers(fields: SmartFields, onChange: (SmartFields) -> Unit) {
    Row {
        NumberField(
            fields.fromYear,
            { onChange(fields.copy(fromYear = it)) },
            R.string.smart_from_year,
            Modifier.weight(1f)
        )
        NumberField(
            fields.toYear,
            { onChange(fields.copy(toYear = it)) },
            R.string.smart_to_year,
            Modifier.weight(1f)
        )
    }
    Row {
        NumberField(
            fields.minMb,
            { onChange(fields.copy(minMb = it)) },
            R.string.smart_min_mb,
            Modifier.weight(1f)
        )
        NumberField(
            fields.minMinutes,
            { onChange(fields.copy(minMinutes = it)) },
            R.string.smart_min_minutes,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun <T> Segments(
    options: List<T?>,
    selected: T?,
    onSelect: (T?) -> Unit,
    labels: List<Int>
) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) { Text(stringResource(labels[index]), maxLines = 1) }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(MAX_DIGITS)) },
        label = { Text(stringResource(label), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.padding(top = 4.dp, end = 4.dp)
    )
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun yearOf(millis: Long?, zone: ZoneId): String =
    millis?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).year.toString() }.orEmpty()

private fun startOfYear(year: Int, zone: ZoneId): Long =
    LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

private const val MB = 1_000_000L
private const val MINUTE_MS = 60_000L
private const val MAX_DIGITS = 6

/**
 * *Pin to home screen* (spec §4.8): asks the launcher for a shortcut that opens album [albumId],
 * with its cover as the icon. False when the launcher can't pin shortcuts.
 */
suspend fun requestAlbumShortcut(
    context: Context,
    albumId: String,
    title: String,
    cover: Any?
): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return false
    val intent = Intent(launch).apply {
        action = Intent.ACTION_VIEW
        data = albumLink(albumId)
        putExtra(EXTRA_ALBUM_TITLE, title)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val bitmap = cover?.let { model ->
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(SHORTCUT_PX)
                .allowHardware(false)
                .build()
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
        }.getOrNull()
    }
    val icon = bitmap?.let { IconCompat.createWithBitmap(it.squared()) }
        ?: IconCompat.createWithResource(context, DesignR.drawable.ic_photo_library)
    val shortcut = ShortcutInfoCompat.Builder(context, "album:$albumId")
        .setShortLabel(title.ifEmpty { context.getString(R.string.shortcut_album) })
        .setIcon(icon)
        .setIntent(intent)
        .build()
    return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

/** `photoxt://album/<id>`: the link a home-screen shortcut opens (spec §4.1, deep links). */
fun albumLink(albumId: String) = "photoxt://album/${android.net.Uri.encode(albumId)}".toUri()

/** The album id of a [albumLink], or null for any other link. */
fun albumOfLink(link: android.net.Uri?): String? =
    link?.takeIf { it.scheme == "photoxt" && it.host == "album" }?.lastPathSegment

const val EXTRA_ALBUM_TITLE = "com.photoxt.album_title"
private const val SHORTCUT_PX = 192

private fun Bitmap.squared(): Bitmap {
    val side = minOf(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
