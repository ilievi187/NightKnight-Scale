package org.nightknight.scale.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

internal data class RendererSnapshot(
    val source: PixelSize,
    val processedTarget: PixelSize,
    val surface: PixelSize,
    val gpuRenderer: String,
    val glVersion: String,
    val maxTextureSize: Int,
    val fps: Float,
    val synchronizedFrameMs: Float?,
    val error: String?,
)

internal class FsrDemoRenderer(
    private val context: Context,
) : GLSurfaceView.Renderer {
    private companion object {
        const val TAG = "NKScaleTV"
    }

    @Volatile
    var enhanced = true

    @Volatile
    var sharpnessProgress = 8

    @Volatile
    private var sourceSize = PixelSize(0, 0)

    @Volatile
    private var targetSize = PixelSize(0, 0)

    @Volatile
    private var surfaceSize = PixelSize(0, 0)

    @Volatile
    private var gpuRenderer = "Detecting GLES renderer…"

    @Volatile
    private var glVersion = ""

    @Volatile
    private var maxTextureSize = 0

    @Volatile
    private var fps = 0.0f

    @Volatile
    private var synchronizedFrameMs: Float? = null

    @Volatile
    private var error: String? = null

    private var easuProgram = 0
    private var rcasProgram = 0
    private var presentProgram = 0
    private var inputTexture = 0
    private var outputTexture = 0
    private var rcasTexture = 0
    private var framebuffer = 0
    private var rcasFramebuffer = 0
    private var quad: FloatBuffer = createQuadBuffer()
    private var framesInWindow = 0
    private var fpsWindowStartNs = 0L
    private var renderedFrames = 0L
    private var loggedFirstFps = false

    fun snapshot() = RendererSnapshot(
        source = sourceSize,
        processedTarget = targetSize,
        surface = surfaceSize,
        gpuRenderer = gpuRenderer,
        glVersion = glVersion,
        maxTextureSize = maxTextureSize,
        fps = fps,
        synchronizedFrameMs = synchronizedFrameMs,
        error = error,
    )

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0.025f, 0.045f, 0.075f, 1.0f)
        gpuRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown GLES renderer"
        glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "Unknown GLES version"
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        maxTextureSize = size[0]

        runCatching {
            easuProgram = createProgram("shaders/fullscreen.vert", "shaders/fsr_easu.frag")
            rcasProgram = createProgram("shaders/fullscreen.vert", "shaders/fsr_rcas.frag")
            presentProgram = createProgram("shaders/fullscreen.vert", "shaders/present.frag")
            error = null
            Log.i(TAG, "shaders=ready renderer=$gpuRenderer version=$glVersion")
        }.onFailure { failure ->
            error = "Shader initialization failed: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(TAG, error, failure)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceSize = PixelSize(width, height)
        // Avoid allocating an unbounded multi-4K intermediate on unusually large displays.
        targetSize = RenderPolicy.targetFor(surfaceSize, minOf(maxTextureSize, 4096))
        sourceSize = RenderPolicy.testSourceFor(targetSize)
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The GLES device reported an invalid render target size."
            return
        }
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not create the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }.onSuccess {
                Log.i(TAG, "surface=${surfaceSize} source=${sourceSize} target=${targetSize}")
            }
    }

    override fun onDrawFrame(unused: GL10?) {
        val frameStartNs = System.nanoTime()
        if (error != null || presentProgram == 0 || inputTexture == 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val outputWidth = targetSize.width
        val outputHeight = targetSize.height
        if (enhanced && easuProgram != 0 && rcasProgram != 0 && framebuffer != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, outputWidth, outputHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            drawEasu()

            if (targetSize == surfaceSize) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
                drawRcas()
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rcasFramebuffer)
                GLES30.glViewport(0, 0, outputWidth, outputHeight)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                drawRcas()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
                drawPresent(rcasTexture, flipY = false)
            }
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
            drawPresent(inputTexture, flipY = true)
        }

        framesInWindow += 1
        renderedFrames += 1L
        val now = System.nanoTime()
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
        val elapsed = now - fpsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            fps = framesInWindow * 1_000_000_000.0f / elapsed.toFloat()
            framesInWindow = 0
            fpsWindowStartNs = now
            if (!loggedFirstFps) {
                loggedFirstFps = true
                Log.i(TAG, "render_fps=$fps")
            }
        }

        // A sparse glFinish sample measures CPU-submit-to-GPU-completion time.
        // It is deliberately not called every frame because it serializes the GPU.
        if (renderedFrames % 120L == 0L) {
            GLES30.glFinish()
            synchronizedFrameMs = (System.nanoTime() - frameStartNs) / 1_000_000.0f
        }
    }

    private fun drawEasu() {
        GLES30.glUseProgram(easuProgram)
        bindQuad(easuProgram)
        bindTexture(inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(easuProgram, "uInput"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(easuProgram, "uInputSize"),
            sourceSize.width.toFloat(),
            sourceSize.height.toFloat(),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(easuProgram, "uOutputSize"),
            targetSize.width.toFloat(),
            targetSize.height.toFloat(),
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawRcas() {
        GLES30.glUseProgram(rcasProgram)
        bindQuad(rcasProgram)
        bindTexture(outputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rcasProgram, "uInput"), 0)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(rcasProgram, "uSharpnessScale"),
            RenderPolicy.rcasScaleFor(sharpnessProgress),
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPresent(texture: Int, flipY: Boolean) {
        GLES30.glUseProgram(presentProgram)
        bindQuad(presentProgram)
        bindTexture(texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(presentProgram, "uInput"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(presentProgram, "uFlipY"), if (flipY) 1 else 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad(program: Int) {
        quad.position(0)
        val position = GLES30.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glEnableVertexAttribArray(texCoord)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(0)
    }

    private fun bindTexture(texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun createOrReplaceTargets() {
        if (inputTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        if (outputTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        if (rcasTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(rcasTexture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (rcasFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(rcasFramebuffer), 0)

        inputTexture = createChartTexture(sourceSize)
        outputTexture = createEmptyTexture(targetSize)
        framebuffer = createFramebuffer(outputTexture)
        if (targetSize != surfaceSize) {
            rcasTexture = createEmptyTexture(targetSize, GLES30.GL_LINEAR)
            rcasFramebuffer = createFramebuffer(rcasTexture)
        } else {
            rcasTexture = 0
            rcasFramebuffer = 0
        }
    }

    private fun createFramebuffer(texture: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "GLES framebuffer is incomplete (0x${status.toString(16)})"
        }
        return ids[0]
    }

    private fun createChartTexture(size: PixelSize): Int {
        val bitmap = createTestChart(size.width, size.height)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        bindTexture(ids[0])
        setTextureParameters(GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    private fun createEmptyTexture(size: PixelSize, filter: Int = GLES30.GL_NEAREST): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        bindTexture(ids[0])
        setTextureParameters(filter)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            size.width,
            size.height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        return ids[0]
    }

    private fun setTextureParameters(filter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun createProgram(vertexAsset: String, fragmentAsset: String): Int {
        val vertexSource = context.assets.open(vertexAsset).bufferedReader().use { it.readText() }
        val fragmentSource = context.assets.open(fragmentAsset).bufferedReader().use { it.readText() }
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, vertexAsset)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, fragmentAsset)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val link = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(link[0] == GLES30.GL_TRUE) {
            "Program link failed: ${GLES30.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compile = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compile, 0)
        check(compile[0] == GLES30.GL_TRUE) {
            "$name compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun createTestChart(width: Int, height: Int): Bitmap {
        val chart = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val bars = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            Color.MAGENTA,
            Color.RED,
            Color.BLUE,
            Color.BLACK,
        )
        val barHeight = (height / 5).coerceAtLeast(1)
        val gridX = (width / 80).coerceAtLeast(1)
        val gridY = (height / 45).coerceAtLeast(1)
        val checkerSize = (width / 96).coerceAtLeast(2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = if (y < barHeight) {
                    bars[(x * bars.size / width).coerceAtMost(bars.lastIndex)]
                } else {
                    val localY = y - barHeight
                    val checker = ((x / checkerSize) + (localY / checkerSize)) and 1
                    val diagonal = abs(localY - (x * (height - barHeight) / width)) <= 2
                    val grid = x % gridX == 0 || y % gridY == 0
                    val red = ((x * 255) / width).coerceIn(0, 255)
                    val green = ((localY * 255) / (height - barHeight).coerceAtLeast(1)).coerceIn(0, 255)
                    when {
                        diagonal -> Color.WHITE
                        grid -> Color.rgb(80, 105, 130)
                        x < width / 3 && checker == 0 -> Color.rgb(210, 220, 230)
                        x < width / 3 -> Color.rgb(38, 48, 60)
                        else -> Color.rgb(red, green, 255 - red)
                    }
                }
                pixels[y * width + x] = color
            }
        }
        chart.setPixels(pixels, 0, width, 0, 0, width, height)
        return chart
    }

    private fun createQuadBuffer(): FloatBuffer {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
        return ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
    }
}
