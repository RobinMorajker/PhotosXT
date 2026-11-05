package com.photoxt.feature.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import com.photoxt.core.database.ShareTarget
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.shareMedia

// The viewer's quick actions that need no later phase (spec §4.5): share, open with, set as,
// copy. Each hands the item to another app with read access to that one item only.

internal fun Context.share(item: ViewerItem) {
    val uri = item.contentUri ?: return
    shareMedia(listOf(ShareTarget(uri, item.mime)))
}

internal fun Context.openWith(item: ViewerItem) {
    val uri = item.contentUri?.toUri() ?: return
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(view, getString(R.string.action_open_with)))
}

/** Set as wallpaper, contact photo and so on: whatever the installed apps offer. */
internal fun Context.setAs(item: ViewerItem) {
    val uri = item.contentUri?.toUri() ?: return
    val attach = Intent(Intent.ACTION_ATTACH_DATA)
        .setDataAndType(uri, item.mime)
        .putExtra("mimeType", item.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(attach, getString(R.string.action_set_as)))
}

/** Copies the photo for pasting into another app. Android 13 and later confirm it themselves. */
internal fun Context.copyToClipboard(item: ViewerItem) {
    val uri = item.contentUri?.toUri() ?: return
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newUri(contentResolver, item.name, uri))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
