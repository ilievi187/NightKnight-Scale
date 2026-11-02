package org.nightknight.scale.tv

internal enum class OutputResolutionMode(val id: String, val label: String) {
    AUTO("auto", "AUTO"),
    P1080("1080p", "1080P"),
    NATIVE_4K("native_4k", "NATIVE 4K");

    fun next(): OutputResolutionMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): OutputResolutionMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

internal enum class EffectiveOutputResolution(val id: String, val label: String) {
    UNVERIFIED("unverified", "UNVERIFIED"),
    P1080("1080p", "1080P"),
    NATIVE_4K("native_4k", "NATIVE 4K");
}

internal data class GlesOutputLimits(
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val maxViewportSize: PixelSize,
)

/**
 * Selects an EGL window-buffer request and validates output from measured surface/GLES evidence.
 * Display-mode labels alone never establish that a 4K rendering surface exists.
 */
internal object OutputResolutionPolicy {
    val FULL_HD = PixelSize(1920, 1080)
    val NATIVE_4K = PixelSize(3840, 2160)

    fun requestedSurfaceSize(
        requested: OutputResolutionMode,
        currentDisplayMode: PixelSize?,
        supportedDisplayModes: Set<PixelSize>,
        native4kPerformanceValidated: Boolean,
    ): PixelSize = when (requested) {
        OutputResolutionMode.P1080 -> FULL_HD
        OutputResolutionMode.NATIVE_4K -> NATIVE_4K
        OutputResolutionMode.AUTO -> {
            val displayReportsNative4k = currentDisplayMode == NATIVE_4K && NATIVE_4K in supportedDisplayModes
            if (displayReportsNative4k && native4kPerformanceValidated) NATIVE_4K else FULL_HD
        }
    }

    fun canValidateCurrentSurfaceImmediately(requestedSurfaceSize: PixelSize, currentSurfaceSize: PixelSize): Boolean =
        requestedSurfaceSize == currentSurfaceSize

    fun effectiveResolutionForBuffer(
        bufferSize: PixelSize,
        validationFailureReason: String?,
    ): EffectiveOutputResolution {
        if (validationFailureReason != null) return EffectiveOutputResolution.UNVERIFIED
        return when (bufferSize) {
            FULL_HD -> EffectiveOutputResolution.P1080
            NATIVE_4K -> EffectiveOutputResolution.NATIVE_4K
            else -> EffectiveOutputResolution.UNVERIFIED
        }
    }

    fun outputBufferFailureReason(
        requestedSurfaceSize: PixelSize,
        surfaceCallbackSize: PixelSize,
        eglSurfaceSize: PixelSize?,
        limits: GlesOutputLimits,
        processingTarget: PixelSize,
        processingTargetAtOutputResolution: Boolean,
        framebufferAllocationComplete: Boolean,
        rgbaTextureBytes: Long,
    ): String? {
        if (surfaceCallbackSize != requestedSurfaceSize) {
            return "GL surface callback reported $surfaceCallbackSize; requested buffer $requestedSurfaceSize was not reached"
        }
        if (eglSurfaceSize == null) {
            return "EGL window-surface dimensions unavailable; requested buffer $requestedSurfaceSize cannot be verified"
        }
        if (eglSurfaceSize != requestedSurfaceSize) {
            return "EGL window surface is $eglSurfaceSize, not requested buffer $requestedSurfaceSize"
        }
        if (processingTarget.width <= 0 || processingTarget.height <= 0 ||
            limits.maxTextureSize < processingTarget.width ||
            limits.maxTextureSize < processingTarget.height ||
            limits.maxViewportSize.width < surfaceCallbackSize.width ||
            limits.maxViewportSize.height < surfaceCallbackSize.height
        ) {
            return "GLES texture/viewport limits do not support processing target $processingTarget and window surface $surfaceCallbackSize"
        }
        if (!framebufferAllocationComplete) {
            return "Processing framebuffer is incomplete for requested buffer $requestedSurfaceSize"
        }
        if (rgbaTextureBytes > RenderPolicy.MAX_RGBA_TEXTURE_BYTES) {
            return "RGBA working set for requested buffer $requestedSurfaceSize exceeds the ${RenderPolicy.MAX_RGBA_TEXTURE_BYTES}-byte soft budget"
        }
        if (!processingTargetAtOutputResolution) {
            return "FSR processing target $processingTarget was reduced below the aspect-fit output size for $requestedSurfaceSize"
        }
        return null
    }

    /**
     * Clears transient fallback-buffer validation errors after a later callback/EGL pair verifies 1080P,
     * while retaining the original AUTO/native-4K decision reason.
     */
    fun fallbackReasonAfterVerifiedBuffer(
        requestedOutputResolution: OutputResolutionMode,
        existingReason: String?,
    ): String? {
        val reason = existingReason?.takeIf { it.isNotBlank() } ?: return null
        val transientFallbackMarker = "; 1080P fallback buffer is unverified:"
        val transientMarkerIndex = reason.indexOf(transientFallbackMarker)
        if (transientMarkerIndex >= 0) {
            return reason.substring(0, transientMarkerIndex).trim().takeIf { it.isNotEmpty() }
        }
        return when (requestedOutputResolution) {
            OutputResolutionMode.P1080 -> null
            OutputResolutionMode.AUTO,
            OutputResolutionMode.NATIVE_4K,
            -> reason
        }
    }

    /** Returns a visible failure reason, or null only when the 4K surface and active target validate. */
    fun native4kFailureReason(
        surfaceCallbackSize: PixelSize,
        eglSurfaceSize: PixelSize?,
        limits: GlesOutputLimits,
        processingTarget: PixelSize,
        processingTargetAtOutputResolution: Boolean,
        framebufferAllocationComplete: Boolean,
        rgbaTextureBytes: Long,
    ): String? = outputBufferFailureReason(
        requestedSurfaceSize = NATIVE_4K,
        surfaceCallbackSize = surfaceCallbackSize,
        eglSurfaceSize = eglSurfaceSize,
        limits = limits,
        processingTarget = processingTarget,
        processingTargetAtOutputResolution = processingTargetAtOutputResolution,
        framebufferAllocationComplete = framebufferAllocationComplete,
        rgbaTextureBytes = rgbaTextureBytes,
    )
}
