package com.photoxt.feature.albums

import com.photoxt.core.model.SmartRule
import com.photoxt.core.model.TargetType
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.organize.Hiding
import com.photoxt.core.organize.OwnAlbums
import com.photoxt.core.organize.Pins
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the Albums tab's organising needs (spec §4.8), injected once. */
class AlbumsOrganizeParts @Inject constructor(
    internal val pins: Pins,
    internal val hiding: Hiding,
    internal val options: AlbumOptions,
    internal val ownAlbums: OwnAlbums
) {
    fun forScreen(scope: CoroutineScope) = AlbumsOrganize(scope, this)
}

/** An album tile's organising (spec §4.8): pins, hiding, groups and PhotoXT's own albums. */
class AlbumsOrganize internal constructor(
    private val scope: CoroutineScope,
    private val parts: AlbumsOrganizeParts
) {
    fun pin(albumId: String, pinned: Boolean) = launch {
        parts.pins.setPinned(TargetType.ALBUM, listOf(albumId), pinned)
    }

    fun hide(albumId: String) = launch { parts.hiding.setAlbumHidden(albumId, true) }

    fun hideFromPictures(albumId: String, hidden: Boolean) =
        launch { parts.hiding.setHideFromPictures(albumId, hidden) }

    /** Into group [groupId], or out of its group when null. */
    fun moveToGroup(albumId: String, groupId: String?) =
        launch { parts.options.moveToGroup(listOf(albumId), groupId) }

    fun newGroup(name: String, albumId: String) =
        launch { parts.options.newGroup(name, listOf(albumId)) }

    fun renameGroup(groupId: String, name: String) =
        launch { parts.options.renameGroup(groupId, name) }

    /** Ungroups: the group goes, its albums return to the Albums tab. */
    fun deleteGroup(groupId: String) = launch { parts.options.deleteGroup(groupId) }

    fun renameOwn(albumId: String, name: String) = launch { parts.ownAlbums.rename(albumId, name) }

    fun deleteOwn(albumId: String) = launch { parts.ownAlbums.delete(albumId) }

    /** A new, empty virtual album; [onMade] gets its id. */
    fun newVirtual(name: String, onMade: (String) -> Unit) =
        launch { onMade(parts.ownAlbums.newVirtual(name)) }

    fun newSmart(name: String, rule: SmartRule, onMade: (String) -> Unit) =
        launch { onMade(parts.ownAlbums.newSmart(name, rule)) }

    private fun launch(action: suspend () -> Unit) {
        scope.launch { action() }
    }
}
