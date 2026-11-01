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
    val cpuTimings: RenderCpuTimings,
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
    val runtimeFallbackReason: String?,
    val videoInputPath: String,
    val externalEasuAvailable: Boolean,
    val videoSource: String,
    val decodedVideoFrames: Long,
    val videoInputError: String?,
    val geometry: VideoGeometry = VideoGeometry(0, 0),
    val aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
)

internal class FsrDemoRenderer(
    private val context: Context,
    private val onVideoSurfaceAvailable: (SurfaceTexture, VideoTestSource) -> Unit,
) : GLSurfaceView.Renderer {
    private data class ShaderBindings(
        val position: Int,
        val texCoord: Int,
        val input: Int,
        val inputSize: Int,
        val outputSize: Int,
        val flipInputY: Int,
        val sharpnessScale: Int,
        val viewportOrigin: Int,
        val strength: Int,
        val flipY: Int,
        val textureTransform: Int,
    )

    private companion object {
        const val TAG = "NKScaleTV"
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val PERFORMANCE_SAMPLE_INTERVAL_NS = 2_000_000_000L
    }

    @Volatile
    var mode = RenderMode.FSR1

    @Volatile
    var sharpnessProgress = 8

    @Volatile
    var aspectMode = AspectRatioMode.AUTO

    @Volatile
    private var videoGeometry = VideoGeometry(0, 0)

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

    /** Test-only opt-in; runs one blocking pixel comparison on the GL thread, never in normal playback. */
    @Volatile
    var validateExternalEasuOnce = false

    private var externalEasuValidationConsumed = false
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

    private val runtimeRenderFallbackPolicy = RuntimeRenderFallbackPolicy()

    @Volatile
    private var runtimeEffectiveMode: RenderMode? = null

    @Volatile
    private var runtimeFallbackReason: String? = null

    private var lastFallbackSourceFps: Float? = null
    @Volatile
    private var effectiveMode = RenderMode.FSR1

    private var lastModeOnGlThread: RenderMode? = null

    private var easuProgram = 0
    private var rcasProgram = 0
    private var presentProgram = 0
    private var denoiseProgram = 0
    private var externalCopyProgram = 0
    private var externalEasuProgram = 0
    private val shaderBindings = HashMap<Int, ShaderBindings>()
    @Volatile
    private var externalEasuAvailable = false
    private var inputTexture = 0
    private var allocatedSourceSize = PixelSize(0, 0)
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
    private var easuStageSizes: List<PixelSize> = emptyList()
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

    @Volatile
    private var videoInputPath = "rgba-copy"

    private val frameCpuSubmitWindow = CpuSubmitWindow()
    private val inputUpdateCpuSubmitWindow = CpuSubmitWindow()
    private val denoiseCpuSubmitWindow = CpuSubmitWindow()
    private val easuCpuSubmitWindow = CpuSubmitWindow()
    private val rcasCpuSubmitWindow = CpuSubmitWindow()
    private val presentCpuSubmitWindow = CpuSubmitWindow()

    @Volatile
    private var latestCpuTimings = RenderCpuTimings(null, null, null, null, null, null)

    private var performanceSampleWindowStartNs = 0L
    private var quad: FloatBuffer = createQuadBuffer()
    private var framesInWindow = 0
    private var fpsWindowStartNs = 0L
    private var renderedFrames = 0L
    private var loggedFirstFps = false
    private var skipNextFrameCpuSubmitSample = false

    fun snapshot() = RendererSnapshot(
        source = sourceSize,
        processedTarget = targetSize,
        surface = surfaceSize,
        gpuRenderer = gpuRenderer,
        glVersion = glVersion,
        maxTextureSize = maxTextureSize,
        rendererCallbackFps = rendererCallbackFps,
        cpuGlSubmitMs = cpuGlSubmitMs,
        cpuTimings = latestCpuTimings,
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
            rcasIntermediateRequired = if (videoGeometry.codedWidth > 0) {
                RenderPolicy.requiresRcasIntermediate(targetSize, surfaceSize, videoGeometry, aspectMode)
            } else {
                null
            },
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
        runtimeFallbackReason = runtimeFallbackReason,
        videoInputPath = videoInputPath,
        externalEasuAvailable = externalEasuAvailable,
        videoSource = selectedVideoSource?.label ?: "Waiting for output size",
        decodedVideoFrames = decodedVideoFrames,
        videoInputError = videoInputError,
        geometry = videoGeometry,
        aspectMode = aspectMode,
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
        externalEasuProgram = 0
        shaderBindings.clear()
        externalEasuAvailable = false
        externalEasuValidationConsumed = false
        inputTexture = 0
        allocatedSourceSize = PixelSize(0, 0)
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
        easuStageSizes = emptyList()
        runtimeRenderFallbackPolicy.reset()
        runtimeEffectiveMode = null
        runtimeFallbackReason = null
        lastFallbackSourceFps = null
        performanceSampleWindowStartNs = 0L
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
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
                    "easu=${easuProgram != 0} easuExternal=$externalEasuAvailable rcas=${rcasProgram != 0} " +
                    "denoise=${denoiseProgram != 0}",
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
        if (easuProgram != 0) {
            runCatching {
                externalEasuProgram = createProgram(
                    "shaders/fullscreen.vert",
                    "shaders/fsr_easu.frag",
                    fragmentDefines = setOf("EASU_EXTERNAL_INPUT"),
                )
                externalEasuAvailable = true
                Log.i(TAG, "easu_external=ready extension=GL_OES_EGL_image_external_essl3")
            }.onFailure { failure ->
                externalEasuProgram = 0
                externalEasuAvailable = false
                Log.i(TAG, "easu_external=unavailable fallback=rgba-copy reason=${failure.message ?: failure.javaClass.simpleName}")
            }
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
        videoGeometry = VideoGeometry.fromSize(sourceSize)
        selectedVideoSource = source
        val renderMode = availableModeFor(mode)
        targetSize = RenderPolicy.targetForGeometry(
            surface = surfaceSize,
            geometry = videoGeometry,
            maxTextureSize = minOf(maxTextureSize, 4096),
            aspectMode = aspectMode,
            sourceTextureSize = sourceSize,
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
        updateEasuStagePlan(renderMode)
        Log.i(TAG, "surface=${surfaceSize} source=${sourceSize} target=${targetSize} easu_stages=$easuStageCount rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} rcas_output=${rcasOutputLabel()} video=${source.assetPath}")
    }

    /** Update aspect ratio override (AUTO, 16:9, 4:3, SQUARE_PIXELS). */
    fun setAspectRatioMode(mode: AspectRatioMode) {
        aspectMode = mode
        if (videoGeometry.codedWidth > 0 && surfaceSize.width > 0) {
            updateDecodedVideoGeometry(videoGeometry)
        }
    }

    /** Called only on the GL thread after the decoder reports its actual input dimensions. */
    fun updateDecodedVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        val currentGeometry = if (videoGeometry.codedWidth > 0) videoGeometry else VideoGeometry(width, height)
        updateDecodedVideoGeometry(currentGeometry.copy(codedWidth = width, codedHeight = height))
    }

    /** Called on the GL thread when video geometry (SAR, rotation, dimensions) is updated. */
    fun updateDecodedVideoGeometry(geometry: VideoGeometry) {
        if (geometry.codedWidth <= 0 || geometry.codedHeight <= 0 || surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        val newSourceSize = geometry.orientedSize
        val renderMode = availableModeFor(mode)
        val newTargetSize = RenderPolicy.targetForGeometry(
            surface = surfaceSize,
            geometry = geometry,
            maxTextureSize = minOf(maxTextureSize, 4096),
            aspectMode = aspectMode,
            sourceTextureSize = newSourceSize,
            includeNoiseReduction = renderMode.usesNoiseReduction,
            includeEasu = renderMode.usesEasu,
            includeRcas = renderMode.usesRcas,
        )
        if (newTargetSize.width <= 0 || newTargetSize.height <= 0) {
            error = "The decoded video size cannot be processed by the GLES renderer."
            return
        }
        if (geometry == videoGeometry && newSourceSize == sourceSize && newTargetSize == targetSize) return

        videoGeometry = geometry
        sourceSize = newSourceSize
        targetSize = newTargetSize
        error = null
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not resize the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }
        updateEasuStagePlan(renderMode)
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
        Log.i(TAG, "decoded_size=$sourceSize target=$targetSize surface=$surfaceSize easu_stages=$easuStageCount rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} rcas_output=${rcasOutputLabel()}")
    }

    private fun recordFrameInterval(frameStartNs: Long): Boolean {
        var intervalWindowCompleted = false
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
            intervalWindowCompleted = true
        }
        return intervalWindowCompleted
    }

    private fun resetRendererMeasurementWindows() {
        previousFrameStartNs = 0L
        frameIntervalWindowStartNs = 0L
        frameIntervalWindowTotalNs = 0L
        frameIntervalWindowCount = 0L
        frameIntervalWindowMaxNs = 0L
        lastFrameIntervalMs = null
        averageFrameIntervalMs = null
        maximumFrameIntervalMs = null
        framesInWindow = 0
        fpsWindowStartNs = System.nanoTime()
        rendererCallbackFps = 0.0f
    }

    private fun applyRequestedMode() {
        val requested = mode
        if (requested == lastModeOnGlThread) return
        runtimeRenderFallbackPolicy.reset()
        runtimeEffectiveMode = null
        runtimeFallbackReason = null
        lastFallbackSourceFps = sourceVideoFps
        val resourceMode = availableModeFor(requested)
        if (surfaceSize.width > 0 && sourceSize.width > 0 && maxTextureSize > 0) {
            val nextTarget = RenderPolicy.targetForGeometry(
                surface = surfaceSize,
                geometry = videoGeometry,
                maxTextureSize = minOf(maxTextureSize, 4096),
                aspectMode = aspectMode,
                sourceTextureSize = sourceSize,
                includeNoiseReduction = resourceMode.usesNoiseReduction,
                includeEasu = resourceMode.usesEasu,
                includeRcas = resourceMode.usesRcas,
            )
            if (nextTarget.width > 0 && nextTarget.height > 0) {
                targetSize = nextTarget
                createOrReplaceTargets()
                updateEasuStagePlan(resourceMode)
            }
        }
        if (!requested.usesNoiseReduction) {
            releaseDenoiseTarget()
            qualityFallbackReason = null
        }
        lastModeOnGlThread = requested
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
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
        val intervalWindowCompleted = recordFrameInterval(frameStartNs)
        applyRequestedMode()
        maybeLogPerformanceSample(frameStartNs)
        if (intervalWindowCompleted && decodedVideoFrames >= minimumFramesForBudgetCheck()) {
            updateRuntimeFallbackForFrameBudget()
        }
        if (error != null || presentProgram == 0 || (inputTexture == 0 && externalInputTexture == 0)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        pollGpuTimerQuery()
        val timedGpuFrame = beginGpuTimerQuery()
        try {
            val activeMode = resolveActiveMode()
            effectiveMode = activeMode
            var directEasuInput = externalEasuAvailable &&
                activeMode.usesEasu && !activeMode.usesNoiseReduction && externalInputTexture != 0
            val directPresentationInput = activeMode == RenderMode.BILINEAR &&
                externalCopyProgram != 0 && externalInputTexture != 0
            var copyDecodedFrameToRgba = when {
                activeMode.usesNoiseReduction -> true
                activeMode.usesEasu -> !directEasuInput
                else -> !directPresentationInput
            }

            if (frameAvailable) {
                measureCpuSubmit(inputUpdateCpuSubmitWindow) {
                    updateDecodedVideoFrame(copyToRgba = copyDecodedFrameToRgba, directEasuInput = directEasuInput)
                }
            } else {
                updateDecodedVideoFrame(copyToRgba = copyDecodedFrameToRgba, directEasuInput = directEasuInput)
            }
            val hasDecodedFrame = decodedVideoFrames > 0L
            if (validateExternalEasuOnce && !externalEasuValidationConsumed && hasDecodedFrame) {
                externalEasuValidationConsumed = true
                if (!BuildConfig.DEBUG) {
                    Log.w(TAG, "easu_equivalence=unavailable reason=debug-validation-disabled")
                } else if (directEasuInput) {
                    val validation = runCatching { compareExternalEasuWithRgbaCopy() }
                    val difference = validation.getOrNull()
                    val passed = difference != null && EasuQualityComparison.isEquivalent(difference)
                    val validationReason = validation.exceptionOrNull()?.javaClass?.simpleName
                        ?: if (difference == null) "invalid-output" else if (passed) "within-threshold" else "pixel-difference-over-threshold"
                    Log.i(
                        TAG,
                        "easu_equivalence=${if (passed) "pass" else "fail"} " +
                            "mae_lsb=${difference?.let { "%.4f".format(it.meanAbsoluteError) } ?: "N/A"} " +
                            "max_lsb=${difference?.maximumAbsoluteError ?: "N/A"} " +
                            "psnr_db=${difference?.let { if (it.psnrDb.isInfinite()) "inf" else "%.2f".format(it.psnrDb) } ?: "N/A"} " +
                            "channels=${difference?.comparedChannels ?: 0} reason=$validationReason",
                    )
                    resetCpuSubmitWindows()
                    performanceSampleWindowStartNs = System.nanoTime()
                    if (!passed) {
                        externalEasuAvailable = false
                        directEasuInput = false
                        copyDecodedFrameToRgba = true
                        videoInputPath = "rgba-copy-after-equivalence-failure"
                        runCatching { copyExternalVideoToRgba() }
                            .onFailure { failure -> Log.e(TAG, "Fallback OES→RGBA copy failed", failure) }
                        Log.w(TAG, "easu_external=disabled fallback=rgba-copy after output comparison")
                    }
                } else {
                    Log.i(TAG, "easu_equivalence=unavailable reason=external-path-not-active")
                }
            }

            when (activeMode) {
                RenderMode.BILINEAR -> measureCpuSubmit(presentCpuSubmitWindow) {
                    if (hasDecodedFrame && directPresentationInput) presentExternalVideoDirect() else presentOriginal()
                }
                RenderMode.EASU_ONLY -> {
                    measureCpuSubmit(easuCpuSubmitWindow) {
                        drawEasuChain(inputTexture, useExternalInput = directEasuInput && hasDecodedFrame)
                    }
                    measureCpuSubmit(presentCpuSubmitWindow) { presentProcessed(outputTexture) }
                }
                RenderMode.FSR1, RenderMode.FSR1_DENOISE -> {
                    val easuInput = if (activeMode.usesNoiseReduction) {
                        measureCpuSubmit(denoiseCpuSubmitWindow) { drawDenoise() }
                        denoisedTexture
                    } else {
                        inputTexture
                    }
                    measureCpuSubmit(easuCpuSubmitWindow) {
                        drawEasuChain(easuInput, useExternalInput = directEasuInput && hasDecodedFrame)
                    }
                    if (rcasTexture == 0) {
                        measureCpuSubmit(rcasCpuSubmitWindow) { drawRcasToSurface() }
                    } else {
                        measureCpuSubmit(rcasCpuSubmitWindow) {
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rcasFramebuffer)
                            GLES30.glViewport(0, 0, targetSize.width, targetSize.height)
                            drawRcas()
                        }
                        measureCpuSubmit(presentCpuSubmitWindow) { presentProcessed(rcasTexture) }
                    }
                }
            }
        } finally {
            if (timedGpuFrame) endGpuTimerQuery()
        }

        framesInWindow += 1
        renderedFrames += 1L
        val now = System.nanoTime()
        val submitDurationNs = now - frameStartNs
        cpuGlSubmitMs = submitDurationNs / 1_000_000.0f
        if (skipNextFrameCpuSubmitSample) {
            skipNextFrameCpuSubmitSample = false
        } else {
            frameCpuSubmitWindow.record(submitDurationNs)
        }
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

    private fun resolveActiveMode(): RenderMode {
        if (allocationFallbackReason != null) return RenderMode.BILINEAR
        if (mode.usesEasu && !canUpscaleWithEasu()) return RenderMode.BILINEAR
        val capabilityMode = availableModeFor(mode)
        if (capabilityMode.usesNoiseReduction && !ensureDenoiseTarget()) return RenderMode.FSR1
        return runtimeEffectiveMode ?: capabilityMode
    }

    private fun minimumFramesForBudgetCheck(): Long =
        maxOf(5L, ((sourceVideoFps ?: 0f) * 0.5f).toLong())

    private fun updateRuntimeFallbackForFrameBudget() {
        val sourceFps = sourceVideoFps?.takeIf { it > 0f && it.isFinite() }
        if (sourceFps != lastFallbackSourceFps) {
            runtimeRenderFallbackPolicy.reset()
            runtimeEffectiveMode = null
            runtimeFallbackReason = null
            lastFallbackSourceFps = sourceFps
            resetCpuSubmitWindows()
            resetRendererMeasurementWindows()
            performanceSampleWindowStartNs = System.nanoTime()
            return
        }
        val knownSourceFps = sourceFps ?: return
        val measuredRendererIntervalMs = averageFrameIntervalMs
        val current = runtimeEffectiveMode ?: resolveActiveMode()
        val next = runtimeRenderFallbackPolicy.observe(current, knownSourceFps, measuredRendererIntervalMs) ?: return
        if (next == current) return
        runtimeEffectiveMode = next
        runtimeFallbackReason =
            "Sustained renderer callback intervals exceeded the known ${"%.2f".format(knownSourceFps)} fps source budget; " +
                "using ${next.label} instead of ${current.label}. This is not display FPS."
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
        Log.w(
            TAG,
            "render_auto_fallback requested=${mode.id} from=${current.id} to=${next.id} " +
                "source_fps=$knownSourceFps avg_renderer_interval_ms=$measuredRendererIntervalMs",
        )
    }

    private inline fun measureCpuSubmit(window: CpuSubmitWindow, action: () -> Unit) {
        val startNs = System.nanoTime()
        try {
            action()
        } finally {
            window.record(System.nanoTime() - startNs)
        }
    }

    private fun resetCpuSubmitWindows() {
        frameCpuSubmitWindow.drain()
        inputUpdateCpuSubmitWindow.drain()
        denoiseCpuSubmitWindow.drain()
        easuCpuSubmitWindow.drain()
        rcasCpuSubmitWindow.drain()
        presentCpuSubmitWindow.drain()
        latestCpuTimings = RenderCpuTimings(null, null, null, null, null, null)
        skipNextFrameCpuSubmitSample = true
    }

    private fun maybeLogPerformanceSample(nowNs: Long) {
        if (performanceSampleWindowStartNs == 0L) {
            performanceSampleWindowStartNs = nowNs
            return
        }
        if (nowNs - performanceSampleWindowStartNs < PERFORMANCE_SAMPLE_INTERVAL_NS) return

        val timings = RenderCpuTimings(
            frameSubmit = frameCpuSubmitWindow.drain(),
            inputUpdate = inputUpdateCpuSubmitWindow.drain(),
            denoise = denoiseCpuSubmitWindow.drain(),
            easu = easuCpuSubmitWindow.drain(),
            rcas = rcasCpuSubmitWindow.drain(),
            present = presentCpuSubmitWindow.drain(),
        )
        latestCpuTimings = timings
        performanceSampleWindowStartNs = nowNs

        fun CpuSubmitSummary?.field(): String = this?.let {
            "${"%.3f".format(it.averageMs)}/${"%.3f".format(it.maximumMs)}/${it.samples}"
        } ?: "N/A"
        Log.i(
            TAG,
            "performance_sample requested=${mode.id} effective=${effectiveMode.id} " +
                "source_fps=${sourceVideoFps?.let { "%.2f".format(it) } ?: "unknown"} " +
                "surface_callback_fps=${surfaceTextureCallbackFps?.let { "%.2f".format(it) } ?: "N/A"} " +
                "renderer_callback_fps=${"%.2f".format(rendererCallbackFps)} " +
                "renderer_callback_interval_avg_ms=${averageFrameIntervalMs?.let { "%.2f".format(it) } ?: "N/A"} " +
                "renderer_callback_interval_max_ms=${maximumFrameIntervalMs?.let { "%.2f".format(it) } ?: "N/A"} " +
                "cpu_gl_submit_avg_ms=${timings.frameSubmit.field()} " +
                "cpu_input_update_avg_ms=${timings.inputUpdate.field()} " +
                "cpu_denoise_submit_avg_ms=${timings.denoise.field()} " +
                "cpu_easu_submit_avg_ms=${timings.easu.field()} " +
                "cpu_rcas_submit_avg_ms=${timings.rcas.field()} " +
                "cpu_present_submit_avg_ms=${timings.present.field()} " +
                "video_input_path=$videoInputPath gpu_timer_query=$gpuTimerQueryAvailable " +
                "gpu_execution_ms=${gpuExecutionMs?.let { "%.3f".format(it) } ?: "N/A"} " +
                "runtime_fallback_active=${runtimeFallbackReason != null}",
        )
    }


    private fun presentOriginal() = presentFitted(inputTexture, flipY = decodedVideoFrames == 0L)

    private fun presentExternalVideoDirect() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = RenderPolicy.glViewportForContent(surfaceSize, videoGeometry, aspectMode)
        if (externalInputTexture == 0 || viewport.width <= 0 || viewport.height <= 0 || decodedVideoFrames == 0L) return
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        drawExternalTexture()
    }

    private fun drawExternalTexture() {
        val program = externalCopyProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalInputTexture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniformMatrix4fv(bindings.textureTransform, 1, false, textureTransform, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawDenoise() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, denoiseFramebuffer)
        GLES30.glViewport(0, 0, sourceSize.width, sourceSize.height)
        val program = denoiseProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        bindTexture(inputTexture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniform1f(bindings.strength, FilterPolicy.DEFAULT_DENOISE_STRENGTH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun presentProcessed(texture: Int) = presentFitted(texture, flipY = false)

    private fun drawRcasToSurface() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = RenderPolicy.glViewportForContent(surfaceSize, videoGeometry, aspectMode)
        if (viewport.width <= 0 || viewport.height <= 0) return
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        drawRcas(viewportOriginX = viewport.x, viewportOriginY = viewport.y)
    }

    private fun presentFitted(texture: Int, flipY: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val viewport = if (videoGeometry.codedWidth > 0 && videoGeometry.codedHeight > 0) {
            RenderPolicy.contentViewport(surfaceSize, videoGeometry, aspectMode)
        } else {
            RenderPolicy.contentViewport(surfaceSize, sourceSize)
        }
        if (viewport.width <= 0 || viewport.height <= 0) return
        GLES30.glViewport(
            viewport.left,
            surfaceSize.height - viewport.top - viewport.height,
            viewport.width,
            viewport.height,
        )
        drawPresent(texture, flipY)
    }

    private fun drawEasuChain(firstInputTexture: Int, useExternalInput: Boolean = false) {
        val stages = easuStageSizes
        check(stages.isNotEmpty()) { "No valid EASU stage for $sourceSize → $targetSize" }
        var inputTextureForStage = firstInputTexture
        var inputSize = sourceSize
        for (index in stages.indices) {
            val stageSize = stages[index]
            val finalStage = index == stages.lastIndex
            val (destinationTexture, destinationFramebuffer) = when {
                finalStage -> outputTexture to framebuffer
                index % 2 == 0 -> easuIntermediateTextureA to easuIntermediateFramebufferA
                else -> easuIntermediateTextureB to easuIntermediateFramebufferB
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destinationFramebuffer)
            GLES30.glViewport(0, 0, stageSize.width, stageSize.height)
            if (index == 0 && useExternalInput) {
                drawEasuExternal(inputSize, stageSize)
            } else {
                drawEasu(
                    inputTextureForStage,
                    inputSize,
                    stageSize,
                    flipInputY = index == 0 && decodedVideoFrames == 0L,
                )
            }
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
        val program = easuProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        bindTexture(sourceTexture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniform2f(bindings.inputSize, inputSize.width.toFloat(), inputSize.height.toFloat())
        GLES30.glUniform2f(bindings.outputSize, outputSize.width.toFloat(), outputSize.height.toFloat())
        GLES30.glUniform1i(bindings.flipInputY, if (flipInputY) 1 else 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawEasuExternal(inputSize: PixelSize, outputSize: PixelSize) {
        val program = externalEasuProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalInputTexture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniform2f(bindings.inputSize, inputSize.width.toFloat(), inputSize.height.toFloat())
        GLES30.glUniform2f(bindings.outputSize, outputSize.width.toFloat(), outputSize.height.toFloat())
        GLES30.glUniform1i(bindings.flipInputY, 0)
        GLES30.glUniformMatrix4fv(bindings.textureTransform, 1, false, textureTransform, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawRcas(viewportOriginX: Int = 0, viewportOriginY: Int = 0) {
        val program = rcasProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        bindTexture(outputTexture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniform1f(bindings.sharpnessScale, RenderPolicy.rcasScaleFor(sharpnessProgress))
        GLES30.glUniform2f(bindings.viewportOrigin, viewportOriginX.toFloat(), viewportOriginY.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPresent(texture: Int, flipY: Boolean) {
        val program = presentProgram
        val bindings = bindingsFor(program)
        GLES30.glUseProgram(program)
        bindQuad(program)
        bindTexture(texture)
        GLES30.glUniform1i(bindings.input, 0)
        GLES30.glUniform1i(bindings.flipY, if (flipY) 1 else 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad(program: Int) {
        val bindings = bindingsFor(program)
        quad.position(0)
        if (bindings.position >= 0) {
            GLES30.glEnableVertexAttribArray(bindings.position)
            GLES30.glVertexAttribPointer(bindings.position, 2, GLES30.GL_FLOAT, false, 16, quad)
        }
        if (bindings.texCoord >= 0) {
            quad.position(2)
            GLES30.glEnableVertexAttribArray(bindings.texCoord)
            GLES30.glVertexAttribPointer(bindings.texCoord, 2, GLES30.GL_FLOAT, false, 16, quad)
        }
        quad.position(0)
    }

    private fun bindTexture(texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun createOrReplaceTargets() {
        releaseProcessingTargets()
        allocationFallbackReason = null
        qualityFallbackReason = null

        try {
            if (inputTexture == 0 || sourceFramebuffer == 0 || allocatedSourceSize != sourceSize) {
                releaseSourceTarget()
                inputTexture = createChartTexture(sourceSize)
                sourceFramebuffer = createFramebuffer(inputTexture)
                allocatedSourceSize = sourceSize
            }

            val canUpscale = canUpscaleWithEasu()
            val renderMode = availableModeFor(mode)
            if (canUpscale && renderMode.usesNoiseReduction) ensureDenoiseTarget()

            if (canUpscale && renderMode.usesEasu) {
                outputTexture = createEmptyTexture(targetSize, GLES30.GL_LINEAR)
                framebuffer = createFramebuffer(outputTexture)

                if (renderMode.usesRcas &&
                    RenderPolicy.requiresRcasIntermediate(targetSize, surfaceSize, videoGeometry, aspectMode)
                ) {
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
        if (sourceFramebuffer == 0) releaseSourceTarget()
        allocationFallbackReason = "GPU render-target allocation failed; using bilinear input: ${failure.message ?: failure.javaClass.simpleName}"
        effectiveMode = RenderMode.BILINEAR
        lastModeOnGlThread = mode
        Log.e(TAG, allocationFallbackReason, failure)
    }

    private fun updateEasuStagePlan(renderMode: RenderMode) {
        easuStageSizes = if (renderMode.usesEasu && canUpscaleWithEasu()) {
            RenderPolicy.easuStages(sourceSize, targetSize)
        } else {
            emptyList()
        }
        easuStageCount = easuStageSizes.size
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

    private fun releaseSourceTarget() {
        if (sourceFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(sourceFramebuffer), 0)
        if (inputTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        sourceFramebuffer = 0
        inputTexture = 0
        allocatedSourceSize = PixelSize(0, 0)
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

    private fun compareExternalEasuWithRgbaCopy(): EasuOutputDifference? {
        check(BuildConfig.DEBUG) { "Pixel comparison is restricted to debug builds" }
        check(externalEasuAvailable && externalInputTexture != 0) { "External EASU input is unavailable" }
        check(sourceFramebuffer != 0 && inputTexture != 0 && framebuffer != 0) {
            "EASU comparison render targets are unavailable"
        }
        drawEasuChain(inputTexture, useExternalInput = true)
        val directOutput = readEasuOutputPixels()
        copyExternalVideoToRgba()
        drawEasuChain(inputTexture, useExternalInput = false)
        val rgbaCopyOutput = readEasuOutputPixels()
        return EasuQualityComparison.compare(rgbaCopyOutput, directOutput)
    }

    private fun readEasuOutputPixels(): ByteArray {
        val byteCount = targetSize.width * targetSize.height * 4
        val pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glReadPixels(0, 0, targetSize.width, targetSize.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        checkNoGlError("debug EASU output comparison readback")
        val output = ByteArray(byteCount)
        pixels.position(0)
        pixels.get(output)
        return output
    }

    private fun copyExternalVideoToRgba() {
        check(externalInputTexture != 0 && sourceFramebuffer != 0 && inputTexture != 0) {
            "External-video RGBA copy target is unavailable"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sourceFramebuffer)
        GLES30.glViewport(0, 0, sourceSize.width, sourceSize.height)
        drawExternalTexture()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun updateDecodedVideoFrame(copyToRgba: Boolean, directEasuInput: Boolean): Boolean {
        if (!frameAvailable) return false
        val surfaceTexture = decodedSurfaceTexture ?: return false
        frameAvailable = false
        val callbacks = pendingVideoFrameCallbacks.getAndSet(0L)
        if (callbacks <= 0L) return false
        if (callbacks > 1L) estimatedSkippedFrames += callbacks - 1L
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureTransform)
            if (videoGeometry.normalizedRotation != 0) {
                val matrixHasRotation = when (videoGeometry.normalizedRotation) {
                    90, 270 -> abs(textureTransform[1]) > 0.5f || abs(textureTransform[4]) > 0.5f
                    180 -> textureTransform[0] < -0.5f
                    else -> false
                }
                if (!matrixHasRotation) {
                    val rotMatrix = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(rotMatrix, 0)
                    android.opengl.Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
                    android.opengl.Matrix.rotateM(rotMatrix, 0, videoGeometry.normalizedRotation.toFloat(), 0f, 0f, 1f)
                    android.opengl.Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
                    val combined = FloatArray(16)
                    android.opengl.Matrix.multiplyMM(combined, 0, textureTransform, 0, rotMatrix, 0)
                    System.arraycopy(combined, 0, textureTransform, 0, 16)
                }
            }
            val timestampNs = surfaceTexture.timestamp
            if (timestampNs > 0L) {
                mediaTimestampCadence.record(timestampNs)?.let { consumedMediaTimestampFps = it }
            }
            val copiedToRgba = copyToRgba && externalCopyProgram != 0 && sourceFramebuffer != 0 && inputTexture != 0
            if (copiedToRgba) copyExternalVideoToRgba()
            videoInputPath = when {
                copiedToRgba -> "rgba-copy"
                directEasuInput -> "direct-easu-oes"
                externalInputTexture != 0 -> "direct-oes"
                else -> "no-external-texture"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            decodedVideoFrames += 1L
            if (decodedVideoFrames == 1L || decodedVideoFrames % 60L == 0L) {
                Log.i(TAG, "video_frame=consumed path=$videoInputPath count=$decodedVideoFrames source=$sourceSize")
            }
            return true
        } catch (failure: Exception) {
            videoInputError = "Decoded video frame update failed: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(TAG, videoInputError, failure)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return false
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

    private fun createProgram(
        vertexAsset: String,
        fragmentAsset: String,
        fragmentDefines: Set<String> = emptySet(),
    ): Int {
        val vertexSource = context.assets.open(vertexAsset).bufferedReader().use { it.readText() }
        val rawFragmentSource = context.assets.open(fragmentAsset).bufferedReader().use { it.readText() }
        val fragmentSource = if (fragmentDefines.isEmpty()) {
            rawFragmentSource
        } else {
            val versionLineEnd = rawFragmentSource.indexOf('\n')
            check(versionLineEnd >= 0 && rawFragmentSource.substring(0, versionLineEnd).startsWith("#version")) {
                "$fragmentAsset must begin with a #version directive before shader defines"
            }
            rawFragmentSource.substring(0, versionLineEnd + 1) +
                fragmentDefines.joinToString("\n") { "#define $it 1" } + "\n" +
                rawFragmentSource.substring(versionLineEnd + 1)
        }
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
            cacheShaderBindings(program, fragmentAsset)
            return program
        } catch (failure: Throwable) {
            if (program != 0) GLES30.glDeleteProgram(program)
            throw failure
        } finally {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }

    private fun cacheShaderBindings(program: Int, fragmentAsset: String) {
        val bindings = ShaderBindings(
            position = GLES30.glGetAttribLocation(program, "aPosition"),
            texCoord = GLES30.glGetAttribLocation(program, "aTexCoord"),
            input = GLES30.glGetUniformLocation(program, "uInput"),
            inputSize = GLES30.glGetUniformLocation(program, "uInputSize"),
            outputSize = GLES30.glGetUniformLocation(program, "uOutputSize"),
            flipInputY = GLES30.glGetUniformLocation(program, "uFlipInputY"),
            sharpnessScale = GLES30.glGetUniformLocation(program, "uSharpnessScale"),
            viewportOrigin = GLES30.glGetUniformLocation(program, "uViewportOrigin"),
            strength = GLES30.glGetUniformLocation(program, "uStrength"),
            flipY = GLES30.glGetUniformLocation(program, "uFlipY"),
            textureTransform = GLES30.glGetUniformLocation(program, "uTextureTransform"),
        )
        check(bindings.position >= 0) {
            "$fragmentAsset did not keep the fullscreen position attribute active"
        }
        shaderBindings[program] = bindings
    }

    private fun bindingsFor(program: Int): ShaderBindings =
        checkNotNull(shaderBindings[program]) { "No cached GLES bindings for program $program" }


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
