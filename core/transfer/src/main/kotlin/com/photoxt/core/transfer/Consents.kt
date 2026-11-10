package com.photoxt.core.transfer

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.Original
import com.photoxt.core.model.WaitFor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * MediaStore's own request screen for jobs waiting on [kind] about [uris] (spec §4.7): a write
 * grant, or the trash, restore and delete confirmations, which make the change themselves. With
 * media management granted ([canManageMedia]) it answers at once without showing anything.
 */
fun mediaStoreRequest(context: Context, kind: WaitFor, uris: List<String>): PendingIntent? {
    val resolver = context.contentResolver
    val items = uris.map { it.toUri() }
    if (items.isEmpty()) return null
    return when (kind) {
        WaitFor.DEVICE_WRITE -> MediaStore.createWriteRequest(resolver, items)
        WaitFor.DEVICE_TRASH -> MediaStore.createTrashRequest(resolver, items, true)
        WaitFor.DEVICE_RESTORE -> MediaStore.createTrashRequest(resolver, items, false)
        WaitFor.DEVICE_DELETE -> MediaStore.createDeleteRequest(resolver, items)
        else -> null
    }
}

/** The phone item a waiting job asks MediaStore about. */
val TransferJobEntity.consentUri: String? get() = saved.consentUri ?: source.uri

/** Whether the user granted media management (Android 12+): no confirmation per batch. */
fun canManageMedia(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

/** The system screen that grants media management, or null before Android 12. */
fun manageMediaIntent(context: Context): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, "package:${context.packageName}".toUri())
    } else {
        null
    }

/**
 * *Copy to system clipboard* (spec §4.7): one photo, as a content URI another app can paste. A
 * Drive photo is downloaded first and handed out through PhotoXT's file provider.
 */
class SystemClipboard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val originals: DriveOriginals
) {
    /** False when a Drive photo couldn't be downloaded. */
    suspend fun copy(mediaId: String, contentUri: String?, name: String): Boolean {
        val uri = contentUri?.toUri() ?: run {
            val ready = originals.original(mediaId)
                .first { it is Original.Ready || it is Original.Failed }
            val file = (ready as? Original.Ready)?.file ?: return false
            withContext(Dispatchers.IO) {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, name, uri))
        return true
    }
}
