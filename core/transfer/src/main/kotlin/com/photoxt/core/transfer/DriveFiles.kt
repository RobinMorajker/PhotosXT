package com.photoxt.core.transfer

import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.DriveFileWrite
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.DriveUploads
import com.photoxt.core.drive.FILE_FIELDS
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.drive.TRASH_FIELDS
import com.photoxt.core.drive.driveLiteral
import com.photoxt.core.drive.driveStatus
import com.photoxt.core.media.DriveOriginals
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import okhttp3.Response

/**
 * Drive's side of file operations (spec §4.7): copies and moves inside one account on the server,
 * uploads and downloads that resume, binning, restoring, deleting, renaming and new folders.
 * Every call here writes Drive, so it needs the `drive` scope (spec §2).
 */
class DriveFiles @Inject constructor(
    private val http: DriveHttp,
    /** Resumable uploads: sessions, chunks and their status. */
    val uploads: DriveUploads,
    private val originals: DriveOriginals
) {
    private val api get() = http.api

    /** A file id made in advance, so creating the file twice is impossible. */
    suspend fun newId(account: String): String =
        api.generateIds(DriveAccount(account), count = 1).ids.first()

    /** File [id] if Drive has it (binned or not); null when it doesn't. */
    suspend fun find(account: String, id: String): DriveFile? = runCatching {
        api.get(DriveAccount(account), id, FILE_FIELDS)
    }.getOrElse { error ->
        if (error.driveStatus == HTTP_NOT_FOUND) null else throw error
    }

    /** A file named [name] in [folderId], outside the bin, if there is one. */
    suspend fun findByName(account: String, folderId: String, name: String): DriveFile? = api.list(
        DriveAccount(account),
        "${driveLiteral(folderId)} in parents and name = ${driveLiteral(name)} and " +
            "trashed = false",
        "files($FILE_FIELDS)",
        pageSize = 1
    ).files.firstOrNull()

    suspend fun copy(account: String, fileId: String, write: DriveFileWrite): DriveFile =
        api.copy(DriveAccount(account), fileId, write)

    /**
     * Moves [fileId] from [from] to [to] (instant), renamed to [name] when set. Without [from],
     * it leaves whichever folder it is in.
     */
    suspend fun move(
        account: String,
        fileId: String,
        from: String?,
        to: String,
        name: String?
    ): DriveFile {
        val leaving = from ?: parentOf(account, fileId)
        return api.update(
            DriveAccount(account),
            fileId,
            DriveFileWrite(name = name),
            addParents = to,
            removeParents = leaving?.takeIf { it != to }
        )
    }

    /** The folder [fileId] is in. */
    suspend fun parentOf(account: String, fileId: String): String? =
        api.get(DriveAccount(account), fileId, "id,parents").parents.firstOrNull()

    /**
     * Renames [fileId], keeping its modified time [modifiedAt]: Drive would set it to now, and a
     * file without a capture time in its EXIF (a video, a screenshot) is dated by it.
     */
    suspend fun rename(
        account: String,
        fileId: String,
        name: String,
        modifiedAt: Long?
    ): DriveFile {
        val kept = modifiedAt?.let { Instant.ofEpochMilli(it).toString() }
        return api.update(
            DriveAccount(account),
            fileId,
            DriveFileWrite(name = name, modifiedTime = kept)
        )
    }

    /** Bins [fileId], or takes it back out of the bin. */
    suspend fun setTrashed(account: String, fileId: String, trashed: Boolean): DriveFile =
        api.update(DriveAccount(account), fileId, DriveFileWrite(trashed = trashed))

    /** Takes [fileId] out of folder [folderId] without binning it (spec §4.7, not owned). */
    suspend fun removeFrom(account: String, fileId: String, folderId: String) =
        api.removeParent(DriveAccount(account), fileId, DriveFileWrite(), removeParents = folderId)

    /** Deletes [fileId] for good; one already gone counts as deleted. */
    suspend fun delete(account: String, fileId: String) {
        runCatching { api.delete(DriveAccount(account), fileId) }.onFailure { error ->
            if (error.driveStatus != HTTP_NOT_FOUND) throw error
        }
    }

    suspend fun createFolder(account: String, id: String, name: String, parent: String): DriveFile =
        api.create(
            DriveAccount(account),
            DriveFileWrite(id = id, name = name, mimeType = FOLDER_MIME, parents = listOf(parent))
        )

    /** Binned photos and videos of [account], newest first (spec §4.7, Trash). */
    suspend fun trashed(account: String): List<DriveFile> {
        val files = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val list = api.list(
                DriveAccount(account),
                "trashed = true and (mimeType contains 'image/' or mimeType contains 'video/')",
                TRASH_FIELDS,
                pageToken = page,
                orderBy = "modifiedTime desc"
            )
            files += list.files
            page = list.nextPageToken
        } while (page != null)
        return files
    }

    /** [fileId]'s bytes from [offset] on. The caller closes the stream. */
    suspend fun download(account: String, fileId: String, offset: Long): InputStream {
        val response = http.open(account, http.mediaUrl(fileId), offset)
        val stream = ResponseStream(response)
        // A server that ignores the range answers 200 with the whole file.
        if (response.code != DriveHttp.HTTP_PARTIAL && offset > 0) stream.skipExactly(offset)
        return stream
    }

    /** The original PhotoXT already downloaded and checked for [mediaId], if any. */
    suspend fun cachedOriginal(mediaId: String): File? = originals.localFile(mediaId)

    private class ResponseStream(private val response: Response) :
        FilterInputStream(response.body.byteStream()) {
        override fun close() = response.close()
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}

/** Skips exactly [count] bytes, or fails if the stream ends first. */
internal fun InputStream.skipExactly(count: Long) {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped <= 0) {
            if (read() < 0) throw java.io.EOFException("The stream ended early")
            left--
        } else {
            left -= skipped
        }
    }
}
