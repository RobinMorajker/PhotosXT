package com.photoxt.core.drive

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** Which account a request is made for; [DriveHttp] signs it with that account's token. */
data class DriveAccount(val id: String)

/**
 * The few Drive REST v3 calls PhotoXT makes, hand-written (spec §5.1). Writes (copy, update,
 * create, delete) need the `drive` scope (spec §2); uploads go through [DriveUploads].
 */
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

    /** Server-side copy (spec §4.7, Drive → Drive in one account): nothing is downloaded. */
    @POST("drive/v3/files/{id}/copy")
    suspend fun copy(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Body file: DriveFileWrite,
        @Query("fields") fields: String = FILE_FIELDS,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveFile

    /**
     * Changes a file's metadata: renames it, bins or restores it, or moves it between folders
     * ([addParents], [removeParents], comma-separated), which is instant (spec §4.7).
     */
    @PATCH("drive/v3/files/{id}")
    suspend fun update(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Body file: DriveFileWrite,
        @Query("addParents") addParents: String? = null,
        @Query("removeParents") removeParents: String? = null,
        @Query("fields") fields: String = FILE_FIELDS,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveFile

    /** Creates a file without content: a folder, for *New album* and recursive pastes. */
    @POST("drive/v3/files")
    suspend fun create(
        @Tag account: DriveAccount,
        @Body file: DriveFileWrite,
        @Query("fields") fields: String = FILE_FIELDS,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveFile

    /** Deletes a file for good, skipping the bin (spec §4.7, *Delete forever*). */
    @DELETE("drive/v3/files/{id}")
    suspend fun delete(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    )

    /**
     * File ids made in advance. A create, copy or upload that names one can be repeated after a
     * crash without making a second file: the retry finds the first (spec §0 rule 4).
     */
    @GET("drive/v3/files/generateIds")
    suspend fun generateIds(
        @Tag account: DriveAccount,
        @Query("count") count: Int,
        @Query("space") space: String = "drive",
        @Query("type") type: String = "files"
    ): GeneratedIds

    companion object {
        const val PAGE_SIZE = 1000

        /** Drive gives at most this many ids per generateIds call. */
        const val MAX_GENERATED_IDS = 1000
        const val SHARED_DRIVES_PAGE = 100
    }
}

/** A Drive query string literal: backslashes and single quotes escaped. */
fun driveLiteral(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
