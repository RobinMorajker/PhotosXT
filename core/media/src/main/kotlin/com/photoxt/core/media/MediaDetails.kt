package com.photoxt.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.photoxt.core.common.PxtLog
import com.photoxt.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the details sheet shows beyond the database row (spec §4.5): EXIF and place. */
data class MediaDetails(
    /** Camera make and model, e.g. "samsung SM-A156E". */
    val camera: String? = null,
    val lens: String? = null,
    val exposure: Exposure? = null,
    val location: GeoPoint? = null,
    /** Android hides photo locations from apps without "media location" access. */
    val locationHidden: Boolean = false
)

data class Exposure(
    val fNumber: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null
)

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Reads [MediaDetails] from a photo's EXIF or a video's metadata. GPS coordinates are only
 * visible with the `ACCESS_MEDIA_LOCATION` permission, through MediaStore's original-file URI;
 * without it, the details report the location as hidden instead of missing.
 */
class MediaDetailsReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val canReadLocation: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun read(contentUri: String, kind: MediaKind): MediaDetails =
        withContext(Dispatchers.IO) {
            val withLocation = canReadLocation
            val uri = contentUri.toUri().let {
                if (withLocation) MediaStore.setRequireOriginal(it) else it
            }
            try {
                val details = when (kind) {
                    MediaKind.IMAGE -> readImage(uri)
                    MediaKind.VIDEO -> readVideo(uri)
                }
                details.copy(locationHidden = !withLocation)
            } catch (e: IOException) {
                PxtLog.w(TAG, "Could not read details", e)
                MediaDetails(locationHidden = !withLocation)
            } catch (e: SecurityException) {
                PxtLog.w(TAG, "No access to details", e)
                MediaDetails(locationHidden = !withLocation)
            }
        }

    private fun readImage(uri: Uri): MediaDetails {
        val exif = context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
            ?: return MediaDetails()
        val exposure = Exposure(
            fNumber = exif.double(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = exif.double(ExifInterface.TAG_EXPOSURE_TIME),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                .takeIf { it > 0 },
            focalLengthMm = exif.double(ExifInterface.TAG_FOCAL_LENGTH)
        )
        return MediaDetails(
            camera = listOfNotNull(
                exif.text(ExifInterface.TAG_MAKE),
                exif.text(ExifInterface.TAG_MODEL)
            ).joinToString(" ").ifEmpty { null },
            lens = exif.text(ExifInterface.TAG_LENS_MODEL),
            exposure = exposure.takeIf { it != Exposure() },
            location = exif.latLong?.let { GeoPoint(it[0], it[1]) }?.takeIf { it.isReal() }
        )
    }

    private fun readVideo(uri: Uri): MediaDetails {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            MediaDetails(location = location?.let(::parseIso6709))
        } catch (e: IllegalArgumentException) {
            PxtLog.w(TAG, "Unreadable video metadata", e)
            MediaDetails()
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val TAG = "MediaDetails"
    }
}

/** A point written as ISO 6709, as videos store it: `+37.4220-122.0841/` (altitude optional). */
internal fun parseIso6709(value: String): GeoPoint? {
    val match = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    return GeoPoint(latitude, longitude).takeIf { it.isReal() }
}

/** Android writes 0, 0 where it removed a location; nobody shoots at Null Island. */
private fun GeoPoint.isReal() = latitude in -MAX_LATITUDE..MAX_LATITUDE &&
    longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
    (latitude != 0.0 || longitude != 0.0)

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

private fun ExifInterface.text(tag: String): String? = getAttribute(tag)?.trim()?.ifEmpty { null }

private fun ExifInterface.double(tag: String): Double? =
    getAttributeDouble(tag, 0.0).takeIf { it > 0.0 }
