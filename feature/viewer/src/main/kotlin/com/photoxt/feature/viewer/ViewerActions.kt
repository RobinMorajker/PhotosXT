package com.photoxt.feature.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.ImageTraits
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.ViewerStart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The bars' actions for the [current] item; [other] is the photo Compare pairs it with. */
@Composable
internal fun chromeActions(
    context: Context,
    viewModel: ViewerViewModel,
    page: Int,
    current: ViewerItem?,
    other: ViewerItem?,
    traits: ImageTraits?,
    close: () -> Unit,
    navigation: ViewerNavigation,
    onDetails: (ViewerItem) -> Unit,
    onMotion: (String) -> Unit
): ChromeActions {
    val start = viewModel.start
    val uri = current?.contentUri
    val drive = current?.takeIf { it.source == SourceType.DRIVE }
    val pinned = drive?.let { item ->
        remember(item.id) { viewModel.pinned(item.id) }.collectAsStateWithLifecycle(false).value
    }
    return ChromeActions(
        onBack = close,
        onDisplayMode = viewModel::setDisplayMode,
        onZoomLock = viewModel::setZoomLock,
        onShare = { current?.let(context::share) },
        onDetails = { current?.let(onDetails) },
        onSlideshow = {
            current?.let {
                navigation.onSlideshow(ViewerStart(start.albumId, start.filter, page, it.id))
            }
        },
        onCompare = if (current != null && other != null) {
            { navigation.onCompare(current.id, other.id) }
        } else {
            null
        },
        onOpenWith = { current?.let(context::openWith) },
        onSetAs = { current?.let(context::setAs) },
        onCopy = { current?.let(context::copyToClipboard) },
        onPlay = current?.takeIf {
            it.kind == MediaKind.VIDEO
        }?.let { { navigation.onPlay(it.id) } },
        onMotion = if (current != null && traits?.motionVideo != null) {
            { onMotion(current.id) }
        } else {
            null
        },
        onPanorama = traits?.panorama?.let { panorama ->
            uri?.let { { navigation.onPanorama(it, panorama) } }
        },
        offline = pinned,
        onOffline = { on -> drive?.let { viewModel.setPinned(it, on) } }
    )
}

/** What TalkBack says for a page: "Photo, 5 Nov 2026, 10:42". */
@Composable
internal fun describeItem(item: ViewerItem): String {
    val taken = Instant.ofEpochMilli(item.takenAt).atZone(ZoneId.systemDefault())
    val date = SpokenDate.format(taken)
    return when (item.kind) {
        MediaKind.IMAGE -> stringResource(R.string.describe_photo, date)
        MediaKind.VIDEO -> stringResource(R.string.describe_video, date)
    }
}

private val SpokenDate = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.MEDIUM,
    FormatStyle.SHORT
)
