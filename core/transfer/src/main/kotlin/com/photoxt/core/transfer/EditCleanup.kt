package com.photoxt.core.transfer

import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.EditJson
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Edits whose photo went away (spec §4.10): a photo gone from PhotoXT for [GONE_DAYS] days, longer
 * than the trash keeps it, loses its kept original and its edit; one that comes back, from the
 * trash say, keeps both. Edit assets that no edit, preset, LUT, sticker or copied edit uses go too.
 */
@Singleton
class EditCleanup @Inject constructor(
    private val database: PxtDatabase,
    private val assets: EditAssets,
    private val settings: SettingsStore,
    private val clipboard: EditClipboard
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Once per app start, off the main thread. */
    fun start(clock: Clock = Clock.systemUTC()) {
        scope.launch {
            runCatching {
                run(clock.millis())
            }.onFailure { PxtLog.w(TAG, "Edit cleanup failed", it) }
        }
    }

    suspend fun run(now: Long) {
        val dao = database.editDao()
        dao.markBack()
        dao.markGone(now)
        dao.goneBefore(now - GONE_DAYS * DAY_MS).forEach { edit ->
            edit.originalPath?.let { File(it).delete() }
            dao.delete(edit.mediaId)
        }
        val editor = settings.current().editor
        val used = buildSet {
            dao.recipes().forEach { json ->
                runCatching { EditJson.decode(json) }.getOrNull()?.let { addAll(it.assets) }
            }
            editor.presets.forEach { addAll(it.recipe.assets) }
            editor.luts.forEach { add(it.asset) }
            addAll(editor.stickers)
            clipboard.current()?.let { addAll(it.assets) }
        }
        val freed = assets.keepOnly(used, now)
        if (freed > 0) PxtLog.i(TAG, "Removed $freed unused edit assets")
    }

    private companion object {
        const val TAG = "EditCleanup"
        const val GONE_DAYS = 31L
        const val DAY_MS = 86_400_000L
    }
}
