package com.photoxt.core.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import com.photoxt.core.common.PxtLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A subtitle file next to a video: `clip.srt`, or `clip.en.vtt` with its language. */
data class SubtitleFile(val contentUri: String, val mime: String, val language: String?)

/**
 * Finds the `.srt` and `.vtt` files with the same name as a video in the same folder (spec §4.6).
 * MediaStore lists them as subtitle files, which apps that may read videos can see.
 */
class SubtitleFinder @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** [folder] is the video's `<volume>:<relative path>`; [videoName] its file name. */
    suspend fun find(folder: String, videoName: String): List<SubtitleFile> =
        withContext(Dispatchers.IO) {
            val volume = folder.substringBefore(':')
            val relativePath = folder.substringAfter(':')
            val base = videoName.substringBeforeLast('.')
            val collection = MediaStore.Files.getContentUri(volume)
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaColumns._ID, MediaColumns.DISPLAY_NAME),
                    "${FileColumns.MEDIA_TYPE} = ? AND ${MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                    arrayOf(
                        FileColumns.MEDIA_TYPE_SUBTITLE.toString(),
                        relativePath,
                        escapeLike(base) + ".%"
                    ),
                    null
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                            subtitleFor(base, cursor.getString(1).orEmpty(), uri.toString())
                                ?.let(::add)
                        }
                    }
                }.orEmpty()
            } catch (e: SecurityException) {
                PxtLog.w(TAG, "No access to subtitle files", e)
                emptyList()
            }
        }

    private companion object {
        const val TAG = "Subtitles"
    }
}

/** The subtitle [name] stands for next to a video named [base], or null if it is not one. */
internal fun subtitleFor(base: String, name: String, contentUri: String): SubtitleFile? {
    val extension = name.substringAfterLast('.', "").lowercase()
    val mime = SUBTITLE_TYPES[extension] ?: return null
    val middle = name.dropLast(extension.length + 1).removePrefix(base)
    return if (middle.isEmpty() || middle.startsWith('.')) {
        SubtitleFile(contentUri, mime, middle.removePrefix(".").ifEmpty { null })
    } else {
        null
    }
}

private val SUBTITLE_TYPES = mapOf("srt" to "application/x-subrip", "vtt" to "text/vtt")

private fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
