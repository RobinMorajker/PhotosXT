package com.photoxt.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A saved edit (spec §4.10, §5.4 `edit_recipe`): the recipe now on the item, and what brings the
 * original back. A phone item's original is a copy in app storage ([originalPath]); a Drive
 * file's is its revision [baseRevisionId], kept forever. [originalMd5] proves a revert put back
 * the very same bytes. No foreign key: an item in the trash leaves `media` for a while, and its
 * edit must still revert once it is back; [goneSince] lets the cleanup tell.
 */
@Entity(tableName = "edit_recipe")
data class EditRecipeEntity(
    @PrimaryKey val mediaId: String,
    val opsJson: String,
    val schemaVersion: Int,
    val baseRevisionId: String? = null,
    val originalPath: String? = null,
    val originalMd5: String,
    val originalBytes: Long,
    val originalMime: String,
    val originalName: String,
    /** The edited file's MD5 as written, so a later change by someone else shows. */
    val editedMd5: String? = null,
    val savedAt: Long,
    val goneSince: Long? = null
)

@Dao
interface EditDao {
    @Query("SELECT * FROM edit_recipe WHERE mediaId = :mediaId")
    suspend fun edit(mediaId: String): EditRecipeEntity?

    @Query("SELECT * FROM edit_recipe WHERE mediaId = :mediaId")
    fun observe(mediaId: String): Flow<EditRecipeEntity?>

    @Upsert
    suspend fun save(edit: EditRecipeEntity)

    @Query("DELETE FROM edit_recipe WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    /** Every recipe, for the asset cleanup. */
    @Query("SELECT opsJson FROM edit_recipe")
    suspend fun recipes(): List<String>

    @Query(
        "UPDATE edit_recipe SET goneSince = :now WHERE goneSince IS NULL " +
            "AND mediaId NOT IN (SELECT id FROM media)"
    )
    suspend fun markGone(now: Long)

    @Query(
        "UPDATE edit_recipe SET goneSince = NULL WHERE goneSince IS NOT NULL " +
            "AND mediaId IN (SELECT id FROM media)"
    )
    suspend fun markBack()

    @Query("SELECT * FROM edit_recipe WHERE goneSince IS NOT NULL AND goneSince < :before")
    suspend fun goneBefore(before: Long): List<EditRecipeEntity>
}
