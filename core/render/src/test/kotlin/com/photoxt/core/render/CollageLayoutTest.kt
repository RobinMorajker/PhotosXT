package com.photoxt.core.render

import com.photoxt.core.model.COLLAGE_MAX_PHOTOS
import com.photoxt.core.model.COLLAGE_MIN_PHOTOS
import com.photoxt.core.model.CanvasAspect
import com.photoxt.core.model.CollageCanvas
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageMode
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.SplitAxis
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collage layouts, cells and sizes (spec §4.11), on plain numbers. */
class CollageLayoutTest {
    @Test
    fun everyCountHasTemplatesAndThereAreOverAHundred() {
        val all = CollageTemplates.all
        assertTrue("${all.size} templates", all.size >= 100)
        for (n in COLLAGE_MIN_PHOTOS..COLLAGE_MAX_PHOTOS) {
            val templates = CollageTemplates.grid(n)
            assertTrue("$n photos: ${templates.size}", templates.size >= 4)
            templates.forEach { t ->
                assertEquals(t.id, (0 until n).toList(), t.layout.leaves().sorted())
                assertWeights(t.layout)
            }
            assertEquals(templates.size, templates.map { it.id }.toSet().size)
            assertTrue(CollageTemplates.story(n).isNotEmpty())
        }
    }

    @Test
    fun aDividerDragMovesWeightBetweenItsTwoCellsOnly() {
        val layout = LayoutNode.split(SplitAxis.ROW, List(3) { LayoutNode(cell = it) })
        val project = CollageProject(
            layout = layout,
            cells = List(3) { CollageCell() },
            canvas = CollageCanvas(spacing = 0.01f, margin = 0f)
        )
        val geometry = CollageGeometry(project, 1000, 500) { 1.0 }
        val first = geometry.dividers.first { it.index == 0 }

        val moved = LayoutEdits.dragDivider(layout, first, first.position + 100)

        val after = CollageGeometry(project.copy(layout = moved), 1000, 500) { 1.0 }
        val before0 = geometry.place(0)!!.rect.width
        assertEquals(before0 + 100, after.place(0)!!.rect.width, 0.5)
        assertEquals(geometry.place(2)!!.rect.width, after.place(2)!!.rect.width, 0.01)
        // Never thinner than 12% of what the two span.
        val squeezed = LayoutEdits.dragDivider(layout, first, first.start)
        assertTrue(squeezed.weights[0] / (squeezed.weights[0] + squeezed.weights[1]) >= 0.119f)
    }

    @Test
    fun aDividerSnapsToHalfAndToItsNeighboursLine() {
        val layout = CollageTemplates.plain(4)
        val project = CollageProject(layout = layout, cells = List(4) { CollageCell() })
        val geometry = CollageGeometry(project, 1000, 1000) { 1.0 }
        val divider = geometry.dividers.first { it.axis == SplitAxis.ROW }
        val (at, snapped) = LayoutEdits.snapDivider(
            geometry.dividers,
            divider,
            divider.position + 6,
            10.0
        )
        assertTrue(snapped)
        assertEquals(divider.position, at, 0.01)
        val (free, no) = LayoutEdits.snapDivider(
            geometry.dividers,
            divider,
            divider.position + 60,
            10.0
        )
        assertEquals(false, no)
        assertEquals(divider.position + 60, free, 0.01)
    }

    @Test
    fun smartLayoutFitsThePhotosShapesBetterThanAPlainGrid() {
        val aspects = listOf(4f / 3, 4f / 3, 3f / 4, 3f / 4, 16f / 9)
        val (layout, _) = SmartLayout.build(aspects, aspects.map { emptyList() }, 1f)
        val smart = CollageTemplates.cellRects(layout)
        val plain = CollageTemplates.cellRects(CollageTemplates.plain(aspects.size))
        fun cut(rects: Map<Int, Rect01>) = aspects.indices.sumOf { i ->
            val r = rects.getValue(i)
            val cellAspect = r.width / r.height
            val ratio = cellAspect / aspects[i]
            (1 - minOf(ratio, 1 / ratio)).toDouble()
        }
        assertEquals((0 until aspects.size).toList(), layout.leaves().sorted())
        assertTrue("smart ${cut(smart)} vs plain ${cut(plain)}", cut(smart) < cut(plain))
    }

    @Test
    fun smartLayoutCentresOnFacesAndKeepsThemWhole() {
        // A tall photo with a face near its top, in a cell that must cut it from above and below.
        val face = Rect01(0.4f, 0.05f, 0.6f, 0.2f)
        val focus = SmartLayout.focusOf(listOf(face), 1f to 0.5f)
        assertEquals(0.5f, focus.first, 1e-4f)
        // The window is half the photo tall: its centre sits at 0.25, so the face stays in it.
        assertEquals(0.25f, focus.second, 1e-4f)
        assertEquals(0.5f to 0.5f, SmartLayout.focusOf(emptyList(), 1f to 0.5f))
    }

    @Test
    fun aStitchCutsNothing() {
        val aspects = listOf(16f / 9, 9f / 16, 1f)
        val sizes = listOf(Size(1920, 1080), Size(1080, 1920), Size(1500, 1500))
        val project = CollageProject(
            mode = CollageMode.STITCH,
            // Stale weights, as after a swap: the photos' shapes win.
            layout = Stitch.layout(listOf(1f, 1f, 1f), horizontal = false),
            cells = List(3) { CollageCell() },
            canvas = CollageCanvas(aspect = CanvasAspect.AUTO, spacing = 0.01f, margin = 0.02f)
        )
        val size = CollageSizing.size(project, CollageOutput(longEdge = null)) { sizes[it] }
        // The narrowest photo's width, never enlarged.
        assertEquals(1080, size.width)
        val geometry = CollageGeometry(project, size.width, size.height) { aspects[it].toDouble() }
        geometry.places.forEach { place ->
            val cellAspect = place.rect.width / place.rect.height
            assertEquals(aspects[place.cell].toDouble(), cellAspect, aspects[place.cell] * 0.01)
        }
        assertTrue(geometry.dividers.isEmpty())
    }

    @Test
    fun aPhotoCoversItsCellAndPansWithinIt() {
        val project = CollageProject(
            layout = LayoutNode(cell = 0),
            cells = listOf(CollageCell(mediaId = "a")),
            canvas = CollageCanvas(margin = 0f)
        )
        val geometry = CollageGeometry(project, 1000, 1000) { 4.0 / 3 }
        val frame = geometry.photoFrame(0)!!
        // A 4:3 photo covering a square shows its middle three quarters.
        val visible = frame.visible()
        assertEquals(0.125, visible.left, 1e-6)
        assertEquals(0.875, visible.right, 1e-6)
        assertEquals(0.0, visible.top, 1e-6)
        assertEquals(1.0, visible.bottom, 1e-6)
        // A drag far to the right shows the photo's left edge, and no further.
        val panned = frame.panned(5000.0, 0.0)
        assertEquals(0.375f, panned.focusX, 1e-5f)
        assertEquals(0.5f, panned.focusY, 1e-5f)
        // Turned a quarter, the photo still covers the cell: it grows to.
        val turned = CollageGeometry(
            project.copy(cells = listOf(CollageCell(mediaId = "a", rotation = 90f))),
            1000,
            1000
        ) { 4.0 / 3 }.photoFrame(0)!!
        assertTrue(turned.shownHeight >= 1000 - 1e-6 && turned.shownWidth >= 1000 - 1e-6)
    }

    @Test
    fun aFlippedPhotoShowsItsOtherSide() {
        val project = CollageProject(
            layout = LayoutNode(cell = 0),
            cells = listOf(CollageCell(mediaId = "a", flipH = true, focusX = 0.4f, zoom = 2f)),
            canvas = CollageCanvas(margin = 0f)
        )
        val frame = CollageGeometry(project, 1000, 1000) { 1.0 }.photoFrame(0)!!
        val (x, _) = frame.map.map(0.0, 0.5)
        // The photo's left edge is on the right once flipped.
        assertTrue("x = $x", x > 500)
    }

    @Test
    fun cellsAreFoundUnderTheFinger() {
        val project = CollageProject(
            layout = LayoutNode.split(
                SplitAxis.ROW,
                listOf(LayoutNode(cell = 0), LayoutNode(cell = 1))
            ),
            cells = List(2) { CollageCell() },
            canvas = CollageCanvas(spacing = 0.1f, margin = 0f)
        )
        val geometry = CollageGeometry(project, 1000, 500) { 1.0 }
        assertEquals(0, geometry.cellAt(100.0, 250.0))
        assertEquals(1, geometry.cellAt(900.0, 250.0))
        // The gap between them is no cell, but its divider.
        assertNull(geometry.cellAt(500.0, 250.0))
        assertNotNull(geometry.dividerAt(500.0, 250.0, 12.0))
    }

    @Test
    fun sizesFollowTheCanvas() {
        val wide = CollageProject(canvas = CollageCanvas(aspect = CanvasAspect.SIXTEEN_NINE))
        assertEquals(Size(3840, 2160), CollageSizing.size(wide, CollageOutput()) { null })
        assertEquals(
            Size(7680, 4320),
            CollageSizing.size(wide, CollageOutput(longEdge = CollageOutput.LONG_EDGE_8K)) { null }
        )
        val a4 = CollageProject(canvas = CollageCanvas(aspect = CanvasAspect.A4))
        assertEquals(
            Size(2480, 3508),
            CollageSizing.size(a4, CollageOutput(longEdge = null)) {
                null
            }
        )
        // Past 8K, a ratio is held to 8K.
        val huge = CollageProject(
            canvas = CollageCanvas(
                aspect = CanvasAspect.CUSTOM,
                customWidth = 9000,
                customHeight = 1000
            )
        )
        assertTrue(
            CollageSizing.size(huge, CollageOutput(longEdge = null)) {
                null
            }.longEdge <= 7680
        )
    }

    @Test
    fun theLinearPartitionBalancesItsRuns() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3)),
            LinearPartition.split(listOf(1f, 1f, 1f, 1f), 2)
        )
        assertEquals(listOf(listOf(0), listOf(1, 2)), LinearPartition.split(listOf(3f, 1f, 2f), 2))
    }

    @Test
    fun swappingCellsSwapsTheirPhotosAndLooks() {
        val cells = listOf(
            CollageCell(mediaId = "a", zoom = 2f),
            CollageCell(mediaId = "b", flipH = true)
        )
        val swapped = LayoutEdits.swapCells(cells, 0, 1)
        assertEquals("b", swapped[0].mediaId)
        assertEquals(true, swapped[0].flipH)
        assertEquals("a", swapped[1].mediaId)
        // Each is framed afresh for the cell it lands in.
        assertEquals(1f, swapped[1].zoom)
    }

    private fun assertWeights(node: LayoutNode) {
        if (node.isLeaf) return
        assertEquals(node.children.size, node.weights.size)
        assertTrue(node.weights.all { it > 0f })
        node.children.forEach(::assertWeights)
    }

    private fun assertEquals(expected: Double, actual: Double, delta: Double) =
        org.junit.Assert.assertEquals(expected, actual, delta)

    private fun assertEquals(expected: Float, actual: Float, delta: Float) =
        org.junit.Assert.assertTrue("$expected ≠ $actual", abs(expected - actual) <= delta)
}
