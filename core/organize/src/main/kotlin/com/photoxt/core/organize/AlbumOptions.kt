package com.photoxt.core.organize

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumGroupEntity
import com.photoxt.core.database.AlbumPrefsEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.MediaList
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Album options (spec §4.2) and sorts: each album's cover and sort, the Albums tab's sort and
 * Custom order, Pictures' and the Library lists' sorts, and album groups (spec §4.8).
 */
@Singleton
class AlbumOptions @Inject constructor(
    private val database: PxtDatabase,
    private val settings: SettingsStore,
    private val clock: Clock
) {
    private val prefs = database.albumPrefsDao()

    /** Every album's options, by album id. */
    val allPrefs: Flow<Map<String, AlbumPrefsEntity>> =
        prefs.prefs().map { list -> list.associateBy { it.albumId } }

    fun prefsOf(albumId: String): Flow<AlbumPrefsEntity?> = prefs.prefsFlow(albumId)

    /** Album [albumId]'s sort: its own, else every album's default. */
    fun sortOf(albumId: String): Flow<ItemSort> =
        combine(prefs.prefsFlow(albumId), settings.settings) { own, all ->
            own?.sort?.let { ItemSort(it, own.sortDescending) } ?: all.sort.albumDefault
        }.distinctUntilChanged()

    suspend fun setSort(albumId: String, sort: ItemSort) =
        update(albumId) { it.copy(sort = sort.field, sortDescending = sort.descending) }

    /** *Apply to all albums* (spec §4.2): every album, new ones included, takes [sort]. */
    suspend fun applyToAll(sort: ItemSort) {
        settings.updateSort { it.copy(albumDefault = sort) }
        prefs.clearSorts()
    }

    val albumsSort: Flow<AlbumSort> =
        settings.settings.map { it.sort.albums }.distinctUntilChanged()

    suspend fun setAlbumsSort(sort: AlbumSort) = settings.updateSort { it.copy(albums = sort) }

    val picturesSort: Flow<ItemSort> =
        settings.settings.map { it.sort.pictures }.distinctUntilChanged()

    suspend fun setPicturesSort(sort: ItemSort) = settings.updateSort { it.copy(pictures = sort) }

    fun listSort(list: MediaList): Flow<ItemSort> =
        settings.settings.map { it.sort.lists[list.name] ?: ItemSort() }.distinctUntilChanged()

    suspend fun setListSort(list: MediaList, sort: ItemSort) =
        settings.updateSort { it.copy(lists = it.lists + (list.name to sort)) }

    /** Album [albumId]'s cover (spec §4.2, Cover: any item); null goes back to its newest. */
    suspend fun setCover(albumId: String, mediaId: String?) =
        update(albumId) { it.copy(coverId = mediaId) }

    /**
     * Custom order of the Albums tab (spec §4.2): [ids], albums and groups ([GROUP_PREFIX]), in
     * their new order. Those left out keep theirs.
     */
    suspend fun setOrder(ids: List<String>) = database.withTransaction {
        val groups = prefs.groupList().associateBy { it.id }
        val albums = ids.filterNot { it.startsWith(GROUP_PREFIX) }
        val known = prefs.prefsList().associateBy { it.albumId }
        prefs.savePrefs(
            albums.map { id ->
                (known[id] ?: AlbumPrefsEntity(id)).copy(position = ids.indexOf(id) + 1.0)
            }
        )
        prefs.saveGroups(
            ids.filter { it.startsWith(GROUP_PREFIX) }.mapNotNull { id ->
                groups[id.removePrefix(GROUP_PREFIX)]?.copy(position = ids.indexOf(id) + 1.0)
            }
        )
    }

    // --- Groups (spec §4.8) ----------------------------------------------------------------

    val groups: Flow<List<AlbumGroupEntity>> get() = prefs.groups()

    /** A new group named [name] holding [albumIds]; returns its id. */
    suspend fun newGroup(name: String, albumIds: List<String>): String {
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            prefs.saveGroups(listOf(AlbumGroupEntity(id, name.trim(), clock.millis())))
            moveToGroup(albumIds, id)
        }
        return id
    }

    suspend fun renameGroup(id: String, name: String) = database.withTransaction {
        prefs.groupList().firstOrNull { it.id == id }?.let {
            prefs.saveGroups(listOf(it.copy(name = name.trim())))
        }
    }

    /** Ungroups [id]: the group goes, its albums return to the Albums tab. */
    suspend fun deleteGroup(id: String) = prefs.deleteGroup(id)

    /** Moves [albumIds] into group [groupId], or out of any group when it is null. */
    suspend fun moveToGroup(albumIds: List<String>, groupId: String?) = database.withTransaction {
        val known = prefs.prefsList().associateBy { it.albumId }
        prefs.savePrefs(
            albumIds.map { (known[it] ?: AlbumPrefsEntity(it)).copy(groupId = groupId) }
        )
    }

    private suspend fun update(albumId: String, change: (AlbumPrefsEntity) -> AlbumPrefsEntity) =
        database.withTransaction {
            val old = prefs.prefsOf(albumId) ?: AlbumPrefsEntity(albumId)
            prefs.savePrefs(listOf(change(old)))
        }

    companion object {
        /** A group's id in Custom order lists, beside album ids. */
        const val GROUP_PREFIX = "group:"
    }
}
