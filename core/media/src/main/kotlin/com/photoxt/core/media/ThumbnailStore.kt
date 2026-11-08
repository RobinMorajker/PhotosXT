package com.photoxt.core.media

import android.content.Context
import android.graphics.Bitmap
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Singleton

/**
 * Where Drive items' files live on the phone (spec §5.6), all under app-internal `filesDir` so
 * the system never purges them early: grid thumbnails (kept while the item exists), previews
 * (a capped LRU), cached originals (retention and a cap) and originals pinned offline (never
 * evicted). Keys are file id plus version, so a changed file can never show stale pixels.
 */
class ThumbnailStore(private val filesDir: File) {
    fun grid(fileId: String, version: Long): File = keyed("thumbs/grid", fileId, version)

    fun preview(fileId: String, version: Long): File = keyed("thumbs/preview", fileId, version)

    /** Every preview, for the preview LRU. */
    val previews: File get() = File(filesDir, "thumbs/preview")

    /** Where pinned originals live; the cache cleanup never looks in here. */
    val offline: File get() = File(filesDir, OFFLINE)

    /** [offline] as a path prefix, to tell pinned files from cached ones in the database. */
    val offlinePrefix: String get() = offline.path + File.separator

    /** Every cached and pinned original of [accountId]. */
    fun originals(accountId: String): List<File> =
        listOf(File(filesDir, "$CACHED/$accountId"), File(filesDir, "$OFFLINE/$accountId"))

    /**
     * An original: `media/<account>/<file id>_<version>.<ext>`, or under `media-offline/` when
     * [offline] (pinned).
     */
    fun original(
        accountId: String,
        fileId: String,
        version: Long,
        mime: String,
        offline: Boolean = false
    ): File = File(
        filesDir,
        "${if (offline) OFFLINE else CACHED}/$accountId/${fileId}_$version.${extensionOf(mime)}"
    )

    private fun keyed(kind: String, fileId: String, version: Long): File {
        val key = "${fileId}_$version"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val (first, second) = hash.chunked(2)
        return File(filesDir, "$kind/$first/$second/$key.webp")
    }

    companion object {
        /** Writes [target] through a temporary file, so a half-written one is never read. */
        fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use(write)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Could not move a thumbnail into place" }
            }
        }

        /** Saves [bitmap] as a lossy WebP at [target]. */
        fun writeWebp(target: File, bitmap: Bitmap, quality: Int = WEBP_QUALITY) =
            writeAtomically(target) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it)
            }

        private const val WEBP_QUALITY = 80
        private const val CACHED = "media"
        private const val OFFLINE = "media-offline"

        private val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/avif" to "avif",
            "image/bmp" to "bmp",
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "video/x-matroska" to "mkv",
            "video/webm" to "webm",
            "video/3gpp" to "3gp"
        )

        fun extensionOf(mime: String): String = EXTENSIONS[mime.lowercase()] ?: "bin"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ThumbnailStoreModule {
    @Provides
    @Singleton
    fun store(@ApplicationContext context: Context): ThumbnailStore =
        ThumbnailStore(context.filesDir)

    /** The wall clock; tests pass a fixed one (spec §0 rule 6, fake clock). */
    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
