package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.photoxt.core.model.BackgroundKind
import com.photoxt.core.model.CanvasBackground
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.PatternKind
import com.photoxt.core.model.ShapeOutline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pixels of one cell's photo, upright: [bitmap] shows [region] (fractions) of the photo. */
class CellPixels(val bitmap: Bitmap, val region: Box)

/** Where a collage renderer gets its photos: proxies for the studio, originals for the export. */
interface CollagePhotos {
    /** The pixels cell [cell] shows through [frame], its look applied; null for no photo. */
    fun pixels(cell: Int, frame: CollageGeometry.PhotoFrame): CellPixels?

    /** A small picture of cell [cell]'s photo, for a blurred background; null without one. */
    fun small(cell: Int): Bitmap?
}

/**
 * Draws a collage (spec §4.11) on a canvas the size of [CollageGeometry]: the background, the
 * frame, the photos in their cells (clipped to the shape in Shapes mode), the cells' corners
 * and borders, then the text and stickers. The studio draws it on screen and the export into
 * a bitmap, with the same geometry, so both show the same thing.
 */
class CollageRenderer(private val assets: EditAssets) {
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws [geometry]'s collage. [opaque] is for formats without see-through pixels (JPEG):
     * a transparent background is then white.
     */
    fun draw(canvas: Canvas, geometry: CollageGeometry, photos: CollagePhotos, opaque: Boolean) {
        val project = geometry.project
        val rounded = project.frame.style == FrameStyle.ROUNDED
        val outer = if (rounded) {
            roundedPath(
                geometry.canvas,
                project.frame.radius * geometry.shortEdge
            )
        } else {
            null
        }
        canvas.withSave {
            outer?.let { clipPath(it) }
            if (opaque) drawColor(Color.WHITE)
            drawFrame(this, geometry)
            withSave {
                clipRect(geometry.framed.toRectF())
                drawBackground(this, geometry, photos)
            }
            withSave {
                if (project.mode == CollageMode.SHAPES) {
                    clipPath(CollageShapes.path(project.shape, project.letters, geometry.content))
                }
                geometry.places.forEach { place -> drawCell(this, geometry, place, photos) }
            }
            if (project.layers.isNotEmpty()) {
                OverlayPainter(EditRecipe(layers = project.layers), assets)
                    .draw(this, geometry.canvas.toRectF())
            }
        }
    }

    private fun drawFrame(canvas: Canvas, geometry: CollageGeometry) {
        val frame = geometry.project.frame
        if (frame.style == FrameStyle.NONE) return
        canvas.drawColor(frame.color)
    }

    private fun drawBackground(canvas: Canvas, geometry: CollageGeometry, photos: CollagePhotos) {
        val background = geometry.project.canvas.background
        val area = geometry.framed
        when (background.kind) {
            BackgroundKind.SOLID -> canvas.drawColor(background.color)

            BackgroundKind.TRANSPARENT -> Unit

            BackgroundKind.GRADIENT -> canvas.drawRect(
                area.toRectF(),
                gradientPaint(background, area)
            )

            BackgroundKind.PATTERN -> {
                canvas.drawColor(background.color)
                drawPattern(canvas, background, area, geometry.shortEdge)
            }

            BackgroundKind.BLURRED_PHOTO -> {
                val small = photos.small(background.photoCell)
                if (small == null) {
                    canvas.drawColor(background.color)
                } else {
                    drawBlurred(canvas, small, area)
                }
            }
        }
    }

    private fun gradientPaint(background: CanvasBackground, area: PixelRect): Paint {
        val t = Math.toRadians(background.angle.toDouble())
        val half = max(area.width, area.height) / 2
        val dx = (cos(t) * half).toFloat()
        val dy = (sin(t) * half).toFloat()
        val cx = area.centerX.toFloat()
        val cy = area.centerY.toFloat()
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - dx,
                cy - dy,
                cx + dx,
                cy + dy,
                background.color,
                background.color2,
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun drawPattern(
        canvas: Canvas,
        background: CanvasBackground,
        area: PixelRect,
        edge: Double
    ) {
        val step = max(MIN_PATTERN_PX, (edge * PATTERN_STEP).toFloat())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background.color2
            strokeWidth = step * PATTERN_LINE
        }
        val r = area.toRectF()
        when (background.pattern) {
            PatternKind.DOTS -> dots(canvas, r, step, ink)
            PatternKind.STRIPES -> stripes(canvas, r, step, ink)
            PatternKind.CHECKS -> checks(canvas, r, step, Paint().apply { color = ink.color })
            PatternKind.GRID -> grid(canvas, r, step, ink)
        }
    }

    /** Dots in rows, every other row shifted half a step. */
    private fun dots(canvas: Canvas, r: RectF, step: Float, ink: Paint) {
        var y = r.top + step / 2
        var row = 0
        while (y < r.bottom + step) {
            var x = r.left + step / 2 + if (row % 2 == 1) step / 2 else 0f
            while (x < r.right + step) {
                canvas.drawCircle(x, y, step * DOT_RADIUS, ink)
                x += step
            }
            y += step
            row++
        }
    }

    /** Diagonal stripes, rising to the right. */
    private fun stripes(canvas: Canvas, r: RectF, step: Float, ink: Paint) {
        var x = r.left - r.height()
        while (x < r.right) {
            canvas.drawLine(x, r.bottom, x + r.height(), r.top, ink)
            x += step
        }
    }

    /** A chequerboard: every other square filled. */
    private fun checks(canvas: Canvas, r: RectF, step: Float, fill: Paint) {
        var y = r.top
        var row = 0
        while (y < r.bottom) {
            var x = r.left + if (row % 2 == 1) step else 0f
            while (x < r.right) {
                canvas.drawRect(x, y, x + step, y + step, fill)
                x += step * 2
            }
            y += step
            row++
        }
    }

    /** Lines across and down, a step apart. */
    private fun grid(canvas: Canvas, r: RectF, step: Float, ink: Paint) {
        var x = r.left
        while (x <= r.right) {
            canvas.drawLine(x, r.top, x, r.bottom, ink)
            x += step
        }
        var y = r.top
        while (y <= r.bottom) {
            canvas.drawLine(r.left, y, r.right, y, ink)
            y += step
        }
    }

    /** [small], blurred and a little darkened, covering [area]. */
    private fun drawBlurred(canvas: Canvas, small: Bitmap, area: PixelRect) {
        val blurred = blurredCache?.takeIf { blurredOf === small } ?: run {
            val tiny = small.scale(BLUR_EDGE, max(1, BLUR_EDGE * small.height / small.width))
            Blur.box(tiny, BLUR_RADIUS).also {
                blurredCache = it
                blurredOf = small
            }
        }
        val scale = max(area.width / blurred.width, area.height / blurred.height)
        val w = blurred.width * scale
        val h = blurred.height * scale
        val dst = RectF(
            (area.centerX - w / 2).toFloat(),
            (area.centerY - h / 2).toFloat(),
            (area.centerX + w / 2).toFloat(),
            (area.centerY + h / 2).toFloat()
        )
        canvas.drawBitmap(blurred, null, dst, photoPaint)
        canvas.drawColor(BLUR_SHADE)
    }

    private var blurredOf: Bitmap? = null
    private var blurredCache: Bitmap? = null

    private fun drawCell(
        canvas: Canvas,
        geometry: CollageGeometry,
        place: CellPlace,
        photos: CollagePhotos
    ) {
        val project = geometry.project
        val rect = place.rect
        val edge = geometry.shortEdge
        val radius = min(
            project.canvas.cornerRadius * edge,
            min(rect.width, rect.height) / 2
        ).toFloat()
        val local = RectF(
            (-rect.width / 2).toFloat(),
            (-rect.height / 2).toFloat(),
            (rect.width / 2).toFloat(),
            (rect.height / 2).toFloat()
        )
        canvas.withSave {
            translate(rect.centerX.toFloat(), rect.centerY.toFloat())
            rotate(place.rotation.toFloat())
            if (project.mode == CollageMode.FREEFORM) {
                drawRoundRect(local, radius, radius, shadowPaint(edge))
            }
            withSave {
                clipPath(Path().apply { addRoundRect(local, radius, radius, Path.Direction.CW) })
                rotate(-place.rotation.toFloat())
                translate(-rect.centerX.toFloat(), -rect.centerY.toFloat())
                val frame = geometry.photoFrame(place.cell)
                val pixels = frame?.let { photos.pixels(place.cell, it) }
                if (frame == null || pixels == null) {
                    drawRect(rect.toRectF(), Paint().apply { color = EMPTY_CELL })
                } else {
                    drawPixels(this, frame, pixels)
                }
            }
            val border = project.canvas.borderWidth * edge
            if (border > 0) {
                val inset = (border / 2).toFloat()
                val ring = RectF(
                    local.left + inset,
                    local.top + inset,
                    local.right - inset,
                    local.bottom - inset
                )
                val r = max(0f, radius - inset)
                drawRoundRect(
                    ring,
                    r,
                    r,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = border.toFloat()
                        color = project.canvas.borderColor
                    }
                )
            }
        }
    }

    private fun drawPixels(canvas: Canvas, frame: CollageGeometry.PhotoFrame, pixels: CellPixels) {
        val region = pixels.region
        val bitmap = pixels.bitmap
        val map = frame.map *
            Mat3.translate(region.left, region.top) *
            Mat3.scale(region.width / bitmap.width, region.height / bitmap.height)
        canvas.withSave {
            concat(map.toMatrix())
            drawBitmap(bitmap, 0f, 0f, photoPaint)
        }
    }

    private fun shadowPaint(edge: Double) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer((edge * SHADOW_RADIUS).toFloat(), 0f, (edge * SHADOW_DROP).toFloat(), SHADOW)
    }

    private fun roundedPath(rect: PixelRect, radius: Double) = Path().apply {
        addRoundRect(rect.toRectF(), radius.toFloat(), radius.toFloat(), Path.Direction.CW)
    }

    private companion object {
        const val EMPTY_CELL = -0x333334
        const val PATTERN_STEP = 1.0 / 24
        const val MIN_PATTERN_PX = 4f
        const val PATTERN_LINE = 0.12f
        const val DOT_RADIUS = 0.18f
        const val BLUR_EDGE = 96
        const val BLUR_RADIUS = 9
        const val BLUR_SHADE = 0x33000000
        const val SHADOW_RADIUS = 0.012
        const val SHADOW_DROP = 0.004
        const val SHADOW = 0x66000000
    }
}

/** The outlines of Shapes mode (spec §4.11), each fitted inside a rectangle. */
object CollageShapes {
    fun path(outline: ShapeOutline, letters: String, rect: PixelRect): Path = when (outline) {
        ShapeOutline.CIRCLE -> {
            val r = min(rect.width, rect.height) / 2
            Path().apply {
                addCircle(
                    rect.centerX.toFloat(),
                    rect.centerY.toFloat(),
                    r.toFloat(),
                    Path.Direction.CW
                )
            }
        }

        ShapeOutline.HEXAGON -> polygon(rect, SIDES_HEXAGON)

        ShapeOutline.HEART -> heart(rect)

        ShapeOutline.LETTERS -> lettered(letters.ifBlank { DEFAULT_LETTERS }, rect)
    }

    private fun polygon(rect: PixelRect, sides: Int): Path {
        val r = min(rect.width, rect.height) / 2
        return Path().apply {
            for (i in 0 until sides) {
                val a = -PI / 2 + i * 2 * PI / sides
                val x = (rect.centerX + r * cos(a)).toFloat()
                val y = (rect.centerY + r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** A heart in the largest square inside [rect]. */
    private fun heart(rect: PixelRect): Path {
        val s = min(rect.width, rect.height).toFloat()
        val fit = Matrix().apply {
            setScale(s, s)
            postTranslate((rect.centerX - s / 2).toFloat(), (rect.centerY - s / 2).toFloat())
        }
        return Path().also { UNIT_HEART.transform(fit, it) }
    }

    /** [text] in a heavy sans serif, as large as fits [rect]. */
    private fun lettered(text: String, rect: PixelRect): Path {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = BASE_TEXT
        }
        val glyphs = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, glyphs)
        val bounds = RectF()
        glyphs.computeBounds(bounds, true)
        if (bounds.width() <= 0 || bounds.height() <= 0) return Path()
        val scale = min(rect.width / bounds.width(), rect.height / bounds.height()).toFloat()
        val matrix = Matrix().apply {
            postTranslate(-bounds.centerX(), -bounds.centerY())
            postScale(scale, scale)
            postTranslate(rect.centerX.toFloat(), rect.centerY.toFloat())
        }
        glyphs.transform(matrix)
        return glyphs
    }

    private const val SIDES_HEXAGON = 6

    /** A heart in a unit square: two lobes and a point. Only ever read. */
    private val UNIT_HEART = Path().apply {
        moveTo(0.5f, 0.98f)
        cubicTo(0.08f, 0.68f, -0.02f, 0.33f, 0.2f, 0.12f)
        cubicTo(0.34f, 0f, 0.47f, 0.06f, 0.5f, 0.22f)
        cubicTo(0.53f, 0.06f, 0.66f, 0f, 0.8f, 0.12f)
        cubicTo(1.02f, 0.33f, 0.92f, 0.68f, 0.5f, 0.98f)
        close()
    }
    private const val BASE_TEXT = 200f
    private const val DEFAULT_LETTERS = "A"
}

/** An affine [Mat3] as Android's [Matrix]. */
fun Mat3.toMatrix(): Matrix = Matrix().apply { setValues(FloatArray(m.size) { m[it].toFloat() }) }

fun PixelRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
