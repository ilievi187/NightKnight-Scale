package org.nightknight.scale.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

internal data class RendererSnapshot(
    val source: PixelSize,
    val processedTarget: PixelSize,
    val surface: PixelSize,
    val gpuRenderer: String,
    val glVersion: String,
    val maxTextureSize: Int,
    val rendererCallbackFps: Float,
    val cpuGlSubmitMs: Float?,
    val lastFrameIntervalMs: Float?,
    val averageFrameIntervalMs: Float?,
    val maximumFrameIntervalMs: Float?,
    val consumedMediaTimestampFps: Float?,
    val sourceVideoFps: Float?,
    val surfaceTextureCallbackFps: Float?,
    val easuStageCount: Int,
    val estimatedRgbaTextureBytes: Long,
    val videoFrameCallbacks: Long,
    val estimatedSkippedFrames: Long,
    val decoderLagEvents: Long,
    val gpuExecutionMs: Float?,
    val gpuTimerQueryAvailable: Boolean,
    val displayPresentationTimingAvailable: Boolean,
    val error: String?,
    val mode: RenderMode,
    val effectiveMode: RenderMode,
    val allocationFallbackReason: String?,
    val qualityFallbackReason: String?,
    val shaderFallbackReason: String?,
    val videoSource: String,
    val decodedVideoFrames: Long,
    val videoInputError: String?,
)

internal class FsrDemoRenderer(
    private val context: Context,
    private val onVideoSurfaceAvailable: (SurfaceTexture, VideoTestSource) -> Unit,
) : GLSurfaceView.Renderer {
    private companion object {
        const val TAG = "NKScaleTV"
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
    }

    @Volatile
    var mode = RenderMode.FSR1

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
    private var rendererCallbackFps = 0.0f

    @Volatile
    private var cpuGlSubmitMs: Float? = null

    @Volatile
    private var lastFrameIntervalMs: Float? = null

    @Volatile
    private var averageFrameIntervalMs: Float? = null

    @Volatile
    private var maximumFrameIntervalMs: Float? = null

    @Volatile
    private var consumedMediaTimestampFps: Float? = null

    @Volatile
    var sourceVideoFps: Float? = null

    @Volatile
    private var surfaceTextureCallbackFps: Float? = null

    private val mediaTimestampCadence = FrameRateWindow()
    private val surfaceCallbackCadence = FrameRateWindow()

    @Volatile
    private var easuStageCount = 0

    @Volatile
    private var estimatedSkippedFrames = 0L

    private val totalVideoFrameCallbacks = AtomicLong()
    private val pendingVideoFrameCallbacks = AtomicLong()
    private val decoderLagEvents = AtomicLong()
    private var previousFrameStartNs = 0L
    private var frameIntervalWindowStartNs = 0L
    private var frameIntervalWindowTotalNs = 0L
    private var frameIntervalWindowCount = 0L
    private var frameIntervalWindowMaxNs = 0L

    @Volatile
    private var gpuExecutionMs: Float? = null

    @Volatile
    private var gpuTimerQueryAvailable = false

    private var gpuTimerQuery = 0
    private var gpuTimerQueryPending = false
    private var gpuTimerQueryActive = false

    @Volatile
    private var error: String? = null

    @Volatile
    private var allocationFallbackReason: String? = null

    @Volatile
    private var qualityFallbackReason: String? = null

    @Volatile
    private var shaderFallbackReason: String? = null

    @Volatile
    private var effectiveMode = RenderMode.FSR1

    private var lastModeOnGlThread: RenderMode? = null

    private var easuProgram = 0
    private var rcasProgram = 0
    private var presentProgram = 0
    private var denoiseProgram = 0
    private var externalCopyProgram = 0
    private var inputTexture = 0
    private var externalInputTexture = 0
    @Volatile
    private var denoisedTexture = 0
    @Volatile
    private var outputTexture = 0
    @Volatile
    private var rcasTexture = 0
    private var easuIntermediateTextureA = 0
    private var easuIntermediateTextureB = 0
    private var sourceFramebuffer = 0
    private var denoiseFramebuffer = 0
    private var framebuffer = 0
    private var rcasFramebuffer = 0
    private var easuIntermediateFramebufferA = 0
    private var easuIntermediateFramebufferB = 0
    private var decodedSurfaceTexture: SurfaceTexture? = null
    private var surfaceVideoSource: VideoTestSource? = null
    private var textureTransform = FloatArray(16)
    @Volatile
    private var frameAvailable = false

    @Volatile
    private var selectedVideoSource: VideoTestSource? = null

    @Volatile
    private var decodedVideoFrames = 0L

    @Volatile
    private var videoInputError: String? = null

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
        rendererCallbackFps = rendererCallbackFps,
        cpuGlSubmitMs = cpuGlSubmitMs,
        lastFrameIntervalMs = lastFrameIntervalMs,
        averageFrameIntervalMs = averageFrameIntervalMs,
        maximumFrameIntervalMs = maximumFrameIntervalMs,
        consumedMediaTimestampFps = consumedMediaTimestampFps,
        sourceVideoFps = sourceVideoFps,
        surfaceTextureCallbackFps = surfaceTextureCallbackFps,
        easuStageCount = easuStageCount,
        estimatedRgbaTextureBytes = RenderPolicy.estimatedRgbaTextureBytes(
            sourceSize,
            targetSize,
            surfaceSize,
            includeNoiseReduction = denoisedTexture != 0,
            includeEasu = outputTexture != 0,
            includeRcas = effectiveMode.usesRcas,
            includeSourceTexture = inputTexture != 0,
        ),
        videoFrameCallbacks = totalVideoFrameCallbacks.get(),
        estimatedSkippedFrames = estimatedSkippedFrames,
        decoderLagEvents = decoderLagEvents.get(),
        gpuExecutionMs = gpuExecutionMs,
        gpuTimerQueryAvailable = gpuTimerQueryAvailable,
        displayPresentationTimingAvailable = false,
        error = error,
        mode = mode,
        effectiveMode = effectiveMode,
        allocationFallbackReason = allocationFallbackReason,
        qualityFallbackReason = qualityFallbackReason,
        shaderFallbackReason = shaderFallbackReason,
        videoSource = selectedVideoSource?.label ?: "Waiting for output size",
        decodedVideoFrames = decodedVideoFrames,
        videoInputError = videoInputError,
    )

    fun recordDecoderLag() {
        decoderLagEvents.incrementAndGet()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Context recreation invalidates the old texture name. Release the old producer
        // surface and request a replacement after onSurfaceChanged selects the source.
        decodedSurfaceTexture?.setOnFrameAvailableListener(null)
        decodedSurfaceTexture?.release()
        decodedSurfaceTexture = null
        surfaceVideoSource = null
        externalInputTexture = 0
        sourceFramebuffer = 0
        denoiseFramebuffer = 0
        easuProgram = 0
        rcasProgram = 0
        presentProgram = 0
        denoiseProgram = 0
        externalCopyProgram = 0
        inputTexture = 0
        denoisedTexture = 0
        outputTexture = 0
        rcasTexture = 0
        framebuffer = 0
        rcasFramebuffer = 0
        easuIntermediateTextureA = 0
        easuIntermediateTextureB = 0
        easuIntermediateFramebufferA = 0
        easuIntermediateFramebufferB = 0
        frameAvailable = false
        allocationFallbackReason = null
        qualityFallbackReason = null
        shaderFallbackReason = null
        gpuTimerQuery = 0
        gpuTimerQueryPending = false
        gpuTimerQueryActive = false
        gpuTimerQueryAvailable = false
        gpuExecutionMs = null
        lastModeOnGlThread = null

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0.025f, 0.045f, 0.075f, 1.0f)
        gpuRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown GLES renderer"
        glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "Unknown GLES version"
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        maxTextureSize = size[0]
        initializeGpuTimerQuery()

        initializeShaderPrograms()
        if (presentProgram != 0) {
            runCatching {
                externalCopyProgram = createProgram(
                    "shaders/fullscreen_external.vert",
                    "shaders/copy_external.frag",
                )
                videoInputError = null
            }.onFailure { failure ->
                videoInputError = "External video texture is unavailable: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, videoInputError, failure)
            }
            Log.i(
                TAG,
                "shaders=ready renderer=$gpuRenderer version=$glVersion videoCopy=${externalCopyProgram != 0} " +
                    "easu=${easuProgram != 0} rcas=${rcasProgram != 0} denoise=${denoiseProgram != 0}",
            )
        }
    }

    private fun initializeShaderPrograms() {
        error = null
        shaderFallbackReason = null
        qualityFallbackReason = null
        runCatching {
            presentProgram = createProgram("shaders/fullscreen.vert", "shaders/present.frag")
        }.onFailure { failure ->
            error = "Presentation shader initialization failed: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(TAG, error, failure)
        }
        if (presentProgram == 0) return

        runCatching { easuProgram = createProgram("shaders/fullscreen.vert", "shaders/fsr_easu.frag") }
            .onFailure { failure ->
                shaderFallbackReason = "EASU shader unavailable; using bilinear presentation: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, shaderFallbackReason, failure)
            }
        runCatching { rcasProgram = createProgram("shaders/fullscreen.vert", "shaders/fsr_rcas.frag") }
            .onFailure { failure ->
                shaderFallbackReason = listOfNotNull(
                    shaderFallbackReason,
                    "RCAS shader unavailable; sharpening will be skipped: ${failure.message ?: failure.javaClass.simpleName}",
                ).joinToString("; ")
                Log.e(TAG, "RCAS shader initialization failed", failure)
            }
        runCatching { denoiseProgram = createProgram("shaders/fullscreen.vert", "shaders/fsr_denoise.frag") }
            .onFailure { failure ->
                qualityFallbackReason = "QUALITY denoise shader unavailable; using BALANCED: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, qualityFallbackReason, failure)
            }
    }

    private fun initializeGpuTimerQuery() {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').toSet()
        if ("GL_EXT_disjoint_timer_query" !in extensions) {
            Log.i(TAG, "gpu_timer_query=unsupported")
            return
        }
        try {
            val ids = IntArray(1)
            GLES30.glGenQueries(1, ids, 0)
            check(ids[0] != 0) { "GLES returned an empty timer-query object" }
            gpuTimerQuery = ids[0]
            gpuTimerQueryAvailable = true
            Log.i(TAG, "gpu_timer_query=available extension=GL_EXT_disjoint_timer_query")
        } catch (failure: Throwable) {
            gpuTimerQuery = 0
            gpuTimerQueryAvailable = false
            Log.w(TAG, "gpu_timer_query=unsupported reason=${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceSize = PixelSize(width, height)
        // Avoid allocating an unbounded multi-4K intermediate on unusually large displays.
        targetSize = RenderPolicy.targetFor(surfaceSize, minOf(maxTextureSize, 4096))
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The GLES device reported an invalid render target size."
            return
        }
        val source = VideoTestSource.forTarget(targetSize)
        sourceSize = source.size
        selectedVideoSource = source
        val renderMode = availableModeFor(mode)
        targetSize = RenderPolicy.targetForSource(
            surfaceSize,
            sourceSize,
            minOf(maxTextureSize, 4096),
            includeNoiseReduction = renderMode.usesNoiseReduction,
            includeEasu = renderMode.usesEasu,
            includeRcas = renderMode.usesRcas,
        )
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The decoded video size cannot be processed by the GLES renderer."
            return
        }
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not create the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }
        if (externalCopyProgram != 0) {
            runCatching { createExternalVideoSurface(source) }
                .onFailure { failure ->
                    videoInputError = "Could not create decoded-video input: ${failure.message ?: failure.javaClass.simpleName}"
                    Log.e(TAG, videoInputError, failure)
                }
        }
        easuStageCount = if (renderMode.usesEasu && canUpscaleWithEasu()) {
            RenderPolicy.easuStages(sourceSize, targetSize).size
        } else {
            0
        }
        Log.i(TAG, "surface=${surfaceSize} source=${sourceSize} target=${targetSize} easu_stages=$easuStageCount rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} rcas_output=${rcasOutputLabel()} video=${source.assetPath}")
    }

    /** Called only on the GL thread after the decoder reports its actual input dimensions. */
    fun updateDecodedVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        val decodedSize = PixelSize(width, height)
        if (decodedSize == sourceSize) return
        sourceSize = decodedSize
        val renderMode = availableModeFor(mode)
        targetSize = RenderPolicy.targetForSource(
            surfaceSize,
            sourceSize,
            minOf(maxTextureSize, 4096),
            includeNoiseReduction = renderMode.usesNoiseReduction,
            includeEasu = renderMode.usesEasu,
            includeRcas = renderMode.usesRcas,
        )
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The decoded video size cannot be processed by the GLES renderer."
            return
        }
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not resize the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }
        easuStageCount = if (renderMode.usesEasu && canUpscaleWithEasu()) {
            RenderPolicy.easuStages(sourceSize, targetSize).size
        } else {
            0
        }
        Log.i(TAG, "decoded_size=$sourceSize target=$targetSize surface=$surfaceSize easu_stages=$easuStageCount rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} rcas_output=${rcasOutputLabel()}")
    }

    private fun recordFrameInterval(frameStartNs: Long) {
        if (previousFrameStartNs > 0L && frameStartNs > previousFrameStartNs) {
            val intervalNs = frameStartNs - previousFrameStartNs
            lastFrameIntervalMs = intervalNs / 1_000_000.0f
            frameIntervalWindowTotalNs += intervalNs
            frameIntervalWindowCount += 1L
            frameIntervalWindowMaxNs = maxOf(frameIntervalWindowMaxNs, intervalNs)
        }
        previousFrameStartNs = frameStartNs
        if (frameIntervalWindowStartNs == 0L) frameIntervalWindowStartNs = frameStartNs
        if (frameStartNs - frameIntervalWindowStartNs >= 1_000_000_000L && frameIntervalWindowCount > 0L) {
            averageFrameIntervalMs = frameIntervalWindowTotalNs / frameIntervalWindowCount / 1_000_000.0f
            maximumFrameIntervalMs = frameIntervalWindowMaxNs / 1_000_000.0f
            frameIntervalWindowStartNs = frameStartNs
            frameIntervalWindowTotalNs = 0L
            frameIntervalWindowCount = 0L
            frameIntervalWindowMaxNs = 0L
        }
    }

    private fun applyRequestedMode() {
        val requested = mode
        if (requested == lastModeOnGlThread) return
        val resourceMode = availableModeFor(requested)
        if (surfaceSize.width > 0 && sourceSize.width > 0 && maxTextureSize > 0) {
            val nextTarget = RenderPolicy.targetForSource(
                surfaceSize,
                sourceSize,
                minOf(maxTextureSize, 4096),
                includeNoiseReduction = resourceMode.usesNoiseReduction,
                includeEasu = resourceMode.usesEasu,
                includeRcas = resourceMode.usesRcas,
            )
            if (nextTarget.width > 0 && nextTarget.height > 0) {
                targetSize = nextTarget
                createOrReplaceTargets()
                easuStageCount = if (resourceMode.usesEasu && canUpscaleWithEasu()) {
                    RenderPolicy.easuStages(sourceSize, targetSize).size
                } else {
                    0
                }
            }
        }
        if (!requested.usesNoiseReduction) {
            releaseDenoiseTarget()
            qualityFallbackReason = null
        }
        lastModeOnGlThread = requested
        Log.i(TAG, "render_resources mode=${requested.id} effective=${effectiveMode.id} rgba_bytes=${snapshot().estimatedRgbaTextureBytes}")
    }

    private fun pollGpuTimerQuery() {
        if (!gpuTimerQueryAvailable || !gpuTimerQueryPending) return
        try {
            val disjoint = IntArray(1)
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
            if (disjoint[0] != 0) {
                gpuTimerQueryPending = false
                gpuExecutionMs = null
                return
            }
            val available = IntArray(1)
            GLES30.glGetQueryObjectuiv(gpuTimerQuery, GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
            if (available[0] == 0) return
            val result = IntArray(1)
            GLES30.glGetQueryObjectuiv(gpuTimerQuery, GLES30.GL_QUERY_RESULT, result, 0)
            val elapsedNs = result[0].toLong() and 0xffff_ffffL
            gpuExecutionMs = elapsedNs / 1_000_000.0f
            gpuTimerQueryPending = false
            Log.i(TAG, "gpu_execution_ms=$gpuExecutionMs method=GL_EXT_disjoint_timer_query")
        } catch (failure: Throwable) {
            disableGpuTimerQuery(failure)
        }
    }

    private fun beginGpuTimerQuery(): Boolean {
        if (!gpuTimerQueryAvailable || gpuTimerQueryPending || gpuTimerQueryActive || renderedFrames % 120L != 0L) {
            return false
        }
        return try {
            GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, gpuTimerQuery)
            gpuTimerQueryActive = true
            true
        } catch (failure: Throwable) {
            disableGpuTimerQuery(failure)
            false
        }
    }

    private fun endGpuTimerQuery() {
        if (!gpuTimerQueryActive) return
        try {
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            gpuTimerQueryActive = false
            gpuTimerQueryPending = true
        } catch (failure: Throwable) {
            disableGpuTimerQuery(failure)
        }
    }

    private fun disableGpuTimerQuery(failure: Throwable) {
        gpuTimerQueryAvailable = false
        gpuTimerQueryActive = false
        gpuTimerQueryPending = false
        gpuTimerQuery = 0
        gpuExecutionMs = null
        Log.w(TAG, "gpu_timer_query=disabled reason=${failure.message ?: failure.javaClass.simpleName}")
    }

    override fun onDrawFrame(unused: GL10?) {
        val frameStartNs = System.nanoTime()
        recordFrameInterval(frameStartNs)
        applyRequestedMode()
        if (error != null || presentProgram == 0 || (inputTexture == 0 && externalInputTexture == 0)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        pollGpuTimerQuery()
        val timedGpuFrame = beginGpuTimerQuery()
        try {
            updateDecodedVideoFrame()
            val capabilityMode = availableModeFor(mode)
            val activeMode = when {
                allocationFallbackReason != null -> RenderMode.BILINEAR
                mode.usesEasu && !canUpscaleWithEasu() -> RenderMode.BILINEAR
                capabilityMode.usesNoiseReduction && !ensureDenoiseTarget() -> RenderMode.FSR1
                else -> capabilityMode
            }
            effectiveMode = activeMode

            if (activeMode == RenderMode.BILINEAR) {
                if (inputTexture != 0 && sourceFramebuffer != 0) presentOriginal() else presentExternalVideoDirect()
            } else {
                when (activeMode) {
                    RenderMode.BILINEAR -> presentOriginal()
                    RenderMode.EASU_ONLY -> {
                        drawEasuChain(inputTexture)
                        presentProcessed(outputTexture)
                    }
                    RenderMode.FSR1, RenderMode.FSR1_DENOISE -> {
                        val easuInput = if (activeMode.usesNoiseReduction) {
                            drawDenoise()
                            denoisedTexture
                        } else {
                            inputTexture
                        }
                        drawEasuChain(easuInput)
                        if (rcasTexture == 0) {
                            drawRcasToSurface()
                        } else {
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rcasFramebuffer)
                            GLES30.glViewport(0, 0, targetSize.width, targetSize.height)
                            drawRcas()
                            presentProcessed(rcasTexture)
                        }
                    }
                }
            }
        } finally {
            if (timedGpuFrame) endGpuTimerQuery()
        }

        framesInWindow += 1
        renderedFrames += 1L
        val now = System.nanoTime()
        cpuGlSubmitMs = (now - frameStartNs) / 1_000_000.0f
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
        val elapsed = now - fpsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            rendererCallbackFps = framesInWindow * 1_000_000_000.0f / elapsed.toFloat()
            framesInWindow = 0
            fpsWindowStartNs = now
            if (!loggedFirstFps) {
                loggedFirstFps = true
                Log.i(TAG, "render_fps=$rendererCallbackFps")
            }
        }
    }

    private fun presentOriginal() = presentFitted(inputTexture, flipY = decodedVideoFrames == 0L)

    private fun presentExternalVideoDirect() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = RenderPolicy.glViewportForContent(surfaceSize, sourceSize)
        if (externalInputTexture == 0 || viewport.width <= 0 || viewport.height <= 0 || decodedVideoFrames == 0L) return
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        drawExternalTexture()
    }

    private fun drawExternalTexture() {
        GLES30.glUseProgram(externalCopyProgram)
        bindQuad(externalCopyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalInputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(externalCopyProgram, "uInput"), 0)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(externalCopyProgram, "uTextureTransform"),
            1,
            false,
            textureTransform,
            0,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawDenoise() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, denoiseFramebuffer)
        GLES30.glViewport(0, 0, sourceSize.width, sourceSize.height)
        GLES30.glUseProgram(denoiseProgram)
        bindQuad(denoiseProgram)
        bindTexture(inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(denoiseProgram, "uInput"), 0)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(denoiseProgram, "uStrength"),
            FilterPolicy.DEFAULT_DENOISE_STRENGTH,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun presentProcessed(texture: Int) = presentFitted(texture, flipY = false)

    private fun drawRcasToSurface() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = RenderPolicy.glViewportForContent(surfaceSize, sourceSize)
        if (viewport.width <= 0 || viewport.height <= 0) return
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        drawRcas(viewportOriginX = viewport.x, viewportOriginY = viewport.y)
    }

    private fun presentFitted(texture: Int, flipY: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = RenderPolicy.contentViewport(surfaceSize, sourceSize)
        if (viewport.width <= 0 || viewport.height <= 0) return
        GLES30.glViewport(
            viewport.left,
            surfaceSize.height - viewport.top - viewport.height,
            viewport.width,
            viewport.height,
        )
        drawPresent(texture, flipY)
    }

    private fun drawEasuChain(firstInputTexture: Int) {
        val stages = RenderPolicy.easuStages(sourceSize, targetSize)
        check(stages.isNotEmpty()) { "No valid EASU stage for $sourceSize → $targetSize" }
        var inputTextureForStage = firstInputTexture
        var inputSize = sourceSize
        stages.forEachIndexed { index, stageSize ->
            val finalStage = index == stages.lastIndex
            val (destinationTexture, destinationFramebuffer) = when {
                finalStage -> outputTexture to framebuffer
                index % 2 == 0 -> easuIntermediateTextureA to easuIntermediateFramebufferA
                else -> easuIntermediateTextureB to easuIntermediateFramebufferB
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destinationFramebuffer)
            GLES30.glViewport(0, 0, stageSize.width, stageSize.height)
            drawEasu(inputTextureForStage, inputSize, stageSize, flipInputY = index == 0 && decodedVideoFrames == 0L)
            inputTextureForStage = destinationTexture
            inputSize = stageSize
        }
    }

    private fun drawEasu(
        sourceTexture: Int,
        inputSize: PixelSize,
        outputSize: PixelSize,
        flipInputY: Boolean,
    ) {
        GLES30.glUseProgram(easuProgram)
        bindQuad(easuProgram)
        bindTexture(sourceTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(easuProgram, "uInput"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(easuProgram, "uInputSize"),
            inputSize.width.toFloat(),
            inputSize.height.toFloat(),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(easuProgram, "uOutputSize"),
            outputSize.width.toFloat(),
            outputSize.height.toFloat(),
        )
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(easuProgram, "uFlipInputY"),
            if (flipInputY) 1 else 0,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawRcas(viewportOriginX: Int = 0, viewportOriginY: Int = 0) {
        GLES30.glUseProgram(rcasProgram)
        bindQuad(rcasProgram)
        bindTexture(outputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rcasProgram, "uInput"), 0)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(rcasProgram, "uSharpnessScale"),
            RenderPolicy.rcasScaleFor(sharpnessProgress),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(rcasProgram, "uViewportOrigin"),
            viewportOriginX.toFloat(),
            viewportOriginY.toFloat(),
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
        releaseRenderTargets()
        allocationFallbackReason = null
        qualityFallbackReason = null

        try {
            inputTexture = createChartTexture(sourceSize)
            sourceFramebuffer = createFramebuffer(inputTexture)

            val canUpscale = canUpscaleWithEasu()
            val renderMode = availableModeFor(mode)
            if (canUpscale && renderMode.usesNoiseReduction) ensureDenoiseTarget()

            if (canUpscale && renderMode.usesEasu) {
                outputTexture = createEmptyTexture(targetSize, GLES30.GL_LINEAR)
                framebuffer = createFramebuffer(outputTexture)

                if (renderMode.usesRcas && RenderPolicy.requiresRcasIntermediate(targetSize, surfaceSize, sourceSize)) {
                    rcasTexture = createEmptyTexture(targetSize, GLES30.GL_LINEAR)
                    rcasFramebuffer = createFramebuffer(rcasTexture)
                }

                val intermediateSizes = RenderPolicy.easuIntermediateSizes(sourceSize, targetSize)
                if (intermediateSizes.isNotEmpty()) {
                    easuIntermediateTextureA = createEmptyTexture(intermediateSizes[0], GLES30.GL_NEAREST)
                    easuIntermediateFramebufferA = createFramebuffer(easuIntermediateTextureA)
                }
                if (intermediateSizes.size > 1) {
                    easuIntermediateTextureB = createEmptyTexture(intermediateSizes[1], GLES30.GL_NEAREST)
                    easuIntermediateFramebufferB = createFramebuffer(easuIntermediateTextureB)
                }
            }
            effectiveMode = when {
                renderMode.usesEasu && !canUpscale -> RenderMode.BILINEAR
                renderMode.usesNoiseReduction && denoisedTexture == 0 -> RenderMode.FSR1
                else -> renderMode
            }
            lastModeOnGlThread = mode
        } catch (failure: Exception) {
            fallbackAfterTargetAllocationFailure(failure)
        } catch (failure: OutOfMemoryError) {
            fallbackAfterTargetAllocationFailure(failure)
        }
    }

    private fun fallbackAfterTargetAllocationFailure(failure: Throwable) {
        releaseProcessingTargets()
        allocationFallbackReason = "GPU render-target allocation failed; using bilinear input: ${failure.message ?: failure.javaClass.simpleName}"
        effectiveMode = RenderMode.BILINEAR
        lastModeOnGlThread = mode
        Log.e(TAG, allocationFallbackReason, failure)
    }

    private fun canUpscaleWithEasu(): Boolean =
        sourceSize.width <= targetSize.width && sourceSize.height <= targetSize.height &&
            (sourceSize.width < targetSize.width || sourceSize.height < targetSize.height)

    private fun availableModeFor(requested: RenderMode): RenderMode {
        if (!requested.usesEasu) return requested
        if (easuProgram == 0) return RenderMode.BILINEAR

        var active = requested
        if (active.usesNoiseReduction && denoiseProgram == 0) {
            qualityFallbackReason = "QUALITY denoise shader unavailable; using BALANCED"
            active = RenderMode.FSR1
        }
        if (active.usesRcas && rcasProgram == 0) {
            active = RenderMode.EASU_ONLY
        }
        return active
    }

    private fun rcasOutputLabel(): String = when {
        !effectiveMode.usesRcas -> "disabled"
        rcasTexture == 0 -> "direct"
        else -> "scratch"
    }

    private fun ensureDenoiseTarget(): Boolean {
        if (denoisedTexture != 0 && denoiseFramebuffer != 0) return true
        if (qualityFallbackReason != null) return false
        var texture = 0
        var fbo = 0
        return try {
            texture = createEmptyTexture(sourceSize)
            fbo = createFramebuffer(texture)
            denoisedTexture = texture
            denoiseFramebuffer = fbo
            qualityFallbackReason = null
            true
        } catch (failure: Exception) {
            handleDenoiseAllocationFailure(texture, fbo, failure)
        } catch (failure: OutOfMemoryError) {
            handleDenoiseAllocationFailure(texture, fbo, failure)
        }
    }

    private fun handleDenoiseAllocationFailure(texture: Int, fbo: Int, failure: Throwable): Boolean {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        denoisedTexture = 0
        denoiseFramebuffer = 0
        qualityFallbackReason = "QUALITY noise-reduction target unavailable; using BALANCED: ${failure.message ?: failure.javaClass.simpleName}"
        Log.w(TAG, qualityFallbackReason, failure)
        return false
    }

    private fun releaseDenoiseTarget() {
        if (denoiseFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(denoiseFramebuffer), 0)
        if (denoisedTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(denoisedTexture), 0)
        denoiseFramebuffer = 0
        denoisedTexture = 0
    }

    private fun releaseProcessingTargets() {
        releaseDenoiseTarget()
        val framebuffers = intArrayOf(
            framebuffer,
            rcasFramebuffer,
            easuIntermediateFramebufferA,
            easuIntermediateFramebufferB,
        ).filter { it != 0 }.toIntArray()
        if (framebuffers.isNotEmpty()) GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        framebuffer = 0
        rcasFramebuffer = 0
        easuIntermediateFramebufferA = 0
        easuIntermediateFramebufferB = 0

        val textures = intArrayOf(
            outputTexture,
            rcasTexture,
            easuIntermediateTextureA,
            easuIntermediateTextureB,
        ).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        outputTexture = 0
        rcasTexture = 0
        easuIntermediateTextureA = 0
        easuIntermediateTextureB = 0
    }

    private fun releaseRenderTargets() {
        releaseProcessingTargets()
        if (sourceFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(sourceFramebuffer), 0)
        if (inputTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        sourceFramebuffer = 0
        inputTexture = 0
    }

    private fun createExternalVideoSurface(source: VideoTestSource) {
        if (decodedSurfaceTexture != null && surfaceVideoSource == source) return

        decodedSurfaceTexture?.setOnFrameAvailableListener(null)
        decodedSurfaceTexture?.release()
        if (externalInputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(externalInputTexture), 0)
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val surfaceTexture = SurfaceTexture(ids[0])
        surfaceTexture.setDefaultBufferSize(source.size.width, source.size.height)
        surfaceTexture.setOnFrameAvailableListener(
            {
                surfaceCallbackCadence.record(System.nanoTime())?.let { surfaceTextureCallbackFps = it }
                totalVideoFrameCallbacks.incrementAndGet()
                pendingVideoFrameCallbacks.incrementAndGet()
                frameAvailable = true
            },
            Handler(Looper.getMainLooper()),
        )
        externalInputTexture = ids[0]
        decodedSurfaceTexture = surfaceTexture
        surfaceVideoSource = source
        frameAvailable = false
        decodedVideoFrames = 0L
        totalVideoFrameCallbacks.set(0L)
        pendingVideoFrameCallbacks.set(0L)
        estimatedSkippedFrames = 0L
        consumedMediaTimestampFps = null
        surfaceTextureCallbackFps = null
        mediaTimestampCadence.reset()
        surfaceCallbackCadence.reset()
        decoderLagEvents.set(0L)
        videoInputError = null
        onVideoSurfaceAvailable(surfaceTexture, source)
    }

    private fun updateDecodedVideoFrame() {
        if (!frameAvailable) return
        val surfaceTexture = decodedSurfaceTexture ?: return
        frameAvailable = false
        val callbacks = pendingVideoFrameCallbacks.getAndSet(0L)
        if (callbacks <= 0L) return
        if (callbacks > 1L) estimatedSkippedFrames += callbacks - 1L
        if (externalCopyProgram == 0) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureTransform)
            val timestampNs = surfaceTexture.timestamp
            if (timestampNs > 0L) {
                mediaTimestampCadence.record(timestampNs)?.let { consumedMediaTimestampFps = it }
            }
            val canCopyToRgba = sourceFramebuffer != 0 && inputTexture != 0
            if (canCopyToRgba) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sourceFramebuffer)
                GLES30.glViewport(0, 0, sourceSize.width, sourceSize.height)
                drawExternalTexture()
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            decodedVideoFrames += 1L
            if (decodedVideoFrames == 1L || decodedVideoFrames % 60L == 0L) {
                val path = if (canCopyToRgba) "uploaded" else "direct-fallback"
                Log.i(TAG, "video_frame=$path count=$decodedVideoFrames source=${sourceSize}")
            }
        } catch (failure: Exception) {
            videoInputError = "Decoded video frame upload failed: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(TAG, videoInputError, failure)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun createFramebuffer(texture: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        check(ids[0] != 0) { "GLES could not allocate a framebuffer object" }
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
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, ids, 0)
            throw IllegalStateException("GLES framebuffer is incomplete (0x${status.toString(16)})")
        }
        return ids[0]
    }

    private fun createChartTexture(size: PixelSize): Int {
        checkTextureSize(size)
        val bitmap = createTestChart(size.width, size.height)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "GLES could not allocate the source texture" }
        try {
            bindTexture(ids[0])
            setTextureParameters(GLES30.GL_LINEAR)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            checkNoGlError("source texture allocation")
            return ids[0]
        } catch (failure: Throwable) {
            GLES30.glDeleteTextures(1, ids, 0)
            throw failure
        } finally {
            bitmap.recycle()
        }
    }

    private fun createEmptyTexture(size: PixelSize, filter: Int = GLES30.GL_NEAREST): Int {
        checkTextureSize(size)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "GLES could not allocate a texture object" }
        try {
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
            checkNoGlError("${size.width}×${size.height} RGBA8 texture allocation")
            return ids[0]
        } catch (failure: Throwable) {
            GLES30.glDeleteTextures(1, ids, 0)
            throw failure
        }
    }

    private fun checkTextureSize(size: PixelSize) {
        check(size.width > 0 && size.height > 0) { "Invalid GLES texture size: $size" }
        check(size.width <= maxTextureSize && size.height <= maxTextureSize) {
            "GLES texture size $size exceeds GL_MAX_TEXTURE_SIZE=$maxTextureSize"
        }
    }

    private fun checkNoGlError(operation: String) {
        val errorCode = GLES30.glGetError()
        check(errorCode == GLES30.GL_NO_ERROR) {
            "$operation failed with GLES error 0x${errorCode.toString(16)}"
        }
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
        var vertex = 0
        var fragment = 0
        var program = 0
        try {
            vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, vertexAsset)
            fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, fragmentAsset)
            program = GLES30.glCreateProgram()
            check(program != 0) { "GLES could not allocate a shader program" }
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val link = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0)
            check(link[0] == GLES30.GL_TRUE) {
                "Program link failed: ${GLES30.glGetProgramInfoLog(program)}"
            }
            return program
        } catch (failure: Throwable) {
            if (program != 0) GLES30.glDeleteProgram(program)
            throw failure
        } finally {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "$name shader allocation failed" }
        try {
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compile = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compile, 0)
            check(compile[0] == GLES30.GL_TRUE) {
                "$name compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
            }
            return shader
        } catch (failure: Throwable) {
            GLES30.glDeleteShader(shader)
            throw failure
        }
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
