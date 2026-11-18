package com.photoxt.core.drive

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** A file's versions (spec §4.10, Revert): the calls behind core/transfer's `DriveRevisions`. */
interface DriveRevisionApi {
    /** A file's current revision, for keeping it before an edit replaces it (spec §4.10). */
    @GET("drive/v3/files/{id}")
    suspend fun headRevision(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Query("fields") fields: String = "id,headRevisionId,md5Checksum,size",
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveHead

    /**
     * Keeps revision [revision] forever; Drive allows 200 kept revisions per file (spec §4.10,
     * Revert). It is one-way: Drive answers 400 to `keepForever: false` on a kept revision, so
     * a kept revision goes only by [deleteRevision].
     */
    @PATCH("drive/v3/files/{id}/revisions/{revision}")
    suspend fun keepRevision(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Path("revision") revision: String,
        @Body keep: DriveRevisionWrite,
        @Query("fields") fields: String = FIELDS
    ): DriveRevision

    /** Deletes revision [revision] for good; Drive refuses for a file's last one. */
    @DELETE("drive/v3/files/{id}/revisions/{revision}")
    suspend fun deleteRevision(
        @Tag account: DriveAccount,
        @Path("id") id: String,
        @Path("revision") revision: String
    )

    companion object {
        const val FIELDS = "id,md5Checksum,size,keepForever,mimeType,modifiedTime"
    }
}
