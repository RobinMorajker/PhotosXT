package com.photoxt.feature.viewer

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.photoxt.core.common.PxtLog
import com.photoxt.core.media.Panorama
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * Draws an equirectangular photo sphere as seen from its centre (spec §4.5). A full-screen
 * triangle strip runs a fragment shader that turns each pixel into a viewing direction and looks
 * that direction up in the panorama, so there is no sphere mesh and no seam.
 */
internal class PanoramaRenderer(private val crop: Panorama) : GLSurfaceView.Renderer {
    /** Where the camera looks, in degrees; set from the UI thread, read on the GL thread. */
    @Volatile var yaw = 0f

    @Volatile var pitch = 0f

    @Volatile var fieldOfView = DEFAULT_FOV

    @Volatile private var pending: Bitmap? = null

    private var program = 0
    private var texture = 0
    private var width = 1
    private var height = 1
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD)
        .apply { position(0) }

    /** Hands over the panorama to draw; the GL thread uploads it and recycles it. */
    fun setImage(bitmap: Bitmap) {
        pending = bitmap
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let(::upload)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        uniform("uTexture") { GLES20.glUniform1i(it, 0) }
        uniform("uAspect") { GLES20.glUniform1f(it, width.toFloat() / height) }
        uniform("uTanHalfFov") {
            GLES20.glUniform1f(it, tan(Math.toRadians(fieldOfView / 2.0)).toFloat())
        }
        uniform("uYaw") { GLES20.glUniform1f(it, Math.toRadians(yaw.toDouble()).toFloat()) }
        uniform("uPitch") { GLES20.glUniform1f(it, Math.toRadians(pitch.toDouble()).toFloat()) }
        uniform("uCrop") { GLES20.glUniform4f(it, crop.left, crop.top, crop.width, crop.height) }
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD.size / 2)
    }

    private fun upload(bitmap: Bitmap) {
        pending = null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    private inline fun uniform(name: String, set: (Int) -> Unit) {
        set(GLES20.glGetUniformLocation(program, name))
    }

    private fun link(vertex: String, fragment: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) PxtLog.w(TAG, "Panorama shader failed to link")
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) PxtLog.w(TAG, "Panorama shader failed to compile")
        return shader
    }

    companion object {
        const val DEFAULT_FOV = 75f
        const val MIN_FOV = 30f
        const val MAX_FOV = 110f
        const val MAX_PITCH = 89f

        /** Panoramas are decoded no wider than this, which every GPU can hold as one texture. */
        const val MAX_TEXTURE = 4096

        private const val TAG = "Panorama"
        private const val FLOAT_BYTES = 4
        private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vPosition;
            void main() {
                vPosition = aPosition;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // Each pixel becomes a ray through the camera, turned by pitch then yaw; its longitude
        // and latitude pick the texel, after mapping the full sphere onto the stored crop.
        private const val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexture;
            uniform float uAspect;
            uniform float uTanHalfFov;
            uniform float uYaw;
            uniform float uPitch;
            uniform vec4 uCrop;
            varying vec2 vPosition;
            const float PI = 3.14159265;
            void main() {
                vec3 ray = normalize(vec3(vPosition.x * uTanHalfFov * uAspect,
                    vPosition.y * uTanHalfFov, -1.0));
                float cp = cos(uPitch);
                float sp = sin(uPitch);
                ray = vec3(ray.x, ray.y * cp + ray.z * sp, -ray.y * sp + ray.z * cp);
                float cy = cos(uYaw);
                float sy = sin(uYaw);
                ray = vec3(ray.x * cy - ray.z * sy, ray.y, ray.x * sy + ray.z * cy);
                float longitude = atan(ray.x, -ray.z);
                float latitude = asin(clamp(ray.y, -1.0, 1.0));
                vec2 sphere = vec2(longitude / (2.0 * PI) + 0.5, 0.5 - latitude / PI);
                vec2 stored = (sphere - uCrop.xy) / uCrop.zw;
                if (stored.x < 0.0 || stored.x > 1.0 || stored.y < 0.0 || stored.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, stored);
                }
            }
        """
    }
}
