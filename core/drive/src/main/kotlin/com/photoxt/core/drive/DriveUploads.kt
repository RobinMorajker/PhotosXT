package com.photoxt.core.drive

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** How far a resumable upload got: [Partial] bytes Drive holds, or the [Done] file. */
sealed interface UploadStatus {
    data class Partial(val received: Long) : UploadStatus

    data class Done(val file: DriveFile) : UploadStatus
}

/** A resumable upload of [size] bytes for [account] through Drive's session [url]. */
data class UploadSession(val account: String, val url: String, val size: Long)

/** Drive no longer knows the upload session (it expired after about a week): start a new one. */
class UploadSessionGoneException : IOException("The upload session expired")

/**
 * Drive's resumable uploads (spec §4.7, "Drive resumable uploads"): a session opened with the
 * file's metadata, then the bytes in chunks, each one acknowledged. A session outlives the app,
 * so a killed upload asks Drive how much it holds ([status]) and continues from there.
 */
class DriveUploads(private val http: DriveHttp) {
    // Drive answers a chunk with 308 "Resume Incomplete", which is not a redirect to follow.
    private val client: OkHttpClient = http.client.newBuilder().followRedirects(false).build()

    private val uploadUrl: HttpUrl = http.baseUrl.newBuilder()
        .addPathSegments("upload/drive/v3/files")
        .addQueryParameter("uploadType", "resumable")
        .addQueryParameter("supportsAllDrives", "true")
        .addQueryParameter("fields", FILE_FIELDS)
        .build()

    /** Opens a session for a [size]-byte [mime] file with [metadata]. */
    suspend fun start(
        accountId: String,
        metadata: DriveFileWrite,
        mime: String,
        size: Long
    ): UploadSession {
        val body = DriveHttp.json.encodeToString(DriveFileWrite.serializer(), metadata)
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(uploadUrl)
            .tag(DriveAccount::class.java, DriveAccount(accountId))
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", size.toString())
            .post(body)
            .build()
        val url = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw DriveHttpException(response.code)
            response.header("Location") ?: throw IOException("Drive sent no upload session")
        }
        return UploadSession(accountId, url, size)
    }

    /** How much of the upload [session] holds, or the file when it is complete. */
    suspend fun status(session: UploadSession): UploadStatus =
        send(session, EMPTY, "bytes */${session.size}")

    /**
     * Sends [count] bytes of [buffer] at [offset] of the upload. Chunks other than the last must
     * be multiples of [CHUNK_UNIT].
     */
    suspend fun put(
        session: UploadSession,
        offset: Long,
        buffer: ByteArray,
        count: Int
    ): UploadStatus {
        val size = session.size
        val range = if (count == 0) "bytes */$size" else "bytes $offset-${offset + count - 1}/$size"
        return send(session, buffer.toRequestBody(OCTETS, 0, count), range)
    }

    private suspend fun send(
        session: UploadSession,
        body: RequestBody,
        contentRange: String
    ): UploadStatus {
        val request = Request.Builder()
            .url(session.url.toHttpUrl())
            .tag(DriveAccount::class.java, DriveAccount(session.account))
            .header("Content-Range", contentRange)
            .put(body)
            .build()
        return client.newCall(request).await().use(::statusOf)
    }

    private fun statusOf(response: Response): UploadStatus = when (response.code) {
        HTTP_OK, HTTP_CREATED -> UploadStatus.Done(
            DriveHttp.json.decodeFromString(DriveFile.serializer(), response.body.string())
        )

        // "Range: bytes=0-N" names the last byte Drive holds; no header means none yet.
        HTTP_RESUME_INCOMPLETE -> UploadStatus.Partial(
            response.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
        )

        HTTP_NOT_FOUND, HTTP_GONE -> throw UploadSessionGoneException()

        else -> throw DriveHttpException(response.code)
    }

    companion object {
        /** Chunks other than the last are multiples of 256 KiB (Drive's rule). */
        const val CHUNK_UNIT = 256 * 1024

        /** 8 MiB a chunk: few round trips, little to resend after a cut connection. */
        const val CHUNK = 32 * CHUNK_UNIT

        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(OCTETS)
    }
}
