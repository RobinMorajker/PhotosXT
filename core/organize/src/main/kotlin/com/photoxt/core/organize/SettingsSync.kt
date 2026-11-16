package com.photoxt.core.organize

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photoxt.core.auth.Authorization
import com.photoxt.core.auth.ConsentRequiredException
import com.photoxt.core.auth.DriveAuth
import com.photoxt.core.auth.DriveScope
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.PxtSettings
import com.photoxt.core.datastore.SettingsJson
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.drive.DriveAppData
import com.photoxt.core.sync.DriveSync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Where settings sync stands, for the Settings screen. */
data class SyncStatus(
    val enabled: Boolean,
    val accountId: String?,
    val email: String?,
    val syncedAt: Long?,
    /** The account hasn't allowed PhotoXT its app-data folder yet. */
    val needsConsent: Boolean,
    val running: Boolean,
    val failed: Boolean
)

/**
 * Settings sync (spec §4.8): the album list, album options and groups, PhotoXT's own albums,
 * sort choices, pins, bookmarks, hidden items, history and settings, as one file in the backup
 * account's Drive app-data folder (`drive.appdata`, non-sensitive). A reinstall restores it all
 * once that account is signed in again; no PhotoXT server is involved.
 *
 * Changes upload 15 s after they settle, through WorkManager when there is a network. Nothing is
 * uploaded until this install has looked for a copy to restore: a fresh install must never
 * replace the user's copy with its empty state.
 */
@Singleton
class SettingsSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: PxtDatabase,
    private val settings: SettingsStore,
    private val appData: DriveAppData,
    private val auth: DriveAuth,
    private val driveSync: DriveSync,
    private val clock: Clock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private val restoredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val backups = SnapshotTables(database)

    /** A copy from Drive was restored: the app says so once. */
    val restored: SharedFlow<Unit> = restoredEvents.asSharedFlow()

    val status: Flow<SyncStatus> = combine(
        settings.settings,
        database.driveDao().accounts(),
        running,
        failed
    ) { all, accounts, run, fail ->
        val account = accountOf(all, accounts)
        SyncStatus(
            enabled = all.sync.enabled,
            accountId = account?.id,
            email = account?.email,
            syncedAt = all.device.syncedAt,
            needsConsent = account != null && !DriveScope.APP_DATA.grantedIn(account.scopes),
            running = run,
            failed = fail
        )
    }

    /** Call once, from `Application.onCreate`. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            merge(
                settings.settings.map { SettingsJson.encode(it) }.distinctUntilChanged().drop(1)
                    .map { },
                // The user's own tables; PhotoXT's own albums live among the folders' in album.
                database.invalidationTracker.createFlow(
                    "tracked_folder",
                    "album",
                    "album_prefs",
                    "album_group",
                    "album_item",
                    "item_order",
                    "pin",
                    "bookmark_collection",
                    "bookmark",
                    "hidden_item",
                    "history",
                    "playback_position",
                    emitInitialState = false
                ).map { }
            )
                .debounce(DEBOUNCE_MS)
                .collect { schedule() }
        }
        // An update or a reinstall that already has its account: look for a copy once.
        scope.launch { if (!settings.current().device.restoreChecked) schedule() }
        // An account signed in (a reinstall's first): its restored albums, and the restore.
        scope.launch {
            var known: Set<String>? = null
            database.driveDao().accounts().collect { accounts ->
                val ids = accounts.mapTo(HashSet()) { it.id }
                known?.let { before -> (ids - before).forEach { accountAdded(it) } }
                known = ids
            }
        }
    }

    /** Keeps the copy in [accountId]'s Drive from now on; it is sent there in full. */
    suspend fun setAccount(accountId: String) {
        settings.update {
            it.copy(
                sync = it.sync.copy(accountId = accountId),
                device = it.device.copy(syncedHash = null, syncedAt = null)
            )
        }
        schedule()
    }

    /** Uploads soon, when there is a network; a run going on is followed by another. */
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SettingsSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }

    /**
     * Looks for a copy to restore if this install hasn't yet, then uploads what changed. False
     * when Drive couldn't be reached (try again later); true when done or waiting for the user.
     */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val current = settings.current()
        if (!current.sync.enabled) return@withLock true
        val account = accountOf(current, database.driveDao().accountList()) ?: return@withLock true
        running.value = true
        try {
            // Without the app-data folder it waits for the user (the Settings screen asks).
            if (!allowed(account)) return@withLock true
            if (!current.device.restoreChecked) restoreFrom(account)
            upload(account)
            failed.value = false
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: ConsentRequiredException) {
            PxtLog.w(TAG, "Settings sync waits for Drive consent", error)
            true
        } catch (error: IOException) {
            PxtLog.w(TAG, "Settings sync failed", error)
            failed.value = true
            false
        } finally {
            running.value = false
        }
    }

    /**
     * Account [accountId] was signed in: albums of a restored copy that waited for it are added,
     * and a fresh install looks for its copy.
     */
    suspend fun accountAdded(accountId: String) {
        addPendingFolders(accountId)
        if (!settings.current().device.restoreChecked) schedule()
    }

    /** Asks the backup account for its app-data folder; the screen shows Google's consent. */
    suspend fun authorize(): Authorization? {
        val account = accountOf(settings.current(), database.driveDao().accountList())
            ?: return null
        val result = auth.authorize(account.id, DriveScope.APP_DATA)
        if (result is Authorization.Granted) scope.launch { syncNow() }
        return result
    }

    /** Google's consent screen answered. */
    suspend fun consentResult(data: Intent?) {
        val account = accountOf(settings.current(), database.driveDao().accountList()) ?: return
        if (auth.completeConsent(account.id, data)) scope.launch { syncNow() }
    }

    private suspend fun allowed(account: AccountEntity): Boolean {
        if (DriveScope.APP_DATA.grantedIn(account.scopes)) return true
        // Granted before (a reinstall): Play services answers without a screen.
        return auth.authorize(account.id, DriveScope.APP_DATA) is Authorization.Granted
    }

    private suspend fun restoreFrom(account: AccountEntity) {
        val file = appData.find(account.id, FILE_NAME)
        if (file != null) {
            val snapshot = Snapshots.unpack(appData.read(account.id, file.id))
            if (snapshot != null) {
                apply(snapshot)
                settings.updateDevice {
                    it.copy(
                        restoreChecked = true,
                        syncedAt = snapshot.savedAt,
                        syncedHash = Snapshots.hash(snapshot)
                    )
                }
                restoredEvents.tryEmit(Unit)
                PxtLog.i(TAG, "Restored settings from Drive")
                return
            }
            PxtLog.w(TAG, "The copy in Drive couldn't be read; keeping it")
            throw IOException("Unreadable settings copy")
        }
        settings.updateDevice { it.copy(restoreChecked = true) }
    }

    private suspend fun upload(account: AccountEntity) {
        val snapshot = backups.read(settings.current())
        val hash = Snapshots.hash(snapshot)
        if (hash == settings.current().device.syncedHash) return
        val now = clock.millis()
        val bytes = Snapshots.pack(snapshot.copy(savedAt = now))
        val existing = appData.find(account.id, FILE_NAME)
        appData.write(account.id, FILE_NAME, bytes, GZIP, existing?.id)
        settings.updateDevice { it.copy(syncedAt = now, syncedHash = hash) }
        PxtLog.i(TAG, "Settings synced (${(bytes.size + KIB - 1) / KIB} KiB)")
    }

    /** Restores [snapshot]: settings, the user's tables, and the Drive albums it lists. */
    private suspend fun apply(snapshot: Snapshot) {
        val local = settings.current()
        SettingsJson.decode(snapshot.settings, local)?.let { restored ->
            settings.update { restored.copy(device = it.device) }
        }
        backups.replace(snapshot)
        savePending(snapshot.folders)
        database.driveDao().accountList().forEach { addPendingFolders(it.id) }
    }

    /** Adds the restored Drive albums of [accountId] that wait for it. */
    private suspend fun addPendingFolders(accountId: String) {
        val pending = loadPending()
        val (now, later) = pending.partition { it.accountId == accountId }
        if (now.isEmpty()) return
        now.forEach { folder ->
            runCatching {
                val albumId = driveSync.addFolder(
                    accountId,
                    folder.folderId,
                    folder.name,
                    folder.driveId,
                    folder.merge
                )
                if (folder.offline) database.driveDao().setOffline(albumId, true)
            }.onFailure {
                if (it is CancellationException) throw it
                PxtLog.w(TAG, "A restored Drive album couldn't be added", it)
            }
        }
        savePending(later)
    }

    private val pendingFile: File get() = File(context.filesDir, "settings-sync/pending.json")

    private fun loadPending(): List<SnapFolder> = runCatching {
        Snapshots.json.decodeFromString(
            ListSerializer(SnapFolder.serializer()),
            pendingFile.readText()
        )
    }.getOrDefault(emptyList())

    private fun savePending(folders: List<SnapFolder>) {
        if (folders.isEmpty()) {
            pendingFile.delete()
            return
        }
        pendingFile.parentFile?.mkdirs()
        pendingFile.writeText(
            Snapshots.json.encodeToString(ListSerializer(SnapFolder.serializer()), folders)
        )
    }

    private fun accountOf(all: PxtSettings, accounts: List<AccountEntity>): AccountEntity? =
        accounts.firstOrNull { it.id == all.sync.accountId } ?: accounts.minByOrNull { it.addedAt }

    companion object {
        private const val TAG = "SettingsSync"
        private const val WORK = "settings-sync"
        private const val DEBOUNCE_MS = 15_000L
        private const val KIB = 1024
        private const val GZIP = "application/gzip"

        /** Gzipped since 2026-11-16; the name stays, so a copy written before still restores. */
        const val FILE_NAME = "photoxt-settings.json"
    }
}

/** A settings sync run, as WorkManager starts it. */
class SettingsSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(
            applicationContext,
            OrganizeEntryPoint::class.java
        ).settingsSync()
        return if (sync.syncNow()) Result.success() else Result.retry()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OrganizeEntryPoint {
    fun settingsSync(): SettingsSync
}

/** SHA-256 of [text], hex. */
internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
