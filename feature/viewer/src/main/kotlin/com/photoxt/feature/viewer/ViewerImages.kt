package com.photoxt.feature.viewer

import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.DeviceThumbnail
import com.photoxt.core.media.DriveOriginalRef
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.deviceThumbnailKey
import com.photoxt.core.media.driveFullKey
import com.photoxt.core.media.driveThumbnailKey
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.ui.HeroLaunch
import java.io.File

/** The item's size as shown, with its rotation applied; zero when MediaStore does not know it. */
internal fun ViewerItem.displaySize(): Size {
    val sideways = rotation % HALF_TURN != 0
    val w = (if (sideways) height else width).toFloat()
    val h = (if (sideways) width else height).toFloat()
    return Size(w, h)
}

/** The item as a media source sees it. */
internal fun ViewerItem.ref() =
    MediaRef(id, source, contentUri, modifiedAt, accountId, remoteId, remoteVersion)

/** The grid thumbnail's memory-cache key, so the full photo can show it while it loads. */
internal fun ViewerItem.thumbnailKey(): MemoryCache.Key? = when (source) {
    SourceType.DEVICE -> contentUri?.let { MemoryCache.Key(deviceThumbnailKey(it, modifiedAt)) }

    SourceType.DRIVE -> drive { fileId, version ->
        MemoryCache.Key(driveThumbnailKey(fileId, version))
    }
}

/** A video's poster frame (or any item's grid thumbnail), at the size the layout asks. */
internal fun ViewerItem.poster(): Any? = thumbnailOf(ref())

/** [block] with a Drive item's file id and version, or null for other items. */
internal inline fun <T> ViewerItem.drive(block: (fileId: String, version: Long) -> T): T? {
    val fileId = remoteId ?: return null
    val version = remoteVersion ?: return null
    return if (source == SourceType.DRIVE) block(fileId, version) else null
}

/**
 * The full photo. Decoded to [size] when given (the flight and the slideshow), otherwise to the
 * size of the view showing it. [modifiedAt][ViewerItem.modifiedAt] (a Drive item's version) is in
 * the cache key, so an edited photo never shows its old pixels. The grid's thumbnail shows until
 * it arrives. A Drive photo comes from its cached [file], or is downloaded first.
 */
internal fun fullImageRequest(
    context: Context,
    item: ViewerItem,
    size: IntSize? = null,
    hardware: Boolean = false,
    file: File? = null
): ImageRequest = ImageRequest.Builder(context)
    .data(
        file ?: item.contentUri
            ?: item.drive { id, version -> DriveOriginalRef(item.id, id, version) }
    )
    .memoryCacheKey(
        item.contentUri?.let { deviceThumbnailKey(it, item.modifiedAt) + FULL }
            ?: item.drive { id, version -> driveFullKey(id, version) }
    )
    .placeholderMemoryCacheKey(item.thumbnailKey())
    .allowHardware(hardware)
    .apply { if (size != null) size(size.width, size.height) }
    .build()

/** What flies out of a tile: the full photo at [size] (the tile's thumbnail until it loads). */
internal fun openingModel(context: Context, launch: HeroLaunch?, size: IntSize): Any? {
    val thumbnail = launch?.thumbnail as? DeviceThumbnail ?: return launch?.thumbnail
    if (launch.isVideo) return thumbnail
    return ImageRequest.Builder(context)
        .data(thumbnail.contentUri)
        .memoryCacheKey(deviceThumbnailKey(thumbnail.contentUri, thumbnail.modifiedAt) + FULL)
        .placeholderMemoryCacheKey(
            MemoryCache.Key(deviceThumbnailKey(thumbnail.contentUri, thumbnail.modifiedAt))
        )
        .allowHardware(false)
        .size(size.width, size.height)
        .build()
}

/**
 * What flies back into a tile: the photo as the page showed it, or a video's poster. A Drive
 * photo flies back as its thumbnail, which is always on the phone.
 */
internal fun closingModel(context: Context, item: ViewerItem, size: IntSize): Any? =
    if (item.kind == MediaKind.VIDEO || item.source == SourceType.DRIVE) {
        item.poster()
    } else {
        fullImageRequest(context, item, size)
    }

private const val HALF_TURN = 180
private const val FULL = "#full"
