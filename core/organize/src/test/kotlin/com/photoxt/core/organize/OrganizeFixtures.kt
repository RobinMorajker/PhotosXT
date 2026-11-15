package com.photoxt.core.organize

import androidx.datastore.core.DataStore
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.datastore.PxtSettings
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings kept in memory, as DataStore keeps them on disk. */
class MemorySettings(initial: PxtSettings = PxtSettings()) : DataStore<PxtSettings> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<PxtSettings> = state

    override suspend fun updateData(
        transform: suspend (t: PxtSettings) -> PxtSettings
    ): PxtSettings = transform(state.value).also { state.value = it }
}

/** A clock that moves only when told. */
class TestClock(private var now: Instant = Instant.parse("2026-11-12T10:00:00Z")) : Clock() {
    fun advanceDays(days: Long) {
        now = now.plusSeconds(days * 86_400)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

const val ALBUM = "device:camera"

fun album(id: String = ALBUM, title: String = "Camera") =
    AlbumEntity(id, AlbumType.DEVICE_FOLDER, "external_primary:$title/", title)

fun item(id: String, album: String = ALBUM, kind: MediaKind = MediaKind.IMAGE) = MediaEntity(
    id = id,
    source = SourceType.DEVICE,
    volume = "external_primary",
    contentUri = "content://media/$id",
    albumId = album,
    name = "$id.jpg",
    mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
    kind = kind,
    bytes = 1,
    width = 1,
    height = 1,
    rotation = 0,
    durationMs = if (kind == MediaKind.VIDEO) 60_000 else null,
    takenAt = 1,
    modifiedAt = 1,
    dayKey = 0,
    monthKey = 0,
    favorite = false
)
