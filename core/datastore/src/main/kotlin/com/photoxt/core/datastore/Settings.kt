package com.photoxt.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.DisplayMode
import com.photoxt.core.model.ItemSort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * PhotoXT's settings (spec §4.21). Later phases add their groups here. Everything but [device]
 * goes to the user's Drive with settings sync (spec §4.8) and comes back after a reinstall.
 */
@Serializable
data class PxtSettings(
    val storage: StorageSettings = StorageSettings(),
    val sort: SortSettings = SortSettings(),
    val history: HistorySettings = HistorySettings(),
    val sync: SyncSettings = SyncSettings(),
    val view: ViewSettings = ViewSettings(),
    val organize: OrganizeSettings = OrganizeSettings(),
    val editor: EditorSettings = EditorSettings(),
    /** This phone's own state: never synced. */
    val device: DeviceState = DeviceState()
)

/**
 * The editor's settings (spec §4.10): the object eraser's model (Robin, 2026-11-19: LaMa, MI-GAN
 * and DeepFill v2, switched in the eraser), presets the user saved, imported LUTs and stickers by
 * their asset names, and the last *Save as copy* choices. The asset files themselves stay on this
 * phone.
 */
@Serializable
data class EditorSettings(
    /** An `EraserModel` name (core/ml): LAMA, MIGAN or DEEPFILL; anything else reads as MIGAN. */
    val eraserModel: String = "MIGAN",
    val presets: List<com.photoxt.core.model.EditPreset> = emptyList(),
    val luts: List<LutEntry> = emptyList(),
    val stickers: List<String> = emptyList(),
    val export: com.photoxt.core.model.ExportOptions = com.photoxt.core.model.ExportOptions()
)

/** An imported `.cube` LUT: what the user calls it and its asset. */
@Serializable
data class LutEntry(val name: String, val asset: String)

/**
 * Sort choices (spec §4.2, Scope): the Albums tab and Pictures keep one each; albums keep their
 * own in [perAlbum], by album id, and [albumDefault] is every other album's (*Apply to all albums*
 * sets it); Library lists each keep their own, by [com.photoxt.core.model.MediaList] name.
 *
 * Album sorts live here rather than in Room: a write to `album_prefs` reloads every open grid.
 */
@Serializable
data class SortSettings(
    val albums: AlbumSort = AlbumSort(),
    val pictures: ItemSort = ItemSort(),
    val albumDefault: ItemSort = ItemSort(),
    val perAlbum: Map<String, ItemSort> = emptyMap(),
    val lists: Map<String, ItemSort> = emptyMap()
)

/** The viewing history (spec §4.9). */
@Serializable
data class HistorySettings(
    /** Incognito: nothing is logged, video positions included, while on. */
    val paused: Boolean = false,
    /** Views older than this go; 0 keeps them for good. */
    val keepMonths: Int = 12
) {
    companion object {
        val KEEP_MONTHS = listOf(3, 12, 0)
    }
}

/**
 * Settings sync (spec §4.8) and Drive sync (spec §4.21): [accountId] keeps the copy in its Drive
 * app-data folder (the first account added when null).
 */
@Serializable
data class SyncSettings(
    val enabled: Boolean = true,
    val accountId: String? = null,
    /** How often Drive albums sync in the background. */
    val driveHours: Int = 6,
    /** Background Drive sync also on mobile data. */
    val driveOnMobile: Boolean = true
) {
    companion object {
        val DRIVE_HOURS = listOf(1, 3, 6, 12, 24)
    }
}

/** How grids and the viewer were left (spec §4.2, §4.5), by the names of their choices. */
@Serializable
data class ViewSettings(
    val picturesDensity: String? = null,
    val albumDensity: String? = null,
    val displayMode: DisplayMode = DisplayMode.FIT,
    val zoomLock: Boolean = false,
    val slideshowSeconds: Int = 3,
    val slideshowTransition: String? = null,
    val slideshowShuffle: Boolean = false,
    val slideshowLoop: Boolean = true
)

/** Organising (spec §4.8): albums that were pinned once on their own, so an unpin sticks. */
@Serializable
data class OrganizeSettings(val autoPinned: Set<String> = emptySet())

/** This phone's own state, never synced: when settings sync last ran and what it sent. */
@Serializable
data class DeviceState(
    val syncedAt: Long? = null,
    val syncedHash: String? = null,
    /** A reinstall restored from Drive already, or there was nothing to restore. */
    val restoreChecked: Boolean = false
)

/** When Drive originals load over mobile data (spec §4.4, data saver). */
enum class MobileOriginals {
    /** Always the original, as on Wi-Fi. */
    ALWAYS,

    /** On mobile data the preview opens; the original loads on *Load original*. */
    WIFI_ONLY,

    /** On mobile data the preview opens with a question: load the original, or always do. */
    ASK
}

/** Accounts and storage (spec §4.4, §4.21): the Drive cache's rules. */
@Serializable
data class StorageSettings(
    /** Days a downloaded original stays after it was last opened; 0 keeps it for good. */
    val retentionDays: Int = 30,
    /** Cached originals, pins excluded, above this are evicted least recently opened first. */
    val cacheCapMb: Long = DEFAULT_CACHE_CAP_MB,
    /** Previews above this are evicted least recently used first. */
    val previewCapMb: Long = DEFAULT_PREVIEW_CAP_MB,
    /** Photos on each side of the open one the viewer fetches ahead (spec §4.4, prefetch). */
    val prefetch: Int = 2,
    val mobileOriginals: MobileOriginals = MobileOriginals.WIFI_ONLY
) {
    val retentionMs: Long? get() = if (retentionDays > 0) retentionDays * DAY_MS else null
    val cacheCapBytes: Long get() = cacheCapMb * MB
    val previewCapBytes: Long get() = previewCapMb * MB

    companion object {
        /** The choices the Storage screen offers. */
        val RETENTION_DAYS = listOf(7, 14, 30, 60, 90, 0)
        val CACHE_CAPS_MB = listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L)
        val PREVIEW_CAPS_MB = listOf(250L, 500L, 1_000L, 2_000L)
        val PREFETCH = listOf(0, 1, 2, 3, 4)

        const val DEFAULT_CACHE_CAP_MB = 5_000L
        const val DEFAULT_PREVIEW_CAP_MB = 1_000L

        /** Decimal, as Android shows file sizes: the 5 GB cap reads "5.0 GB". */
        const val MB = 1_000_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** The one settings file (spec §5.2, `core/datastore`). Values arrive as they change. */
class SettingsStore(private val store: DataStore<PxtSettings>) {
    val settings: Flow<PxtSettings> = store.data

    suspend fun current(): PxtSettings = store.data.first()

    suspend fun update(transform: (PxtSettings) -> PxtSettings) {
        store.updateData(transform)
    }

    suspend fun updateStorage(transform: (StorageSettings) -> StorageSettings) =
        update { it.copy(storage = transform(it.storage)) }

    suspend fun updateSort(transform: (SortSettings) -> SortSettings) =
        update { it.copy(sort = transform(it.sort)) }

    suspend fun updateHistory(transform: (HistorySettings) -> HistorySettings) =
        update { it.copy(history = transform(it.history)) }

    suspend fun updateSync(transform: (SyncSettings) -> SyncSettings) =
        update { it.copy(sync = transform(it.sync)) }

    suspend fun updateView(transform: (ViewSettings) -> ViewSettings) =
        update { it.copy(view = transform(it.view)) }

    suspend fun updateDevice(transform: (DeviceState) -> DeviceState) =
        update { it.copy(device = transform(it.device)) }

    suspend fun updateEditor(transform: (EditorSettings) -> EditorSettings) =
        update { it.copy(editor = transform(it.editor)) }
}

/** Settings as settings sync sends them (spec §4.8): JSON, without this phone's own state. */
object SettingsJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: PxtSettings): String =
        json.encodeToString(PxtSettings.serializer(), settings.copy(device = DeviceState()))

    /** [text] as settings, keeping [local]'s own state; null when it can't be read. */
    fun decode(text: String, local: PxtSettings): PxtSettings? =
        runCatching { json.decodeFromString(PxtSettings.serializer(), text) }
            .getOrNull()
            ?.copy(device = local.device)
}

/** Settings as JSON. Unknown keys from a newer version are skipped; missing ones keep defaults. */
internal object SettingsSerializer : Serializer<PxtSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = PxtSettings()

    override suspend fun readFrom(input: InputStream): PxtSettings = try {
        json.decodeFromString(PxtSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable settings", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable settings", e)
    }

    override suspend fun writeTo(t: PxtSettings, output: OutputStream) =
        output.write(json.encodeToString(PxtSettings.serializer(), t).encodeToByteArray())
}

@Module
@InstallIn(SingletonComponent::class)
internal object SettingsModule {
    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): SettingsStore = SettingsStore(
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            // A damaged file must not keep the app from starting: fall back to the defaults.
            corruptionHandler = ReplaceFileCorruptionHandler { PxtSettings() },
            produceFile = { context.dataStoreFile("settings.json") }
        )
    )
}
