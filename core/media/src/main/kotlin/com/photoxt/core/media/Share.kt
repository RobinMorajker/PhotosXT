package com.photoxt.core.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.photoxt.core.database.ShareTarget

/**
 * Hands [items] to the system share sheet, with read access to each one. Sharing Drive items as
 * a thumbnail plus a link arrives with the sharing phase (spec §4.14, Phase 11).
 */
fun Context.shareMedia(items: List<ShareTarget>) {
    if (items.isEmpty()) return
    val uris = items.map { it.contentUri.toUri() }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            ArrayList(uris)
        )
    }
    send.type = commonMimeType(items.map { it.mime })
    // The receiving app may read only what is shared, through these grants.
    send.clipData = ClipData.newRawUri(null, uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

/** The type all items share, else the kind they share (any image, any video), else any type. */
internal fun commonMimeType(types: List<String>): String {
    val distinct = types.distinct()
    if (distinct.size == 1) return distinct.first()
    val kinds = distinct.map { it.substringBefore('/') }.distinct()
    return if (kinds.size == 1) "${kinds.first()}/*" else "*/*"
}
