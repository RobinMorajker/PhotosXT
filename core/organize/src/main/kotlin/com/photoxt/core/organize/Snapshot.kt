package com.photoxt.core.organize

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.AlbumGroupEntity
import com.photoxt.core.database.AlbumItemEntity
import com.photoxt.core.database.AlbumPrefsEntity
import com.photoxt.core.database.BookmarkCollectionEntity
import com.photoxt.core.database.BookmarkEntity
import com.photoxt.core.database.HiddenItemEntity
import com.photoxt.core.database.HistoryEntity
import com.photoxt.core.database.ItemOrderEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PinEntity
import com.photoxt.core.database.PlaybackPositionEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.PxtSettings
import com.photoxt.core.datastore.SettingsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A tracked Drive folder as settings sync keeps it: enough to add it again after a reinstall. */
@Serializable
data class SnapFolder(
    val accountId: String,
    val email: String? = null,
    val folderId: String,
    val driveId: String? = null,
    val name: String,
    val merge: Boolean = false,
    val offline: Boolean = false
)

/** Everything settings sync keeps in Drive (spec §4.8), as one JSON file. */
@Serializable
data class Snapshot(
    val version: Int = VERSION,
    val savedAt: Long = 0,
    /** [PxtSettings] as [SettingsJson] writes it, without this phone's own state. */
    val settings: String,
    val folders: List<SnapFolder> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val albumItems: List<AlbumItemEntity> = emptyList(),
    val prefs: List<AlbumPrefsEntity> = emptyList(),
    val groups: List<AlbumGroupEntity> = emptyList(),
    val order: List<ItemOrderEntity> = emptyList(),
    val pins: List<PinEntity> = emptyList(),
    val collections: List<BookmarkCollectionEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val hidden: List<HiddenItemEntity> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
    val positions: List<PlaybackPositionEntity> = emptyList()
) {
    companion object {
        const val VERSION = 1
    }
}

internal object Snapshots {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: Snapshot): String = json.encodeToString(Snapshot.serializer(), snapshot)

    fun decode(text: String): Snapshot? =
        runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrNull()

    /** What the copy holds, whenever it was saved: an unchanged state isn't sent again. */
    fun hash(snapshot: Snapshot): String = sha256(encode(snapshot.copy(savedAt = 0)))
}

/** Reads and replaces the user's own tables for settings sync. */
internal class SnapshotTables(private val database: PxtDatabase) {
    private val backup = database.backupDao()

    /** The state now, in a stable order (so an unchanged state hashes the same). */
    suspend fun read(settings: PxtSettings): Snapshot = database.withTransaction {
        val accounts = database.driveDao().accountList().associateBy { it.id }
        val titles = database.driveDao().trackedAlbums().associateBy({ it.id }, { it.title })
        Snapshot(
            settings = SettingsJson.encode(settings),
            folders = backup.trackedFolders().map {
                SnapFolder(
                    accountId = it.accountId,
                    email = accounts[it.accountId]?.email,
                    folderId = it.driveFolderId,
                    driveId = it.driveId,
                    name = titles[it.albumId].orEmpty(),
                    merge = it.mergeSubfolders,
                    offline = it.offline
                )
            }.sortedBy { it.accountId + it.folderId },
            albums = backup.ownAlbums().sortedBy { it.id },
            albumItems = database.virtualAlbumDao().allItems()
                .sortedWith(compareBy({ it.albumId }, { it.mediaId })),
            prefs = database.albumPrefsDao().prefsList().sortedBy { it.albumId },
            groups = database.albumPrefsDao().groupList().sortedBy { it.id },
            order = database.virtualAlbumDao().allOrder()
                .sortedWith(compareBy({ it.albumId }, { it.mediaId })),
            pins = database.pinDao().allPins().sortedWith(
                compareBy({
                    it.targetType
                }, { it.targetId })
            ),
            collections = database.bookmarkDao().collectionList().sortedBy { it.id },
            bookmarks = database.bookmarkDao().bookmarkList().sortedBy { it.id },
            hidden = database.hiddenDao().all().sortedBy { it.mediaId },
            history = database.historyDao().all(),
            positions = backup.positions().sortedBy { it.mediaId }
        )
    }

    /** Replaces the user's tables with [snapshot]'s (spec §4.8: a reinstall restores all). */
    suspend fun replace(snapshot: Snapshot) = database.withTransaction {
        backup.clearOwnAlbums()
        database.virtualAlbumDao().saveAlbums(snapshot.albums)
        snapshot.albumItems.chunked(MAX_SQL_VARIABLES).forEach {
            database.virtualAlbumDao().addItems(it)
        }
        backup.clearPrefs()
        database.albumPrefsDao().savePrefs(snapshot.prefs)
        backup.clearGroups()
        database.albumPrefsDao().saveGroups(snapshot.groups)
        backup.clearOrder()
        snapshot.order.chunked(MAX_SQL_VARIABLES).forEach {
            database.virtualAlbumDao().saveOrder(it)
        }
        backup.clearPins()
        database.pinDao().savePins(snapshot.pins)
        backup.clearCollections()
        database.bookmarkDao().saveCollections(snapshot.collections)
        database.bookmarkDao().saveBookmarks(snapshot.bookmarks)
        backup.clearHidden()
        backup.putHidden(snapshot.hidden)
        backup.clearHistory()
        snapshot.history.chunked(MAX_SQL_VARIABLES).forEach { views ->
            database.historyDao().insertAll(views)
        }
        backup.clearPositions()
        backup.putPositions(snapshot.positions)
    }
}
