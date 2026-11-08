package com.photoxt.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
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

/** PhotoXT's settings (spec §4.21). Later phases add their groups here. */
@Serializable
data class PxtSettings(val storage: StorageSettings = StorageSettings())

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
