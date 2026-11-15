package com.photoxt.core.organize

import com.photoxt.core.database.BookmarkCollectionEntity
import com.photoxt.core.database.BookmarkCollectionRow
import com.photoxt.core.database.BookmarkEntity
import com.photoxt.core.database.BookmarkRow
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Bookmarks (spec §4.8): items, albums and moments of videos saved for later in named
 * collections, each with an optional note. A Drive item's bookmark keeps a copy of its thumbnail,
 * so it still shows one, marked "No longer available", once the item is gone or access ends.
 */
@Singleton
class Bookmarks @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val database: PxtDatabase,
    private val thumbnails: ThumbnailStore,
    private val clock: Clock
) {
    private val dao = database.bookmarkDao()

    val collections: Flow<List<BookmarkCollectionRow>> get() = dao.collections()

    fun inCollection(collectionId: String): Flow<List<BookmarkRow>> = dao.bookmarks(collectionId)

    fun isBookmarked(targetId: String): Flow<Boolean> = dao.isBookmarked(targetId)

    /** Collection [id], or a new one named [newName] when it is null; returns its id. */
    suspend fun collection(id: String?, newName: String?): String =
        id ?: newCollection(newName.orEmpty())

    /** A new collection; returns its id. */
    suspend fun newCollection(name: String, note: String? = null): String {
        val id = UUID.randomUUID().toString()
        dao.saveCollections(
            listOf(
                BookmarkCollectionEntity(
                    id = id,
                    name = name.trim(),
                    note = note?.trim()?.ifEmpty { null },
                    createdAt = clock.millis(),
                    position = dao.lastPosition() + 1
                )
            )
        )
        return id
    }

    suspend fun editCollection(id: String, name: String, note: String?) {
        val old = dao.collectionList().firstOrNull { it.id == id } ?: return
        dao.saveCollections(
            listOf(old.copy(name = name.trim(), note = note?.trim()?.ifEmpty { null }))
        )
    }

    /** Deletes a collection with its bookmarks; the items themselves stay. */
    suspend fun deleteCollection(id: String) {
        dao.bookmarkList().filter { it.collectionId == id }.forEach { thumbFile(it.id).delete() }
        dao.deleteCollection(id)
    }

    /**
     * Bookmarks item or album [targetId] in [collectionId], at [positionMs] of a video when set,
     * under the name it has now. A Drive item's thumbnail is kept with it, for once it is gone.
     */
    suspend fun add(
        collectionId: String,
        type: TargetType,
        targetId: String,
        note: String? = null,
        positionMs: Long? = null
    ): String {
        val item = if (type == TargetType.ITEM) database.mediaDao().viewerItem(targetId) else null
        val title = item?.name ?: database.albumDao().albumRow(targetId)?.title.orEmpty()
        val id = UUID.randomUUID().toString()
        dao.saveBookmarks(
            listOf(
                BookmarkEntity(
                    id = id,
                    collectionId = collectionId,
                    targetType = type,
                    targetId = targetId,
                    positionMs = positionMs,
                    note = note?.trim()?.ifEmpty { null },
                    title = title,
                    createdAt = clock.millis()
                )
            )
        )
        val fileId = item?.remoteId
        if (item?.source == SourceType.DRIVE && fileId != null) {
            keepThumbnail(id, fileId, item.remoteVersion ?: 0)
        }
        return id
    }

    suspend fun setNote(bookmark: BookmarkRow, note: String?) {
        val old = dao.bookmarkList().firstOrNull { it.id == bookmark.id } ?: return
        dao.saveBookmarks(listOf(old.copy(note = note?.trim()?.ifEmpty { null })))
    }

    suspend fun delete(bookmarkId: String) {
        withContext(Dispatchers.IO) { thumbFile(bookmarkId).delete() }
        dao.deleteBookmark(bookmarkId)
    }

    /** The thumbnail kept for bookmark [bookmarkId], if one was. */
    fun keptThumbnail(bookmarkId: String): File? = thumbFile(bookmarkId).takeIf { it.isFile }

    private suspend fun keepThumbnail(bookmarkId: String, fileId: String, version: Long) =
        withContext(Dispatchers.IO) {
            val grid = thumbnails.grid(fileId, version)
            if (grid.isFile) {
                runCatching { grid.copyTo(thumbFile(bookmarkId), overwrite = true) }
            }
        }

    private fun thumbFile(bookmarkId: String) =
        File(context.filesDir, "bookmarks/$bookmarkId.webp").also { it.parentFile?.mkdirs() }
}
