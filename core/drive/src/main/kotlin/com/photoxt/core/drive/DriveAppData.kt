package com.photoxt.core.drive

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The user's Drive app-data folder (spec §4.8, settings sync): files only PhotoXT sees, in the
 * user's own Drive, under the non-sensitive `drive.appdata` scope. No PhotoXT server is involved.
 */
class DriveAppData(private val http: DriveHttp) {
    /** The newest app-data file named [name], or null when there is none. */
    suspend fun find(accountId: String, name: String): DriveFile? = http.api.listAppData(
        DriveAccount(accountId),
        "name = ${driveLiteral(name)} and trashed = false",
        "files(id,name,modifiedTime,size)"
    ).files.maxByOrNull { it.modifiedTime.orEmpty() }

    /** The bytes of app-data file [fileId]. */
    suspend fun read(accountId: String, fileId: String): ByteArray =
        http.open(accountId, http.mediaUrl(fileId)).use { response ->
            response.body.bytes()
        }

    /**
     * Writes [bytes], of media type [type], as app-data file [name]: over [fileId] when given,
     * else as a new file.
     */
    suspend fun write(
        accountId: String,
        name: String,
        bytes: ByteArray,
        type: String,
        fileId: String?
    ): DriveFile {
        val url = http.baseUrl.newBuilder()
            .addPathSegments("upload/drive/v3/files")
            .apply { if (fileId != null) addPathSegment(fileId) }
            .addQueryParameter("uploadType", if (fileId != null) "media" else "multipart")
            .addQueryParameter("fields", "id,name,modifiedTime,size")
            .build()
        val content = bytes.toRequestBody(type.toMediaType())
        val request = Request.Builder()
            .url(url)
            .tag(DriveAccount::class.java, DriveAccount(accountId))
            .apply {
                if (fileId != null) {
                    patch(content)
                } else {
                    val metadata = DriveHttp.json.encodeToString(
                        DriveFileWrite.serializer(),
                        DriveFileWrite(name = name, parents = listOf(APP_DATA_FOLDER))
                    )
                    post(
                        MultipartBody.Builder()
                            .setType(RELATED)
                            .addPart(metadata.toRequestBody(JSON))
                            .addPart(content)
                            .build()
                    )
                }
            }
            .build()
        return http.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw DriveHttpException(response.code)
            DriveHttp.json.decodeFromString(DriveFile.serializer(), response.body.string())
        }
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val RELATED = "multipart/related".toMediaType()
    }
}
