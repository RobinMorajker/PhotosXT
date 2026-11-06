package com.photoxt.core.sync

import androidx.room.withTransaction
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.DriveFolderEntity
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.drive.ImageMetadata
import com.photoxt.core.drive.LIST_FIELDS
import com.photoxt.core.drive.driveLiteral
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TrackedFolderState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** What a scan found. */
data class ScanResult(val folders: Int, val items: Int, val removed: Int)

/** The album id of Drive folder [folderId] seen by [accountId]. */
fun driveAlbumId(accountId: String, folderId: String): String = "drive:$accountId:$folderId"

/** The media id of Drive file [fileId] seen by [accountId]. */
fun driveMediaId(accountId: String, fileId: String): String = "drive:$accountId:$fileId"

/**
 * The initial scan of a tracked Drive folder (spec §4.3, §5.5). It takes the Changes API
 * position first, so nothing changed during the scan is missed, then walks the tree breadth-first
 * with `files.list` (1,000 a page, minimal fields, shared drives included), writing each page in
 * one transaction so the album fills as pages arrive. Folders become nested albums, or one album
 * when [TrackedFolderEntity.mergeSubfolders] is on; shortcuts count as their targets; files other
 * than photos and videos are ignored. Items and subfolders no longer there are removed at the
 * end. Running it again is harmless.
 */
class DriveScanner @Inject constructor(
    private val http: DriveHttp,
    private val database: PxtDatabase
) {
    private val drive = database.driveDao()
    private val index = database.mediaIndexDao()

    suspend fun scan(
        tracked: TrackedFolderEntity,
        zone: ZoneId = ZoneId.systemDefault()
    ): ScanResult {
        val account = DriveAccount(tracked.accountId)
        val token = http.api.startPageToken(account).startPageToken
        drive.saveFirstChangesToken(tracked.accountId, token)
        val root = http.api.get(account, tracked.driveFolderId, "id,name")
        val walk = Walk(tracked, zone)
        val pending = ArrayDeque(listOf(walk.folder(root.id, root.name, parent = null)))
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            var pageToken: String? = null
            do {
                val page = http.api.list(
                    account,
                    "${driveLiteral(folder.id)} in parents and trashed = false",
                    LIST_FIELDS,
                    pageToken
                )
                pending += write(walk, folder, page.files.map { resolve(account, it) })
                pageToken = page.nextPageToken
            } while (pageToken != null)
        }
        val removed = sweep(walk)
        drive.setTrackedState(tracked.albumId, TrackedFolderState.READY, System.currentTimeMillis())
        return ScanResult(walk.folders.size, walk.media.size, removed)
    }

    /** A shortcut as its target, when the target is a folder or a photo or video. */
    private suspend fun resolve(account: DriveAccount, file: DriveFile): DriveFile {
        val details = file.shortcutDetails
        val target = details?.targetId
        if (!file.isShortcut || target == null) return file
        val type = details.targetMimeType.orEmpty()
        val wanted = type == FOLDER_MIME || type.startsWith("image/") || type.startsWith("video/")
        return if (wanted) {
            runCatching {
                http.api.get(account, target)
            }.getOrDefault(file)
        } else {
            file
        }
    }

    /** Writes one page under [folder]; returns the subfolders still to walk. */
    private suspend fun write(walk: Walk, folder: Folder, files: List<DriveFile>): List<Folder> {
        val subfolders = files.filter { it.isFolder && it.id !in walk.folders }
            .map { walk.folder(it.id, it.name, parent = folder) }
        val media = files.filter { it.isMedia && !it.trashed }
            .mapNotNull { walk.item(it, folder) }
        database.withTransaction {
            val albums = (listOf(folder) + subfolders).mapNotNull { walk.album(it) }
            if (albums.isNotEmpty()) index.upsertAlbums(albums)
            drive.upsertFolders(subfolders.map { walk.row(it) } + walk.row(folder))
            if (media.isNotEmpty()) index.upsertMedia(media)
        }
        return subfolders
    }

    /** Removes what the walk no longer found. Returns how many items went. */
    private suspend fun sweep(walk: Walk): Int {
        val rootId = walk.tracked.albumId
        val goneItems = drive.mediaIdsUnder(rootId).filterNot { it in walk.media }
        val goneAlbums = drive.albumIdsUnder(rootId).filterNot { it in walk.albums }
        val goneFolders = drive.foldersOf(rootId).map {
            it.folderId
        }.filterNot { it in walk.folders }
        database.withTransaction {
            goneItems.chunked(MAX_SQL_VARIABLES).forEach { index.deleteMedia(it) }
            goneAlbums.chunked(MAX_SQL_VARIABLES).forEach { drive.deleteAlbums(it) }
            goneFolders.chunked(MAX_SQL_VARIABLES).forEach { drive.deleteFolders(rootId, it) }
        }
        return goneItems.size
    }

    /** A folder met during the walk; [path] is from the tracked folder, ending in `/`. */
    private class Folder(val id: String, val name: String, val parent: Folder?, val path: String)

    /** One scan's bookkeeping: what it has seen, and how folders map to albums. */
    private class Walk(val tracked: TrackedFolderEntity, val zone: ZoneId) {
        val folders = HashSet<String>()
        val albums = HashSet<String>()
        val media = HashSet<String>()
        private val accountId = tracked.accountId

        fun folder(id: String, name: String, parent: Folder?): Folder {
            folders += id
            return Folder(id, name, parent, (parent?.path ?: "") + "$name/")
        }

        /** The album of the folder's items: its own, or the tracked folder's when merged. */
        fun albumOf(folder: Folder): String = when {
            folder.parent == null || tracked.mergeSubfolders -> tracked.albumId
            else -> driveAlbumId(accountId, folder.id)
        }

        fun album(folder: Folder): AlbumEntity? {
            val id = albumOf(folder)
            if (folder.parent != null && tracked.mergeSubfolders) return null
            albums += id
            return AlbumEntity(
                id = id,
                type = AlbumType.DRIVE_FOLDER,
                sourceRef = "drive:${folder.path}",
                title = folder.name,
                accountId = accountId,
                parentId = folder.parent?.let(::albumOf),
                rootId = tracked.albumId
            )
        }

        fun row(folder: Folder) = DriveFolderEntity(
            accountId = accountId,
            folderId = folder.id,
            parentFolderId = folder.parent?.id,
            trackedAlbumId = tracked.albumId,
            name = folder.name
        )

        fun item(file: DriveFile, folder: Folder): MediaEntity? =
            file.toMedia(accountId, albumOf(folder), folder.id, zone)?.also { media += it.id }
    }
}

/** A Drive photo or video as a media row (spec §4.3, "store, per file"). */
internal fun DriveFile.toMedia(
    accountId: String,
    albumId: String,
    parentId: String,
    zone: ZoneId
): MediaEntity? {
    val kind = when {
        mimeType.startsWith("image/") -> MediaKind.IMAGE
        mimeType.startsWith("video/") -> MediaKind.VIDEO
        else -> return null
    }
    val image = imageMediaMetadata
    val video = videoMediaMetadata
    val modified = rfc3339(modifiedTime)
    val takenAt = captureTime(zone)
    return MediaEntity(
        id = driveMediaId(accountId, id),
        source = SourceType.DRIVE,
        volume = null,
        contentUri = null,
        albumId = albumId,
        name = name,
        mime = mimeType,
        kind = kind,
        bytes = size?.toLongOrNull() ?: 0L,
        width = image?.width ?: video?.width ?: 0,
        height = image?.height ?: video?.height ?: 0,
        rotation = Math.floorMod((image?.rotation ?: 0) * QUARTER_TURN, FULL_TURN),
        durationMs = video?.durationMillis?.toLongOrNull(),
        takenAt = takenAt,
        modifiedAt = modified ?: takenAt,
        dayKey = DateGrouping.DAY.keyOf(takenAt, zone),
        monthKey = DateGrouping.MONTH.keyOf(takenAt, zone),
        favorite = starred,
        accountId = accountId,
        remoteId = id,
        remoteParentId = parentId,
        remoteVersion = version?.toLongOrNull() ?: 0L,
        md5 = md5Checksum,
        thumbnailLink = thumbnailLink?.takeIf { hasThumbnail },
        lat = image?.location?.latitude,
        lng = image?.location?.longitude,
        camera = image?.camera()
    )
}

/**
 * When a file was taken: its EXIF capture time; else when it was last changed, which uploaders
 * keep from the original; else when it reached Drive.
 */
private fun DriveFile.captureTime(zone: ZoneId): Long =
    exifTime(imageMediaMetadata?.time, zone) ?: rfc3339(modifiedTime) ?: rfc3339(createdTime) ?: 0L

private fun ImageMetadata.camera(): String? =
    listOfNotNull(cameraMake, cameraModel).joinToString(" ").ifBlank { null }

private const val QUARTER_TURN = 90
private const val FULL_TURN = 360
private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

private fun rfc3339(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** An EXIF `yyyy:MM:dd HH:mm:ss` time, read in [zone] (EXIF has no time zone). */
internal fun exifTime(value: String?, zone: ZoneId): Long? = value?.let {
    runCatching {
        LocalDateTime.parse(it.trim(), EXIF_TIME).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}
