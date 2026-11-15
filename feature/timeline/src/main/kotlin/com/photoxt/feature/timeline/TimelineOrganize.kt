package com.photoxt.feature.timeline

import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.SmartRule
import com.photoxt.core.model.TargetType
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.organize.Bookmarks
import com.photoxt.core.organize.Hiding
import com.photoxt.core.organize.OwnAlbums
import com.photoxt.core.organize.Pins
import com.photoxt.core.transfer.OrganizeOps
import com.photoxt.core.ui.NamedChoice
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a timeline's organising needs (spec §4.8), injected once per screen. */
class TimelineOrganizeParts @Inject constructor(
    internal val pins: Pins,
    internal val hiding: Hiding,
    internal val ownAlbums: OwnAlbums,
    internal val options: AlbumOptions,
    internal val ops: OrganizeOps,
    internal val bookmarks: Bookmarks
) {
    fun forTimeline(
        albumId: String?,
        selection: MutableStateFlow<Map<String, Long>>,
        scope: CoroutineScope
    ) = TimelineOrganize(albumId, selection, scope, this)
}

/** Where selected items move in Custom order (spec §4.2: *Move up* and *Move down*). */
enum class OrderMove { UP, DOWN, TOP, BOTTOM }

/** A timeline's organising: what its selection and its album can do (spec §4.8). */
class TimelineOrganize internal constructor(
    private val albumId: String?,
    private val selection: MutableStateFlow<Map<String, Long>>,
    private val scope: CoroutineScope,
    private val parts: TimelineOrganizeParts
) {
    private val selected: List<String> get() = selection.value.keys.toList()

    /** Virtual albums to add items to. */
    val virtualAlbums: Flow<List<NamedChoice>> =
        parts.ownAlbums.virtualAlbums.map { albums ->
            albums.sortedBy { it.title.lowercase() }.map { NamedChoice(it.id, it.title) }
        }

    val collections: Flow<List<NamedChoice>> =
        parts.bookmarks.collections.map { list -> list.map { NamedChoice(it.id, it.name) } }

    val selectionActions = SelectionOrganize()
    val albumActions = AlbumOrganize()

    /** Album [id]'s pin, hidden and bookmark state, for its menu. */
    internal fun albumFacts(id: String): Flow<AlbumState> = combine(
        parts.pins.isPinned(TargetType.ALBUM, id),
        parts.options.prefsOf(id),
        parts.bookmarks.isBookmarked(id)
    ) { pinned, prefs, bookmarked ->
        AlbumState(
            type = com.photoxt.core.model.AlbumType.DEVICE_FOLDER,
            pinned = pinned,
            hidden = prefs?.hidden == true,
            hideFromPictures = prefs?.hideFromPictures == true,
            bookmarked = bookmarked
        )
    }

    /** Picks Custom order for this album, starting from the order on screen ([current]). */
    internal suspend fun startCustomOrder(current: MediaListQuery) {
        val id = albumId ?: return
        parts.ownAlbums.startCustomOrder(id, current.copy(filter = MediaFilter.ALL))
    }

    /** Puts [ids] just before, or [after], [targetId] in Custom order. */
    internal suspend fun moveTo(
        query: MediaListQuery,
        ids: List<String>,
        targetId: String,
        after: Boolean
    ) {
        val id = albumId ?: return
        val order = parts.ownAlbums.customOrder(query.copy(filter = MediaFilter.ALL))
        val rest = order.filterNot { it in ids }
        val before = if (after) rest.getOrNull(rest.indexOf(targetId) + 1) else targetId
        parts.ownAlbums.moveItems(id, order, ids, before)
    }

    private fun launchOnSelection(action: suspend (List<String>) -> Unit) {
        val ids = selected
        if (ids.isEmpty()) return
        scope.launch {
            action(ids)
            selection.value = emptyMap()
        }
    }

    /** The selection's organising (spec §4.1, §4.8). */
    inner class SelectionOrganize {
        fun favorite(favorite: Boolean, title: String) =
            launchOnSelection { parts.ops.setFavorite(it, favorite, title) }

        fun addToAlbum(albumId: String) = launchOnSelection { parts.ownAlbums.add(albumId, it) }

        /** A new virtual album holding the selection; [onMade] gets its id. */
        fun addToNewAlbum(name: String, onMade: (String) -> Unit) = launchOnSelection {
            onMade(parts.ownAlbums.newVirtual(name, it))
        }

        fun pin(pinned: Boolean) =
            launchOnSelection { parts.pins.setPinned(TargetType.ITEM, it, pinned) }

        fun hide(hidden: Boolean) = launchOnSelection {
            if (hidden) parts.hiding.hide(it) else parts.hiding.unhide(it)
        }

        fun moveToVault(title: String) = launchOnSelection { parts.ops.moveToVault(it, title) }

        /** Takes the selection out of this virtual album; the items stay. */
        fun removeFromAlbum() = launchOnSelection { ids ->
            albumId?.let { parts.ownAlbums.remove(it, ids) }
        }

        /** The one selected item becomes this album's cover. */
        fun setAsCover() = launchOnSelection { ids ->
            val album = albumId ?: return@launchOnSelection
            parts.options.setCover(album, ids.single())
        }

        /** Moves the selection in Custom order; [query] lists this album's items. */
        fun move(query: MediaListQuery, move: OrderMove) {
            val ids = selected
            val album = albumId ?: return
            if (ids.isEmpty()) return
            scope.launch {
                val order = parts.ownAlbums.customOrder(query.copy(filter = MediaFilter.ALL))
                val first = order.indexOfFirst { it in ids }
                val last = order.indexOfLast { it in ids }
                if (first < 0) return@launch
                val before = when (move) {
                    OrderMove.UP -> order.getOrNull(first - 1) ?: return@launch

                    OrderMove.DOWN ->
                        if (last + 1 >= order.size) return@launch else order.getOrNull(last + 2)

                    OrderMove.TOP -> order.firstOrNull { it !in ids }

                    OrderMove.BOTTOM -> null
                }
                parts.ownAlbums.moveItems(album, order, ids, before)
            }
        }
    }

    /** The album's own organising (spec §4.2, §4.8). */
    inner class AlbumOrganize {
        fun pin(pinned: Boolean) = albumScoped {
            parts.pins.setPinned(TargetType.ALBUM, listOf(it), pinned)
        }

        fun hide(hidden: Boolean) = albumScoped { parts.hiding.setAlbumHidden(it, hidden) }

        fun hideFromPictures(hidden: Boolean) =
            albumScoped { parts.hiding.setHideFromPictures(it, hidden) }

        /** Bookmarks this album as [title] in a collection, or in a new one. */
        fun bookmark(collectionId: String?, newCollection: String?, note: String) =
            albumScoped { id ->
                val collection = parts.bookmarks.collection(collectionId, newCollection)
                parts.bookmarks.add(collection, TargetType.ALBUM, id, note)
            }

        fun rename(name: String) = albumScoped { parts.ownAlbums.rename(it, name) }

        fun delete() = albumScoped { parts.ownAlbums.delete(it) }

        fun setRule(name: String, rule: SmartRule) = albumScoped {
            parts.ownAlbums.rename(it, name)
            parts.ownAlbums.setRule(it, rule)
        }

        /** The album's cover goes back to its newest item. */
        fun resetCover() = albumScoped { parts.options.setCover(it, null) }

        private fun albumScoped(action: suspend (String) -> Unit) {
            val id = albumId ?: return
            scope.launch { action(id) }
        }
    }
}
