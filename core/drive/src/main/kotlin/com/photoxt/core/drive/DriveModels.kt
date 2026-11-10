package com.photoxt.core.drive

import kotlinx.serialization.Serializable

/**
 * A Drive file with the fields PhotoXT stores (spec §4.3). Drive sends 64-bit numbers as JSON
 * strings, so [size], [version] and durations stay strings here and are read through helpers.
 */
@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val size: String? = null,
    val md5Checksum: String? = null,
    val version: String? = null,
    val modifiedTime: String? = null,
    val createdTime: String? = null,
    val parents: List<String> = emptyList(),
    val starred: Boolean = false,
    val trashed: Boolean = false,
    val hasThumbnail: Boolean = false,
    val thumbnailLink: String? = null,
    val driveId: String? = null,
    val imageMediaMetadata: ImageMetadata? = null,
    val videoMediaMetadata: VideoMetadata? = null,
    val shortcutDetails: ShortcutDetails? = null,
    /** When the file went to the bin (RFC 3339), for the Trash view. */
    val trashedTime: String? = null,
    val ownedByMe: Boolean = true
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isShortcut: Boolean get() = mimeType == SHORTCUT_MIME
    val isMedia: Boolean get() = mimeType.startsWith("image/") || mimeType.startsWith("video/")
}

/**
 * The metadata PhotoXT writes: a new name, folder or bin state, or a whole new file (folder or
 * upload). Fields left null are not sent ([DriveHttp.json] skips them), so an update changes only
 * what is set. [id] names a file id made in advance by `generateIds`.
 */
@Serializable
data class DriveFileWrite(
    val id: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val trashed: Boolean? = null,
    /** RFC 3339; kept from the original on uploads, so dates survive the copy. */
    val modifiedTime: String? = null
)

@Serializable
data class GeneratedIds(val ids: List<String> = emptyList())

@Serializable
data class ImageMetadata(
    val width: Int? = null,
    val height: Int? = null,
    /** Clockwise quarter turns applied to the original. */
    val rotation: Int? = null,
    /** EXIF capture time, `yyyy:MM:dd HH:mm:ss` in the camera's local time. */
    val time: String? = null,
    val location: GeoLocation? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null
)

@Serializable
data class GeoLocation(val latitude: Double? = null, val longitude: Double? = null)

@Serializable
data class VideoMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: String? = null
)

@Serializable
data class ShortcutDetails(val targetId: String? = null, val targetMimeType: String? = null)

@Serializable
data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class StartPageToken(val startPageToken: String)

@Serializable
data class ChangeList(
    val changes: List<Change> = emptyList(),
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null
)

@Serializable
data class Change(
    val changeType: String? = null,
    val fileId: String? = null,
    val removed: Boolean = false,
    val file: DriveFile? = null,
    val driveId: String? = null
)

@Serializable
data class SharedDrive(val id: String, val name: String = "")

@Serializable
data class SharedDriveList(
    val drives: List<SharedDrive> = emptyList(),
    val nextPageToken: String? = null
)

const val FOLDER_MIME = "application/vnd.google-apps.folder"
const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

/** Everything the scan stores per file (spec §4.3), and nothing more. */
const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,version,modifiedTime,createdTime," +
    "parents,starred,trashed,hasThumbnail,thumbnailLink,driveId," +
    "imageMediaMetadata(width,height,rotation,time,location,cameraMake,cameraModel)," +
    "videoMediaMetadata(width,height,durationMillis),shortcutDetails(targetId,targetMimeType)"

const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"

/** What the Trash view shows of a binned Drive photo or video (spec §4.7, Trash). */
const val TRASH_FIELDS = "nextPageToken,files(id,name,mimeType,size,md5Checksum,version," +
    "modifiedTime,trashedTime,ownedByMe,hasThumbnail,thumbnailLink,parents," +
    "imageMediaMetadata(width,height,rotation,time),videoMediaMetadata(durationMillis))"

/** What the folder browser shows. */
const val FOLDER_LIST_FIELDS =
    "nextPageToken,files(id,name,mimeType,driveId,shortcutDetails(targetId,targetMimeType))"
