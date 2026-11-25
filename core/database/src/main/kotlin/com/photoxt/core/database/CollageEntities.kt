package com.photoxt.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A collage draft (spec §4.11 "work auto-saves as a re-editable draft", §5.4 `collage_project`):
 * the project as JSON ([layoutJson]), the media ids it refers to, one per line ([itemRefs]), and
 * the photo its last export made ([exportedMediaId]). Its picture for the drafts list is a file
 * named after [id], written by the studio.
 */
@Entity(tableName = "collage_project")
data class CollageProjectEntity(
    @PrimaryKey val id: String,
    val layoutJson: String,
    val itemRefs: String,
    val createdAt: Long,
    val updatedAt: Long,
    val exportedMediaId: String? = null
)

@Dao
interface CollageDao {
    /** The drafts, the latest change first. */
    @Query("SELECT * FROM collage_project ORDER BY updatedAt DESC")
    fun drafts(): Flow<List<CollageProjectEntity>>

    @Query("SELECT * FROM collage_project WHERE id = :id")
    suspend fun draft(id: String): CollageProjectEntity?

    @Upsert
    suspend fun save(draft: CollageProjectEntity)

    @Query("DELETE FROM collage_project WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE collage_project SET exportedMediaId = :mediaId WHERE id = :id")
    suspend fun exported(id: String, mediaId: String)
}
