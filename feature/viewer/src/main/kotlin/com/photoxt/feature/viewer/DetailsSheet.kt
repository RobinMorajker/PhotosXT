package com.photoxt.feature.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.Exposure
import com.photoxt.core.media.GeoPoint
import com.photoxt.core.model.MediaKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The details sheet (spec §4.5): date, size and resolution, camera, lens and exposure, location
 * with a mini map, and where the file is. Editing the date and place comes with the write
 * permissions of Phase 5; sharing status, tags and OCR text with their phases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsSheet(
    item: ViewerItem,
    load: suspend (ViewerItem) -> ViewerDetails,
    onDismiss: () -> Unit
) {
    var details by remember(item.id) { mutableStateOf<ViewerDetails?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(item.id, reload) { details = load(item) }
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { reload++ }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(16.dp))
            BasicFacts(item)
            details?.let { loaded ->
                MediaFacts(loaded, onShowLocation = {
                    askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BasicFacts(item: ViewerItem) {
    val context = LocalContext.current
    val taken = Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault())
    Fact(DesignR.drawable.ic_info, FullDateFormat.format(taken))
    val size = item.displaySize()
    val megapixels = size.width * size.height / MEGA
    val resolution = if (size.isEmpty()) {
        null
    } else {
        stringResource(
            R.string.details_resolution,
            size.width.roundToInt(),
            size.height.roundToInt(),
            megapixels
        )
    }
    val duration = item.durationMs?.takeIf { item.kind == MediaKind.VIDEO }?.let(::formatDuration)
    Fact(
        DesignR.drawable.ic_aspect_ratio,
        listOfNotNull(resolution, Formatter.formatShortFileSize(context, item.bytes), duration)
            .joinToString(SEPARATOR)
    )
}

@Composable
private fun MediaFacts(details: ViewerDetails, onShowLocation: () -> Unit) {
    val context = LocalContext.current
    val media = details.media
    media.camera?.let { camera ->
        Fact(DesignR.drawable.ic_zoom_in, listOfNotNull(camera, media.lens).joinToString(SEPARATOR))
    }
    media.exposure?.let { Fact(DesignR.drawable.ic_speed, describe(it)) }
    val location = media.location
    when {
        location != null -> LocationFact(location)

        media.locationHidden -> TextButton(onClick = onShowLocation) {
            Text(stringResource(R.string.details_show_location))
        }
    }
    details.folder?.let { folder ->
        Fact(DesignR.drawable.ic_open_in_new, readablePath(context, folder, details.item.name))
    }
}

@Composable
private fun LocationFact(point: GeoPoint) {
    val context = LocalContext.current
    val text = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
    Fact(DesignR.drawable.ic_location, text)
    MiniMap(
        point,
        Modifier
            .fillMaxWidth()
            .height(MapHeight)
            .padding(vertical = 8.dp)
    )
    TextButton(onClick = {
        val geo = "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}"
        val open = Intent(Intent.ACTION_VIEW, geo.toUri())
        context.startActivity(Intent.createChooser(open, null))
    }) {
        Text(stringResource(R.string.details_open_in_maps))
    }
}

@Composable
private fun Fact(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** f/1.8 · 1/120 s · ISO 100 · 5.6 mm */
private fun describe(exposure: Exposure): String = listOfNotNull(
    exposure.fNumber?.let { String.format(Locale.ROOT, "ƒ/%.1f", it) },
    exposure.exposureSeconds?.let { seconds ->
        if (seconds >= 1.0) {
            String.format(Locale.ROOT, "%.1f s", seconds)
        } else {
            "1/${(1.0 / seconds).roundToInt()} s"
        }
    },
    exposure.iso?.let { "ISO $it" },
    exposure.focalLengthMm?.let { String.format(Locale.ROOT, "%.1f mm", it) }
).joinToString(SEPARATOR)

/** "Internal storage/DCIM/Camera/photo.jpg", or the SD card's own name in place of the first. */
private fun readablePath(context: Context, folder: String, name: String): String {
    val volume = folder.substringBefore(':')
    val storage = context.getSystemService(StorageManager::class.java)
        ?.storageVolumes
        ?.firstOrNull { it.mediaStoreVolumeName == volume }
        ?.getDescription(context)
    return listOfNotNull(storage, folder.substringAfter(':').trimEnd('/'), name)
        .filter { it.isNotEmpty() }
        .joinToString("/")
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / MILLIS
    return if (seconds >= HOUR) {
        String.format(
            Locale.ROOT,
            "%d:%02d:%02d",
            seconds / HOUR,
            seconds % HOUR / MINUTE,
            seconds % MINUTE
        )
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / MINUTE, seconds % MINUTE)
    }
}

private const val SEPARATOR = " · "
private const val MEGA = 1_000_000f
private const val MILLIS = 1_000L
private const val MINUTE = 60L
private const val HOUR = 3_600L
private val MapHeight = 160.dp
private val FullDateFormat = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.FULL,
    FormatStyle.SHORT
)
