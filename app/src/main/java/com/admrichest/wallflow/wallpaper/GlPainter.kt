package com.admrichest.wallflow.wallpaper

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

private const val TAG = "WallFlowGl"

/**
 * Draws both still images and video frames onto the wallpaper surface.
 *
 * Everything goes through one GL context on purpose. A Surface that has been
 * locked for Canvas drawing is connected to the CPU buffer API, and MediaCodec
 * cannot then connect to it, so a wallpaper that mixes photos and video has to
 * pick one pipeline and stay on it. Neither setFormat nor setFixedSize gets a
 * fresh Surface out of WallpaperService, so this is the pipeline.
 *
 * All methods must be called from the thread that constructed the instance.
 */
class GlPainter(surface: Surface, private val width: Int, private val height: Int) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProgram = 0
    private var flatProgram = 0
    private var oesTexture = 0
    private var flatTexture = 0

    /** Target for ExoPlayer. Null when the context could not be created. */
    var videoSurface: Surface? = null
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private val textureMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        .also { it.position(0) } as FloatBuffer

    val isReady: Boolean get() = eglSurface != EGL14.EGL_NO_SURFACE

    init {
        runCatching { setUp(surface) }
            .onFailure {
                Log.w(TAG, "GL setup failed", it)
                release()
            }
    }

    // ---- setup -------------------------------------------------------------

    private fun setUp(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "no suitable EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "eglMakeCurrent failed"
        }

        oesProgram = buildProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
        flatProgram = buildProgram(VERTEX_SHADER, FLAT_FRAGMENT_SHADER)

        oesTexture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        flatTexture = createTexture(GLES20.GL_TEXTURE_2D)

        surfaceTexture = SurfaceTexture(oesTexture).apply {
            setDefaultBufferSize(width, height)
        }
        videoSurface = Surface(surfaceTexture)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "link failed: " + GLES20.glGetProgramInfoLog(program)
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "compile failed: " + GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }

    // ---- drawing -----------------------------------------------------------

    /** Paints a solid background, used when there is nothing to show. */
    fun drawBlank() {
        if (!makeCurrent()) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    /**
     * Uploads and draws a still, cover-cropped to the surface.
     *
     * [offsetX] 0..1 slides the visible window across the overflow for
     * parallax, [zoom] above 1 pushes in for the Ken Burns drift.
     */
    fun drawBitmap(bitmap: Bitmap, offsetX: Float = 0.5f, zoom: Float = 1f) {
        if (!makeCurrent() || bitmap.isRecycled) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flatTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // A bitmap texture starts at the top-left, GL at the bottom-left.
        Matrix.setIdentityM(textureMatrix, 0)
        Matrix.translateM(textureMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(textureMatrix, 0, 1f, -1f, 1f)

        drawQuad(
            program = flatProgram,
            target = GLES20.GL_TEXTURE_2D,
            texture = flatTexture,
            contentW = bitmap.width,
            contentH = bitmap.height,
            offsetX = offsetX,
            zoom = zoom,
        )
    }

    /**
     * Pulls the newest decoded frame into the external texture and draws it.
     * Returns false when no frame was available.
     */
    fun drawVideoFrame(contentW: Int, contentH: Int): Boolean {
        val texture = surfaceTexture ?: return false
        if (!makeCurrent()) return false

        runCatching { texture.updateTexImage() }
            .onFailure { Log.w(TAG, "updateTexImage failed", it); return false }
        texture.getTransformMatrix(textureMatrix)

        drawQuad(
            program = oesProgram,
            target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            texture = oesTexture,
            contentW = if (contentW > 0) contentW else width,
            contentH = if (contentH > 0) contentH else height,
            offsetX = 0.5f,
            zoom = 1f,
        )
        return true
    }

    private fun drawQuad(
        program: Int,
        target: Int,
        texture: Int,
        contentW: Int,
        contentH: Int,
        offsetX: Float,
        zoom: Float,
    ) {
        // Cover-crop in clip space: scale the quad until it fills both axes,
        // and let the viewport clip whichever axis overflows.
        val scale = max(width.toFloat() / contentW, height.toFloat() / contentH) * zoom
        val halfW = contentW * scale / width
        val halfH = contentH * scale / height

        // Slide the overflow for parallax; centred when there is none.
        val slack = (halfW - 1f).coerceAtLeast(0f)
        val shift = slack * (1f - 2f * offsetX.coerceIn(0f, 1f))

        vertexBuffer.clear()
        vertexBuffer.put(
            floatArrayOf(
                -halfW + shift, -halfH,
                halfW + shift, -halfH,
                -halfW + shift, halfH,
                halfW + shift, halfH,
            )
        )
        vertexBuffer.position(0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val uTex = GLES20.glGetUniformLocation(program, "uTex")

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, textureMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun setOnFrameAvailable(listener: SurfaceTexture.OnFrameAvailableListener?) {
        surfaceTexture?.setOnFrameAvailableListener(listener)
    }

    private fun makeCurrent(): Boolean {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    // ---- teardown ----------------------------------------------------------

    fun release() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { videoSurface?.release() }
        runCatching { surfaceTexture?.release() }
        videoSurface = null
        surfaceTexture = null

        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        const val FLAT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """

        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
    }
}
