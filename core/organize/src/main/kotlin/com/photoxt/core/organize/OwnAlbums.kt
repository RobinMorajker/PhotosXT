package com.photoxt.core.organize

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.AlbumItemEntity
import com.photoxt.core.database.ItemOrderEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.SMART_PREFIX
import com.photoxt.core.database.VIRTUAL_PREFIX
import com.photoxt.core.database.VirtualAlbumSummary
import com.photoxt.core.database.idsOf
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField
import com.photoxt.core.model.SmartRule
import com.photoxt.core.model.ViewerStart
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * PhotoXT's own albums (spec §4.8): virtual albums of hand-picked items from anywhere, nothing
 * moved, and smart albums of a saved rule. Also every album's Custom order of items (spec §4.2).
 */
@Singleton
class OwnAlbums @Inject constructor(
    private val database: PxtDatabase,
    private val mediaDao: MediaDao,
    private val clock: Clock
) {
    private val dao = database.virtualAlbumDao()

    val virtualAlbums: Flow<List<VirtualAlbumSummary>> get() = dao.virtualAlbums()

    val smartAlbums: Flow<List<AlbumEntity>> get() = dao.smartAlbums()

    /** A new virtual album named [name] holding [itemIds]; returns its id. */
    suspend fun newVirtual(name: String, itemIds: List<String> = emptyList()): String {
        val id = VIRTUAL_PREFIX + UUID.randomUUID()
        database.withTransaction {
            dao.saveAlbums(listOf(own(id, AlbumType.VIRTUAL, name)))
            add(id, itemIds)
        }
        return id
    }

    /** Adds [itemIds] to virtual album [albumId]; those in it already stay where they are. */
    suspend fun add(albumId: String, itemIds: List<String>) {
        val now = clock.millis()
        itemIds.chunked(MAX_SQL_VARIABLES).forEach { chunk ->
            dao.addItems(chunk.map { AlbumItemEntity(albumId, it, now) })
        }
    }

    /** Takes [itemIds] out of virtual album [albumId]; the items themselves stay. */
    suspend fun remove(albumId: String, itemIds: List<String>) =
        itemIds.chunked(MAX_SQL_VARIABLES).forEach { dao.removeItems(albumId, it) }

    /** A new smart album named [name] with [rule]; returns its id. */
    suspend fun newSmart(name: String, rule: SmartRule): String {
        val id = SMART_PREFIX + UUID.randomUUID()
        dao.saveAlbums(listOf(own(id, AlbumType.SMART, name, encode(rule))))
        return id
    }

    suspend fun setRule(albumId: String, rule: SmartRule) = dao.setRule(albumId, encode(rule))

    suspend fun rename(albumId: String, name: String) = dao.retitle(albumId, name.trim())

    /** Deletes a virtual or smart album; no item is touched. */
    suspend fun delete(albumId: String) = dao.deleteOwn(albumId)

    // --- Custom order (spec §4.2) ----------------------------------------------------------

    /**
     * Picks Custom order for album [albumId]: it starts from the order on screen, the album's
     * [current] sort, so nothing jumps.
     */
    suspend fun startCustomOrder(albumId: String, current: MediaListQuery) {
        if (current.sort.field == ItemSortField.CUSTOM) return
        val ids = mediaDao.idsOf(current)
        database.withTransaction {
            ids.chunked(MAX_SQL_VARIABLES).forEachIndexed { chunk, part ->
                dao.saveOrder(
                    part.mapIndexed { i, id ->
                        ItemOrderEntity(albumId, id, (chunk * MAX_SQL_VARIABLES + i + 1).toDouble())
                    }
                )
            }
        }
    }

    /**
     * Moves [moved] items of album [albumId] in Custom order to just before [before], or to the
     * end when it is null. [order] is the album's items in their current Custom order.
     */
    suspend fun moveItems(
        albumId: String,
        order: List<String>,
        moved: List<String>,
        before: String?
    ) {
        val result = reordered(order, moved, before)
        if (result == order) return
        val known = dao.orderOf(albumId).associateBy({ it.mediaId }, { it.position })
        database.withTransaction {
            result.chunked(MAX_SQL_VARIABLES).forEachIndexed { chunk, part ->
                dao.saveOrder(
                    part.mapIndexedNotNull { i, id ->
                        val position = (chunk * MAX_SQL_VARIABLES + i + 1).toDouble()
                        if (known[id] == position) null else ItemOrderEntity(albumId, id, position)
                    }
                )
            }
        }
    }

    /** The items a viewer or slideshow opened at [start] pages through, in the grid's order. */
    suspend fun queryOf(start: ViewerStart): MediaListQuery {
        val rule = start.albumId?.takeIf { it.startsWith(SMART_PREFIX) }
            ?.let { decode(database.albumDao().albumRow(it)?.rule) }
        return MediaListQuery(start.filter, start.albumId, start.sort, start.list, rule)
    }

    /** Album [albumId]'s items in Custom order, for moves. */
    suspend fun customOrder(query: MediaListQuery): List<String> =
        mediaDao.idsOf(query.copy(sort = ItemSort(ItemSortField.CUSTOM)))

    private fun own(id: String, type: AlbumType, name: String, rule: String? = null) = AlbumEntity(
        id = id,
        type = type,
        sourceRef = "",
        title = name.trim(),
        createdAt = clock.millis(),
        rule = rule
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(rule: SmartRule): String = json.encodeToString(SmartRule.serializer(), rule)

        /** A stored rule; an unreadable one matches nothing rather than everything. */
        fun decode(rule: String?): SmartRule? =
            rule?.let { runCatching { json.decodeFromString(SmartRule.serializer(), it) } }
                ?.getOrNull()
    }
}

/**
 * [order] with [moved] taken out and put back, in their order, just before [before] (or at the
 * end when it is null or one of them).
 */
internal fun reordered(order: List<String>, moved: List<String>, before: String?): List<String> {
    val set = moved.toSet()
    val rest = order.filterNot { it in set }
    val picked = order.filter { it in set }
    val at = before?.takeIf { it !in set }?.let(rest::indexOf)?.takeIf { it >= 0 } ?: rest.size
    return rest.subList(0, at) + picked + rest.subList(at, rest.size)
}
