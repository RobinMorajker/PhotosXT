package com.photoxt.core.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.photoxt.core.model.BackgroundMode
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.MaskKind
import com.photoxt.core.model.Point01
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/** The GPU couldn't do what the editor asked (no GLES 3, a shader that didn't compile). */
class GlException(message: String) : RuntimeException(message)

/**
 * One OpenGL ES 3 context with the editor's shader (spec §4.10, "one GPU pipeline"). Not thread
 * safe: everything happens on the thread that made it, and [close] must run there too.
 *
 * Per photo it holds the guides and masks ([setPhoto]); per recipe, the uniforms and tables
 * ([setRecipe]); per tile, the source pixels ([setSource]); then [draw] renders an output
 * rectangle and reads it back as premultiplied RGBA rows, top row first.
 */
class GlSession : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface
    private val program: Int
    private val textures = IntArray(TEXTURE_COUNT)
    private val uniforms = HashMap<String, Int>()
    private var framebuffer = 0
    private var target = 0
    private var targetSize = Size(0, 0)
    private var photo: PhotoInputs? = null
    private var sourceSize = Size(1, 1)
    private val quad: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** The largest texture side this GPU takes. */
    val maxTextureSize: Int

    init {
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlException("No EGL")
        val config = chooseConfig()
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION, EGL14.EGL_NONE),
            0
        )
        if (context == EGL14.EGL_NO_CONTEXT) throw GlException("No GLES 3 context")
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw GlException("Couldn't make the context current")
        }
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
        maxTextureSize = max[0]
        program = link(EditShader.VERTEX, EditShader.FRAGMENT)
        GLES30.glGenTextures(TEXTURE_COUNT, textures, 0)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glUseProgram(program)
        bindSamplers()
        uploadDefaults()
    }

    /** The guides, subject mask and background picture of [inputs], uploaded when they change. */
    fun setPhoto(inputs: PhotoInputs) {
        val old = photo
        if (old === inputs) return
        if (old?.guide !== inputs.guide) {
            upload2d(GUIDE, inputs.guide)
            upload2d(GUIDE_SOFT, inputs.guideSoft)
            upload2d(GUIDE_BLUR, inputs.guideBlur)
        }
        if (old?.subject !== inputs.subject) {
            val mask = inputs.subject
            if (mask ==
                null
            ) {
                uploadR8(SUBJECT, 1, 1, byteArrayOf(-1))
            } else {
                uploadR8(SUBJECT, mask.width, mask.height, mask.bytes)
            }
        }
        if (old?.backgroundImage !== inputs.backgroundImage) {
            inputs.backgroundImage?.let { upload2d(BG_IMAGE, it) }
                ?: uploadRgba(BG_IMAGE, 1, 1, WHITE_PIXEL)
        }
        photo = inputs
    }

    /** The source pixels for the next draws: [bitmap] covers [region] (fractions of the raster). */
    fun setSource(bitmap: Bitmap, region: Box) {
        upload2d(SOURCE, bitmap)
        sourceSize = Size(bitmap.width, bitmap.height)
        uniform4("uRegion", region.left, region.top, 1 / region.width, 1 / region.height)
        GLES30.glUniform2f(loc("uSrcStep"), 1f / bitmap.width, 1f / bitmap.height)
    }

    /** Everything [recipe] sets, drawn through [geometry], with tables from [tables]. */
    fun setRecipe(recipe: EditRecipe, geometry: RenderGeometry, tables: RecipeTables) {
        val inputs = checkNotNull(photo) { "setPhoto first" }
        GLES30.glUniformMatrix3fv(loc("uMap"), 1, false, geometry.outToSource.toGl(), 0)
        GLES30.glUniform2f(
            loc("uRaster"),
            geometry.raster.width.toFloat(),
            geometry.raster.height.toFloat()
        )
        uniform4(
            "uContent",
            geometry.border.left.toDouble(),
            geometry.border.top.toDouble(),
            geometry.content.width.toDouble(),
            geometry.content.height.toDouble()
        )
        val frame = recipe.frame
        if (frame.style == FrameStyle.NONE) {
            GLES30.glUniform4f(loc("uBorderColor"), 0f, 0f, 0f, 0f)
        } else {
            color4("uBorderColor", frame.color)
        }
        GLES30.glUniform1f(loc("uRadius"), geometry.cornerRadius)
        setLight(recipe)
        setColor(recipe)
        setEffects(recipe, inputs)
        if (tables.curves == null) {
            GLES30.glUniform1i(loc("uCurvesOn"), 0)
        } else {
            uploadRgba(CURVES, ToneCurves.ENTRIES, 1, tables.curves)
            GLES30.glUniform1i(loc("uCurvesOn"), 1)
        }
        val lut = tables.lut
        if (lut == null) {
            GLES30.glUniform1f(loc("uLutMix"), 0f)
        } else {
            upload3d(lut)
            GLES30.glUniform1f(loc("uLutMix"), recipe.filter?.intensity ?: 0f)
            GLES30.glUniform1f(loc("uLutSize"), lut.size.toFloat())
        }
        tables.brush?.let { uploadRgba(BRUSH, it.width, it.height, it.bytes) }
        setMasks(recipe, tables)
        setRetouch(recipe)
        setRedactions(recipe)
        setBackground(recipe, geometry, inputs)
    }

    /**
     * Renders output pixels [x], [y], [w], [h] into [into] (w·h·4 bytes from its position),
     * premultiplied RGBA, top row first.
     */
    fun draw(x: Int, y: Int, w: Int, h: Int, into: ByteBuffer) {
        require(w <= maxTextureSize && h <= maxTextureSize) { "A tile over the GPU's limit" }
        ensureTarget(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUniform2f(loc("uTileOrigin"), x.toFloat(), y.toFloat())
        GLES30.glUniform2f(loc("uTileSize"), w.toFloat(), h.toFloat())
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, into)
        checkError("draw")
    }

    override fun close() {
        GLES30.glDeleteTextures(TEXTURE_COUNT, textures, 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (target != 0) GLES30.glDeleteTextures(1, intArrayOf(target), 0)
        GLES30.glDeleteProgram(program)
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    private fun setLight(recipe: EditRecipe) {
        val light = recipe.light
        GLES30.glUniform1f(loc("uExposure"), light.exposure)
        GLES30.glUniform1f(loc("uBrightness"), light.brightness)
        GLES30.glUniform1f(loc("uContrast"), light.contrast)
        GLES30.glUniform1f(loc("uHighlights"), light.highlights)
        GLES30.glUniform1f(loc("uShadows"), light.shadows)
        GLES30.glUniform1f(loc("uWhites"), light.whites)
        GLES30.glUniform1f(loc("uBlacks"), light.blacks)
    }

    private fun setColor(recipe: EditRecipe) {
        val color = recipe.color
        val gains = whiteBalance(color.temperature, color.tint)
        GLES30.glUniform3f(loc("uWb"), gains[0], gains[1], gains[2])
        GLES30.glUniform1f(loc("uSaturation"), color.saturation)
        GLES30.glUniform1f(loc("uVibrance"), color.vibrance)
        GLES30.glUniform1f(loc("uHue"), color.hue)
        val bands = FloatArray(HslColor.entries.size * VEC3)
        HslColor.entries.forEachIndexed { i, band ->
            val v = color.hsl[band] ?: return@forEachIndexed
            bands[i * VEC3] = v.hue
            bands[i * VEC3 + 1] = v.saturation
            bands[i * VEC3 + 2] = v.luminance
        }
        GLES30.glUniform3fv(loc("uHsl[0]"), HslColor.entries.size, bands, 0)
        GLES30.glUniform1i(loc("uHslOn"), if (bands.any { it != 0f }) 1 else 0)
    }

    private fun setEffects(recipe: EditRecipe, inputs: PhotoInputs) {
        val e = recipe.effects
        GLES30.glUniform1f(loc("uSharpen"), e.sharpen)
        GLES30.glUniform1f(loc("uClarity"), e.clarity)
        GLES30.glUniform1f(loc("uDehaze"), e.dehaze)
        GLES30.glUniform3f(loc("uHaze"), inputs.haze[0], inputs.haze[1], inputs.haze[2])
        GLES30.glUniform1f(loc("uVignette"), e.vignette)
        GLES30.glUniform1f(loc("uGrain"), e.grain)
        GLES30.glUniform1f(loc("uFade"), e.fade)
    }

    private fun setMasks(recipe: EditRecipe, tables: RecipeTables) {
        val masks = recipe.masks.take(EditShader.MAX_MASKS)
        val kinds = IntArray(EditShader.MAX_MASKS * 2)
        val geom = FloatArray(EditShader.MAX_MASKS * VEC4)
        val extra = FloatArray(EditShader.MAX_MASKS * VEC4)
        val a = FloatArray(EditShader.MAX_MASKS * VEC4)
        val b = FloatArray(EditShader.MAX_MASKS * VEC4)
        masks.forEachIndexed { i, mask ->
            kinds[i * 2] = mask.kind.ordinal
            kinds[i * 2 + 1] = if (mask.invert) 1 else 0
            when (mask.kind) {
                MaskKind.LINEAR -> {
                    val start = mask.start ?: TOP
                    val end = mask.end ?: BOTTOM
                    geom.put(i, start.x, start.y, end.x, end.y)
                }

                MaskKind.RADIAL -> {
                    val c = mask.center ?: MIDDLE
                    geom.put(i, c.x, c.y, mask.radiusX, mask.radiusY)
                }

                else -> Unit
            }
            extra.put(
                i,
                Math.toRadians(mask.angle.toDouble()).toFloat(),
                mask.feather,
                tables.brushChannel[mask.id]?.toFloat() ?: 0f,
                0f
            )
            val adj = mask.adjust
            a.put(i, adj.exposure, adj.contrast, adj.highlights, adj.shadows)
            b.put(i, adj.saturation, adj.temperature, adj.tint, adj.clarity)
        }
        GLES30.glUniform1i(loc("uMaskCount"), masks.size)
        GLES30.glUniform2iv(loc("uMaskKind[0]"), EditShader.MAX_MASKS, kinds, 0)
        GLES30.glUniform4fv(loc("uMaskGeom[0]"), EditShader.MAX_MASKS, geom, 0)
        GLES30.glUniform4fv(loc("uMaskExtra[0]"), EditShader.MAX_MASKS, extra, 0)
        GLES30.glUniform4fv(loc("uMaskA[0]"), EditShader.MAX_MASKS, a, 0)
        GLES30.glUniform4fv(loc("uMaskB[0]"), EditShader.MAX_MASKS, b, 0)
    }

    private fun setRetouch(recipe: EditRecipe) {
        val r = recipe.retouch
        val eyes = r.redEyes.take(EditShader.MAX_EYES)
        val eyeData = FloatArray(EditShader.MAX_EYES * VEC3)
        eyes.forEachIndexed { i, e ->
            eyeData[i * VEC3] = e.x
            eyeData[i * VEC3 + 1] = e.y
            eyeData[i * VEC3 + 2] = e.radius
        }
        GLES30.glUniform1i(loc("uEyeCount"), eyes.size)
        GLES30.glUniform3fv(loc("uEyes[0]"), EditShader.MAX_EYES, eyeData, 0)
        val faces = r.faces.take(EditShader.MAX_FACES)
        val faceData = FloatArray(EditShader.MAX_FACES * VEC4)
        faces.forEachIndexed { i, f -> faceData.put(i, f.x, f.y, f.radiusX, f.radiusY) }
        GLES30.glUniform1f(loc("uSmooth"), r.skinSmoothing)
        GLES30.glUniform1i(loc("uFaceCount"), faces.size)
        GLES30.glUniform4fv(loc("uFaces[0]"), EditShader.MAX_FACES, faceData, 0)
        GLES30.glUniform1f(loc("uBgBlur"), r.backgroundBlur)
    }

    private fun setRedactions(recipe: EditRecipe) {
        val regions = recipe.redactions.take(EditShader.MAX_REDACTIONS)
        val rects = FloatArray(EditShader.MAX_REDACTIONS * VEC4)
        val styles = IntArray(EditShader.MAX_REDACTIONS * 2)
        val colors = FloatArray(EditShader.MAX_REDACTIONS * VEC4)
        regions.forEachIndexed { i, r ->
            rects.put(i, r.rect.left, r.rect.top, r.rect.right, r.rect.bottom)
            styles[i * 2] = r.style.ordinal
            styles[i * 2 + 1] = r.shape.ordinal
            colors.put(i, rgba(r.color))
        }
        GLES30.glUniform1i(loc("uRedactCount"), regions.size)
        GLES30.glUniform4fv(loc("uRedactRect[0]"), EditShader.MAX_REDACTIONS, rects, 0)
        GLES30.glUniform2iv(loc("uRedactStyle[0]"), EditShader.MAX_REDACTIONS, styles, 0)
        GLES30.glUniform4fv(loc("uRedactColor[0]"), EditShader.MAX_REDACTIONS, colors, 0)
        GLES30.glUniform1f(loc("uCell"), PIXELATE_CELL)
    }

    private fun setBackground(recipe: EditRecipe, geometry: RenderGeometry, inputs: PhotoInputs) {
        val bg = recipe.background
        // Without a subject mask nothing is background, so cut-out changes nothing.
        val mode = if (inputs.subject == null) BackgroundMode.KEEP else bg.mode
        GLES30.glUniform1i(loc("uBgMode"), mode.ordinal)
        color4("uBgColor", bg.color)
        val image = inputs.backgroundImage
        if (image != null) {
            val contentAspect = geometry.content.width.toDouble() / geometry.content.height
            val imageAspect = image.width.toDouble() / image.height
            if (imageAspect > contentAspect) {
                val sx = contentAspect / imageAspect
                uniform4("uBgImageMap", sx, 1.0, (1 - sx) / 2, 0.0)
            } else {
                val sy = imageAspect / contentAspect
                uniform4("uBgImageMap", 1.0, sy, 0.0, (1 - sy) / 2)
            }
        } else {
            uniform4("uBgImageMap", 1.0, 1.0, 0.0, 0.0)
        }
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (targetSize.width >= w && targetSize.height >= h && target != 0) return
        if (target != 0) GLES30.glDeleteTextures(1, intArrayOf(target), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        val size = Size(max(w, targetSize.width), max(h, targetSize.height))
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        target = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + TARGET_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, size.width, size.height)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            target,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status !=
            GLES30.GL_FRAMEBUFFER_COMPLETE
        ) {
            throw GlException("Framebuffer status $status")
        }
        targetSize = size
    }

    private fun bindSamplers() {
        SAMPLERS.forEach { (name, unit) -> GLES30.glUniform1i(loc(name), unit) }
    }

    private fun uploadDefaults() {
        uploadRgba(
            CURVES,
            ToneCurves.ENTRIES,
            1,
            ToneCurves.texture(com.photoxt.core.model.Curves())
        )
        upload3d(Lut3d.of(2) { r, g, b -> floatArrayOf(r, g, b) })
        uploadRgba(BRUSH, 1, 1, ByteArray(VEC4))
        uploadR8(SUBJECT, 1, 1, byteArrayOf(-1))
        uploadRgba(BG_IMAGE, 1, 1, WHITE_PIXEL)
        uploadRgba(SOURCE, 1, 1, WHITE_PIXEL)
        for (unit in listOf(GUIDE, GUIDE_SOFT, GUIDE_BLUR)) uploadRgba(unit, 1, 1, WHITE_PIXEL)
    }

    private fun upload2d(unit: Int, bitmap: Bitmap) {
        bind2d(unit)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        checkError("texImage2D")
    }

    private fun uploadRgba(unit: Int, w: Int, h: Int, bytes: ByteArray) {
        bind2d(unit)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
        )
    }

    private fun uploadR8(unit: Int, w: Int, h: Int, bytes: ByteArray) {
        bind2d(unit)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
        )
    }

    private fun upload3d(lut: Lut3d) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + LUT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[LUT])
        for (param in listOf(
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_TEXTURE_WRAP_R
        )) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(lut.bytes())
        )
    }

    private fun bind2d(unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[unit])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    private fun color4(name: String, argb: Int) {
        GLES30.glUniform4fv(loc(name), 1, rgba(argb), 0)
    }

    private fun uniform4(name: String, a: Double, b: Double, c: Double, d: Double) =
        GLES30.glUniform4f(loc(name), a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat())

    private fun loc(name: String): Int = uniforms.getOrPut(name) {
        GLES30.glGetUniformLocation(program, name)
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, BITS, EGL14.EGL_GREEN_SIZE, BITS, EGL14.EGL_BLUE_SIZE, BITS,
            EGL14.EGL_ALPHA_SIZE, BITS,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) ||
            count[0] == 0
        ) {
            throw GlException("No GLES 3 config")
        }
        return checkNotNull(configs[0])
    }

    private fun link(vertex: String, fragment: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw GlException("Link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw GlException("Compile: " + GLES30.glGetShaderInfoLog(shader))
        return shader
    }

    private fun checkError(what: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) throw GlException("$what: GL error $error")
    }

    private companion object {
        const val GLES_VERSION = 3
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val BITS = 8
        const val VEC3 = 3
        const val VEC4 = 4

        // Where a linear mask runs and a radial one sits until the user moves them.
        val TOP = Point01(0.5f, 0f)
        val BOTTOM = Point01(0.5f, 1f)
        val MIDDLE = Point01(0.5f, 0.5f)
        const val QUAD_VERTICES = 4
        const val SOURCE = 0
        const val GUIDE = 1
        const val GUIDE_SOFT = 2
        const val GUIDE_BLUR = 3
        const val CURVES = 4
        const val LUT = 5
        const val BRUSH = 6
        const val SUBJECT = 7
        const val BG_IMAGE = 8
        const val TEXTURE_COUNT = 9
        const val TARGET_UNIT = 9

        /** A pixelation cell: 2% of the photo's short edge. */
        const val PIXELATE_CELL = 0.02f

        val WHITE_PIXEL = byteArrayOf(-1, -1, -1, -1)

        val SAMPLERS = listOf(
            "uSrc" to SOURCE,
            "uGuide" to GUIDE,
            "uGuideSoft" to GUIDE_SOFT,
            "uGuideBlur" to GUIDE_BLUR,
            "uCurves" to CURVES,
            "uLut" to LUT,
            "uBrush" to BRUSH,
            "uSubject" to SUBJECT,
            "uBgImage" to BG_IMAGE
        )

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(values)
                    position(0)
                }

        /** Sets vec4 number [index] of an array of them. */
        fun FloatArray.put(index: Int, a: Float, b: Float, c: Float, d: Float) =
            put(index, floatArrayOf(a, b, c, d))

        fun FloatArray.put(index: Int, vec4: FloatArray) {
            System.arraycopy(vec4, 0, this, index * VEC4, VEC4)
        }
    }
}

/** Red, green, blue and alpha of [argb], each 0 to 1. */
internal fun rgba(argb: Int): FloatArray {
    val max = Argb.MAX.toFloat()
    return floatArrayOf(
        Argb.red(argb) / max,
        Argb.green(argb) / max,
        Argb.blue(argb) / max,
        Argb.alpha(argb) / max
    )
}

/** White-balance gains for [temperature] and [tint] (−1 to 1), keeping luminance (Rec. 709). */
@Suppress("MagicNumber") // How far each slider pulls a channel, and the luma weights.
internal fun whiteBalance(temperature: Float, tint: Float): FloatArray {
    val r = 1 + 0.3f * temperature
    val g = 1 - 0.2f * tint
    val b = 1 - 0.3f * temperature
    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return floatArrayOf(r / l, g / l, b / l)
}
