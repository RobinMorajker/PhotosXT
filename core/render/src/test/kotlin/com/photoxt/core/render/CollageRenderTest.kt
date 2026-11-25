package com.photoxt.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.photoxt.core.model.BackgroundKind
import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CanvasBackground
import com.photoxt.core.model.CollageCanvas
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.ShapeOutline
import com.photoxt.core.model.SplitAxis
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** The collage renderer and export draw cells, gaps and shapes where the geometry says. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageRenderTest {
    private val dir: File = Files.createTempDirectory("collage").toFile()
    private val assets = EditAssets(File(dir, "assets"))

    private fun solid(color: Int, w: Int = 40, h: Int = 30): Bitmap =
        createBitmap(w, h).apply { eraseColor(color) }

    private fun photos(vararg colors: Int) = object : CollagePhotos {
        override fun pixels(cell: Int, frame: CollageGeometry.PhotoFrame) =
            CellPixels(solid(colors[cell]), Box.WHOLE)

        override fun small(cell: Int): Bitmap = solid(colors[cell])
    }

    private fun twoInARow(spacing: Float) = CollageProject(
        layout = LayoutNode.split(
            SplitAxis.ROW,
            listOf(LayoutNode(cell = 0), LayoutNode(cell = 1))
        ),
        cells = listOf(CollageCell(mediaId = "a"), CollageCell(mediaId = "b")),
        canvas = CollageCanvas(spacing = spacing, margin = 0f)
    )

    private fun drawn(project: CollageProject, w: Int, h: Int, photos: CollagePhotos): Bitmap {
        val bitmap = createBitmap(w, h)
        val geometry = CollageGeometry(project, w, h) { 4.0 / 3 }
        CollageRenderer(assets).draw(Canvas(bitmap), geometry, photos, opaque = true)
        return bitmap
    }

    @Test
    fun cellsShowTheirPhotosAndTheGapShowsTheBackground() {
        val bitmap = drawn(twoInARow(spacing = 0.1f), 200, 100, photos(Color.RED, Color.BLUE))
        assertEquals(Color.RED, bitmap[40, 50])
        assertEquals(Color.BLUE, bitmap[160, 50])
        assertEquals(Color.WHITE, bitmap[100, 50])
    }

    @Test
    fun shapesKeepThePhotosInsideTheirOutline() {
        val project = CollageProject(
            mode = CollageMode.SHAPES,
            shape = ShapeOutline.CIRCLE,
            layout = LayoutNode(cell = 0),
            cells = listOf(CollageCell(mediaId = "a")),
            canvas = CollageCanvas(margin = 0f)
        )
        val bitmap = drawn(project, 100, 100, photos(Color.GREEN))
        assertEquals(Color.GREEN, bitmap[50, 50])
        assertEquals(Color.WHITE, bitmap[3, 3])
    }

    @Test
    fun aPatternAndAGradientFillTheBackground() {
        val project = twoInARow(spacing = 0.4f).copy(
            canvas = CollageCanvas(
                spacing = 0.4f,
                margin = 0f,
                background = CanvasBackground(
                    BackgroundKind.GRADIENT,
                    Color.BLACK,
                    Color.WHITE,
                    angle = 0f
                )
            )
        )
        val bitmap = drawn(project, 200, 100, photos(Color.RED, Color.BLUE))
        // The gap is mid-canvas, so mid-gradient: a grey.
        val gap = bitmap[100, 50]
        assertTrue(Color.red(gap) in 80..175)
    }

    @Test
    fun theExportWritesAFileThatDecodesAtItsSize() = runBlocking {
        val files = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { i, color ->
            File(dir, "p$i.jpg").also { file ->
                file.outputStream().use {
                    solid(color, 400, 300).compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
            }
        }
        val project = CollageProject(
            layout = LayoutNode.split(SplitAxis.ROW, List(3) { LayoutNode(cell = it) }),
            cells = List(3) { CollageCell(mediaId = "m$it") },
            canvas = CollageCanvas(
                aspect = CanvasAspect.CUSTOM,
                customWidth = 600,
                customHeight = 200,
                spacing = 0f,
                margin = 0f
            )
        )
        val out = File(dir, "collage.jpg")
        val context: Context = RuntimeEnvironment.getApplication()
        val result = CanvasCollageExporter(context, assets).export(
            CollageRequest(
                project,
                files.mapIndexed { i, f -> "m$i" to f }.toMap(),
                CollageOutput(longEdge = null),
                out
            )
        )
        assertEquals(600, result.width)
        assertEquals(200, result.height)
        val back = BitmapFactory.decodeFile(out.path)
        assertEquals(600, back.width)
        fun near(expected: Int, actual: Int) = abs(Color.red(expected) - Color.red(actual)) < 24 &&
            abs(Color.green(expected) - Color.green(actual)) < 24 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 24
        assertTrue(near(Color.RED, back[100, 100]))
        assertTrue(near(Color.GREEN, back[300, 100]))
        assertTrue(near(Color.BLUE, back[500, 100]))
        dir.deleteRecursively()
        Unit
    }
}
