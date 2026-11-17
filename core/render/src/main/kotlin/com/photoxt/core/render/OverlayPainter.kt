package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.photoxt.core.model.BrushTool
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.FontChoice
import com.photoxt.core.model.Layer
import com.photoxt.core.model.ShapeKind
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.StickerLayer
import com.photoxt.core.model.Stroke
import com.photoxt.core.model.TextAlignment
import com.photoxt.core.model.TextLayer
import com.photoxt.core.model.Watermark
import com.photoxt.core.model.WatermarkPosition
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a recipe's overlays (spec §4.10, Add): layers, freehand drawing and the watermark, on a
 * canvas where the cropped picture fills [content]. The preview and each strip of an export
 * call it with the same geometry, so text and strokes land on the same pixels.
 */
class OverlayPainter(private val recipe: EditRecipe, private val assets: EditAssets) {
    private val layouts = HashMap<String, StaticLayout>()
    private val pictures = HashMap<String, Bitmap?>()

    val isEmpty: Boolean
        get() = recipe.layers.isEmpty() && recipe.strokes.isEmpty() && recipe.watermark == null

    fun draw(canvas: Canvas, content: RectF) {
        if (isEmpty) return
        val edge = min(content.width(), content.height())
        if (recipe.strokes.isNotEmpty()) {
            val save = canvas.saveLayer(content, null)
            recipe.strokes.forEach { drawStroke(canvas, it, content, edge) }
            canvas.restoreToCount(save)
        }
        recipe.layers.forEach { layer ->
            val x = content.left + layer.x * content.width()
            val y = content.top + layer.y * content.height()
            canvas.withTranslation(x, y) {
                rotate(layer.rotation)
                drawLayer(this, layer, content, edge)
            }
        }
        recipe.watermark?.let { drawWatermark(canvas, it, content, edge) }
    }

    /** The layer's box, centred on its point, in canvas pixels before rotation. */
    fun bounds(layer: Layer, content: RectF): RectF {
        val edge = min(content.width(), content.height())
        val (w, h) = when (layer) {
            is TextLayer -> textLayout(layer, content, edge).let {
                it.width.toFloat() to
                    it.height.toFloat()
            }

            is StickerLayer -> stickerSize(layer, edge)

            is ShapeLayer -> layer.width * edge to layer.height * edge
        }
        val pad = if (layer is TextLayer) layer.size * edge * TEXT_PAD else 0f
        val cx = content.left + layer.x * content.width()
        val cy = content.top + layer.y * content.height()
        return RectF(cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad)
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, content: RectF, edge: Float) {
        when (layer) {
            is TextLayer -> drawText(canvas, layer, content, edge)
            is StickerLayer -> drawSticker(canvas, layer, edge)
            is ShapeLayer -> drawShape(canvas, layer, edge)
        }
    }

    private fun drawText(canvas: Canvas, layer: TextLayer, content: RectF, edge: Float) {
        val layout = textLayout(layer, content, edge)
        val w = layout.width.toFloat()
        val h = layout.height.toFloat()
        val size = layer.size * edge
        layer.background?.let { color ->
            val pad = size * TEXT_PAD
            canvas.drawRoundRect(
                RectF(-w / 2 - pad, -h / 2 - pad, w / 2 + pad, h / 2 + pad),
                pad,
                pad,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            )
        }
        canvas.withTranslation(-w / 2, -h / 2) {
            val paint = layout.paint
            layer.outlineColor?.let { outline ->
                // The outline is the same text stroked under the fill, twice its width wide.
                val fill = paint.color
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = layer.outlineWidth * size * 2
                paint.color = outline
                layout.draw(this)
                paint.style = Paint.Style.FILL
                paint.color = fill
            }
            layout.draw(this)
        }
    }

    private fun textLayout(layer: TextLayer, content: RectF, edge: Float): StaticLayout {
        val size = layer.size * edge
        val key = "${layer.id}:$size:${layer.hashCode()}"
        return layouts.getOrPut(key) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                color = layer.color
                typeface = Fonts.typeface(layer.font, layer.bold)
                if (layer.shadow) {
                    setShadowLayer(
                        size * SHADOW_RADIUS,
                        size * SHADOW_DX,
                        size * SHADOW_DY,
                        SHADOW
                    )
                }
            }
            val max = ceil(content.width() * MAX_TEXT_WIDTH).toInt()
            val widest = layer.text.lines().maxOfOrNull { paint.measureText(it) } ?: 0f
            val width = max(1, min(max, ceil(widest).toInt() + 1))
            val alignment = when (layer.align) {
                TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
                TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
            }
            StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint, width)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
        }
    }

    private fun stickerSize(layer: StickerLayer, edge: Float): Pair<Float, Float> {
        val h = layer.size * edge
        val picture = layer.image?.let(::picture)
        return if (picture != null) h * picture.width / picture.height to h else h to h
    }

    private fun drawSticker(canvas: Canvas, layer: StickerLayer, edge: Float) {
        val (w, h) = stickerSize(layer, edge)
        val picture = layer.image?.let(::picture)
        if (picture != null) {
            canvas.drawBitmap(
                picture,
                null,
                RectF(-w / 2, -h / 2, w / 2, h / 2),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return
        }
        val emoji = layer.emoji ?: return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * EMOJI_SCALE
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        canvas.drawText(emoji, 0f, -(metrics.ascent + metrics.descent) / 2, paint)
    }

    private fun drawShape(canvas: Canvas, layer: ShapeLayer, edge: Float) {
        val w = layer.width * edge
        val h = layer.height * edge
        val box = RectF(-w / 2, -h / 2, w / 2, h / 2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, layer.strokeWidth * edge)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = layer.stroke
        }
        val fill = layer.fill?.let { c -> Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c } }
        when (layer.shape) {
            ShapeKind.RECTANGLE -> {
                fill?.let { canvas.drawRect(box, it) }
                canvas.drawRect(box, stroke)
            }

            ShapeKind.ELLIPSE -> {
                fill?.let { canvas.drawOval(box, it) }
                canvas.drawOval(box, stroke)
            }

            ShapeKind.LINE -> canvas.drawLine(-w / 2, 0f, w / 2, 0f, stroke)

            ShapeKind.ARROW -> {
                val head = max(stroke.strokeWidth * ARROW_HEAD, h / 2)
                canvas.drawLine(-w / 2, 0f, w / 2 - stroke.strokeWidth, 0f, stroke)
                val path = Path().apply {
                    moveTo(w / 2, 0f)
                    lineTo(w / 2 - head, -head * ARROW_SPREAD)
                    lineTo(w / 2 - head, head * ARROW_SPREAD)
                    close()
                }
                canvas.drawPath(path, Paint(stroke).apply { style = Paint.Style.FILL_AND_STROKE })
            }
        }
    }

    /** A drawn line: its points come as x, y and pressure, in fractions of the picture. */
    private fun drawStroke(canvas: Canvas, stroke: Stroke, content: RectF, edge: Float) {
        val p = stroke.points
        val count = p.size / POINT
        if (count == 0) return
        val width = max(1f, stroke.width * edge)
        val paint = strokePaint(stroke)
        fun x(i: Int) = content.left + p[i * POINT] * content.width()
        fun y(i: Int) = content.top + p[i * POINT + 1] * content.height()
        fun pressed(i: Int) = p[i * POINT + 2]
        if (stroke.tool == BrushTool.PEN) {
            // Pressure changes the width along the line, a segment at a time.
            if (count == 1) {
                paint.strokeWidth = width * pressure(pressed(0))
                canvas.drawPoint(x(0), y(0), paint)
            }
            for (i in 0 until count - 1) {
                paint.strokeWidth = width * pressure((pressed(i) + pressed(i + 1)) / 2)
                canvas.drawLine(x(i), y(i), x(i + 1), y(i + 1), paint)
            }
        } else {
            paint.strokeWidth = width
            val path = Path().apply {
                moveTo(x(0), y(0))
                for (i in 1 until count) lineTo(x(i), y(i))
                // A tap is one point: a line too short to see still leaves its dot.
                if (count == 1) lineTo(x(0) + DOT, y(0))
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun strokePaint(stroke: Stroke) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val highlighter = stroke.tool == BrushTool.HIGHLIGHTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = if (highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        color = stroke.color
        when (stroke.tool) {
            BrushTool.MARKER -> alpha = MARKER_ALPHA
            BrushTool.HIGHLIGHTER -> alpha = HIGHLIGHTER_ALPHA
            BrushTool.ERASER -> xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            BrushTool.PEN -> Unit
        }
    }

    private fun drawWatermark(canvas: Canvas, mark: Watermark, content: RectF, edge: Float) {
        val stamp = stampOf(mark, mark.size * edge) ?: return
        if (mark.tiled) {
            tile(canvas, content, stamp)
            return
        }
        val margin = edge * MARGIN
        val cx = when (mark.position) {
            WatermarkPosition.TOP_START, WatermarkPosition.BOTTOM_START ->
                content.left + margin + stamp.width / 2

            WatermarkPosition.CENTER -> content.centerX()

            WatermarkPosition.TOP_END, WatermarkPosition.BOTTOM_END ->
                content.right - margin - stamp.width / 2
        }
        val cy = when (mark.position) {
            WatermarkPosition.TOP_START, WatermarkPosition.TOP_END ->
                content.top + margin + stamp.height / 2

            WatermarkPosition.CENTER -> content.centerY()

            WatermarkPosition.BOTTOM_START, WatermarkPosition.BOTTOM_END ->
                content.bottom - margin - stamp.height / 2
        }
        stamp.draw(canvas, cx, cy)
    }

    /** One copy of a watermark, [width] by [height], which [draw] centres on a point. */
    private class Stamp(
        val width: Float,
        val height: Float,
        val draw: (Canvas, Float, Float) -> Unit
    )

    /** The watermark's logo, or else its text, [size] pixels high; null when it has neither. */
    private fun stampOf(mark: Watermark, size: Float): Stamp? {
        val alpha = (mark.opacity.coerceIn(0f, 1f) * OPAQUE).toInt()
        val logo = mark.logo?.let(::picture)
        val text = mark.text?.takeIf { it.isNotBlank() }
        return when {
            logo != null -> {
                val w = size * logo.width / logo.height
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
                Stamp(w, size) { canvas, cx, cy ->
                    val box = RectF(cx - w / 2, cy - size / 2, cx + w / 2, cy + size / 2)
                    canvas.drawBitmap(logo, null, box, paint)
                }
            }

            text != null -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    color = mark.color
                    this.alpha = alpha
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                val metrics = paint.fontMetrics
                val rise = (metrics.ascent + metrics.descent) / 2
                Stamp(paint.measureText(text), size) { canvas, cx, cy ->
                    canvas.drawText(text, cx, cy - rise, paint)
                }
            }

            else -> null
        }
    }

    /** [stamp] repeated across [content] on a slant, every other row shifted half a step. */
    private fun tile(canvas: Canvas, content: RectF, stamp: Stamp) = canvas.withClip(content) {
        rotate(TILE_ANGLE, content.centerX(), content.centerY())
        val stepX = stamp.width * TILE_GAP_X
        val stepY = stamp.height * TILE_GAP_Y
        // Far enough each way to cover the picture at any turn.
        val reach = hypot(content.width(), content.height())
        var row = 0
        var y = content.centerY() - reach / 2
        while (y < content.centerY() + reach / 2) {
            var x = content.centerX() - reach / 2 + if (row % 2 == 1) stepX / 2 else 0f
            while (x < content.centerX() + reach / 2) {
                stamp.draw(this, x, y)
                x += stepX
            }
            y += stepY
            row++
        }
    }

    private fun picture(name: String): Bitmap? = pictures.getOrPut(name) {
        assets.bitmap(name, MAX_PICTURE)
    }

    /** How wide a pen line is at pressure [p]; a finger, which reports none, draws full width. */
    private fun pressure(p: Float) =
        if (p <= 0f) 1f else MIN_PRESSURE + (1 - MIN_PRESSURE) * p.coerceIn(0f, 1f)

    private companion object {
        const val POINT = 3
        const val DOT = 0.1f
        const val TEXT_PAD = 0.25f
        const val MAX_TEXT_WIDTH = 0.92f
        const val SHADOW_RADIUS = 0.12f
        const val SHADOW_DX = 0.03f
        const val SHADOW_DY = 0.05f
        const val SHADOW = -0x67000000
        const val EMOJI_SCALE = 0.85f
        const val ARROW_HEAD = 3.5f
        const val ARROW_SPREAD = 0.6f
        const val MARKER_ALPHA = 215
        const val HIGHLIGHTER_ALPHA = 95
        const val OPAQUE = 255f
        const val MIN_PRESSURE = 0.35f
        const val MARGIN = 0.03f
        const val TILE_ANGLE = -30f
        const val TILE_GAP_X = 1.8f
        const val TILE_GAP_Y = 3.5f
        const val MAX_PICTURE = 2048
    }
}

/** The text tool's typefaces (spec §4.10: "fonts including Devanagari"). */
object Fonts {
    private val cache = HashMap<Pair<FontChoice, Boolean>, Typeface>()

    @Synchronized
    fun typeface(choice: FontChoice, bold: Boolean): Typeface = cache.getOrPut(choice to bold) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        when (choice) {
            FontChoice.SANS -> Typeface.create(Typeface.SANS_SERIF, style)

            FontChoice.SERIF -> Typeface.create(Typeface.SERIF, style)

            FontChoice.MONO -> Typeface.create(Typeface.MONOSPACE, style)

            FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", style)

            FontChoice.HANDWRITING -> Typeface.create("cursive", style)

            FontChoice.DEVANAGARI_SANS -> system("NotoSansDevanagari", bold)
                ?: Typeface.create(Typeface.SANS_SERIF, style)

            FontChoice.DEVANAGARI_SERIF -> system("NotoSerifDevanagari", bold)
                ?: Typeface.create(Typeface.SERIF, style)
        }
    }

    /** A system font file whose name starts with [prefix], falling back to the system fonts. */
    private fun system(prefix: String, bold: Boolean): Typeface? = runCatching {
        val fonts = SystemFonts.getAvailableFonts().filter {
            it.file?.name?.startsWith(prefix) ==
                true
        }
        val want = if (bold) BOLD_WEIGHT else NORMAL_WEIGHT
        val font: Font =
            fonts.minByOrNull { kotlin.math.abs(it.style.weight - want) } ?: return null
        Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
            .setSystemFallback(if (prefix.contains("Serif")) "serif" else "sans-serif")
            .build()
    }.getOrNull()

    /** Whether this phone has the font for [choice] (Devanagari faces may be missing). */
    fun available(choice: FontChoice): Boolean = when (choice) {
        FontChoice.DEVANAGARI_SANS -> system("NotoSansDevanagari", false) != null
        FontChoice.DEVANAGARI_SERIF -> system("NotoSerifDevanagari", false) != null
        else -> true
    }

    private const val NORMAL_WEIGHT = 400
    private const val BOLD_WEIGHT = 700
}
