package com.photoxt.core.transfer

import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp
import com.photoxt.core.vault.VaultEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Organising that changes files, through the transfer queue so it waits for consent, retries and
 * survives the app stopping (spec §4.8): favorites, and moves into and out of the vault.
 */
@Singleton
class OrganizeOps @Inject constructor(
    private val database: PxtDatabase,
    private val queue: TransferQueue
) {
    private val planner = PastePlanner(database.fileOpsDao())

    /**
     * Favorites [mediaIds] or takes them out (spec §4.8): the heart shows at once, MediaStore or
     * Drive follows (a phone item of another app asks first; a Drive item needs full Drive), and
     * a change that can't be made takes the heart back.
     */
    suspend fun setFavorite(mediaIds: List<String>, favorite: Boolean, title: String): Long? {
        val rows = planner.mediaRows(mediaIds).filter { (item) -> item.favorite != favorite }
        if (rows.isEmpty()) return null
        rows.map { it.first.id }.chunked(MAX_SQL_VARIABLES).forEach {
            database.mediaDao().setFavorite(it, favorite)
        }
        val op = if (favorite) TransferOp.FAVORITE else TransferOp.UNFAVORITE
        val plan = rows.map { (item, album) -> Planned(op, item.toRef(album), null) }
        return queue.enqueue(op, title, plan)
    }

    /** Moves [mediaIds] into the vault; each original is deleted once its copy reads back. */
    suspend fun moveToVault(mediaIds: List<String>, title: String): Long? {
        val plan = planner.mediaRows(mediaIds).map { (item, album) ->
            Planned(TransferOp.VAULT, item.toRef(album), null)
        }
        return plan.takeIf { it.isNotEmpty() }?.let { queue.enqueue(TransferOp.VAULT, title, it) }
    }

    /** Moves [entries] out of the vault, back to the phone folder each came from. */
    suspend fun moveOutOfVault(entries: List<VaultEntry>, title: String): Long? {
        val plan = entries.map { entry ->
            val ref = FileRef(
                SourceType.DEVICE,
                entry.meta.name,
                entry.meta.mime,
                entry.meta.bytes,
                uri = VaultMoves.VAULT_SCHEME + entry.id,
                takenAt = entry.meta.takenAt,
                md5 = entry.meta.md5
            )
            Planned(TransferOp.UNVAULT, ref, null)
        }
        return plan.takeIf { it.isNotEmpty() }?.let { queue.enqueue(TransferOp.UNVAULT, title, it) }
    }
}
