package com.photoxt.core.transfer

import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveHead
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.DriveRevision
import com.photoxt.core.drive.DriveRevisionWrite
import java.io.InputStream

/**
 * A Drive file's versions (spec §4.10, Revert): an edit is saved as the file's next version, and
 * the original one is marked to be kept forever, so Revert can bring it back at any time.
 */
class DriveRevisions internal constructor(private val http: DriveHttp) {
    private val api get() = http.revisionApi

    /** [fileId]'s current revision and checksum. */
    suspend fun head(account: String, fileId: String): DriveHead =
        api.headRevision(DriveAccount(account), fileId)

    /** Keeps [revision] of [fileId] forever. Drive never takes that back; see [delete]. */
    suspend fun keep(account: String, fileId: String, revision: String): DriveRevision =
        api.keepRevision(DriveAccount(account), fileId, revision, DriveRevisionWrite(true))

    /** Deletes [revision] of [fileId] for good, kept or not. */
    suspend fun delete(account: String, fileId: String, revision: String) =
        api.deleteRevision(DriveAccount(account), fileId, revision)

    /** [revision] of [fileId]'s bytes from [offset] on. The caller closes the stream. */
    suspend fun download(
        account: String,
        fileId: String,
        revision: String,
        offset: Long
    ): InputStream {
        val response = http.open(account, http.revisionUrl(fileId, revision), offset)
        val stream = ResponseStream(response)
        // A server that ignores the range answers 200 with the whole file.
        if (response.code != DriveHttp.HTTP_PARTIAL && offset > 0) stream.skipExactly(offset)
        return stream
    }
}
