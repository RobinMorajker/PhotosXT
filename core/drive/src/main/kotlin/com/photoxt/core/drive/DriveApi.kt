package com.photoxt.core.drive

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** Which account a request is made for; [DriveHttp] signs it with that account's token. */
data class DriveAccount(val id: String)

/** The few Drive REST v3 calls PhotoXT makes, hand-written (spec §5.1). */
interface DriveApi {
    @GET("drive/v3/files")
    suspend fun list(
        @Tag account: DriveAccount,
        @Query("q") query: String,
        @Query("fields") fields: String,
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = PAGE_SIZE,
        @Query("orderBy") orderBy: String? = null,
        @Query("corpora") corpora: String? = null,
        @Query("driveId") driveId: String? = null,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeItemsFromAllDrives: Boolean = true
    ): FileList

    @GET("drive/v3/files/{id}")
    suspend fun get(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Query("fields") fields: String = FILE_FIELDS,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveFile

    @GET("drive/v3/changes/startPageToken")
    suspend fun startPageToken(
        @Tag account: DriveAccount,
        @Query("driveId") driveId: String? = null,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): StartPageToken

    @GET("drive/v3/changes")
    suspend fun changes(
        @Tag account: DriveAccount,
        @Query("pageToken") pageToken: String,
        @Query("fields") fields: String =
            "nextPageToken,newStartPageToken,changes(changeType,fileId,removed,driveId," +
                "file($FILE_FIELDS))",
        @Query("driveId") driveId: String? = null,
        @Query("pageSize") pageSize: Int = PAGE_SIZE,
        @Query("includeRemoved") includeRemoved: Boolean = true,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeItemsFromAllDrives: Boolean = true
    ): ChangeList

    @GET("drive/v3/drives")
    suspend fun sharedDrives(
        @Tag account: DriveAccount,
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = SHARED_DRIVES_PAGE
    ): SharedDriveList

    companion object {
        const val PAGE_SIZE = 1000
        const val SHARED_DRIVES_PAGE = 100
    }
}

/** A Drive query string literal: backslashes and single quotes escaped. */
fun driveLiteral(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
