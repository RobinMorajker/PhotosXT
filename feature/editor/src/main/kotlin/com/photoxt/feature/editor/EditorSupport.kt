package com.photoxt.feature.editor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.photoxt.core.database.EditRecipeEntity
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.Original
import com.photoxt.core.ml.EraserModel
import com.photoxt.core.ml.Inpainter
import com.photoxt.core.ml.PhotoDetectors
import com.photoxt.core.model.BackgroundMode
import com.photoxt.core.model.Circle01
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Ellipse01
import com.photoxt.core.model.Erasure
import com.photoxt.core.model.Frame
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.MaskStroke
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.RedactFound
import com.photoxt.core.model.RedactShape
import com.photoxt.core.model.RedactStyle
import com.photoxt.core.model.Redaction
import com.photoxt.core.model.SourceType
import com.photoxt.core.render.Box
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.MaskRaster
import com.photoxt.core.render.Orientations
import com.photoxt.core.render.PhotoFinder
import com.photoxt.core.render.PhotoInputs
import com.photoxt.core.render.PreviewMode
import com.photoxt.core.render.PreviewSession
import com.photoxt.core.render.RenderGeometry
import com.photoxt.core.render.Size
import com.photoxt.core.render.SourceImage
import com.photoxt.core.transfer.DriveFiles
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The file the editor renders from: always the original (spec §4.10, non-destructive). For an
 * item edited before, that is the kept copy (phone) or the kept revision (Drive); otherwise the
 * item itself, copied out of MediaStore with its location, or Drive's downloaded original.
 */
class EditorSources @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val originals: DriveOriginals,
    private val drive: DriveFiles
) {
    private val dir = File(context.cacheDir, "editor")

    suspend fun original(
        item: ViewerItem,
        edit: EditRecipeEntity?,
        progress: (Float?) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.filter {
            System.currentTimeMillis() - it.lastModified() > DAY_MS
        }?.forEach { it.delete() }
        when {
            item.source == SourceType.DEVICE -> edit?.originalPath?.let(::File)?.takeIf {
                it.isFile
            }
                ?: phoneCopy(item)

            edit?.baseRevisionId != null -> revision(
                item,
                edit,
                checkNotNull(edit.baseRevisionId)
            )

            else -> {
                var file: File? = null
                originals.original(item.id).first { state ->
                    when (state) {
                        is Original.Loading -> {
                            progress(state.fraction)
                            false
                        }

                        is Original.Ready -> {
                            file = state.file
                            true
                        }

                        is Original.Failed -> true
                    }
                }
                file
            }
        }
    }

    private fun phoneCopy(item: ViewerItem): File? {
        val uri = item.contentUri?.toUri() ?: return null
        val target = File(dir, key(item.id) + "." + item.name.substringAfterLast('.', "jpg"))
        val located = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val source = if (located) MediaStore.setRequireOriginal(uri) else uri
        val stream = runCatching { context.contentResolver.openInputStream(source) }.getOrNull()
            ?: context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        return target
    }

    private suspend fun revision(
        item: ViewerItem,
        edit: EditRecipeEntity,
        revision: String
    ): File? {
        val target = File(
            dir,
            key(item.id) + "-" + revision + "." + item.name.substringAfterLast('.', "jpg")
        )
        fun matches() = md5Of(target).equals(edit.originalMd5, ignoreCase = true)
        if (target.isFile && matches()) return target
        val account = checkNotNull(item.accountId)
        drive.revisions.download(account, checkNotNull(item.remoteId), revision, 0).use { input ->
            FileOutputStream(target).use { input.copyTo(it) }
        }
        return target.takeIf { matches() }
    }

    private fun key(id: String) =
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BUFFER = 64 * 1024
    }
}

/**
 * The editor's detections (spec §4.10), found on the upright photo and turned into the raster
 * coordinates recipes use: faces and their eyes, text and number plates, the subject.
 */
internal class Detections(private val detectors: PhotoDetectors) {
    private var uprightOf: Bitmap? = null
    private var upright: Bitmap? = null

    private fun upright(proxy: Bitmap, orientation: Int): Bitmap {
        if (uprightOf !== proxy) {
            upright = Orientations.upright(proxy, orientation)
            uprightOf = proxy
        }
        return checkNotNull(upright)
    }

    /** An upright-pixel box of [picture] as a raster rectangle. */
    private fun toRaster(box: Rect, picture: Bitmap, orientation: Int): Rect01 {
        val a = Orientations.toRaster(
            orientation,
            box.left.toFloat() / picture.width,
            box.top.toFloat() / picture.height
        )
        val b = Orientations.toRaster(
            orientation,
            box.right.toFloat() / picture.width,
            box.bottom.toFloat() / picture.height
        )
        return Rect01(
            min(a.x, b.x).coerceIn(0f, 1f),
            min(a.y, b.y).coerceIn(0f, 1f),
            max(a.x, b.x).coerceIn(0f, 1f),
            max(a.y, b.y).coerceIn(0f, 1f)
        )
    }

    suspend fun redactions(proxy: Bitmap, orientation: Int, style: RedactStyle): List<Redaction> {
        val picture = upright(proxy, orientation)
        val faces = detectors.faces(picture).map { face ->
            Redaction(
                grow(toRaster(face.box, picture, orientation), FACE_MARGIN),
                RedactShape.ELLIPSE,
                style,
                found = RedactFound.FACE
            )
        }
        val text = detectors.text(picture).map { line ->
            Redaction(
                grow(toRaster(line.box, picture, orientation), TEXT_MARGIN),
                RedactShape.RECT,
                style,
                found = if (line.plate) RedactFound.PLATE else RedactFound.TEXT
            )
        }
        return faces + text
    }

    suspend fun eyes(proxy: Bitmap, orientation: Int): List<Circle01> {
        val picture = upright(proxy, orientation)
        val short = min(picture.width, picture.height).toFloat()
        return detectors.faces(picture).flatMap { face ->
            val radius = face.box.width() * EYE_SHARE / short
            listOfNotNull(face.leftEye, face.rightEye).map { eye ->
                val p = Orientations.toRaster(
                    orientation,
                    eye.x / picture.width,
                    eye.y / picture.height
                )
                Circle01(p.x, p.y, radius)
            }
        }
    }

    suspend fun faceAreas(proxy: Bitmap, orientation: Int): List<Ellipse01> {
        val picture = upright(proxy, orientation)
        val raster = if (orientation in
            RenderGeometry.TRANSPOSED
        ) {
            Size(picture.height, picture.width)
        } else {
            Size(picture.width, picture.height)
        }
        val short = raster.shortEdge.toFloat()
        return detectors.faces(picture).map { face ->
            val r = toRaster(face.box, picture, orientation)
            Ellipse01(
                (r.left + r.right) / 2,
                (r.top + r.bottom) / 2,
                r.width * raster.width / 2 / short * FACE_GROW,
                r.height * raster.height / 2 / short * FACE_GROW
            )
        }
    }

    /** The subject mask of the photo shown by [proxy], stored as an asset; null when none. */
    suspend fun subjectMask(proxy: Bitmap, orientation: Int, assets: EditAssets): String? {
        val picture = upright(proxy, orientation)
        val mask = detectors.subject(picture)
        if (mask.bytes.none { (it.toInt() and BYTE) > HALF }) return null
        val raster = Orientations.maskToRaster(mask.bytes, mask.width, mask.height, orientation)
        return withContext(Dispatchers.IO) { assets.putMask(raster) }
    }

    private fun grow(r: Rect01, by: Float): Rect01 {
        val dx = r.width * by
        val dy = r.height * by
        val right = (r.right + dx).coerceAtMost(1f)
        val bottom = (r.bottom + dy).coerceAtMost(1f)
        return Rect01(
            (r.left - dx).coerceAtLeast(0f),
            (r.top - dy).coerceAtLeast(0f),
            right,
            bottom
        )
    }

    private companion object {
        const val FACE_MARGIN = 0.12f
        const val TEXT_MARGIN = 0.08f
        const val EYE_SHARE = 0.09f
        const val FACE_GROW = 1.1f
        const val BYTE = 0xFF
        const val HALF = 128
    }
}

/** Finds a photo's subject for the transfer queue (a pasted recipe that needs one). */
@Singleton
class MlPhotoFinder @Inject constructor(
    private val detectors: PhotoDetectors,
    private val assets: EditAssets
) : PhotoFinder {
    override suspend fun subjectMask(photo: File): String? {
        val (proxy, orientation) = proxyOf(photo)
        return Detections(detectors).subjectMask(proxy, orientation, assets)
    }

    override suspend fun faces(photo: File): List<Ellipse01> {
        val (proxy, orientation) = proxyOf(photo)
        return Detections(detectors).faceAreas(proxy, orientation)
    }

    private suspend fun proxyOf(photo: File) = withContext(Dispatchers.IO) {
        SourceImage(photo).use { it.decodeScaled(PROXY_EDGE) to it.orientation }
    }

    private companion object {
        const val PROXY_EDGE = 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface EditorModule {
    @Binds
    fun photoFinder(finder: MlPhotoFinder): PhotoFinder
}

/** *Lift subject* (spec §4.10, Cut-out): the subject alone, transparent around it. */
internal object Lifts {
    /** The subject's box in the raster, from its mask; null when the mask is empty. */
    fun subjectBox(mask: MaskRaster): Rect01? {
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if ((mask.bytes[y * mask.width + x].toInt() and BYTE) > HALF) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }
        if (maxX < 0) return null
        return Rect01(
            minX.toFloat() / mask.width,
            minY.toFloat() / mask.height,
            (maxX + 1f) / mask.width,
            (maxY + 1f) / mask.height
        )
    }

    /** [recipe] reduced to its subject: background removed, cropped to it, nothing laid on top. */
    fun subjectOnly(
        recipe: EditRecipe,
        raster: Size,
        orientation: Int,
        assets: EditAssets? = null
    ): EditRecipe {
        val mask = assets?.let { a -> recipe.subjectMask?.let(a::mask) }
        val box = mask?.let(::subjectBox)
        val uncropped = recipe.geometry.copy(crop = Rect01.FULL)
        val crop = box?.let { b ->
            val g = RenderGeometry(raster, orientation, uncropped, 1.0)
            val toOut = g.sourceToOut
            val corners = listOf(
                b.left to b.top,
                b.right to b.top,
                b.right to b.bottom,
                b.left to b.bottom
            )
                .map { (x, y) -> toOut.map(x.toDouble(), y.toDouble()) }
            val w = g.output.width.toDouble()
            val h = g.output.height.toDouble()
            Rect01(
                (corners.minOf { it.first } / w).toFloat().coerceIn(0f, 1f),
                (corners.minOf { it.second } / h).toFloat().coerceIn(0f, 1f),
                (corners.maxOf { it.first } / w).toFloat().coerceIn(0f, 1f),
                (corners.maxOf { it.second } / h).toFloat().coerceIn(0f, 1f)
            )
        } ?: recipe.geometry.crop
        return recipe.copy(
            background = recipe.background.copy(mode = BackgroundMode.REMOVE),
            geometry = recipe.geometry.copy(crop = crop),
            frame = Frame(),
            layers = emptyList(),
            strokes = emptyList(),
            watermark = null
        )
    }

    /** The subject cut out at up to 1,024 px, or null without a subject. */
    suspend fun cutOut(session: PreviewSession, recipe: EditRecipe): Bitmap? {
        recipe.subjectMask ?: return null
        val only = subjectOnly(recipe, session.raster, session.orientation, sessionAssets(session))
        val preview = session.render(only, PreviewMode.EDIT, LIFT_EDGE)
        return preview.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** [bitmap] on the system clipboard as a PNG, through PhotoXT's file provider. */
    fun toClipboard(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "lifted").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "subject-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uri))
    }

    private fun sessionAssets(session: PreviewSession): EditAssets = session.assets

    private const val LIFT_EDGE = 1024
    private const val PNG_QUALITY = 100
    private const val BYTE = 0xFF
    private const val HALF = 128
}

/** The object eraser (spec §4.10): the strokes' area inpainted into a patch over the source. */
internal class Erasing(
    private val session: PreviewSession,
    private val inpainter: Inpainter,
    private val assets: EditAssets
) {
    /** The patch that hides what [strokes] cover, made by [model]; null when they cover nothing. */
    suspend fun erase(model: EraserModel, recipe: EditRecipe, strokes: List<MaskStroke>): Erasure? {
        val rect = areaOf(strokes) ?: return null
        val region = session.region(rect, REGION_EDGE).copy(Bitmap.Config.ARGB_8888, true)
        val shown = Box(
            rect.left.toDouble(),
            rect.top.toDouble(),
            rect.right.toDouble(),
            rect.bottom.toDouble()
        )
        // Earlier erasures first: the model fills in over what is already gone.
        PhotoInputs.bakeErasures(region, recipe.erasures, assets, shown)
        val side = EraserModel.SIDE
        val small = region.scale(side, side)
        val hole = holeOf(maskOf(strokes, region, rect, feather = false))
        val filled = inpainter.inpaint(model, small, hole)
        // The patch: the model's picture where the strokes were, see-through elsewhere.
        val patch = createBitmap(region.width, region.height)
        val canvas = Canvas(patch)
        val grown = filled.scale(region.width, region.height)
        canvas.drawBitmap(grown, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val inside = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        canvas.drawBitmap(maskOf(strokes, region, rect, feather = true), 0f, 0f, inside)
        val asset = withContext(Dispatchers.IO) { assets.putPng(patch) }
        return Erasure(asset, rect)
    }

    /** A square around [strokes] with room for the model to see what surrounds them. */
    private fun areaOf(strokes: List<MaskStroke>): Rect01? {
        val raster = session.raster
        val short = raster.shortEdge.toFloat()
        // Each stroke's points, in pixels, grown by the brush.
        val reach = strokes.flatMap { stroke ->
            val pad = stroke.size * short
            stroke.points.chunked(2).filter { it.size == 2 }.map { (x, y) ->
                RectF(
                    x * raster.width - pad,
                    y * raster.height - pad,
                    x * raster.width + pad,
                    y * raster.height + pad
                )
            }
        }
        if (reach.isEmpty()) return null
        val bounds = RectF(reach.first()).apply { reach.forEach(::union) }
        val side = min(max(max(bounds.width(), bounds.height()) * CONTEXT, MIN_SIDE), short)
        val cx = bounds.centerX().coerceIn(side / 2, raster.width - side / 2)
        val cy = bounds.centerY().coerceIn(side / 2, raster.height - side / 2)
        return Rect01(
            (cx - side / 2) / raster.width,
            (cy - side / 2) / raster.height,
            (cx + side / 2) / raster.width,
            (cy + side / 2) / raster.height
        )
    }

    /** [mask] at the model's size, one byte a pixel: 255 where it is painted. */
    private fun holeOf(mask: Bitmap): ByteArray {
        val side = EraserModel.SIDE
        val alpha = IntArray(side * side)
        mask.scale(side, side).getPixels(alpha, 0, side, 0, 0, side, side)
        return ByteArray(alpha.size) { if (alpha[it] ushr ALPHA_SHIFT > 0) -1 else 0 }
    }

    /** [strokes] drawn into a mask the size of [region], which shows [rect] of the raster. */
    private fun maskOf(
        strokes: List<MaskStroke>,
        region: Bitmap,
        rect: Rect01,
        feather: Boolean
    ): Bitmap {
        val raster = session.raster
        val mask = createBitmap(region.width, region.height)
        val canvas = Canvas(mask)
        val sx = region.width / (rect.width * raster.width)
        val sy = region.height / (rect.height * raster.height)
        for (stroke in strokes) {
            val width = max(1f, stroke.size * raster.shortEdge * sx * 2)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = width * if (feather) FEATHER_GROW else HARD_GROW
                color = -1
                if (feather) {
                    maskFilter =
                        BlurMaskFilter(width * FEATHER, BlurMaskFilter.Blur.NORMAL)
                }
            }
            val points = stroke.points.chunked(2).filter { it.size == 2 }.map { (x, y) ->
                PointF((x - rect.left) * raster.width * sx, (y - rect.top) * raster.height * sy)
            }
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i ==
                    0
                ) {
                    path.moveTo(p.x, p.y)
                } else {
                    path.lineTo(p.x, p.y)
                }
            }
            // A tap is one point: a line too short to see still leaves its dot.
            if (points.size == 1) path.lineTo(points[0].x + DOT, points[0].y)
            canvas.drawPath(path, paint)
        }
        return mask
    }

    private companion object {
        const val CONTEXT = 1.8f
        const val MIN_SIDE = 96f
        const val REGION_EDGE = 1024
        const val ALPHA_SHIFT = 24
        const val FEATHER = 0.18f
        const val FEATHER_GROW = 1.15f
        const val HARD_GROW = 1.25f
        const val DOT = 0.1f
    }
}
