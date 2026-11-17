package com.photoxt.core.render

import com.photoxt.core.model.CropAspect
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Frame
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.HslBand
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.Light
import com.photoxt.core.model.LocalMask
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.PastePart
import com.photoxt.core.model.Point01
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.Redaction
import com.photoxt.core.model.ShapeKind
import com.photoxt.core.model.ShapeLayer
import com.photoxt.core.model.TextLayer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's geometry, curves, LUTs and recipe format (spec §4.10). */
class RenderMathTest {
    private val raster = Size(400, 300)

    private fun geometry(g: Geometry = Geometry(), orientation: Int = 1, scale: Double = 1.0) =
        RenderGeometry(raster, orientation, g, scale)

    private fun assertMaps(geo: RenderGeometry, x: Double, y: Double, sx: Double, sy: Double) {
        val (u, v) = geo.outToSource.map(x, y)
        // Recipes hold floats: a fraction like 0.1 is good to about 1e-8.
        assertEquals("x of ($x, $y)", sx, u, FLOAT_EPS)
        assertEquals("y of ($x, $y)", sy, v, FLOAT_EPS)
    }

    @Test
    fun identityMapsCornersToCorners() {
        val geo = geometry()
        assertEquals(Size(400, 300), geo.output)
        assertMaps(geo, 0.0, 0.0, 0.0, 0.0)
        assertMaps(geo, 400.0, 300.0, 1.0, 1.0)
        assertMaps(geo, 200.0, 150.0, 0.5, 0.5)
    }

    @Test
    fun exifRotationIsUndone() {
        // Orientation 6: the raster is shown turned a quarter clockwise.
        val geo = geometry(orientation = 6)
        assertEquals(Size(300, 400), geo.output)
        assertMaps(geo, 0.0, 0.0, 0.0, 1.0)
        assertMaps(geo, 300.0, 0.0, 0.0, 0.0)
        assertMaps(geo, 300.0, 400.0, 1.0, 0.0)
        // Orientation 8: a quarter counter-clockwise.
        val ccw = geometry(orientation = 8)
        assertMaps(ccw, 0.0, 0.0, 1.0, 0.0)
        assertMaps(ccw, 0.0, 400.0, 0.0, 0.0)
    }

    @Test
    fun quarterTurnsAndFlips() {
        val turned = geometry(Geometry(quarterTurns = 1))
        assertEquals(Size(300, 400), turned.output)
        assertMaps(turned, 0.0, 0.0, 0.0, 1.0)
        val half = geometry(Geometry(quarterTurns = 2))
        assertMaps(half, 0.0, 0.0, 1.0, 1.0)
        val flipped = geometry(Geometry(flipH = true))
        assertMaps(flipped, 0.0, 0.0, 1.0, 0.0)
        val both = geometry(Geometry(flipV = true))
        assertMaps(both, 0.0, 0.0, 0.0, 1.0)
    }

    @Test
    fun cropAndScale() {
        val geo = geometry(Geometry(crop = Rect01(0.25f, 0.25f, 0.75f, 0.75f)), scale = 0.5)
        assertEquals(Size(100, 75), geo.output)
        assertMaps(geo, 0.0, 0.0, 0.25, 0.25)
        assertMaps(geo, 100.0, 75.0, 0.75, 0.75)
    }

    @Test
    fun straighteningNeverShowsEmptyCorners() {
        for (angle in listOf(-45f, -12.5f, 3f, 30f, 45f)) {
            val geo = geometry(Geometry(straighten = angle))
            for ((x, y) in listOf(0.0 to 0.0, 400.0 to 0.0, 400.0 to 300.0, 0.0 to 300.0)) {
                val (u, v) = geo.outToSource.map(x, y)
                assertTrue(
                    "$angle° corner ($x, $y) → ($u, $v)",
                    u in -EPS..1 + EPS && v in -EPS..1 + EPS
                )
            }
            assertMaps(geo, 200.0, 150.0, 0.5, 0.5)
        }
    }

    @Test
    fun keystoneNarrowsTheSourceEdge() {
        val geo = geometry(Geometry(keystoneV = 1f))
        assertMaps(geo, 0.0, 0.0, 0.25, 0.0)
        assertMaps(geo, 400.0, 0.0, 0.75, 0.0)
        assertMaps(geo, 0.0, 300.0, 0.0, 1.0)
        assertMaps(geo, 400.0, 300.0, 1.0, 1.0)
        val corners = geometry(Geometry(corners = listOf(0.1f, 0.1f, 0.9f, 0f, 1f, 1f, 0f, 0.9f)))
        assertMaps(corners, 0.0, 0.0, 0.1, 0.1)
        assertMaps(corners, 400.0, 0.0, 0.9, 0.0)
        assertMaps(corners, 0.0, 300.0, 0.0, 0.9)
    }

    @Test
    fun frameBorderSurroundsTheContent() {
        val polaroid = Frame(style = FrameStyle.POLAROID, width = 0.1f, radius = 0f)
        val geo = RenderGeometry(raster, 1, Geometry(), 1.0, polaroid)
        assertEquals(30, geo.border.left)
        assertEquals(120, geo.border.bottom)
        assertEquals(Size(460, 450), geo.output)
        assertMaps(geo, 30.0, 30.0, 0.0, 0.0)
        assertMaps(geo, 430.0, 330.0, 1.0, 1.0)
    }

    @Test
    fun inverseRoundTrips() {
        val geo =
            geometry(
                Geometry(
                    quarterTurns = 3,
                    straighten = 7f,
                    keystoneH = -0.4f,
                    crop = Rect01(0.1f, 0.2f, 0.8f, 0.9f)
                ),
                orientation = 6
            )
        val back = geo.sourceToOut
        for ((x, y) in listOf(10.0 to 20.0, 100.0 to 50.0, 150.0 to 140.0)) {
            val (u, v) = geo.outToSource.map(x, y)
            val (px, py) = back.map(u, v)
            assertEquals(x, px, 1e-6)
            assertEquals(y, py, 1e-6)
        }
    }

    @Test
    fun centredCropHasTheAspect() {
        val frame = Size(4000, 3000)
        for (aspect in CropAspect.entries.mapNotNull { it.ratio }) {
            val r = RenderGeometry.centredCrop(frame, aspect)
            assertEquals(aspect.toDouble(), r.width * 4000.0 / (r.height * 3000.0), 1e-3)
            assertTrue(r.left >= 0f && r.right <= 1f && r.top >= 0f && r.bottom <= 1f)
        }
    }

    @Test
    fun curvesPassThroughTheirPointsWithoutOvershoot() {
        assertTrue(
            ToneCurves.table(emptyList()).withIndex().all { (i, v) ->
                abs(v - i / 255f) <
                    1e-6
            }
        )
        val points =
            listOf(
                Point01(0f, 0f),
                Point01(0.25f, 0.15f),
                Point01(0.5f, 0.5f),
                Point01(0.75f, 0.9f),
                Point01(1f, 1f)
            )
        val table = ToneCurves.table(points)
        assertEquals(0.5f, table[(0.5f * 255).toInt()], 0.01f)
        assertEquals(0.9f, table[(0.75f * 255).toInt()], 0.01f)
        // Monotone points give a monotone curve.
        assertTrue(table.toList().zipWithNext().all { (a, b) -> b >= a - 1e-6f })
        assertTrue(
            ToneCurves.isIdentity(
                com.photoxt.core.model.Curves(rgb = listOf(Point01(0f, 0f), Point01(1f, 1f)))
            )
        )
        assertFalse(ToneCurves.isIdentity(com.photoxt.core.model.Curves(red = points)))
    }

    @Test
    fun cubeFilesParse() {
        val cube = """
            # A comment
            TITLE "Swap red and blue"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            1 1 1
        """.trimIndent()
        val lut = CubeLut.parse(cube.reader())
        assertEquals(2, lut.size)
        val red = lut.apply(1f, 0f, 0f)
        assertEquals(0f, red[0], 1e-6f)
        assertEquals(1f, red[2], 1e-6f)
        assertThrows(CubeFormatException::class.java) { CubeLut.parse("LUT_1D_SIZE 4\n".reader()) }
        assertThrows(CubeFormatException::class.java) {
            CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n".reader())
        }
        assertThrows(CubeFormatException::class.java) { CubeLut.parse("0 0 0\n".reader()) }
    }

    @Test
    fun builtInLooksExistAndStayInRange() {
        for (id in Looks.ids) {
            val lut = checkNotNull(Looks.lut(id)) { id }
            assertTrue(id, lut.data.all { it in 0f..1f })
        }
        val mono = checkNotNull(Looks.lut("mono")).apply(0.9f, 0.2f, 0.1f)
        assertEquals(mono[0], mono[1], 1e-3f)
    }

    @Test
    fun recipesRoundTripAsSmallJson() {
        assertEquals("{}", EditJson.encode(EditRecipe()))
        val recipe = EditRecipe(
            geometry = Geometry(
                quarterTurns = 1,
                crop = Rect01(0.1f, 0.1f, 0.9f, 0.9f),
                aspect = CropAspect.SQUARE
            ),
            light = Light(exposure = 0.5f, contrast = -0.2f),
            color = com.photoxt.core.model.ColorAdjust(
                hsl = mapOf(HslColor.BLUE to HslBand(saturation = -0.5f))
            ),
            masks = listOf(
                LocalMask(id = "m1", kind = MaskKind.RADIAL, center = Point01(0.3f, 0.4f))
            ),
            redactions = listOf(Redaction(Rect01(0.1f, 0.1f, 0.2f, 0.2f))),
            layers = listOf(
                TextLayer(id = "t", text = "नमस्ते"),
                ShapeLayer(id = "s", shape = ShapeKind.ARROW)
            )
        )
        val back = EditJson.decode(EditJson.encode(recipe))
        assertEquals(recipe, back)
        // A newer build's keys don't stop an older one.
        assertEquals(
            Light(exposure = 1f),
            EditJson.decode("""{"light":{"exposure":1,"future":2},"later":true}""").light
        )
        assertTrue(EditRecipe().isIdentity)
        assertFalse(recipe.isIdentity)
    }

    @Test
    fun pasteCarriesTheLookNotThePlaces() {
        val recipe = EditRecipe(
            light = Light(exposure = 1f),
            geometry = Geometry(quarterTurns = 1),
            redactions = listOf(Redaction(Rect01(0f, 0f, 0.1f, 0.1f))),
            layers = listOf(TextLayer(id = "t", text = "Hi"))
        )
        val plain = recipe.forPaste(emptySet())
        assertEquals(Light(exposure = 1f), plain.light)
        assertEquals(Geometry(), plain.geometry)
        assertTrue(plain.redactions.isEmpty() && plain.layers.isEmpty())
        val all = recipe.forPaste(PastePart.entries.toSet())
        assertEquals(1, all.geometry.quarterTurns)
        assertEquals(1, all.layers.size)
        assertTrue(all.redactions.isEmpty())
    }

    private companion object {
        const val EPS = 1e-9
        const val FLOAT_EPS = 1e-6
    }
}
