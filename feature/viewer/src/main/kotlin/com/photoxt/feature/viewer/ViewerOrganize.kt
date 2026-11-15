package com.photoxt.feature.viewer

import com.photoxt.core.database.SMART_PREFIX
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.ViewerStart
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.organize.Bookmarks
import com.photoxt.core.organize.Hiding
import com.photoxt.core.organize.History
import com.photoxt.core.organize.OwnAlbums
import com.photoxt.core.transfer.OrganizeOps
import com.photoxt.core.ui.NamedChoice
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the viewer's organising needs (spec §4.8, §4.9), injected once. */
class ViewerOrganizeParts @Inject constructor(
    internal val history: History,
    internal val ops: OrganizeOps,
    internal val bookmarks: Bookmarks,
    internal val hiding: Hiding,
    internal val ownAlbums: OwnAlbums,
    internal val options: AlbumOptions
)

/**
 * The viewer's organising (spec §4.8, §4.9): the heart, bookmarks, *Add to album*, hide, the
 * vault, the album cover, and the history of what was viewed.
 */
class ViewerOrganize internal constructor(
    private val start: ViewerStart,
    private val scope: CoroutineScope,
    private val parts: ViewerOrganizeParts
) {
    val virtualAlbums: Flow<List<NamedChoice>> = parts.ownAlbums.virtualAlbums.map { albums ->
        albums.sortedBy { it.title.lowercase() }.map { NamedChoice(it.id, it.title) }
    }

    val collections: Flow<List<NamedChoice>> =
        parts.bookmarks.collections.map { list -> list.map { NamedChoice(it.id, it.name) } }

    /** An album's own cover can be set from it; a smart album's follows its rule. */
    val canSetCover: Boolean = start.albumId?.startsWith(SMART_PREFIX) == false

    /** [item] was viewed (spec §4.9). A video's view is logged by the player instead. */
    fun viewed(item: ViewerItem) {
        if (item.kind == MediaKind.VIDEO) return
        scope.launch { parts.history.viewed(item.id, start.albumId) }
    }

    fun viewCount(id: String): Flow<Int> = parts.history.viewCount(id)

    fun setFavorite(item: ViewerItem, favorite: Boolean) {
        scope.launch { parts.ops.setFavorite(listOf(item.id), favorite, item.name) }
    }

    /** Bookmarks [item] in a collection, or in a new one (spec §4.8). */
    fun bookmark(item: ViewerItem, collectionId: String?, newCollection: String?, note: String) {
        scope.launch {
            val collection = parts.bookmarks.collection(collectionId, newCollection)
            parts.bookmarks.add(collection, TargetType.ITEM, item.id, note)
        }
    }

    fun addToAlbum(item: ViewerItem, albumId: String) {
        scope.launch { parts.ownAlbums.add(albumId, listOf(item.id)) }
    }

    fun addToNewAlbum(item: ViewerItem, name: String) {
        scope.launch { parts.ownAlbums.newVirtual(name, listOf(item.id)) }
    }

    fun hide(item: ViewerItem) {
        scope.launch { parts.hiding.hide(listOf(item.id)) }
    }

    fun moveToVault(item: ViewerItem) {
        scope.launch { parts.ops.moveToVault(listOf(item.id), item.name) }
    }

    /** [item] becomes the cover of the album the viewer was opened from. */
    fun setAsCover(item: ViewerItem) {
        val albumId = start.albumId ?: return
        scope.launch { parts.options.setCover(albumId, item.id) }
    }
}
