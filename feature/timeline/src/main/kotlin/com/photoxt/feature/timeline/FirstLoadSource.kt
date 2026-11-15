package com.photoxt.feature.timeline

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * [source] as it is, calling [onLoaded] once its first page is in: how long a picked sort takes
 * to show is timed with it (spec §4.2; Phase 6: under 1 s on a 20,000-item album).
 */
internal class FirstLoadSource<T : Any>(
    private val source: PagingSource<Int, T>,
    private val onLoaded: () -> Unit
) : PagingSource<Int, T>() {
    @Volatile
    private var loaded = false

    init {
        source.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(source::invalidate)
    }

    override val jumpingSupported: Boolean get() = source.jumpingSupported

    override val keyReuseSupported: Boolean get() = source.keyReuseSupported

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = source.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
        source.load(params).also {
            if (!loaded) {
                loaded = true
                onLoaded()
            }
        }
}
