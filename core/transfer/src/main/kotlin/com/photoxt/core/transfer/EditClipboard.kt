package com.photoxt.core.transfer

import android.content.Context
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.PastePart
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.MAX_MASKS
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** *Paste edits* (spec §4.10): the copied look laid over an item's own recipe. */
object Pasting {
    fun merge(base: EditRecipe, pasted: EditRecipe, parts: Set<PastePart>): EditRecipe {
        val p = pasted.forPaste(parts)
        val subjectKinds = setOf(MaskKind.SUBJECT, MaskKind.BACKGROUND)
        return base.copy(
            light = p.light,
            color = p.color,
            curves = p.curves,
            effects = p.effects,
            filter = p.filter,
            geometry = if (PastePart.GEOMETRY in parts) p.geometry else base.geometry,
            masks = (base.masks.filter { it.kind !in subjectKinds } + p.masks).take(MAX_MASKS),
            retouch = base.retouch.copy(
                skinSmoothing = p.retouch.skinSmoothing,
                backgroundBlur = p.retouch.backgroundBlur
            ),
            background = p.background,
            layers = if (PastePart.OVERLAYS in parts) p.layers else base.layers,
            strokes = if (PastePart.OVERLAYS in parts) p.strokes else base.strokes,
            frame = if (PastePart.FRAME in parts) p.frame else base.frame,
            watermark = if (PastePart.FRAME in parts) p.watermark else base.watermark
        )
    }
}

/**
 * *Copy edits* holds one recipe for *Paste edits* (spec §4.10), kept in app storage so it
 * survives the app being stopped between the two.
 */
@Singleton
class EditClipboard @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "edits/clipboard.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _recipe = MutableStateFlow<EditRecipe?>(null)
    private val loaded = CompletableDeferred<Unit>()
    val recipe: StateFlow<EditRecipe?> = _recipe.asStateFlow()

    /** The copied recipe once the one on disk is read, for the asset cleanup. */
    suspend fun current(): EditRecipe? {
        loaded.await()
        return _recipe.value
    }

    init {
        scope.launch {
            val kept = runCatching { EditJson.decode(file.readText()) }.getOrNull()
            // A copy made while this was reading wins over the older one on disk.
            _recipe.compareAndSet(null, kept)
            loaded.complete(Unit)
        }
    }

    suspend fun copy(recipe: EditRecipe) {
        // The copied photo's own subject and faces stay with it; a paste finds the new photo's.
        val portable = recipe.copy(
            subjectMask = null,
            retouch = recipe.retouch.copy(faces = emptyList(), redEyes = emptyList())
        )
        _recipe.value = portable
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(EditJson.encode(portable))
        }
    }
}
