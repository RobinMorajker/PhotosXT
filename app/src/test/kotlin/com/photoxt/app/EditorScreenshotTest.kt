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
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Light
import com.photoxt.core.render.Preview
import com.photoxt.core.render.PreviewMode
import com.photoxt.core.render.Size
import com.photoxt.core.render.previewGeometry
import com.photoxt.feature.editor.EditorContent
import com.photoxt.feature.editor.EditorState
import com.photoxt.feature.editor.EditorTools
import com.photoxt.feature.editor.Phase
import com.photoxt.feature.editor.R
import com.photoxt.feature.editor.Step
import com.photoxt.feature.editor.Tool
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The editor (spec §4.10) on a photo, bare and with a tool open, at 100% and 200% text (§7). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, tool: Tool?) {
        val recipe = EditRecipe(light = Light(exposure = 0.4f, contrast = 0.2f))
        val geometry = previewGeometry(Size(PHOTO_WIDTH, PHOTO_HEIGHT), 1, recipe, PREVIEW_EDGE)
        val picture = picture(geometry.output.width, geometry.output.height)
        val state = EditorState(
            phase = Phase.Ready,
            title = "IMG_0412.jpg",
            recipe = recipe,
            steps = listOf(
                Step(R.string.editor_step_open, EditRecipe()),
                Step(R.string.editor_step_light, recipe)
            ),
            stepIndex = 1,
            tool = tool
        )
        // In the light theme, as most phones are by day: the editor turns itself dark.
        compose.setContent {
            PxtTheme(darkTheme = false) {
                EditorContent(
                    state = state,
                    preview = Preview(picture, geometry, PreviewMode.EDIT),
                    tools = EditorTools(),
                    onClose = {},
                    snackbar = remember { SnackbarHostState() }
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** A sky-to-sand gradient, the same every run. */
    private fun picture(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val paint = Paint().apply {
                shader =
                    LinearGradient(0f, 0f, 0f, height.toFloat(), SKY, SAND, Shader.TileMode.CLAMP)
            }
            Canvas(it).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

    @Test
    fun editor() = capture("editor", tool = null)

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun editorAt200PercentText() = capture("editor_200", tool = null)

    @Test
    fun editorLight() = capture("editor_light", Tool.LIGHT)

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun editorLightAt200PercentText() = capture("editor_light_200", Tool.LIGHT)

    private companion object {
        const val PHOTO_WIDTH = 4000
        const val PHOTO_HEIGHT = 3000
        const val PREVIEW_EDGE = 800
        const val SKY = -0xa8642a
        const val SAND = -0x1a2b4c
    }
}
