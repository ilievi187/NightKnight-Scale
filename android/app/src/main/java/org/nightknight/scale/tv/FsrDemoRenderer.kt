package org.nightknight.scale.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.EGL14
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
    val eglSurface: PixelSize?,
    val gpuRenderer: String,
    val glVersion: String,
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val maxViewportSize: PixelSize,
    val requestedOutputResolution: OutputResolutionMode,
    val effectiveOutputResolution: EffectiveOutputResolution,
    val outputResolutionFallbackReason: String?,
    val native4kSurfaceVerified: Boolean,
    val native4kPerformanceValidatedForAuto: Boolean,
    val surfaceFallbackRequested: Boolean,
    val surfaceChangeCount: Long,
    val requestedScalingStrategy: FsrComparisonStrategy,
    val effectiveScalingStrategy: FsrComparisonStrategy,
    val scalingStrategyFallbackReason: String?,
    val linearFinalScale: Boolean,
    val systemScalerExpected: Boolean,
    val easuStageSizes: List<PixelSize>,
    val shaderPassOutputSizes: List<PixelSize>,
    val estimatedRgbaWriteBytesPerFrame: Long,
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
    val externalEasuValidated: Boolean,
    val externalEasuRejected: Boolean,
    val videoSource: String,
    val decodedVideoFrames: Long,
    val videoInputError: String?,
    val geometry: VideoGeometry = VideoGeometry(0, 0),
    val aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
)

internal fun shouldTimeoutNative4kSurface(
    requestedBuffer: PixelSize,
    verified: Boolean,
    fallbackApplied: Boolean,
    elapsedMs: Long,
): Boolean = requestedBuffer == OutputResolutionPolicy.NATIVE_4K && !verified && !fallbackApplied && elapsedMs > 5_000L

internal class OutputSurfaceDeadline {
    private var startedMs = 0L
    private var wasVerified = false
    private var pausedAtMs: Long? = null

    fun start(nowMs: Long) {
        startedMs = nowMs
        if (pausedAtMs != null) pausedAtMs = nowMs
        wasVerified = false
    }

    fun pause(nowMs: Long) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    fun resume(nowMs: Long) {
        pausedAtMs?.let { startedMs += nowMs - it }
        pausedAtMs = null
    }

    fun expired(verified: Boolean, nowMs: Long): Boolean {
        if (wasVerified && !verified) startedMs = nowMs
        wasVerified = verified
        return !verified && nowMs - startedMs > 5_000L
    }
}

internal fun shouldReportUnverified1080(deadline: OutputSurfaceDeadline, effective: EffectiveOutputResolution, nowMs: Long): Boolean =
    deadline.expired(effective == EffectiveOutputResolution.P1080, nowMs)

internal class FsrDemoRenderer(
    private val context: Context,
    private val onVideoSurfaceAvailable: (SurfaceTexture, VideoTestSource) -> Unit,
    private val requestRender: () -> Unit = {},
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

    private data class RenderPlanRequestKey(
        val source: PixelSize,
        val geometry: VideoGeometry,
        val surface: PixelSize,
        val mode: RenderMode,
        val strategy: FsrComparisonStrategy,
        val aspectMode: AspectRatioMode,
        val maxTextureSize: Int,
    )

    private companion object {
        const val TAG = "NKScaleTV"
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val PERFORMANCE_SAMPLE_INTERVAL_NS = 2_000_000_000L
        const val FALLBACK_SOURCE_ACTIVITY_TIMEOUT_NS = 1_000_000_000L
    }

    @Volatile
    var requestedMode = RenderMode.FSR1

    @Volatile
    private var mode = RenderMode.FSR1

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
    private var eglSurfaceSize: PixelSize? = null

    @Volatile
    private var gpuRenderer = "Detecting GLES renderer…"

    @Volatile
    private var glVersion = ""

    @Volatile
    private var maxTextureSize = 0

    @Volatile
    private var maxRenderbufferSize = 0

    @Volatile
    private var maxViewportSize = PixelSize(0, 0)

    @Volatile
    private var requestedOutputResolution = OutputResolutionMode.AUTO

    @Volatile
    private var requestedSurfaceBufferSize = OutputResolutionPolicy.FULL_HD

    @Volatile
    private var effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED

    @Volatile
    private var outputResolutionFallbackReason: String? = null

    @Volatile
    private var native4kSurfaceVerified = false

    @Volatile
    private var native4kPerformanceValidatedForAuto = false

    @Volatile
    private var surfaceFallbackRequested = false

    @Volatile
    private var native4kFallbackLatched = false

    @Volatile
    private var surfaceChangeCount = 0L

    @Volatile
    var scalingStrategy = FsrComparisonStrategy.AUTO

    @Volatile
    var testSourceOverride: VideoTestSource? = null

    @Volatile
    private var effectiveScalingStrategy = FsrComparisonStrategy.AUTO

    @Volatile
    private var currentScalingPlan: FsrScalingPlan? = null

    private var appliedPlanRequestKey: RenderPlanRequestKey? = null

    @Volatile
    private var strategyBaseTarget = PixelSize(0, 0)

    @Volatile
    private var linearFinalScale = false

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

    private val isDebuggableBuild =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
    private val lastVideoFrameCallbackNs = AtomicLong()
    private val decoderLagEvents = AtomicLong()
    private val frameRenderRequestGate = FrameRenderRequestGate()
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
    private val native4kPerformanceValidationPolicy = Native4kPerformanceValidationPolicy()
    private val native4kResolutionFallbackPolicy = Native4kResolutionFallbackPolicy()

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
    @Volatile
    private var externalEasuValidated = false
    @Volatile
    private var externalEasuRejected = false
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
    private var strategyBaseTexture = 0
    private var sourceFramebuffer = 0
    private var denoiseFramebuffer = 0
    private var framebuffer = 0
    private var rcasFramebuffer = 0
    private var easuIntermediateFramebufferA = 0
    private var easuIntermediateFramebufferB = 0
    private var strategyBaseFramebuffer = 0
    private var easuIntermediateSizeA = PixelSize(0, 0)
    private var easuIntermediateSizeB = PixelSize(0, 0)
    @Volatile
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
    private val easuStageCpuSubmitWindows = Array(16) { CpuSubmitWindow() }
    private val linearFinalCpuSubmitWindow = CpuSubmitWindow()
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
        eglSurface = eglSurfaceSize,
        gpuRenderer = gpuRenderer,
        glVersion = glVersion,
        maxTextureSize = maxTextureSize,
        maxRenderbufferSize = maxRenderbufferSize,
        maxViewportSize = maxViewportSize,
        requestedOutputResolution = requestedOutputResolution,
        effectiveOutputResolution = effectiveOutputResolution,
        outputResolutionFallbackReason = outputResolutionFallbackReason,
        native4kSurfaceVerified = native4kSurfaceVerified,
        native4kPerformanceValidatedForAuto = native4kPerformanceValidatedForAuto,
        surfaceFallbackRequested = surfaceFallbackRequested,
        surfaceChangeCount = surfaceChangeCount,
        requestedScalingStrategy = scalingStrategy,
        effectiveScalingStrategy = effectiveScalingStrategy,
        scalingStrategyFallbackReason = currentScalingPlan?.strategyFallbackReason,
        linearFinalScale = linearFinalScale,
        systemScalerExpected = currentScalingPlan?.systemScalerExpected ?: false,
        easuStageSizes = easuStageSizes,
        shaderPassOutputSizes = currentScalingPlan?.passOutputSizes.orEmpty(),
        estimatedRgbaWriteBytesPerFrame = currentScalingPlan?.estimatedRgbaWriteBytesPerFrame ?: 0L,
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
        estimatedRgbaTextureBytes = currentScalingPlan?.estimatedRgbaTextureBytes ?: RenderPolicy.estimatedRgbaTextureBytes(
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
        mode = requestedMode,
        effectiveMode = effectiveMode,
        allocationFallbackReason = allocationFallbackReason,
        qualityFallbackReason = qualityFallbackReason,
        shaderFallbackReason = shaderFallbackReason,
        runtimeFallbackReason = runtimeFallbackReason,
        videoInputPath = videoInputPath,
        externalEasuAvailable = externalEasuAvailable,
        externalEasuValidated = externalEasuValidated,
        externalEasuRejected = externalEasuRejected,
        videoSource = selectedVideoSource?.label ?: "Waiting for output size",
        decodedVideoFrames = decodedVideoFrames,
        videoInputError = videoInputError,
        geometry = videoGeometry,
        aspectMode = aspectMode,
    )

    fun recordDecoderLag() {
        decoderLagEvents.incrementAndGet()
    }

    fun resetFrameRenderRequests() {
        frameRenderRequestGate.reset()
    }

    fun resetFramePacingWindows() {
        resetRendererMeasurementWindows()
        resetCpuSubmitWindows()
        runtimeRenderFallbackPolicy.reset()
        resetNative4kPerformanceValidation()
        performanceSampleWindowStartNs = System.nanoTime()
    }

    fun resetNative4kPerformanceValidation() {
        native4kPerformanceValidationPolicy.reset()
        native4kResolutionFallbackPolicy.reset()
        native4kPerformanceValidatedForAuto = false
    }

    /** Called on the UI thread before the matching SurfaceHolder buffer-size request. */
    @Synchronized
    fun requestOutputResolution(
        mode: OutputResolutionMode,
        requestedBufferSize: PixelSize,
        policyReason: String? = null,
    ) {
        requestedOutputResolution = mode
        requestedSurfaceBufferSize = requestedBufferSize
        // Do not mark any requested buffer effective until callback/EGL/GLES/target validation passes.
        effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED
        outputResolutionFallbackReason = policyReason
        native4kSurfaceVerified = false
        resetNative4kPerformanceValidation()
        native4kResolutionFallbackPolicy.reset()
        surfaceFallbackRequested = false
        native4kFallbackLatched = false
    }

    /** Rechecks a fixed-size request when the existing callback dimensions already match it. */
    fun validateOutputResolutionForCurrentSurface() {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        eglSurfaceSize = queryCurrentEglSurfaceSize()
        updateOutputResolutionValidation()
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
        externalEasuValidated = false
        externalEasuRejected = false
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
        strategyBaseTexture = 0
        easuIntermediateFramebufferA = 0
        easuIntermediateFramebufferB = 0
        strategyBaseFramebuffer = 0
        easuIntermediateSizeA = PixelSize(0, 0)
        easuIntermediateSizeB = PixelSize(0, 0)
        strategyBaseTarget = PixelSize(0, 0)
        currentScalingPlan = null
        appliedPlanRequestKey = null
        resetNative4kPerformanceValidation()
        native4kSurfaceVerified = false
        effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED
        surfaceFallbackRequested = false
        native4kFallbackLatched = false
        frameAvailable = false
        frameRenderRequestGate.reset()
        lastVideoFrameCallbackNs.set(0L)
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
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, size, 0)
        maxRenderbufferSize = size[0]
        val viewportLimits = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, viewportLimits, 0)
        maxViewportSize = PixelSize(viewportLimits[0], viewportLimits[1])
        Log.i(
            TAG,
            "gles_limits max_texture=$maxTextureSize max_renderbuffer=$maxRenderbufferSize " +
                "max_viewport=${maxViewportSize.width}x${maxViewportSize.height}",
        )
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
                    "easu=${easuProgram != 0} easuExternalProgram=$externalEasuAvailable rcas=${rcasProgram != 0} " +
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
        surfaceChangeCount += 1L
        surfaceSize = PixelSize(width, height)
        val previousMode = mode
        mode = requestedMode
        if (mode != previousMode) resetModeTransitionState()
        appliedPlanRequestKey = null
        eglSurfaceSize = queryCurrentEglSurfaceSize()
        // Avoid allocating an unbounded multi-4K intermediate on unusually large displays.
        targetSize = RenderPolicy.targetFor(surfaceSize, minOf(maxTextureSize, 4096))
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The GLES device reported an invalid render target size."
            return
        }
        val source = selectedVideoSource ?: testSourceOverride ?: VideoTestSource.forTarget(targetSize)
        if (selectedVideoSource == null) {
            sourceSize = source.size
            videoGeometry = VideoGeometry.fromSize(sourceSize)
            selectedVideoSource = source
        } else if (videoGeometry.codedWidth <= 0 || videoGeometry.codedHeight <= 0) {
            sourceSize = source.size
            videoGeometry = VideoGeometry.fromSize(sourceSize)
        }
        val renderMode = availableModeFor(mode)
        val nativePlannerCallsBeforePlan = NativeEasuStagePlanner.nativePlannerCalls
        applyScalingPlan(createScalingPlan(renderMode, sourceSize, videoGeometry))
        val nativePlannerPlanCalls = NativeEasuStagePlanner.nativePlannerCalls - nativePlannerCallsBeforePlan
        val nativePlannerRuntimePlan = resolveNativePlannerRuntimePlan(currentScalingPlan, nativePlannerPlanCalls)
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            error = "The decoded video size cannot be processed by the GLES renderer."
            return
        }
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not create the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }
        if (externalCopyProgram != 0 && (externalInputTexture == 0 || surfaceVideoSource != source)) {
            runCatching { createExternalVideoSurface(source) }
                .onFailure { failure ->
                    videoInputError = "Could not create decoded-video input: ${failure.message ?: failure.javaClass.simpleName}"
                    Log.e(TAG, videoInputError, failure)
                }
        }
        updateOutputResolutionValidation()
        runtimeRenderFallbackPolicy.reset()
        resetNative4kPerformanceValidation()
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
        Log.i(
            TAG,
            "surface=$surfaceSize source=${sourceSize} target=${targetSize} requested_buffer=$requestedSurfaceBufferSize " +
                "strategy_requested=${scalingStrategy.id} strategy_effective=${effectiveScalingStrategy.id} " +
                "strategy_fallback=${currentScalingPlan?.strategyFallbackReason ?: "none"} " +
                "easu_stages=${easuStageSizes.joinToString("→")} linear_final=$linearFinalScale " +
                "native_planner=${NativeEasuStagePlanner.status} " +
                "native_planner_runtime_plan=$nativePlannerRuntimePlan " +
                "native_planner_plan_calls=$nativePlannerPlanCalls " +
                "native_planner_calls=${NativeEasuStagePlanner.nativePlannerCalls} " +
                "native_planner_last_call_us=${NativeEasuStagePlanner.lastNativePlannerCallMicros} " +
                "pass_outputs=${currentScalingPlan?.passOutputSizes.orEmpty().joinToString("→")} " +
                "rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} " +
                "rcas_output=${rcasOutputLabel()} video=${source.assetPath}",
        )
        requestRender()
    }

    /** Update aspect ratio override (AUTO, 16:9, 4:3, SQUARE_PIXELS). */
    fun setAspectRatioMode(mode: AspectRatioMode) {
        aspectMode = mode
        if (videoGeometry.codedWidth > 0 && surfaceSize.width > 0) {
            updateDecodedVideoGeometry(videoGeometry)
        }
    }

    fun applyScalingStrategy(strategy: FsrComparisonStrategy) {
        if (scalingStrategy == strategy) return
        scalingStrategy = strategy
        runtimeRenderFallbackPolicy.reset()
        runtimeEffectiveMode = null
        runtimeFallbackReason = null
        resetNative4kPerformanceValidation()
        val nativePlannerCallsBeforePlan = NativeEasuStagePlanner.nativePlannerCalls
        var appliedPlan: FsrScalingPlan? = null
        if (surfaceSize.width > 0 && sourceSize.width > 0 && maxTextureSize > 0) {
            val plan = createScalingPlan(availableModeFor(mode), sourceSize, videoGeometry)
            appliedPlan = plan
            applyScalingPlan(plan)
            runCatching { createOrReplaceTargets() }
                .onFailure { failure ->
                    error = "Could not allocate targets for ${strategy.label}: ${failure.message ?: failure.javaClass.simpleName}"
                    Log.e(TAG, error, failure)
                }
            updateOutputResolutionValidation()
        }
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
        val nativePlannerPlanCalls = NativeEasuStagePlanner.nativePlannerCalls - nativePlannerCallsBeforePlan
        val nativePlannerRuntimePlan = resolveNativePlannerRuntimePlan(appliedPlan, nativePlannerPlanCalls)
        Log.i(
            TAG,
            "scaling_strategy requested=${strategy.id} effective=${effectiveScalingStrategy.id} " +
                "target=$targetSize easu_stages=${easuStageSizes.joinToString("→")} linear_final=$linearFinalScale " +
                "native_planner_runtime_plan=$nativePlannerRuntimePlan native_planner_plan_calls=$nativePlannerPlanCalls " +
                "rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes}",
        )
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
        val requestKey = renderPlanRequestKey(newSourceSize, geometry)
        if (requestKey == appliedPlanRequestKey) return
        val renderMode = availableModeFor(mode)
        val nativePlannerCallsBeforePlan = NativeEasuStagePlanner.nativePlannerCalls
        val newPlan = createScalingPlan(renderMode, newSourceSize, geometry)
        val nativePlannerPlanCalls = NativeEasuStagePlanner.nativePlannerCalls - nativePlannerCallsBeforePlan
        val nativePlannerRuntimePlan = resolveNativePlannerRuntimePlan(newPlan, nativePlannerPlanCalls)
        val newTargetSize = newPlan.processingTarget
        if (newTargetSize.width <= 0 || newTargetSize.height <= 0) {
            error = "The decoded video size cannot be processed by the GLES renderer."
            return
        }
        if (geometry == videoGeometry && newSourceSize == sourceSize && newTargetSize == targetSize &&
            newPlan.easuStages == easuStageSizes && newPlan.linearFinalScale == linearFinalScale
        ) {
            appliedPlanRequestKey = requestKey
            return
        }

        externalEasuValidated = false
        externalEasuRejected = false
        videoGeometry = geometry
        sourceSize = newSourceSize
        applyScalingPlan(newPlan)
        error = null
        runCatching { createOrReplaceTargets() }
            .onFailure { failure ->
                error = "Could not resize the FSR render target: ${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, error, failure)
            }
        updateOutputResolutionValidation()
        runtimeRenderFallbackPolicy.reset()
        resetNative4kPerformanceValidation()
        resetCpuSubmitWindows()
        resetRendererMeasurementWindows()
        performanceSampleWindowStartNs = System.nanoTime()
        Log.i(TAG, "decoded_size=$sourceSize target=$targetSize surface=$surfaceSize easu_stages=$easuStageCount native_planner_runtime_plan=$nativePlannerRuntimePlan native_planner_plan_calls=$nativePlannerPlanCalls rgba_texture_bytes=${snapshot().estimatedRgbaTextureBytes} rcas_output=${rcasOutputLabel()}")
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

    /** Apply the latest volatile UI request on the GL queue before the next dirty draw. */
    fun applyRequestedModeOnGlThread() {
        val requested = requestedMode
        if (requested == mode) return
        mode = requested
        resetNative4kPerformanceValidation()
        applyRequestedMode()
    }

    private fun resolveNativePlannerRuntimePlan(plan: FsrScalingPlan?, nativePlannerPlanCalls: Long): String = when {
        plan == null -> "not-ready"
        plan.easuStages.isEmpty() -> "not-required"
        nativePlannerPlanCalls > 0L && NativeEasuStagePlanner.status == "Rust EASU stage planner" -> "rust"
        else -> "kotlin-fallback"
    }

    private fun resetModeTransitionState() {
        runtimeRenderFallbackPolicy.reset()
        runtimeEffectiveMode = null
        runtimeFallbackReason = null
        lastFallbackSourceFps = sourceVideoFps
    }

    private fun applyRequestedMode() {
        val requested = mode
        if (requested == lastModeOnGlThread) return
        resetModeTransitionState()
        val resourceMode = availableModeFor(requested)
        val nativePlannerCallsBeforePlan = NativeEasuStagePlanner.nativePlannerCalls
        var appliedPlan: FsrScalingPlan? = null
        if (surfaceSize.width > 0 && sourceSize.width > 0 && maxTextureSize > 0) {
            val nextPlan = createScalingPlan(resourceMode, sourceSize, videoGeometry)
            val nextTarget = nextPlan.processingTarget
            if (nextTarget.width > 0 && nextTarget.height > 0) {
                applyScalingPlan(nextPlan)
                appliedPlan = nextPlan
                createOrReplaceTargets()
                updateOutputResolutionValidation()
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
        val nativePlannerPlanCalls = NativeEasuStagePlanner.nativePlannerCalls - nativePlannerCallsBeforePlan
        val runtimePlan = resolveNativePlannerRuntimePlan(appliedPlan, nativePlannerPlanCalls)
        Log.i(
            TAG,
            "render_resources mode=${requested.id} effective=${effectiveMode.id} " +
                "native_planner_runtime_plan=$runtimePlan native_planner_plan_calls=$nativePlannerPlanCalls " +
                "rgba_bytes=${snapshot().estimatedRgbaTextureBytes}",
        )
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
        frameRenderRequestGate.onRenderStarted()
        val frameStartNs = System.nanoTime()
        val intervalWindowCompleted = recordFrameInterval(frameStartNs)
        maybeLogPerformanceSample(frameStartNs)
        val recentSourceFrameActivity = VideoActivityPolicy.hasRecentFrameCallback(
            lastCallbackNs = lastVideoFrameCallbackNs.get(),
            nowNs = System.nanoTime(),
            maxIdleNs = FALLBACK_SOURCE_ACTIVITY_TIMEOUT_NS,
        )
        if (intervalWindowCompleted && decodedVideoFrames >= minimumFramesForBudgetCheck() && recentSourceFrameActivity) {
            updateNative4kPerformanceValidation(recentSourceFrameActivity)
            updateRuntimeFallbackForFrameBudget()
        } else if (!recentSourceFrameActivity) {
            runtimeRenderFallbackPolicy.reset()
            native4kResolutionFallbackPolicy.reset()
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
            val externalEasuValidationEligible = externalEasuAvailable && !externalEasuRejected &&
                externalInputTexture != 0 && activeMode.usesEasu && easuStageSizes.isNotEmpty() && !activeMode.usesNoiseReduction
            var directEasuInput = EasuExternalInputPolicy.shouldUseDirectInput(
                programAvailable = externalEasuAvailable && !externalEasuRejected,
                outputEquivalent = externalEasuValidated,
                externalTextureAvailable = externalInputTexture != 0,
                mode = activeMode,
            )
            if (linearFinalScale && easuStageSizes.isEmpty()) directEasuInput = false
            val directPresentationInput = activeMode == RenderMode.BILINEAR &&
                externalCopyProgram != 0 && externalInputTexture != 0
            val copyDecodedFrameToRgba = when {
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
            if (validateExternalEasuOnce && !externalEasuValidationConsumed && hasDecodedFrame && externalEasuValidationEligible) {
                externalEasuValidationConsumed = true
                if (!isDebuggableBuild) {
                    Log.w(TAG, "easu_equivalence=unavailable reason=debug-validation-disabled")
                } else {
                    val validation = runCatching { compareExternalEasuWithRgbaCopy() }
                    val difference = validation.getOrNull()
                    val passed = difference != null && EasuQualityComparison.isEquivalent(difference)
                    val validationReason = validation.exceptionOrNull()?.javaClass?.simpleName
                        ?: if (difference == null) "invalid-output" else if (passed) "within-threshold" else "pixel-difference-over-threshold"
                    val maxErrorPixelIndex = difference?.maximumErrorByteOffset?.div(4)
                    val maxErrorPosition = maxErrorPixelIndex?.let { pixelIndex ->
                        if (targetSize.width > 0) "${pixelIndex % targetSize.width},${pixelIndex / targetSize.width}" else "N/A"
                    } ?: "N/A"
                    val maxErrorChannel = listOf("r", "g", "b", "a")
                        .getOrNull(difference?.maximumErrorChannelIndex ?: -1) ?: "N/A"
                    Log.i(
                        TAG,
                        "easu_equivalence=${if (passed) "pass" else "fail"} " +
                            "mae_lsb=${difference?.let { "%.4f".format(it.meanAbsoluteError) } ?: "N/A"} " +
                            "mae_rgba_lsb=${difference?.let { "%.3f,%.3f,%.3f,%.3f".format(it.redMeanAbsoluteError, it.greenMeanAbsoluteError, it.blueMeanAbsoluteError, it.alphaMeanAbsoluteError) } ?: "N/A"} " +
                            "max_lsb=${difference?.maximumAbsoluteError ?: "N/A"} max_pixel=${maxErrorPosition} max_channel=$maxErrorChannel " +
                            "psnr_db=${difference?.let { if (it.psnrDb.isInfinite()) "inf" else "%.2f".format(it.psnrDb) } ?: "N/A"} " +
                            "channels=${difference?.comparedChannels ?: 0} reason=$validationReason",
                    )
                    Log.i(
                        TAG,
                        "easu_equivalence_transform=source_${sourceSize.width}x${sourceSize.height} " +
                            textureTransform.joinToString(",", prefix = "matrix=") { "%.5f".format(it) },
                    )
                    resetCpuSubmitWindows()
                    performanceSampleWindowStartNs = System.nanoTime()
                    if (passed) {
                        externalEasuValidated = true
                        externalEasuRejected = false
                        directEasuInput = true
                        videoInputPath = "direct-easu-oes-validated"
                        Log.i(TAG, "easu_external=validated; direct EASU enabled for this session")
                    } else {
                        externalEasuValidated = false
                        externalEasuRejected = true
                        directEasuInput = false
                        videoInputPath = "rgba-copy-after-equivalence-failure"
                        Log.w(TAG, "easu_external=disabled fallback=rgba-copy after output comparison")
                    }
                }
            }

            when (activeMode) {
                RenderMode.BILINEAR -> measureCpuSubmit(presentCpuSubmitWindow) {
                    if (hasDecodedFrame && directPresentationInput) presentExternalVideoDirect() else presentOriginal()
                }
                RenderMode.EASU_ONLY -> {
                    drawScalingStrategy(inputTexture, useExternalInput = directEasuInput && hasDecodedFrame)
                    measureCpuSubmit(presentCpuSubmitWindow) { presentProcessed(outputTexture) }
                }
                RenderMode.FSR1, RenderMode.FSR1_DENOISE -> {
                    val easuInput = if (activeMode.usesNoiseReduction) {
                        measureCpuSubmit(denoiseCpuSubmitWindow) { drawDenoise() }
                        denoisedTexture
                    } else {
                        inputTexture
                    }
                    drawScalingStrategy(easuInput, useExternalInput = directEasuInput && hasDecodedFrame)
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

    private fun updateNative4kPerformanceValidation(sourceActive: Boolean) {
        val newlyValidated = native4kPerformanceValidationPolicy.observe(
            native4kSurfaceVerified = native4kSurfaceVerified &&
                requestedSurfaceBufferSize == OutputResolutionPolicy.NATIVE_4K &&
                mode.usesEasu && effectiveMode == mode && runtimeFallbackReason == null &&
                allocationFallbackReason == null && qualityFallbackReason == null,
            sourceActive = sourceActive,
            sourceVideoFps = sourceVideoFps,
            averageRendererIntervalMs = averageFrameIntervalMs,
        )
        if (newlyValidated) {
            native4kPerformanceValidatedForAuto = true
            Log.i(
                TAG,
                "native4k_auto_profile=validated source_fps=${sourceVideoFps?.let { "%.2f".format(it) } ?: "unknown"} " +
                    "renderer_interval_avg_ms=${averageFrameIntervalMs?.let { "%.2f".format(it) } ?: "N/A"} " +
                    "effective_mode=${effectiveMode.id}",
            )
        }
    }

    private fun updateRuntimeFallbackForFrameBudget() {
        val sourceFps = sourceVideoFps?.takeIf { it > 0f && it.isFinite() }
        if (sourceFps != lastFallbackSourceFps) {
            runtimeRenderFallbackPolicy.reset()
            native4kResolutionFallbackPolicy.reset()
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
        val next = runtimeRenderFallbackPolicy.observe(current, knownSourceFps, measuredRendererIntervalMs)
        if (next != null && next != current) {
            runtimeEffectiveMode = next
            runtimeFallbackReason =
                "Sustained renderer callback intervals exceeded the known ${"%.2f".format(knownSourceFps)} fps source budget; " +
                    "using ${next.label} instead of ${current.label}. This is not display FPS."
            native4kResolutionFallbackPolicy.reset()
            resetCpuSubmitWindows()
            resetRendererMeasurementWindows()
            performanceSampleWindowStartNs = System.nanoTime()
            Log.w(
                TAG,
                "render_auto_fallback requested=${mode.id} from=${current.id} to=${next.id} " +
                    "source_fps=$knownSourceFps avg_renderer_interval_ms=$measuredRendererIntervalMs",
            )
            return
        }

        val reduceOutputResolution = native4kResolutionFallbackPolicy.observe(
            native4kSurfaceVerified = native4kSurfaceVerified &&
                requestedSurfaceBufferSize == OutputResolutionPolicy.NATIVE_4K &&
                surfaceSize == OutputResolutionPolicy.NATIVE_4K &&
                eglSurfaceSize == OutputResolutionPolicy.NATIVE_4K &&
                !native4kFallbackLatched,
            currentMode = current,
            sourceActive = true,
            sourceVideoFps = knownSourceFps,
            averageRendererIntervalMs = measuredRendererIntervalMs,
        )
        if (reduceOutputResolution) {
            val reason = "4K rendering remained over the known source cadence budget after processing fell back to bilinear; requesting a 1080P EGL buffer"
            native4kFallbackLatched = true
            native4kSurfaceVerified = false
            native4kPerformanceValidationPolicy.reset()
            native4kPerformanceValidatedForAuto = false
            effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED
            outputResolutionFallbackReason = reason
            surfaceFallbackRequested = true
            resetCpuSubmitWindows()
            resetRendererMeasurementWindows()
            performanceSampleWindowStartNs = System.nanoTime()
            Log.w(TAG, "output_resolution_auto_fallback requested=${requestedOutputResolution.id} fallback_buffer=1080p effective=unverified source_fps=$knownSourceFps avg_renderer_interval_ms=$measuredRendererIntervalMs reason=$reason")
        }
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
        easuStageCpuSubmitWindows.forEach { it.drain() }
        linearFinalCpuSubmitWindow.drain()
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
            easuStages = easuStageSizes.indices.map { stageIndex ->
                easuStageCpuSubmitWindows[stageIndex].drain()
            },
            linearFinalScale = linearFinalCpuSubmitWindow.drain(),
        )
        latestCpuTimings = timings
        performanceSampleWindowStartNs = nowNs

        fun CpuSubmitSummary?.field(): String = this?.let {
            "${"%.3f".format(it.averageMs)}/${"%.3f".format(it.maximumMs)}/${it.samples}"
        } ?: "N/A"
        val easuPassTimings = timings.easuStages.mapIndexed { index, summary ->
            "${index}=${summary.field()}"
        }.joinToString(",").ifEmpty { "N/A" }
        Log.i(
            TAG,
            "performance_sample requested=${mode.id} effective=${effectiveMode.id} " +
                "strategy_requested=${scalingStrategy.id} strategy_effective=${effectiveScalingStrategy.id} " +
                "output_requested=${requestedOutputResolution.id} output_effective=${effectiveOutputResolution.id} " +
                "surface_callback_size=$surfaceSize egl_surface_size=${eglSurfaceSize ?: "N/A"} " +
                "source_size=$sourceSize processing_target=$targetSize " +
                "easu_stages=${easuStageSizes.joinToString("→")} linear_final_scale=$linearFinalScale " +
                "rgba_target_bytes_est=${snapshot().estimatedRgbaTextureBytes} " +
                "rgba_shader_write_bytes_est=${currentScalingPlan?.estimatedRgbaWriteBytesPerFrame ?: 0L} " +
                "source_fps=${sourceVideoFps?.let { "%.2f".format(it) } ?: "unknown"} " +
                "surface_callback_fps=${surfaceTextureCallbackFps?.let { "%.2f".format(it) } ?: "N/A"} " +
                "renderer_callback_fps=${"%.2f".format(rendererCallbackFps)} " +
                "renderer_callback_interval_avg_ms=${averageFrameIntervalMs?.let { "%.2f".format(it) } ?: "N/A"} " +
                "renderer_callback_interval_max_ms=${maximumFrameIntervalMs?.let { "%.2f".format(it) } ?: "N/A"} " +
                "cpu_gl_submit_avg_ms=${timings.frameSubmit.field()} " +
                "cpu_input_update_avg_ms=${timings.inputUpdate.field()} " +
                "cpu_denoise_submit_avg_ms=${timings.denoise.field()} " +
                "cpu_easu_submit_avg_ms=${timings.easu.field()} " +
                "cpu_easu_pass_submit_avg_max_n_ms=$easuPassTimings " +
                "cpu_linear_final_submit_avg_ms=${timings.linearFinalScale.field()} " +
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

    private fun drawScalingStrategy(firstInputTexture: Int, useExternalInput: Boolean = false) {
        if (!linearFinalScale) {
            measureCpuSubmit(easuCpuSubmitWindow) {
                drawEasuChain(firstInputTexture, useExternalInput)
            }
            return
        }

        val finalScaleInput = if (easuStageSizes.isNotEmpty()) {
            check(strategyBaseTexture != 0 && strategyBaseFramebuffer != 0) {
                "Linear-final FSR strategy is missing its base-resolution target"
            }
            measureCpuSubmit(easuCpuSubmitWindow) {
                drawEasuChain(
                    firstInputTexture,
                    useExternalInput,
                    finalOutputTexture = strategyBaseTexture,
                    finalOutputFramebuffer = strategyBaseFramebuffer,
                )
            }
            strategyBaseTexture
        } else {
            firstInputTexture
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, targetSize.width, targetSize.height)
        measureCpuSubmit(linearFinalCpuSubmitWindow) {
            drawPresent(finalScaleInput, flipY = easuStageSizes.isEmpty() && decodedVideoFrames == 0L)
        }
    }

    private fun drawEasuChain(
        firstInputTexture: Int,
        useExternalInput: Boolean = false,
        finalOutputTexture: Int = outputTexture,
        finalOutputFramebuffer: Int = framebuffer,
    ) {
        val stages = easuStageSizes
        check(stages.isNotEmpty()) { "No valid EASU stage for $sourceSize → $targetSize" }
        var inputTextureForStage = firstInputTexture
        var inputSize = sourceSize
        for (index in stages.indices) {
            val stageSize = stages[index]
            val finalStage = index == stages.lastIndex
            val (destinationTexture, destinationFramebuffer) = when {
                finalStage -> finalOutputTexture to finalOutputFramebuffer
                index % 2 == 0 -> easuIntermediateTextureA to easuIntermediateFramebufferA
                else -> easuIntermediateTextureB to easuIntermediateFramebufferB
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destinationFramebuffer)
            GLES30.glViewport(0, 0, stageSize.width, stageSize.height)
            val stageWindow = easuStageCpuSubmitWindows.getOrNull(index)
            if (stageWindow == null) {
                error = "EASU stage $index exceeds the per-pass telemetry capacity"
                return
            }
            measureCpuSubmit(stageWindow) {
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

                if (linearFinalScale && easuStageSizes.isNotEmpty()) {
                    strategyBaseTexture = createEmptyTexture(strategyBaseTarget, GLES30.GL_LINEAR)
                    strategyBaseFramebuffer = createFramebuffer(strategyBaseTexture)
                }

                val intermediateSizes = RenderPolicy.easuIntermediateSizesForStages(easuStageSizes)
                if (intermediateSizes.isNotEmpty()) {
                    easuIntermediateTextureA = createEmptyTexture(intermediateSizes[0], GLES30.GL_NEAREST)
                    easuIntermediateFramebufferA = createFramebuffer(easuIntermediateTextureA)
                    easuIntermediateSizeA = intermediateSizes[0]
                }
                if (intermediateSizes.size > 1) {
                    easuIntermediateTextureB = createEmptyTexture(intermediateSizes[1], GLES30.GL_NEAREST)
                    easuIntermediateFramebufferB = createFramebuffer(easuIntermediateTextureB)
                    easuIntermediateSizeB = intermediateSizes[1]
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

    private fun createScalingPlan(
        renderMode: RenderMode,
        source: PixelSize,
        geometry: VideoGeometry,
    ): FsrScalingPlan {
        fun plan(includeEasu: Boolean, includeRcas: Boolean) = FsrScalingPlanPolicy.create(
            strategy = scalingStrategy,
            source = source,
            outputSurface = surfaceSize,
            geometry = geometry,
            aspectMode = aspectMode,
            maxTextureSize = minOf(maxTextureSize, 4096),
            includeNoiseReduction = renderMode.usesNoiseReduction && includeEasu,
            includeEasu = includeEasu,
            includeRcas = includeRcas && includeEasu,
        )

        val candidate = plan(includeEasu = renderMode.usesEasu, includeRcas = renderMode.usesRcas)
        val canUpscale = source.width <= candidate.processingTarget.width &&
            source.height <= candidate.processingTarget.height &&
            (source.width < candidate.processingTarget.width || source.height < candidate.processingTarget.height)
        return if (renderMode.usesEasu && !canUpscale) {
            plan(includeEasu = false, includeRcas = false)
        } else {
            candidate
        }
    }

    private fun applyScalingPlan(plan: FsrScalingPlan) {
        effectiveScalingStrategy = plan.effectiveStrategy
        targetSize = plan.processingTarget
        strategyBaseTarget = plan.easuTarget
        easuStageSizes = plan.easuStages
        easuStageCount = easuStageSizes.size
        linearFinalScale = plan.linearFinalScale
        currentScalingPlan = plan
        appliedPlanRequestKey = renderPlanRequestKey(sourceSize, videoGeometry)
    }

    private fun renderPlanRequestKey(source: PixelSize, geometry: VideoGeometry) = RenderPlanRequestKey(
        source = source,
        geometry = geometry,
        surface = surfaceSize,
        mode = mode,
        strategy = scalingStrategy,
        aspectMode = aspectMode,
        maxTextureSize = maxTextureSize,
    )

    private fun queryCurrentEglSurfaceSize(): PixelSize? = runCatching {
        val display = EGL14.eglGetCurrentDisplay()
        val surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return null
        val width = IntArray(1)
        val height = IntArray(1)
        check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, width, 0)) {
            "eglQuerySurface(EGL_WIDTH) failed"
        }
        check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, height, 0)) {
            "eglQuerySurface(EGL_HEIGHT) failed"
        }
        PixelSize(width[0], height[0]).takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()

    @Synchronized
    private fun updateOutputResolutionValidation() {
        // A previous-size callback can arrive after a new request; it cannot decide
        // whether that request failed. The activity's deadline bounds this wait.
        if (surfaceSize != requestedSurfaceBufferSize) {
            native4kSurfaceVerified = false
            effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED
            return
        }
        val viewport = RenderPolicy.contentViewport(surfaceSize, videoGeometry, aspectMode)
        val targetMatchesOutput = targetSize == PixelSize(viewport.width, viewport.height)
        val fboComplete = !mode.usesEasu || (outputTexture != 0 && framebuffer != 0)
        val reason = OutputResolutionPolicy.outputBufferFailureReason(
            requestedSurfaceSize = requestedSurfaceBufferSize,
            surfaceCallbackSize = surfaceSize,
            eglSurfaceSize = eglSurfaceSize,
            limits = GlesOutputLimits(maxTextureSize, maxRenderbufferSize, maxViewportSize),
            processingTarget = targetSize,
            processingTargetAtOutputResolution = targetMatchesOutput,
            framebufferAllocationComplete = fboComplete,
            rgbaTextureBytes = snapshot().estimatedRgbaTextureBytes,
        )
        if (reason != null) {
            native4kSurfaceVerified = false
            effectiveOutputResolution = EffectiveOutputResolution.UNVERIFIED
            if (requestedSurfaceBufferSize == OutputResolutionPolicy.NATIVE_4K) {
                surfaceFallbackRequested = true
                if (!native4kFallbackLatched) {
                    native4kFallbackLatched = true
                    outputResolutionFallbackReason = reason
                    Log.w(TAG, "output_resolution_fallback requested=${requestedOutputResolution.id} fallback_buffer=1080p effective=unverified reason=$reason")
                }
            } else {
                surfaceFallbackRequested = false
                val previousReason = outputResolutionFallbackReason
                val fallbackValidationDetail = "1080P fallback buffer is unverified: $reason"
                outputResolutionFallbackReason = when {
                    previousReason.isNullOrBlank() || previousReason == reason -> reason
                    previousReason.contains(fallbackValidationDetail) -> previousReason
                    else -> "$previousReason; $fallbackValidationDetail"
                }
                Log.w(TAG, "output_resolution_unverified requested=${requestedOutputResolution.id} buffer=$requestedSurfaceBufferSize reason=$reason")
            }
        } else {
            native4kSurfaceVerified = requestedSurfaceBufferSize == OutputResolutionPolicy.NATIVE_4K
            effectiveOutputResolution = OutputResolutionPolicy.effectiveResolutionForBuffer(requestedSurfaceBufferSize, null)
            surfaceFallbackRequested = false
            outputResolutionFallbackReason = if (native4kSurfaceVerified) {
                null
            } else {
                OutputResolutionPolicy.fallbackReasonAfterVerifiedBuffer(
                    requestedOutputResolution = requestedOutputResolution,
                    existingReason = outputResolutionFallbackReason,
                )
            }
            Log.i(
                TAG,
                "output_resolution_verified requested=${requestedOutputResolution.id} effective=${effectiveOutputResolution.id} " +
                    "surface=$surfaceSize egl=$eglSurfaceSize target=$targetSize",
            )
        }
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
            strategyBaseFramebuffer,
        ).filter { it != 0 }.toIntArray()
        if (framebuffers.isNotEmpty()) GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        framebuffer = 0
        rcasFramebuffer = 0
        easuIntermediateFramebufferA = 0
        easuIntermediateFramebufferB = 0
        strategyBaseFramebuffer = 0

        val textures = intArrayOf(
            outputTexture,
            rcasTexture,
            easuIntermediateTextureA,
            easuIntermediateTextureB,
            strategyBaseTexture,
        ).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        outputTexture = 0
        rcasTexture = 0
        easuIntermediateTextureA = 0
        easuIntermediateTextureB = 0
        strategyBaseTexture = 0
        easuIntermediateSizeA = PixelSize(0, 0)
        easuIntermediateSizeB = PixelSize(0, 0)
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

        externalEasuValidated = false
        externalEasuRejected = false
        externalEasuValidationConsumed = false
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
                val callbackNs = System.nanoTime()
                lastVideoFrameCallbackNs.set(callbackNs)
                surfaceCallbackCadence.record(callbackNs)?.let { surfaceTextureCallbackFps = it }
                totalVideoFrameCallbacks.incrementAndGet()
                pendingVideoFrameCallbacks.incrementAndGet()
                frameAvailable = true
                if (frameRenderRequestGate.markFramePending()) requestRender()
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
        lastVideoFrameCallbackNs.set(0L)
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
        check(isDebuggableBuild) { "Pixel comparison is restricted to debug builds" }
        check(externalEasuAvailable && externalInputTexture != 0) { "External EASU input is unavailable" }
        check(sourceFramebuffer != 0 && inputTexture != 0 && framebuffer != 0) {
            "EASU comparison render targets are unavailable"
        }
        check(easuStageSizes.isNotEmpty()) { "EASU comparison requires at least one EASU stage" }
        val comparisonSize = if (linearFinalScale) strategyBaseTarget else targetSize
        val comparisonTexture = if (linearFinalScale) strategyBaseTexture else outputTexture
        val comparisonFramebuffer = if (linearFinalScale) strategyBaseFramebuffer else framebuffer
        drawEasuChain(
            inputTexture,
            useExternalInput = true,
            finalOutputTexture = comparisonTexture,
            finalOutputFramebuffer = comparisonFramebuffer,
        )
        val directOutput = readEasuOutputPixels(comparisonSize, comparisonFramebuffer)
        copyExternalVideoToRgba()
        drawEasuChain(
            inputTexture,
            useExternalInput = false,
            finalOutputTexture = comparisonTexture,
            finalOutputFramebuffer = comparisonFramebuffer,
        )
        val rgbaCopyOutput = readEasuOutputPixels(comparisonSize, comparisonFramebuffer)
        return EasuQualityComparison.compare(rgbaCopyOutput, directOutput)
    }

    private fun readEasuOutputPixels(size: PixelSize, destinationFramebuffer: Int): ByteArray {
        val byteCount = size.width * size.height * 4
        val pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destinationFramebuffer)
        GLES30.glReadPixels(0, 0, size.width, size.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
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
