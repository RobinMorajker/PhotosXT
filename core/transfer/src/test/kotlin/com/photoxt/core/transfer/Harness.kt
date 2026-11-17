package com.photoxt.core.transfer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.DriveFolderEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.DriveUploads
import com.photoxt.core.media.DeviceSource
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.DriveSource
import com.photoxt.core.media.MediaSources
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Ellipse01
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.ExportRequest
import com.photoxt.core.render.ExportResult
import com.photoxt.core.render.PhotoExporter
import com.photoxt.core.vault.Vault
import com.photoxt.core.vault.VaultKeys
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Everything a transfer test needs: a fake Drive and a fake MediaStore, a Room database seeded
 * with albums and items, and the engine, [TransferQueue] and [Transfers] built on them as the app
 * builds them.
 */
class Harness : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val drive = FakeDrive().apply { start() }
    val device = FakeDevice()
    val clock = MovableClock(Instant.parse("2026-11-08T10:00:00Z"))
    val database: PxtDatabase = Room.inMemoryDatabaseBuilder(context, PxtDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val env = FakeEnvironment(File(context.cacheDir, "transfer-work").apply { deleteRecursively() })
    private val http = DriveHttp(
        object : DriveTokens {
            override suspend fun token(accountId: String) = "token-$accountId"

            override suspend fun invalidate(accountId: String, token: String) = Unit
        },
        drive.server.url("/")
    )
    private val store =
        ThumbnailStore(File(context.cacheDir, "transfer-store").apply { deleteRecursively() })
    private val originals = DriveOriginals(
        database.driveDao(),
        database.driveCacheDao(),
        MediaSources(DeviceSource(context), DriveSource(http)),
        store,
        clock
    )
    val files = DriveFiles(http, DriveUploads(http), originals)
    val kicks = mutableListOf<Boolean>()
    val vault = Vault(context, testVaultKeys()).also {
        File(context.filesDir, "vault").deleteRecursively()
    }

    /** A new engine, as a new process would have after the old one was killed. */
    fun newEngine() = TransferEngine(database, device, files, env, clock, vault, store, exporter)

    /** Renders an "edit" without a GPU: the original's bytes, reversed, plus the recipe. */
    val exporter = FakeExporter()

    var engine = newEngine()
    val queue: TransferQueue
        get() = TransferQueue(
            database,
            engine,
            { kicks += it },
            TransferNotifications(context),
            clock
        )
    val transfers: Transfers
        get() = Transfers(database, queue, device, PdfExport(context, device, files), clock)

    val dao get() = database.transferDao()

    suspend fun drain() = engine.drain()

    // --- Seeding -------------------------------------------------------------------------

    suspend fun phoneAlbum(volume: String, path: String): String {
        val id = "device:${"$volume:$path".hashCode()}"
        database.mediaIndexDao().upsertAlbums(
            listOf(AlbumEntity(id, AlbumType.DEVICE_FOLDER, "$volume:$path", folderName(path)))
        )
        return id
    }

    /** A camera photo in [path] that PhotoXT may change (granted, as with media management). */
    suspend fun phoneItem(
        volume: String,
        path: String,
        name: String,
        bytes: ByteArray,
        granted: Boolean = true
    ): String {
        val album = phoneAlbum(volume, path)
        val uri = device.add(volume, path, name, bytes)
        if (granted) device.granted += uri
        val id = "device:${uri.substringAfterLast('/')}"
        database.mediaIndexDao().upsertMedia(
            listOf(
                media(id, SourceType.DEVICE, album, name, bytes.size.toLong())
                    .copy(volume = volume, contentUri = uri)
            )
        )
        return id
    }

    suspend fun account(id: String, full: Boolean = true) {
        database.driveDao().upsertAccount(
            AccountEntity(
                id,
                "$id@example.com",
                null,
                null,
                if (full) FULL else READONLY,
                false,
                "1",
                null,
                1
            )
        )
        if (!full) env.noScope += id
    }

    /** A tracked Drive folder [folderId] of [account] as an album; its parent is "root". */
    suspend fun driveAlbum(account: String, folderId: String, name: String): String {
        val albumId = "drive:$account:$folderId"
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    albumId,
                    AlbumType.DRIVE_FOLDER,
                    "drive:$name/",
                    name,
                    account,
                    null,
                    albumId
                )
            )
        )
        database.driveDao().insertTracked(
            TrackedFolderEntity(
                albumId,
                account,
                folderId,
                null,
                false,
                TrackedFolderState.READY,
                1
            )
        )
        database.driveDao().upsertFolders(
            listOf(DriveFolderEntity(account, folderId, null, albumId, name))
        )
        if (drive.files[folderId] == null) drive.folder(account, folderId, name, "root")
        return albumId
    }

    /** A subfolder of tracked album [rootAlbum], as its own nested album. */
    suspend fun driveSubAlbum(
        account: String,
        rootAlbum: String,
        parent: String,
        folderId: String,
        name: String
    ): String {
        val albumId = "drive:$account:$folderId"
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    albumId,
                    AlbumType.DRIVE_FOLDER,
                    "drive:$name/",
                    name,
                    account,
                    rootAlbum,
                    rootAlbum
                )
            )
        )
        database.driveDao().upsertFolders(
            listOf(DriveFolderEntity(account, folderId, parent, rootAlbum, name))
        )
        drive.folder(account, folderId, name, parent)
        return albumId
    }

    @Suppress("LongParameterList") // Where the item is in Drive, and as an album item.
    suspend fun driveItem(
        account: String,
        albumId: String,
        folderId: String,
        fileId: String,
        name: String,
        bytes: ByteArray
    ): String {
        drive.add(account, fileId, name, folderId, bytes)
        val id = "drive:$account:$fileId"
        database.mediaIndexDao().upsertMedia(
            listOf(
                media(id, SourceType.DRIVE, albumId, name, bytes.size.toLong()).copy(
                    accountId = account,
                    remoteId = fileId,
                    remoteParentId = folderId,
                    remoteVersion = 1,
                    md5 = FakeDrive.md5(bytes)
                )
            )
        )
        return id
    }

    private fun media(id: String, source: SourceType, album: String, name: String, bytes: Long) =
        MediaEntity(
            id = id,
            source = source,
            volume = null,
            contentUri = null,
            albumId = album,
            name = name,
            mime = "image/jpeg",
            kind = MediaKind.IMAGE,
            bytes = bytes,
            width = 4,
            height = 3,
            rotation = 0,
            durationMs = null,
            takenAt = 1_700_000_000_000,
            modifiedAt = 1_700_000_000_000,
            dayKey = 0,
            monthKey = 0,
            favorite = false
        )

    override fun close() {
        database.close()
        drive.close()
    }

    companion object {
        const val PRIMARY = "external_primary"
        const val SD = "1234-5678"
        const val FULL = "https://www.googleapis.com/auth/drive"
        const val READONLY = "https://www.googleapis.com/auth/drive.readonly"

        fun bytes(size: Int, seed: Int = 7) =
            ByteArray(size) { ((it * seed + it / 7) % 251).toByte() }
    }
}

/** The GPU export stood in for: a deterministic function of the original and the recipe. */
class FakeExporter : PhotoExporter {
    val exported = mutableListOf<String>()

    override suspend fun export(request: ExportRequest, progress: (Float) -> Unit): ExportResult {
        val json = EditJson.encode(request.recipe)
        exported += json
        request.output.writeBytes(request.input.readBytes().reversedArray() + json.toByteArray())
        return ExportResult(request.inputMime, 1, 1, request.output.length())
    }
}

class FakeEnvironment(override val workDir: File) : TransferEnvironment {
    val noScope = HashSet<String>()
    val written = mutableListOf<String>()
    override var readsOriginals = true
    val tracked = mutableListOf<String>()
    override var online = true

    override suspend fun canWriteDrive(account: String) = account !in noScope

    override suspend fun driveWritten(account: String) {
        written += account
    }

    override suspend fun trackFolder(account: String, folderId: String, name: String) {
        tracked += "$account:$folderId:$name"
    }

    /** What the face finder finds in every photo. */
    var faces: List<Ellipse01> = emptyList()

    override suspend fun faces(photo: File): List<Ellipse01> = faces
}

/** The vault's key without the Android Keystore, which Robolectric doesn't have. */
fun testVaultKeys(): VaultKeys {
    StreamingAeadConfig.register()
    val config = RegistryConfiguration.get()
    val aead = KeysetHandle.generateNew(
        PredefinedStreamingAeadParameters.AES128_GCM_HKDF_4KB,
        config
    )
        .getPrimitive(config, StreamingAead::class.java)
    return VaultKeys { aead }
}

/** A clock that moves only when told: retries wait out their backoff on it. */
class MovableClock(private var now: Instant) : Clock() {
    fun advance(millis: Long) {
        now = now.plusMillis(millis)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
