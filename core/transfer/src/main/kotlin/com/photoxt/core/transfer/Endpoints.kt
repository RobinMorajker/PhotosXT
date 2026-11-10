package com.photoxt.core.transfer

import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.model.SourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One file as an operation sees it, wherever it lives: a MediaStore item ([uri] in folder [path]
 * of [volume]) or a Drive file ([fileId] in folder [parentId] of [account]). Stored as a job's
 * `src` and `result`; also a folder, for recursive pastes and folder moves.
 */
@Serializable
data class FileRef(
    val source: SourceType,
    val name: String,
    val mime: String,
    val bytes: Long = 0,
    /** PhotoXT's media id, when the item is in an album already. */
    val mediaId: String? = null,
    val uri: String? = null,
    val volume: String? = null,
    /** MediaStore's relative path of the folder, ending in `/`, e.g. `DCIM/Camera/`. */
    val path: String? = null,
    val account: String? = null,
    val fileId: String? = null,
    val parentId: String? = null,
    /** Drive's checksum; MediaStore keeps none, so a phone item's is computed when needed. */
    val md5: String? = null,
    val takenAt: Long? = null,
    val modifiedAt: Long? = null,
    val isFolder: Boolean = false
) {
    val isDrive: Boolean get() = source == SourceType.DRIVE
}

/**
 * Where a job puts its item: folder [path] of [volume] on the phone, or Drive folder [folderId]
 * of [account]. A folder that a recursive paste makes first is [folderJob], the job making it,
 * until that job has run. [title] is what screens call it; [albumId] the album to refresh.
 */
@Serializable
data class Destination(
    val source: SourceType,
    val title: String,
    val albumId: String? = null,
    val volume: String? = null,
    val path: String? = null,
    val account: String? = null,
    val folderId: String? = null,
    val folderJob: Long? = null,
    /** The new name of a rename or conversion; else the item keeps its own. */
    val name: String? = null,
    /** A new top-level Drive folder becomes a tracked album once made (*New album*). */
    val track: Boolean = false
) {
    val isDrive: Boolean get() = source == SourceType.DRIVE
}

/**
 * What a job has done so far, so a killed job resumes instead of starting over (spec §0 rule 4):
 * the name it settled on, a Drive id made in advance, an upload session, a pending MediaStore
 * item, a part-downloaded file, the source's checksum, and the verified copy.
 */
@Serializable
data class JobProgress(
    val name: String? = null,
    /** The item of that name a *Replace* bins once the copy is verified. */
    val replaces: FileRef? = null,
    val generatedId: String? = null,
    val session: String? = null,
    val pendingUri: String? = null,
    val tempPath: String? = null,
    val md5: String? = null,
    /** The copy, verified; a cut then only has its source left to remove. */
    val copy: FileRef? = null,
    /** The Drive folder a moved file or folder came from, for Undo. */
    val fromParent: String? = null,
    /** The phone item MediaStore wants the user's consent for. */
    val consentUri: String? = null,
    /** A Drive file the user may not bin leaves its folder instead (their choice). */
    val removeInstead: Boolean = false
)

internal val TransferJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

internal fun FileRef.encode(): String = TransferJson.encodeToString(FileRef.serializer(), this)

internal fun Destination.encode(): String =
    TransferJson.encodeToString(Destination.serializer(), this)

internal fun JobProgress.encode(): String =
    TransferJson.encodeToString(JobProgress.serializer(), this)

val TransferJobEntity.source: FileRef
    get() = TransferJson.decodeFromString(FileRef.serializer(), src)

val TransferJobEntity.target: Destination?
    get() = dst?.let { TransferJson.decodeFromString(Destination.serializer(), it) }

val TransferJobEntity.outcome: FileRef?
    get() = result?.let { TransferJson.decodeFromString(FileRef.serializer(), it) }

internal val TransferJobEntity.saved: JobProgress
    get() = progress?.let { TransferJson.decodeFromString(JobProgress.serializer(), it) }
        ?: JobProgress()

/** A media row as a [FileRef]; [album] gives a phone item its folder. */
internal fun MediaEntity.toRef(album: AlbumEntity?): FileRef = when (source) {
    SourceType.DEVICE -> FileRef(
        source = SourceType.DEVICE,
        name = name,
        mime = mime,
        bytes = bytes,
        mediaId = id,
        uri = contentUri,
        volume = volume,
        path = album?.devicePath,
        takenAt = takenAt,
        modifiedAt = modifiedAt
    )

    SourceType.DRIVE -> FileRef(
        source = SourceType.DRIVE,
        name = name,
        mime = mime,
        bytes = bytes,
        mediaId = id,
        account = accountId,
        fileId = remoteId,
        parentId = remoteParentId,
        md5 = md5,
        takenAt = takenAt,
        modifiedAt = modifiedAt
    )
}

/** A phone album's `<volume>:<path>` source ref split into its parts. */
internal val AlbumEntity.deviceVolume: String get() = sourceRef.substringBefore(':')

internal val AlbumEntity.devicePath: String get() = sourceRef.substringAfter(':')

/** The last folder name of a relative path: `Pictures/Trip/` → `Trip`. */
internal fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** `IMG_1.jpg` → `IMG_1` and `.jpg`; a name without a dot has no extension. */
internal fun splitName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

/** `IMG_1.jpg` with suffix 2 → `IMG_1 (2).jpg`: Keep both (spec §4.7). */
internal fun suffixed(name: String, n: Int): String {
    val (base, extension) = splitName(name)
    return "$base ($n)$extension"
}
