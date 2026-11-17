package com.photoxt.feature.viewer

import com.photoxt.core.database.EditDao
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.model.PastePart
import com.photoxt.core.transfer.EditOps
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ViewerEditParts @Inject constructor(internal val ops: EditOps, internal val dao: EditDao)

/**
 * The viewer's part of editing (spec §4.10): *Copy edits* from an edited photo, *Paste edits*
 * onto this one and *Revert to original*, each through the transfer queue like the editor's.
 */
class ViewerEdits internal constructor(
    private val scope: CoroutineScope,
    private val parts: ViewerEditParts
) {
    val canPaste: Flow<Boolean> = parts.ops.hasCopy

    /** Whether [mediaId] has a saved edit, to copy or take back. */
    fun edited(mediaId: String): Flow<Boolean> =
        parts.dao.observe(mediaId).map { it != null }.distinctUntilChanged()

    fun copy(item: ViewerItem, onCopied: () -> Unit) {
        scope.launch { if (parts.ops.copy(item.id)) onCopied() }
    }

    fun paste(item: ViewerItem, pasteParts: Set<PastePart>) {
        scope.launch { parts.ops.paste(listOf(item.id), pasteParts, item.name) }
    }

    fun revert(item: ViewerItem) {
        scope.launch { parts.ops.revert(listOf(item.id), item.name) }
    }
}
