package com.photoxt.core.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import com.photoxt.core.model.MediaKind
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** One MediaStore photo or video, as the indexer needs it. */
data class DeviceMedia(
    val id: Long,
    val volume: String,
    val contentUri: String,
    val kind: MediaKind,
    val bucketId: Long,
    val bucketName: String?,
    val relativePath: String?,
    val name: String,
    val mime: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long?,
    val dateTakenMs: Long?,
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val favorite: Boolean
)

/**
 * The part of MediaStore the indexer reads. Calls block, so run them off the main thread. Tests
 * substitute a fake. A failed query throws instead of reporting "nothing there", so a glitch can
 * never look like every item was deleted.
 */
interface MediaStoreReader {
    fun volumes(): Set<String>

    fun version(volume: String): String

    fun generation(volume: String): Long

    /** Items added or changed after [sinceGeneration], or every item when it is null, in batches. */
    suspend fun scan(
        volume: String,
        sinceGeneration: Long?,
        onBatch: suspend (List<DeviceMedia>) -> Unit
    )

    /** The ids of every item PhotoXT can see on [volume]. */
    fun ids(volume: String): Set<Long>

    /** The items among [ids] that PhotoXT can see on [volume]. */
    fun fetch(volume: String, ids: Collection<Long>): List<DeviceMedia>
}

class ContentResolverMediaStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MediaStoreReader {
    override fun volumes(): Set<String> = MediaStore.getExternalVolumeNames(context)

    override fun version(volume: String): String = MediaStore.getVersion(context, volume)

    override fun generation(volume: String): Long = MediaStore.getGeneration(context, volume)

    override suspend fun scan(
        volume: String,
        sinceGeneration: Long?,
        onBatch: suspend (List<DeviceMedia>) -> Unit
    ) {
        val selection = if (sinceGeneration == null) {
            PHOTOS_AND_VIDEOS
        } else {
            "$PHOTOS_AND_VIDEOS AND ${MediaColumns.GENERATION_MODIFIED} > ?"
        }
        val args = sinceGeneration?.let { arrayOf(it.toString()) }
        query(volume, PROJECTION, selection, args).use { cursor ->
            val columns = Columns(cursor)
            val batch = ArrayList<DeviceMedia>(BATCH_SIZE)
            while (cursor.moveToNext()) {
                columns.read(cursor, volume)?.let(batch::add)
                if (batch.size == BATCH_SIZE) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch)
        }
    }

    override fun ids(volume: String): Set<Long> =
        query(volume, arrayOf(MediaColumns._ID), PHOTOS_AND_VIDEOS, null).use { cursor ->
            HashSet<Long>(cursor.count).apply { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    override fun fetch(volume: String, ids: Collection<Long>): List<DeviceMedia> =
        ids.chunked(IDS_PER_QUERY).flatMap { chunk ->
            // Numeric ids from MediaStore itself, so they can sit in the SQL as literals.
            val selection = "$PHOTOS_AND_VIDEOS AND ${MediaColumns._ID} IN (${chunk.joinToString(
                ","
            )})"
            query(volume, PROJECTION, selection, null).use { cursor ->
                val columns = Columns(cursor)
                buildList { while (cursor.moveToNext()) columns.read(cursor, volume)?.let(::add) }
            }
        }

    private fun query(
        volume: String,
        projection: Array<String>,
        selection: String,
        args: Array<String>?
    ): Cursor = checkNotNull(
        context.contentResolver.query(
            MediaStore.Files.getContentUri(volume),
            projection,
            selection,
            args,
            null
        )
    ) { "MediaStore query returned no cursor" }

    private class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaColumns._ID)
        private val mediaType = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        private val bucketId = cursor.getColumnIndexOrThrow(MediaColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndexOrThrow(MediaColumns.BUCKET_DISPLAY_NAME)
        private val relativePath = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
        private val name = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        private val mime = cursor.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        private val size = cursor.getColumnIndexOrThrow(MediaColumns.SIZE)
        private val width = cursor.getColumnIndexOrThrow(MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        private val orientation = cursor.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        private val duration = cursor.getColumnIndexOrThrow(MediaColumns.DURATION)
        private val dateTaken = cursor.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        private val favorite = cursor.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)

        fun read(cursor: Cursor, volume: String): DeviceMedia? {
            val kind = when (cursor.getInt(mediaType)) {
                FileColumns.MEDIA_TYPE_IMAGE -> MediaKind.IMAGE
                FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
                else -> return null
            }
            val itemId = cursor.getLong(id)
            val collection = when (kind) {
                MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
                MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            }
            return DeviceMedia(
                id = itemId,
                volume = volume,
                contentUri = ContentUris.withAppendedId(collection, itemId).toString(),
                kind = kind,
                bucketId = cursor.getLong(bucketId),
                bucketName = cursor.getString(bucketName),
                relativePath = cursor.getString(relativePath),
                name = cursor.getString(name).orEmpty(),
                mime =
                    cursor.getString(mime) ?: if (kind == MediaKind.IMAGE) "image/*" else "video/*",
                bytes = cursor.getLong(size),
                width = cursor.getInt(width),
                height = cursor.getInt(height),
                orientation = cursor.getInt(orientation),
                durationMs = cursor.longOrNull(duration),
                dateTakenMs = cursor.longOrNull(dateTaken),
                dateModifiedSec = cursor.getLong(dateModified),
                dateAddedSec = cursor.getLong(dateAdded),
                favorite = cursor.getInt(favorite) != 0
            )
        }

        private fun Cursor.longOrNull(index: Int): Long? =
            if (isNull(index)) null else getLong(index)
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val IDS_PER_QUERY = 500
        const val PHOTOS_AND_VIDEOS = "${FileColumns.MEDIA_TYPE} IN " +
            "(${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
        val PROJECTION = arrayOf(
            MediaColumns._ID,
            FileColumns.MEDIA_TYPE,
            MediaColumns.BUCKET_ID,
            MediaColumns.BUCKET_DISPLAY_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.MIME_TYPE,
            MediaColumns.SIZE,
            MediaColumns.WIDTH,
            MediaColumns.HEIGHT,
            MediaColumns.ORIENTATION,
            MediaColumns.DURATION,
            MediaColumns.DATE_TAKEN,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.DATE_ADDED,
            MediaColumns.IS_FAVORITE
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaStoreModule {
    @Binds
    abstract fun mediaStoreReader(reader: ContentResolverMediaStore): MediaStoreReader
}
