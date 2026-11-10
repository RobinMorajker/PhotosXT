package com.photoxt.core.transfer

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import androidx.core.net.toUri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject

/** A phone item as file operations see it: where it is, and whether it is in the trash. */
data class DeviceFile(
    val uri: String,
    val volume: String,
    val path: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val takenAt: Long? = null,
    val modifiedAt: Long? = null,
    val trashed: Boolean = false,
    /** When MediaStore deletes a trashed item for good (UTC epoch ms). */
    val expiresAt: Long? = null
)

/**
 * The phone's media as file operations need it (spec §4.7): read, find by name, write new items
 * (pending until checked), move, rename, trash, restore and delete. Calls block; run them off
 * the main thread. Changing another app's item throws [SecurityException] until the user allows
 * it through MediaStore's own request screens, which the app shows (core/transfer `Consents`).
 * Tests substitute a fake.
 */
interface DeviceStore {
    /** The item at [uri], trashed or not; null when it is gone. */
    fun file(uri: String): DeviceFile?

    /** The item named [name] (ignoring case) in folder [path] of [volume], trash aside. */
    fun find(volume: String, path: String, name: String): DeviceFile?

    fun open(uri: String): InputStream

    /** A new item that only PhotoXT sees until [publish]; returns its URI. */
    fun create(volume: String, path: String, name: String, mime: String, takenAt: Long?): String

    /** Writes an item PhotoXT made, from the start. */
    fun openOutput(uri: String): OutputStream

    /** Shows a pending item to everyone, now that its bytes are checked. */
    fun publish(uri: String, takenAt: Long?)

    /** Deletes an item PhotoXT made: a copy that failed its check. */
    fun discard(uri: String)

    /** Moves [uri] into folder [path] of its volume, renamed to [name] when set. */
    fun move(uri: String, path: String, name: String?)

    fun rename(uri: String, name: String)

    fun trash(uri: String)

    fun restore(uri: String)

    /** Deletes [uri] for good. */
    fun delete(uri: String)

    /** Every photo and video in MediaStore's trash, newest first. */
    fun trashed(): List<DeviceFile>

    /**
     * The bucket id MediaStore gives the folder [path] of [volume] once it holds an item, so a
     * new empty album can take its final id now; null when the volume isn't mounted.
     */
    fun bucketId(volume: String, path: String): Long?
}

class MediaStoreDevice @Inject constructor(@param:ApplicationContext private val context: Context) :
    DeviceStore {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun file(uri: String): DeviceFile? =
        // An item's own collection (images, video) has no media type column; the files one does.
        resolver.query(uri.toUri(), ITEM_PROJECTION, Bundle().includeAll(), null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.read(uri) else null
        }

    override fun find(volume: String, path: String, name: String): DeviceFile? {
        // LIKE ignores case, as the phone's shared storage does; wildcards in names are escaped.
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' AND " +
                    "${MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(likeLiteral(path), likeLiteral(name))
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return query(MediaStore.Files.getContentUri(volume), args)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.read(null) else null
        }
    }

    override fun open(uri: String): InputStream =
        resolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException("No such item")

    override fun create(
        volume: String,
        path: String,
        name: String,
        mime: String,
        takenAt: Long?
    ): String {
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.MIME_TYPE, mime)
            put(MediaColumns.RELATIVE_PATH, path)
            put(MediaColumns.IS_PENDING, 1)
            takenAt?.let { put(MediaColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(collectionOf(volume, mime), values)
            ?: throw FileNotFoundException("MediaStore refused the new item")
        return uri.toString()
    }

    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(uri.toUri(), "wt") ?: throw FileNotFoundException("No such item")

    override fun publish(uri: String, takenAt: Long?) {
        val values = ContentValues().apply {
            put(MediaColumns.IS_PENDING, 0)
            takenAt?.let { put(MediaColumns.DATE_TAKEN, it) }
        }
        resolver.update(uri.toUri(), values, null, null)
    }

    override fun discard(uri: String) {
        resolver.delete(uri.toUri(), Bundle().includeAll())
    }

    override fun move(uri: String, path: String, name: String?) = update(
        uri,
        ContentValues().apply {
            put(MediaColumns.RELATIVE_PATH, path)
            name?.let { put(MediaColumns.DISPLAY_NAME, it) }
        }
    )

    override fun rename(uri: String, name: String) =
        update(uri, ContentValues().apply { put(MediaColumns.DISPLAY_NAME, name) })

    override fun trash(uri: String) =
        update(uri, ContentValues().apply { put(MediaColumns.IS_TRASHED, 1) })

    override fun restore(uri: String) =
        update(uri, ContentValues().apply { put(MediaColumns.IS_TRASHED, 0) })

    override fun delete(uri: String) {
        resolver.delete(uri.toUri(), Bundle().includeAll())
    }

    override fun trashed(): List<DeviceFile> = MediaStore.getExternalVolumeNames(context).flatMap {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${FileColumns.MEDIA_TYPE} IN " +
                    "(${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaColumns.DATE_EXPIRES)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }
        query(MediaStore.Files.getContentUri(it), args)?.use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.read(null)?.let(::add) }
        }.orEmpty()
    }

    override fun bucketId(volume: String, path: String): Long? {
        val storage = context.getSystemService(StorageManager::class.java) ?: return null
        // Throws for a volume that isn't mounted.
        val root = runCatching { storage.getStorageVolume(MediaStore.Files.getContentUri(volume)) }
            .getOrNull()?.directory ?: return null
        // MediaStore's bucket id is the hash of the lower-cased folder path (MediaProvider).
        return "${root.path}/${path.trimEnd('/')}".lowercase(Locale.ROOT).hashCode().toLong()
    }

    private fun update(uri: String, values: ContentValues) {
        val changed = resolver.update(uri.toUri(), values, Bundle().includeAll())
        if (changed == 0) throw FileNotFoundException("MediaStore changed nothing")
    }

    private fun query(uri: Uri, args: Bundle): Cursor? = resolver.query(uri, PROJECTION, args, null)

    /** The row as a [DeviceFile]; its URI is [knownUri], or built from a files-collection row. */
    private fun Cursor.read(knownUri: String?): DeviceFile? {
        val volume = getString(getColumnIndexOrThrow(MediaColumns.VOLUME_NAME)) ?: return null
        return DeviceFile(
            uri = knownUri ?: itemUri(volume),
            volume = volume,
            path = getString(getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)).orEmpty(),
            name = getString(getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)).orEmpty(),
            mime = getString(getColumnIndexOrThrow(MediaColumns.MIME_TYPE)).orEmpty(),
            bytes = getLong(getColumnIndexOrThrow(MediaColumns.SIZE)),
            takenAt = longOrNull(MediaColumns.DATE_TAKEN),
            modifiedAt = longOrNull(MediaColumns.DATE_MODIFIED)?.times(MILLIS_PER_SECOND),
            trashed = getInt(getColumnIndexOrThrow(MediaColumns.IS_TRASHED)) != 0,
            expiresAt = longOrNull(MediaColumns.DATE_EXPIRES)?.times(MILLIS_PER_SECOND)
        )
    }

    private fun Cursor.itemUri(volume: String): String {
        val collection = when (getInt(getColumnIndexOrThrow(FileColumns.MEDIA_TYPE))) {
            FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            else -> MediaStore.Files.getContentUri(volume)
        }
        return ContentUris.withAppendedId(
            collection,
            getLong(getColumnIndexOrThrow(MediaColumns._ID))
        )
            .toString()
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Bundle.includeAll() = apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        /** The columns of one item, which every media collection has. */
        val ITEM_PROJECTION = arrayOf(
            MediaColumns._ID,
            MediaColumns.VOLUME_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.MIME_TYPE,
            MediaColumns.SIZE,
            MediaColumns.DATE_TAKEN,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.IS_TRASHED,
            MediaColumns.DATE_EXPIRES
        )

        /** Rows of the files collection also say which collection the item is in. */
        val PROJECTION = ITEM_PROJECTION + FileColumns.MEDIA_TYPE

        fun collectionOf(volume: String, mime: String): Uri = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
            else -> MediaStore.Images.Media.getContentUri(volume)
        }

        /** [value] for an exact `LIKE ? ESCAPE '\'`: its wildcards and backslashes escaped. */
        fun likeLiteral(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DeviceStoreModule {
    @Binds
    fun deviceStore(store: MediaStoreDevice): DeviceStore
}
