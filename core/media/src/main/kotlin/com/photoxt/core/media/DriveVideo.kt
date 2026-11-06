package com.photoxt.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.photoxt.core.common.PxtLog
import com.photoxt.core.drive.DriveHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drive videos for Media3 (spec §4.4): streamed over OkHttp with the account's token and HTTP
 * Range requests, so playback can start and seek anywhere without the whole file, through
 * Media3's size-capped LRU [SimpleCache] in `cacheDir/exo`.
 */
@Singleton
@OptIn(UnstableApi::class)
class DriveVideo @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val http: DriveHttp
) {
    // ponytail: size-capped LRU only; the 30-day rule for video spans comes with retention in
    // Phase 4 (spec §4.4).
    private val cache by lazy {
        SimpleCache(
            File(context.cacheDir, "exo"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context)
        )
    }

    private val streamed = AtomicLong()

    /** Counts what playback actually downloads, so tests can show seeking does not fetch all. */
    private val meter = object : TransferListener {
        override fun onTransferInitializing(
            source: DataSource,
            spec: DataSpec,
            isNetwork: Boolean
        ) = Unit

        override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            spec: DataSpec,
            isNetwork: Boolean,
            bytes: Int
        ) {
            if (isNetwork) streamed.addAndGet(bytes.toLong())
        }

        override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) {
                PxtLog.d(TAG, "Streamed ${streamed.get() / KIB} KiB of Drive video so far")
            }
        }
    }

    /** The URL Media3 plays [fileId] from. */
    fun uri(fileId: String): String = http.mediaUrl(fileId).toString()

    /** The cache key of [fileId] at [version], so an edited video never plays stale spans. */
    fun cacheKey(fileId: String, version: Long): String = "drive:$fileId@$version"

    /**
     * Where Media3 reads [accountId]'s Drive videos from: the cache, then Drive. The first call
     * opens the cache, which reads the disk, so it happens off the main thread.
     */
    suspend fun dataSourceFactory(accountId: String): DataSource.Factory = CacheDataSource.Factory()
        .setCache(withContext(Dispatchers.IO) { cache })
        .setUpstreamDataSourceFactory(
            OkHttpDataSource.Factory(http.client)
                .setDefaultRequestProperties(mapOf(DriveHttp.ACCOUNT_HEADER to accountId))
                .setTransferListener(meter)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private companion object {
        const val TAG = "DriveVideo"
        const val CACHE_BYTES = 1024L * 1024 * 1024
        const val KIB = 1024
    }
}
