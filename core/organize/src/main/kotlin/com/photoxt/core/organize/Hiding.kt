package com.photoxt.core.organize

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumPrefsEntity
import com.photoxt.core.database.HiddenAlbumRow
import com.photoxt.core.database.HiddenItemEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PxtDatabase
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Hidden items and albums (spec §4.8): out of Pictures, albums and search, listed in Hidden
 * behind the unlock. History never holds them (spec §4.9): hiding forgets their views and video
 * positions, and nothing new is logged while they stay hidden.
 */
@Singleton
class Hiding @Inject constructor(private val database: PxtDatabase, private val clock: Clock) {
    private val hidden = database.hiddenDao()
    private val prefs = database.albumPrefsDao()
    private val history = database.historyDao()
    private val positions = database.playbackDao()

    /** How many items are hidden, for the Library row. */
    val count: Flow<Int> get() = hidden.count()

    val hiddenAlbums: Flow<List<HiddenAlbumRow>> get() = prefs.hiddenAlbums()

    suspend fun hide(ids: List<String>) = database.withTransaction {
        val now = clock.millis()
        ids.chunked(MAX_SQL_VARIABLES).forEach { chunk ->
            hidden.hide(chunk.map { HiddenItemEntity(it, now) })
            history.forget(chunk)
            positions.forget(chunk)
        }
    }

    suspend fun unhide(ids: List<String>) =
        ids.chunked(MAX_SQL_VARIABLES).forEach { hidden.unhide(it) }

    /** Hides album [albumId] entirely, or shows it again. */
    suspend fun setAlbumHidden(albumId: String, hide: Boolean) = database.withTransaction {
        val old = prefs.prefsOf(albumId) ?: AlbumPrefsEntity(albumId)
        prefs.savePrefs(listOf(old.copy(hidden = hide)))
        if (hide) {
            history.forgetAlbum(albumId)
            positions.forgetAlbum(albumId)
        }
    }

    /** Keeps album [albumId] in Albums but its items out of Pictures, or lets them back. */
    suspend fun setHideFromPictures(albumId: String, hide: Boolean) = database.withTransaction {
        val old = prefs.prefsOf(albumId) ?: AlbumPrefsEntity(albumId)
        prefs.savePrefs(listOf(old.copy(hideFromPictures = hide)))
    }

    /** Whether nothing about [mediaId] may be logged: it or its album is hidden. */
    suspend fun isHidden(mediaId: String): Boolean = hidden.isHiddenAnywhere(mediaId)
}
