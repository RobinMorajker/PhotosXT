package com.photoxt.core.transfer

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.media.DeviceThumbnail
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.SourceType
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer

/** One binned photo or video, on the phone or in a Drive account (spec §4.7, Trash). */
data class TrashEntry(
    val ref: FileRef,
    /** When it is deleted for good (UTC epoch ms): MediaStore's expiry, or 30 days in Drive. */
    val expiresAt: Long?,
    /** A Coil model of its thumbnail. */
    val thumbnail: Any?,
    /** The Google account's e-mail for a Drive item. */
    val account: String? = null,
    /** Drive files shared with the user can't be restored or deleted by them. */
    val owned: Boolean = true
) {
    val key: String get() = ref.uri ?: "${ref.account}:${ref.fileId}"
}

/** What the Trash view shows: every bin's items, newest first, and which bins couldn't load. */
data class TrashContents(val entries: List<TrashEntry>, val failedAccounts: List<String>)

/**
 * The unified Trash (spec §4.7): MediaStore's trash and the Drive bin of every account, read live
 * when the view opens. Each bin deletes its items itself after 30 days; restoring, deleting for
 * good and emptying go through the transfer queue ([Transfers]).
 */
class TrashRepository @Inject constructor(
    database: PxtDatabase,
    private val device: DeviceStore,
    private val drive: DriveFiles,
    private val store: ThumbnailStore
) {
    private val files = database.fileOpsDao()

    suspend fun load(): TrashContents = withContext(Dispatchers.IO) {
        coroutineScope {
            val phone = async { device.trashed().map { it.toEntry() } }
            val accounts = files.accounts().map { account ->
                async {
                    account.email to runCatching { drive.trashed(account.id) }.map { list ->
                        list.map { file ->
                            val ref = file.toRef(account.id)
                            TrashEntry(
                                ref = ref,
                                expiresAt = file.trashedTime?.let(::rfc3339)
                                    ?.plus(DRIVE_RETENTION_MS),
                                thumbnail = driveThumbnail(account.id, file.id, file),
                                account = account.email,
                                owned = file.ownedByMe
                            )
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        PxtLog.w(TAG, "Couldn't read a Drive bin", it)
                    }
                }
            }.awaitAll()
            val failed = accounts.filter { it.second.isFailure }.map { it.first }
            val entries = phone.await() + accounts.flatMap { it.second.getOrNull().orEmpty() }
            TrashContents(entries.sortedByDescending { it.expiresAt ?: 0 }, failed)
        }
    }

    /** The grid thumbnail PhotoXT still keeps (an orphan's), else Drive's own one. */
    private fun driveThumbnail(
        account: String,
        fileId: String,
        file: com.photoxt.core.drive.DriveFile
    ): Any? {
        val version = file.version?.toLongOrNull()
        val kept = version?.let { store.grid(fileId, it) }?.takeIf { it.exists() }
        return kept ?: file.thumbnailLink?.takeIf { file.hasThumbnail }
            ?.let { DriveLinkThumbnail(account, it.replace(SIZE, "=s$THUMB_PX")) }
    }

    private fun DeviceFile.toEntry() = TrashEntry(
        ref = toRef(),
        expiresAt = expiresAt,
        thumbnail = DeviceThumbnail(uri, modifiedAt ?: 0)
    )

    private companion object {
        const val TAG = "Trash"
        const val THUMB_PX = 384
        const val DRIVE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        val SIZE = Regex("=s\\d+$")

        fun rfc3339(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }
}

/** Drive's own thumbnail of a binned file, fetched with the account's token. */
data class DriveLinkThumbnail(val accountId: String, val link: String)

class DriveLinkThumbnailFetcher(private val data: DriveLinkThumbnail, private val http: DriveHttp) :
    Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(data.link.toHttpUrl())
            .header(DriveHttp.ACCOUNT_HEADER, data.accountId)
            .build()
        val bytes = http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive answered HTTP ${response.code}")
            response.body.bytes()
        }
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), okio.FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory(private val http: DriveHttp) : Fetcher.Factory<DriveLinkThumbnail> {
        override fun create(data: DriveLinkThumbnail, options: Options, imageLoader: ImageLoader) =
            DriveLinkThumbnailFetcher(data, http)
    }
}

class DriveLinkThumbnailKeyer : Keyer<DriveLinkThumbnail> {
    override fun key(data: DriveLinkThumbnail, options: Options) = data.link
}

/** A trash entry is a phone item or a Drive file of an account. */
val TrashEntry.isDrive: Boolean get() = ref.source == SourceType.DRIVE
