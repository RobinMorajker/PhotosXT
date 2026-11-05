package com.photoxt.core.media

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Point
import android.os.Bundle
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toUri as toCoilUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * A grid thumbnail of a MediaStore item. [modifiedAt] is part of the cache key, so an item edited
 * in another app never shows its old thumbnail.
 */
data class DeviceThumbnail(val contentUri: String, val modifiedAt: Long)

/**
 * Reads the thumbnail MediaStore already keeps for each photo and video, rather than the full
 * file, and nothing is copied into PhotoXT's own storage (spec §4.3). Coil decodes it like any
 * image, scaled to the tile. Register it, and [DeviceThumbnailKeyer], on the app's Coil image
 * loader.
 */
class DeviceThumbnailFetcher(private val data: DeviceThumbnail, private val options: Options) :
    Fetcher {
    // The descriptor closes with its input stream, which Coil closes after decoding.
    @SuppressLint("Recycle")
    override suspend fun fetch(): FetchResult {
        val size = Point(
            options.size.width.pxOrElse { FALLBACK_SIZE_PX },
            options.size.height.pxOrElse { FALLBACK_SIZE_PX }
        )
        val request = Bundle(1).apply { putParcelable(ContentResolver.EXTRA_SIZE, size) }
        val thumbnail = withContext(thumbnailDispatcher) {
            options.context.contentResolver
                .openTypedAssetFile(data.contentUri.toUri(), "image/*", request, null)
        }
        checkNotNull(thumbnail) { "MediaStore has no thumbnail for this item" }
        return SourceFetchResult(
            source = ImageSource(
                source = thumbnail.createInputStream().source().buffer(),
                fileSystem = options.fileSystem,
                metadata = ContentMetadata(data.contentUri.toCoilUri(), thumbnail)
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<DeviceThumbnail> {
        override fun create(data: DeviceThumbnail, options: Options, imageLoader: ImageLoader) =
            DeviceThumbnailFetcher(data, options)
    }

    private companion object {
        const val FALLBACK_SIZE_PX = 512

        // Bounds concurrent MediaStore thumbnail reads, so a fast fling queues work instead of
        // flooding the media provider. Tuning knob: raise it if thumbnails trail a slow scroll.
        val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(4)
    }
}

class DeviceThumbnailKeyer : Keyer<DeviceThumbnail> {
    override fun key(data: DeviceThumbnail, options: Options) =
        "${data.contentUri}@${data.modifiedAt}"
}
