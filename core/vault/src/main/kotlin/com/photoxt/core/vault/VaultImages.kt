package com.photoxt.core.vault

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source

/**
 * A vault item's picture for Coil (spec §4.8): its kept thumbnail, or the photo itself, decrypted
 * in memory. Nothing decrypted goes to Coil's disk cache, which only network images use.
 */
data class VaultImage(val id: String, val thumbnail: Boolean)

class VaultImageFetcher(
    private val vault: Vault,
    private val data: VaultImage,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val source = withContext(Dispatchers.IO) {
            val thumbnail = if (data.thumbnail) vault.thumbnail(data.id) else null
            thumbnail?.let { Buffer().write(it) } ?: vault.open(data.id).source().buffer()
        }
        return SourceFetchResult(
            source = ImageSource(source, options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val vault: Vault) : Fetcher.Factory<VaultImage> {
        override fun create(data: VaultImage, options: Options, imageLoader: ImageLoader) =
            VaultImageFetcher(vault, data, options)
    }
}

/** Thumbnails stay in the memory cache for the grid; full photos aren't kept at all. */
class VaultImageKeyer : Keyer<VaultImage> {
    override fun key(data: VaultImage, options: Options): String? =
        if (data.thumbnail) "vault:${data.id}" else null
}
