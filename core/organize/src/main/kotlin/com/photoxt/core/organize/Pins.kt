package com.photoxt.core.organize

import androidx.room.withTransaction
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PinEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.model.TargetType
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pins (spec §4.8): albums at the top of Albums, items at the top of their album, in the user's
 * drag order. New pins join after the ones already there.
 */
@Singleton
class Pins @Inject constructor(
    private val database: PxtDatabase,
    private val settings: SettingsStore,
    private val clock: Clock
) {
    private val dao = database.pinDao()
    private val autoLock = Mutex()

    fun pinned(type: TargetType): Flow<List<PinEntity>> = dao.pins(type)

    fun isPinned(type: TargetType, id: String): Flow<Boolean> = dao.isPinned(type, id)

    /** Pins or unpins [ids]; pins go after the ones already there, in this order. */
    suspend fun setPinned(type: TargetType, ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        database.withTransaction {
            if (pinned) {
                val last = dao.lastPosition(type)
                val now = clock.millis()
                dao.insert(ids.mapIndexed { i, id -> PinEntity(type, id, last + i + 1, now) })
            } else {
                ids.chunked(MAX_SQL_VARIABLES).forEach { dao.unpin(type, it) }
            }
        }
    }

    /** The pins of [type] in their new drag order; pins left out keep their place after them. */
    suspend fun reorder(type: TargetType, ids: List<String>) = database.withTransaction {
        val pins = dao.allPins().filter { it.targetType == type }.associateBy { it.targetId }
        val ordered = ids.mapNotNull(pins::get)
        val rest = pins.values.filter { it.targetId !in ids }.sortedBy { it.position }
        dao.savePins(
            (ordered + rest).mapIndexed { i, pin -> pin.copy(position = i + 1.0) }
        )
    }

    /**
     * Camera and Screenshots are pinned the first time they appear (spec §4.2, Pins stay on
     * top), in this order. Each album is pinned once only: unpinned, it sorts like any other.
     */
    suspend fun autoPin(albumIds: List<String>) = autoLock.withLock {
        val done = settings.current().organize.autoPinned
        val fresh = albumIds.filter { it !in done }
        if (fresh.isEmpty()) return@withLock
        setPinned(TargetType.ALBUM, fresh, pinned = true)
        settings.update { it.copy(organize = it.organize.copy(autoPinned = done + fresh)) }
    }
}
