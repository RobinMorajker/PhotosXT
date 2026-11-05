package com.photoxt.feature.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.database.allViewerItems
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.ViewerStart
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the slideshow's controls do. */
internal data class SlideshowControlActions(
    val onClose: () -> Unit,
    val onPlaying: (Boolean) -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onChange: ((SlideshowSettings) -> SlideshowSettings) -> Unit,
    /** True to pick an audio file, false to stop the music. */
    val onMusic: (pick: Boolean) -> Unit
)

/** The order a slideshow plays in, and where it is. */
internal data class SlideshowPlan(val order: List<Int> = emptyList(), val position: Int = 0)

/**
 * A slideshow of one grid's items, from [start] onwards (spec §4.5): in grid order, wrapping round
 * so every item shows once a lap, or shuffled with the start item first.
 */
@HiltViewModel(assistedFactory = SlideshowViewModel.Factory::class)
class SlideshowViewModel @AssistedInject constructor(
    @Assisted private val start: ViewerStart,
    mediaDao: MediaDao,
    private val session: ViewerSession,
    private val savedState: SavedStateHandle
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(start: ViewerStart): SlideshowViewModel
    }

    // ponytail: the whole list is in memory (about 100 bytes an item); page it past ~100k items.
    private val items = MutableStateFlow<List<ViewerItem>>(emptyList())
    private val plan = MutableStateFlow(SlideshowPlan())

    val settings: StateFlow<SlideshowSettings> = session.slideshow.asStateFlow()
    val displayMode: StateFlow<DisplayMode> = session.displayMode.asStateFlow()
    val playing = MutableStateFlow(true)

    /** A picked audio file to play along, as a content URI, or null. */
    val music: StateFlow<String?> = savedState.getStateFlow(KEY_MUSIC, null)

    val current: StateFlow<ViewerItem?> = combine(items, plan) { list, plan ->
        plan.order.getOrNull(plan.position)?.let(list::getOrNull)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The item after the current one, to decode ahead of time. */
    val upcoming: StateFlow<ViewerItem?> = combine(items, plan) { list, plan ->
        val next = if (plan.order.isEmpty()) null else (plan.position + 1) % plan.order.size
        next?.let { plan.order.getOrNull(it) }?.let(list::getOrNull)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val list = mediaDao.allViewerItems(MediaListQuery(start.filter, start.albumId))
            items.value = list
            val first = list.indexOfFirst { it.id == start.id }.takeIf { it >= 0 } ?: start.index
            plan.value = SlideshowPlan(
                slideOrder(list.size, first, settings.value.shuffle, Random.Default),
                savedState[KEY_POSITION] ?: 0
            )
        }
    }

    /** Moves on; false when the show ended because it reached the end without looping. */
    fun next(): Boolean {
        val now = plan.value
        val atEnd = now.position >= now.order.lastIndex
        val ended = now.order.isEmpty() || (atEnd && !settings.value.loop)
        if (ended) playing.value = false else setPosition(if (atEnd) 0 else now.position + 1)
        return !ended
    }

    fun previous() {
        val now = plan.value
        if (now.order.isEmpty()) return
        setPosition(if (now.position == 0) now.order.lastIndex else now.position - 1)
    }

    fun setPlaying(on: Boolean) {
        playing.value = on
    }

    fun update(change: (SlideshowSettings) -> SlideshowSettings) {
        val before = session.slideshow.value
        session.slideshow.update(change)
        val after = session.slideshow.value
        if (after.shuffle != before.shuffle) reshuffle(after.shuffle)
    }

    fun setMusic(uri: String?) {
        savedState[KEY_MUSIC] = uri
    }

    /** Keeps the current item and plays the rest in the new order after it. */
    private fun reshuffle(shuffle: Boolean) {
        val now = plan.value
        val current = now.order.getOrNull(now.position) ?: return
        plan.value = SlideshowPlan(slideOrder(now.order.size, current, shuffle, Random.Default), 0)
        savedState[KEY_POSITION] = 0
    }

    private fun setPosition(position: Int) {
        plan.update { it.copy(position = position) }
        savedState[KEY_POSITION] = position
    }

    private companion object {
        const val KEY_MUSIC = "music"
        const val KEY_POSITION = "position"
    }
}

/**
 * The order a slideshow of [count] items plays in, starting with item [first]: the items after
 * it, then wrapping round to those before it, or with [shuffle] a random order after it.
 */
internal fun slideOrder(count: Int, first: Int, shuffle: Boolean, random: Random): List<Int> {
    if (count <= 0) return emptyList()
    val start = first.coerceIn(0, count - 1)
    val rest = (1 until count).map { (start + it) % count }
    return listOf(start) + if (shuffle) rest.shuffled(random) else rest
}
