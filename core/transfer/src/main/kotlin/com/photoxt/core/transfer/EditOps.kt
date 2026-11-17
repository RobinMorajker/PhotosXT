package com.photoxt.core.transfer

import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.TransferOp
import com.photoxt.core.render.EditJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Edits that change files (spec §4.10), through the transfer queue so they wait for consent,
 * resume and offer Undo: *Save*, *Save as copy*, *Revert to original*, and *Paste edits* onto a
 * selection (batch apply).
 */
@Singleton
class EditOps @Inject constructor(
    private val database: PxtDatabase,
    private val queue: TransferQueue,
    private val clipboard: EditClipboard
) {
    private val planner = PastePlanner(database.fileOpsDao())

    /** Whether edits are copied, so *Paste edits* has something to paste. */
    val hasCopy: Flow<Boolean> = clipboard.recipe.map { it != null }.distinctUntilChanged()

    /** Saves [recipe] (JSON) over [mediaId]; the original is kept first. */
    suspend fun save(
        mediaId: String,
        recipe: String,
        options: ExportOptions,
        title: String
    ): Long? = enqueue(TransferOp.EDIT, listOf(mediaId), title) { EditPayload(recipe, options) }

    /** Saves [recipe] over each of [mediaIds]: *Paste edits*, the recipe made per item. */
    suspend fun apply(
        mediaIds: List<String>,
        title: String,
        recipeFor: suspend (String) -> String
    ): Long? = enqueue(TransferOp.EDIT, mediaIds, title) { EditPayload(recipeFor(it)) }

    /** *Copy edits* from a saved edit, without opening the editor; false when [mediaId] has none. */
    suspend fun copy(mediaId: String): Boolean {
        val recipe = database.editDao().edit(mediaId)?.let {
            runCatching { EditJson.decode(it.opsJson) }.getOrNull()
        }
        recipe?.let { clipboard.copy(it) }
        return recipe != null
    }

    /**
     * *Paste edits* onto [mediaIds] (batch apply): each keeps its own edit, with the copied look
     * laid over it, and [parts] of the rest. Null when nothing is copied or nothing is a photo.
     */
    suspend fun paste(mediaIds: List<String>, parts: Set<PastePart>, title: String): Long? {
        val pasted = clipboard.recipe.value ?: return null
        return apply(mediaIds, title) { id ->
            val own = database.editDao().edit(id)?.let {
                runCatching { EditJson.decode(it.opsJson) }.getOrNull()
            }
            EditJson.encode(Pasting.merge(own ?: EditRecipe(), pasted, parts))
        }
    }

    /** Writes [recipe] over [mediaId] into a new file beside it, as [options] say. */
    suspend fun saveCopy(
        mediaId: String,
        recipe: String,
        options: ExportOptions,
        name: String?,
        title: String
    ): Long? = enqueue(TransferOp.EDIT_COPY, listOf(mediaId), title) {
        EditPayload(recipe, options, name)
    }

    /** Brings back the originals of [mediaIds], byte for byte. */
    suspend fun revert(mediaIds: List<String>, title: String): Long? {
        val edited = mediaIds.filter { database.editDao().edit(it) != null }
        return enqueue(TransferOp.REVERT, edited, title, payload = null)
    }

    private suspend fun enqueue(
        op: TransferOp,
        mediaIds: List<String>,
        title: String,
        payload: (suspend (String) -> EditPayload)?
    ): Long? {
        val plan = planner.mediaRows(mediaIds).filter { (item) ->
            item.kind == MediaKind.IMAGE &&
                (op != TransferOp.EDIT || canOverwrite(item.mime))
        }.map { (item, album) ->
            val src = item.toRef(album)
            val dst = payload?.let {
                Destination(src.source, src.name, albumId = item.albumId, edit = it(item.id))
            }
            Planned(op, src, dst)
        }
        return plan.takeIf { it.isNotEmpty() }?.let { queue.enqueue(op, title, it) }
    }
}

/**
 * Whether *Save* can write over a photo of [mime] in its own format; a GIF or a RAW file is only
 * ever saved as a copy, never as a JPEG under its old name.
 */
fun canOverwrite(mime: String): Boolean = ExportFormat.ofMime(mime) != null
