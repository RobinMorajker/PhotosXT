package com.photoxt.core.ml

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A face ML Kit found: its box and its eyes, in pixels of the picture it was given. */
data class FoundFace(val box: Rect, val leftEye: PointF?, val rightEye: PointF?)

/** A line of text ML Kit found, and whether it reads like a number plate. */
data class FoundText(val box: Rect, val plate: Boolean)

/** The subject mask: one confidence byte per pixel of the picture given. */
class SubjectMask(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * What the editor finds in a photo (spec §4.10: faces, text and number plates to redact, faces
 * for red-eye and skin, the subject for masks, blur and cut-out), all on the phone through ML Kit
 * in Google Play services, whose models come with Play services (spec §5.8). Give it the photo
 * upright, at about screen size.
 */
interface PhotoDetectors {
    suspend fun faces(picture: Bitmap): List<FoundFace>

    suspend fun text(picture: Bitmap): List<FoundText>

    suspend fun subject(picture: Bitmap): SubjectMask
}

@Singleton
class MlKitDetectors @Inject constructor() : PhotoDetectors {
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(MIN_FACE)
                .build()
        )
    }
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
    }

    override suspend fun faces(picture: Bitmap): List<FoundFace> =
        faceDetector.process(InputImage.fromBitmap(picture, 0)).await().map { face ->
            FoundFace(
                box = face.boundingBox,
                leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            )
        }

    override suspend fun text(picture: Bitmap): List<FoundText> {
        val image = InputImage.fromBitmap(picture, 0)
        val lines = buildList {
            for (recognizer in listOf(latin, devanagari)) {
                val result =
                    runCatching { recognizer.process(image).await() }.getOrNull() ?: continue
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line -> line.boundingBox?.let { add(it to line.text) } }
                }
            }
        }
        // Both recognizers read Latin digits: keep one box where two overlap mostly.
        val kept = mutableListOf<Pair<Rect, String>>()
        for (line in lines.sortedByDescending { it.first.width() * it.first.height() }) {
            if (kept.none { overlap(it.first, line.first) > SAME_BOX }) kept += line
        }
        return kept.map { (box, text) -> FoundText(box, Plates.looksLikePlate(text)) }
    }

    override suspend fun subject(picture: Bitmap): SubjectMask {
        val result = segmenter.process(InputImage.fromBitmap(picture, 0)).await()
        val confidence = checkNotNull(result.foregroundConfidenceMask) { "No subject mask" }
        confidence.rewind()
        val bytes = ByteArray(picture.width * picture.height)
        for (i in bytes.indices) {
            bytes[i] =
                (confidence.get().coerceIn(0f, 1f) * MAX_BYTE).toInt().toByte()
        }
        return SubjectMask(picture.width, picture.height, bytes)
    }

    private fun overlap(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val area = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return i.width() * i.height() / area.toFloat()
    }

    private companion object {
        const val MIN_FACE = 0.05f
        const val SAME_BOX = 0.7f
        const val MAX_BYTE = 255f
    }
}

/** Number plates (spec §4.10, Redact): a line of text shaped like one. */
object Plates {
    // Indian plates (GA 07 AB 1234, 22 BH 1234 AA) and the common letter-digit mixes elsewhere.
    private val PATTERNS = listOf(
        Regex("^[A-Z]{2}\\s?-?\\d{1,2}\\s?-?[A-Z]{0,3}\\s?-?\\d{3,4}$"),
        Regex("^\\d{2}\\s?BH\\s?\\d{4}\\s?[A-Z]{1,2}$"),
        Regex("^[A-Z]{1,3}[- ]?\\d{1,4}[- ]?[A-Z]{0,3}$"),
        Regex("^[A-Z]{2}\\d{2}\\s?[A-Z]{3}$")
    )

    fun looksLikePlate(text: String): Boolean {
        val t = text.uppercase().replace(Regex("[^A-Z0-9 -]"), "").trim()
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        return t.length in MIN_LENGTH..MAX_LENGTH && letters > 0 && digits > 0 &&
            PATTERNS.any { it.matches(t) }
    }

    private const val MIN_LENGTH = 5
    private const val MAX_LENGTH = 14
}

/** [this] task's result, suspending until it completes. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
