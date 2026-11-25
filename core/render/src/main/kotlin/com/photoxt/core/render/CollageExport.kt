package com.photoxt.core.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.photoxt.core.common.PxtLog
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.Frame
import com.photoxt.core.model.Geometry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** One collage export: [project], with each photo's original file by media id, into [output]. */
class CollageRequest(
    val project: CollageProject,
    val photos: Map<String, File>,
    val options: CollageOutput,
    val output: File
)

/** Writes a collage at full size (spec §4.11, Export). */
interface CollageExporter {
    /** Renders [request] and checks the file decodes; throws when it doesn't. */
    suspend fun export(request: CollageRequest): ExportResult
}

/**
 * The collage export (spec §4.11): every cell's part of its photo decoded from the original at
 * the resolution its cell needs (a few at a time), looks through the editor's shader, all drawn
 * by [CollageRenderer] into one bitmap that fits in memory, encoded, and decoded back as the
 * check that the file is whole.
 */
class CanvasCollageExporter(private val context: Context, private val assets: EditAssets) :
    CollageExporter {
    override suspend fun export(request: CollageRequest): ExportResult {
        val started = SystemClock.elapsedRealtime()
        val glThread = Executors.newSingleThreadExecutor { Thread(it, "photoxt-collage-gl") }
        val sources = HashMap<String, SourceImage>()
        try {
            return withContext(Dispatchers.IO) {
                request.photos.forEach { (id, file) -> sources[id] = SourceImage(file) }
                LookPass(assets).use { looks ->
                    render(request, sources, looks, glThread.asCoroutineDispatcher())
                }
            }.also {
                val ms = SystemClock.elapsedRealtime() - started
                PxtLog.i(
                    TAG,
                    "Collage ${it.width}×${it.height} from ${sources.size} photos in $ms ms"
                )
            }
        } finally {
            sources.values.forEach { it.close() }
            glThread.shutdown()
        }
    }

    private suspend fun render(
        request: CollageRequest,
        sources: Map<String, SourceImage>,
        looks: LookPass,
        gl: kotlinx.coroutines.CoroutineDispatcher
    ): ExportResult {
        val project = request.project
        fun source(cell: Int) = project.cells.getOrNull(cell)?.mediaId?.let { sources[it] }
        val format =
            request.options.format.takeIf { it in CollageOutput.FORMATS } ?: ExportFormat.JPEG
        val asked = CollageSizing.size(project, request.options) { cell -> source(cell)?.upright }
        val size = fitted(asked, format)
        val geometry = CollageGeometry(project, size.width, size.height) { cell ->
            source(cell)?.upright?.let { it.width.toDouble() / it.height } ?: 1.0
        }
        val decoders = Dispatchers.Default.limitedParallelism(DECODERS)
        val pixels = coroutineScope {
            geometry.places.map { place ->
                val look = project.cells.getOrNull(place.cell)?.look?.takeUnless { it.isIdentity }
                async(if (look != null) gl else decoders) {
                    val frame = geometry.photoFrame(place.cell)
                    val image = source(place.cell)
                    place.cell to if (frame == null || image == null) {
                        null
                    } else if (look != null) {
                        looks.region(
                            image,
                            look,
                            frame.visible(),
                            frame.density(image.upright.width)
                        )
                    } else {
                        decoded(image, frame)
                    }
                }
            }.awaitAll().toMap()
        }
        val bitmap = createBitmap(size.width, size.height)
        val photos = object : CollagePhotos {
            override fun pixels(cell: Int, frame: CollageGeometry.PhotoFrame) = pixels[cell]

            override fun small(cell: Int): Bitmap? = source(cell)?.let {
                Orientations.upright(it.decodeScaled(SMALL_EDGE), it.orientation)
            }
        }
        CollageRenderer(assets).draw(
            Canvas(bitmap),
            geometry,
            photos,
            opaque =
                format == ExportFormat.JPEG
        )
        request.output.parentFile?.mkdirs()
        Encoders.write(bitmap, format, request.options.quality, request.output)
        bitmap.recycle()
        verify(request.output, size)
        return ExportResult(
            checkNotNull(format.mime),
            size.width,
            size.height,
            request.output.length()
        )
    }

    /** The part of [image] that [frame] shows, upright, at about the resolution it is drawn at. */
    private fun decoded(image: SourceImage, frame: CollageGeometry.PhotoFrame): CellPixels {
        val upright = image.upright
        val raster = image.size
        val want = Orientation.rasterBox(frame.visible(), image.orientation)
        val rect = Rect(
            floor(want.left * raster.width).toInt().coerceIn(0, raster.width - 1),
            floor(want.top * raster.height).toInt().coerceIn(0, raster.height - 1),
            ceil(want.right * raster.width).toInt().coerceIn(1, raster.width),
            ceil(want.bottom * raster.height).toInt().coerceIn(1, raster.height)
        )
        // Canvas pixels per raster pixel; a sample of 2^k keeps at least that many.
        val density = frame.density(upright.width)
        val needed = max(1, (max(rect.width(), rect.height()) * density).roundToInt())
        val sample = SourceImage.sampleFor(max(rect.width(), rect.height()), needed)
        var bitmap = Orientations.upright(image.decodeRegion(rect, sample), image.orientation)
        // Well over the pixels needed: shrink now, so all the cells fit in memory at once.
        if (max(bitmap.width, bitmap.height) > needed * SHRINK_OVER) {
            val scale = needed.toDouble() / max(bitmap.width, bitmap.height)
            bitmap = bitmap.scaledTo(
                Size(
                    max(1, (bitmap.width * scale).roundToInt()),
                    max(1, (bitmap.height * scale).roundToInt())
                )
            )
        }
        val got = Box(
            rect.left.toDouble() / raster.width,
            rect.top.toDouble() / raster.height,
            rect.right.toDouble() / raster.width,
            rect.bottom.toDouble() / raster.height
        )
        return CellPixels(bitmap, Orientation.uprightBox(got, image.orientation))
    }

    /** [size], shrunk until the canvas and its cells fit in memory and the format holds it. */
    private fun fitted(size: Size, format: ExportFormat): Size {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val budget = min(info.availMem / MEMORY_SHARE, MAX_BYTES)
        // The canvas, and about as much again for the cells' pixels. No figure: no limit known.
        val need = size.pixels * PIXEL * 2
        var scale = if (budget <= 0 || need <= budget) 1.0 else sqrt(budget.toDouble() / need)
        if (format == ExportFormat.WEBP && size.longEdge * scale > WEBP_MAX) {
            scale = WEBP_MAX.toDouble() / size.longEdge
        }
        if (scale >= 1.0) return size
        val fits = Size(max(1, (size.width * scale).toInt()), max(1, (size.height * scale).toInt()))
        PxtLog.i(TAG, "Collage shrunk to fit: $size to $fits")
        return fits
    }

    /** The written file decoded back whole (at half size, which still reads every byte). */
    private fun verify(file: File, size: Size) {
        val options = BitmapFactory.Options().apply { inSampleSize = VERIFY_SAMPLE }
        val back = BitmapFactory.decodeFile(file.path, options)
            ?: throw IOException("The collage doesn't decode")
        val okW = kotlin.math.abs(back.width - size.width / VERIFY_SAMPLE.toDouble()) <= 1
        val okH = kotlin.math.abs(back.height - size.height / VERIFY_SAMPLE.toDouble()) <= 1
        back.recycle()
        if (!okW || !okH) throw IOException("The collage decodes at the wrong size")
    }

    private companion object {
        const val TAG = "Collage"
        const val DECODERS = 3
        const val SMALL_EDGE = 256
        const val SHRINK_OVER = 1.5
        const val PIXEL = 4L
        const val MEMORY_SHARE = 3L
        const val MAX_BYTES = 1_200_000_000L
        const val WEBP_MAX = 16_383
        const val VERIFY_SAMPLE = 2
    }
}

/** A photo's size upright: its raster turned by its EXIF orientation. */
val SourceImage.upright: Size
    get() = if (orientation in RenderGeometry.TRANSPOSED) Size(size.height, size.width) else size

/** Boxes between the upright photo and the raster as stored, by EXIF orientation. */
internal object Orientation {
    fun rasterBox(upright: Box, orientation: Int): Box = mapped(upright, orientation)

    fun uprightBox(raster: Box, orientation: Int): Box = mapped(raster, inverse(orientation))

    /** [box]'s corners through the upright-to-raster map of [orientation], boxed. */
    private fun mapped(box: Box, orientation: Int): Box {
        if (orientation <= 1) return box
        val corners = listOf(
            box.left to box.top,
            box.right to box.top,
            box.right to box.bottom,
            box.left to box.bottom
        ).map { (x, y) -> Orientations.toRaster(orientation, x.toFloat(), y.toFloat()) }
        return Box(
            corners.minOf { it.x.toDouble() },
            corners.minOf { it.y.toDouble() },
            corners.maxOf { it.x.toDouble() },
            corners.maxOf { it.y.toDouble() }
        )
    }

    /** The orientation that undoes [orientation]: quarter turns swap, the rest undo themselves. */
    private fun inverse(orientation: Int): Int = when (orientation) {
        ROTATE_90 -> ROTATE_270
        ROTATE_270 -> ROTATE_90
        else -> orientation
    }

    private const val ROTATE_90 = 6
    private const val ROTATE_270 = 8
}

/**
 * A cell's look (spec §4.11: "per-cell filter and adjustments, using the editor pipeline"): the
 * photo drawn through the editor's shader with only the look's settings, so a cell looks as the
 * editor would show that photo. Uses one GPU context: call it from one thread only.
 */
class LookPass(private val assets: EditAssets) : AutoCloseable {
    private var session: GlSession? = null
    private val gl: GlSession get() = session ?: GlSession().also { session = it }

    /** The studio's proxy of a photo (upright) with [look] over it, at the same size. */
    fun whole(proxy: Bitmap, look: EditRecipe): Bitmap {
        val raster = Size(proxy.width, proxy.height)
        val guide = proxy.scaledTo(SourceImage.fit(raster, PhotoInputs.GUIDE_EDGE)).mutableCopy()
        val shown = look.only()
        val geometry = RenderGeometry(raster, 1, Geometry(), 1.0, Frame())
        gl.setPhoto(PhotoInputs.fromGuide(guide, shown, assets))
        gl.setRecipe(shown, geometry, RecipeTables.build(shown, raster, assets))
        gl.setSource(proxy, Box.WHOLE)
        return drawn(0, 0, raster.width, raster.height)
    }

    /**
     * [visible] (upright fractions) of the photo in [source] with [look] over it, at [density]
     * output pixels per upright photo pixel.
     */
    fun region(source: SourceImage, look: EditRecipe, visible: Box, density: Double): CellPixels {
        val shown = look.only()
        val geometry = RenderGeometry(source.size, source.orientation, Geometry(), density, Frame())
        val out = geometry.output
        val x = floor(visible.left * out.width).toInt().coerceIn(0, out.width - 1)
        val y = floor(visible.top * out.height).toInt().coerceIn(0, out.height - 1)
        val w = (ceil(visible.right * out.width).toInt() - x).coerceIn(1, out.width - x)
        val h = (ceil(visible.bottom * out.height).toInt() - y).coerceIn(1, out.height - y)
        gl.setPhoto(PhotoInputs.build(source, shown, assets))
        gl.setRecipe(shown, geometry, RecipeTables.build(shown, source.size, assets))
        loadRegion(source, geometry, Rect(x, y, x + w, y + h))
        val bitmap = drawn(x, y, w, h)
        return CellPixels(
            bitmap,
            Box(
                x.toDouble() / out.width,
                y.toDouble() / out.height,
                (x + w).toDouble() / out.width,
                (y + h).toDouble() / out.height
            )
        )
    }

    /** Output pixels [x], [y], [w], [h], drawn in tiles the GPU takes. */
    private fun drawn(x: Int, y: Int, w: Int, h: Int): Bitmap {
        val tile = min(TILE, gl.maxTextureSize)
        val bitmap = createBitmap(w, h)
        val buffer = ByteBuffer.allocateDirect(tile * tile * PIXEL)
        val strip = createBitmap(tile, tile)
        val canvas = Canvas(bitmap)
        var ty = 0
        while (ty < h) {
            val th = min(tile, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = min(tile, w - tx)
                buffer.clear()
                gl.draw(x + tx, y + ty, tw, th, buffer)
                buffer.position(0)
                val part = if (tw == tile && th == tile) strip else createBitmap(tw, th)
                buffer.limit(tw * th * PIXEL)
                part.copyPixelsFromBuffer(buffer)
                canvas.drawBitmap(part, tx.toFloat(), ty.toFloat(), null)
                tx += tw
            }
            ty += th
        }
        return bitmap
    }

    /** The raster pixels output [tile] reads, at the resolution it needs, as the GPU's source. */
    private fun loadRegion(source: SourceImage, geometry: RenderGeometry, tile: Rect) {
        val raster = source.size
        val read = geometry.sourceBounds(
            tile.left.toDouble(),
            tile.top.toDouble(),
            tile.width().toDouble(),
            tile.height().toDouble()
        )
        val density = geometry.density(
            tile.exactCenterX().toDouble(),
            tile.exactCenterY().toDouble()
        )
        var sample = 1
        while (sample * 2 <= density) sample *= 2
        var rect: Rect
        while (true) {
            val apron = APRON * sample
            rect = Rect(
                max(0, floor(read.left * raster.width).toInt() - apron),
                max(0, floor(read.top * raster.height).toInt() - apron),
                min(raster.width, ceil(read.right * raster.width).toInt() + apron),
                min(raster.height, ceil(read.bottom * raster.height).toInt() + apron)
            )
            if (max(rect.width(), rect.height()) / sample <= gl.maxTextureSize) break
            sample *= 2
        }
        val bitmap = source.decodeRegion(rect, sample)
        gl.setSource(
            bitmap,
            Box(
                rect.left.toDouble() / raster.width,
                rect.top.toDouble() / raster.height,
                rect.right.toDouble() / raster.width,
                rect.bottom.toDouble() / raster.height
            )
        )
    }

    override fun close() {
        session?.close()
        session = null
    }

    private companion object {
        const val TILE = 2048
        const val PIXEL = 4
        const val APRON = 4
    }
}

/** Only what a cell's look sets: light, colour, curves, effects and the filter. */
fun EditRecipe.only(): EditRecipe =
    EditRecipe(light = light, color = color, curves = curves, effects = effects, filter = filter)

/**
 * The studio's looks (spec §4.11): each cell's proxy drawn through [LookPass] on a GPU thread of
 * its own, so the screen keeps showing the last one meanwhile.
 */
class LookSession(assets: EditAssets) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "photoxt-collage-looks") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val pass = LookPass(assets)

    suspend fun looked(proxy: Bitmap, look: EditRecipe): Bitmap =
        withContext(dispatcher) { pass.whole(proxy, look) }

    override fun close() {
        executor.execute { pass.close() }
        executor.shutdown()
    }
}
