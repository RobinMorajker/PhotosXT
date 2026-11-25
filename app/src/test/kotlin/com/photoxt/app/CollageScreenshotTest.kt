package com.photoxt.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.paging.PagingData
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.render.Box
import com.photoxt.core.render.CellPixels
import com.photoxt.core.render.CollageGeometry
import com.photoxt.core.render.CollagePhotos
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.Size
import com.photoxt.core.transfer.SourceCounts
import com.photoxt.feature.collage.BeforeAfterLabels
import com.photoxt.feature.collage.CollageContent
import com.photoxt.feature.collage.CollageCore
import com.photoxt.feature.collage.CollageEdits
import com.photoxt.feature.collage.CollagePhase
import com.photoxt.feature.collage.CollageStep
import com.photoxt.feature.collage.CollageStudio
import com.photoxt.feature.collage.CollageTool
import com.photoxt.feature.collage.PhotoInfo
import com.photoxt.feature.collage.Picked
import com.photoxt.feature.collage.R
import com.photoxt.feature.collage.Starts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The collage studio (spec §4.11) on four photos, with the Layout and the Canvas tools open, at
 * 100% and 200% text (§7).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class CollageScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun capture(name: String, tool: CollageTool) {
        val ids = listOf("a", "b", "c", "d")
        val project = Starts.grid(ids)
        val core =
            CollageCore(CoroutineScope(Dispatchers.Unconfined), Channel(Channel.UNLIMITED)) {
                    _,
                    _
                ->
            }
        core.update {
            it.copy(
                phase = CollagePhase.Ready,
                project = project,
                photos = ids.associateWith {
                    PhotoInfo(Size(PHOTO_WIDTH, PHOTO_HEIGHT), drive = false)
                },
                steps = listOf(CollageStep(R.string.collage_step_open, project)),
                stepIndex = 0,
                tool = tool
            )
        }
        val studio = StandIn(
            CollageEdits(core, faces = {
                emptyMap()
            }, labels = BeforeAfterLabels("Before", "After")),
            EditAssets(folder.newFolder())
        )
        val pictures = ids.indices.map { picture(it) }
        val photos = object : CollagePhotos {
            override fun pixels(cell: Int, frame: CollageGeometry.PhotoFrame) =
                pictures.getOrNull(cell)?.let { CellPixels(it, Box.WHOLE) }

            override fun small(cell: Int): Bitmap? = pictures.getOrNull(cell)
        }
        val ui = core.state.value
        // In the light theme, as most phones are by day: the studio turns itself dark.
        compose.setContent {
            PxtTheme(darkTheme = false) {
                CollageContent(
                    ui = ui,
                    photos = photos,
                    studio = studio,
                    onClose = {},
                    snackbar = remember { SnackbarHostState() }
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** A gradient of its own for each photo, the same every run. */
    private fun picture(index: Int): Bitmap =
        Bitmap.createBitmap(PHOTO_WIDTH / 10, PHOTO_HEIGHT / 10, Bitmap.Config.ARGB_8888).also {
            val (top, bottom) = GRADIENTS[index % GRADIENTS.size]
            val paint = Paint().apply {
                shader =
                    LinearGradient(
                        0f,
                        0f,
                        0f,
                        it.height.toFloat(),
                        top,
                        bottom,
                        Shader.TileMode.CLAMP
                    )
            }
            Canvas(it).drawRect(0f, 0f, it.width.toFloat(), it.height.toFloat(), paint)
        }

    @Test
    fun layout() = capture("collage_layout", CollageTool.LAYOUT)

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun layoutAt200PercentText() = capture("collage_layout_200", CollageTool.LAYOUT)

    @Test
    fun canvas() = capture("collage_canvas", CollageTool.CANVAS)

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun canvasAt200PercentText() = capture("collage_canvas_200", CollageTool.CANVAS)

    /** The studio's view model, but for the drawing: nothing to load, save or export. */
    private class StandIn(override val edits: CollageEdits, override val assets: EditAssets) :
        CollageStudio {
        override val albums: Flow<List<AlbumSummary>> = flowOf(emptyList())
        override val library: Flow<PagingData<ViewerItem>> = flowOf(PagingData.empty())

        override fun undo() = Unit

        override fun redo() = Unit

        override fun setTool(tool: CollageTool?) = Unit

        override fun pick(picked: Picked?) = Unit

        override fun replacing(cell: Int?) = Unit

        override fun replace(cell: Int, item: ViewerItem) = Unit

        override suspend fun defaultAlbum(): Pair<String, String>? = null

        override suspend fun counts() = SourceCounts(0, 0)

        override fun export(
            output: CollageOutput,
            albumId: String,
            albumTitle: String,
            deleteSources: Boolean
        ) = Unit
    }

    private companion object {
        const val PHOTO_WIDTH = 4000
        const val PHOTO_HEIGHT = 3000
        val GRADIENTS = listOf(
            -0xa8642a to -0x1a2b4c,
            -0x2a8b5a to -0x4b2a1c,
            -0x7a3b2c to -0x2b5a7a,
            -0x3a5b8c to -0x1a3b2c
        )
    }
}
