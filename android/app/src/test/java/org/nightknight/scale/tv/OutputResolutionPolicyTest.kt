package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputResolutionPolicyTest {
    private val fourK = PixelSize(3840, 2160)
    private val fullHd = PixelSize(1920, 1080)
    private val capableLimits = GlesOutputLimits(
        maxTextureSize = 4096,
        maxRenderbufferSize = 4096,
        maxViewportSize = PixelSize(4096, 4096),
    )

    @Test
    fun autoUses1080pUntilNative4kHasAValidatedDeviceProfile() {
        assertEquals(
            fullHd,
            OutputResolutionPolicy.requestedSurfaceSize(
                requested = OutputResolutionMode.AUTO,
                currentDisplayMode = fourK,
                supportedDisplayModes = setOf(fourK),
                native4kPerformanceValidated = false,
            ),
        )
    }

    @Test
    fun autoSelects4kOnlyWhenModeAndPerformanceProfileAreBothValidated() {
        assertEquals(
            fourK,
            OutputResolutionPolicy.requestedSurfaceSize(
                requested = OutputResolutionMode.AUTO,
                currentDisplayMode = fourK,
                supportedDisplayModes = setOf(fullHd, fourK),
                native4kPerformanceValidated = true,
            ),
        )
    }

    @Test
    fun autoDoesNotSelect4kWhenTheDisplayDoesNotReportA4kMode() {
        assertEquals(
            fullHd,
            OutputResolutionPolicy.requestedSurfaceSize(
                requested = OutputResolutionMode.AUTO,
                currentDisplayMode = fullHd,
                supportedDisplayModes = setOf(fullHd, fourK),
                native4kPerformanceValidated = true,
            ),
        )
    }

    @Test
    fun explicit1080pAndNative4kModesRequestExactBufferDimensions() {
        assertEquals(
            fullHd,
            OutputResolutionPolicy.requestedSurfaceSize(
                OutputResolutionMode.P1080,
                currentDisplayMode = fourK,
                supportedDisplayModes = setOf(fourK),
                native4kPerformanceValidated = true,
            ),
        )
        assertEquals(
            fourK,
            OutputResolutionPolicy.requestedSurfaceSize(
                OutputResolutionMode.NATIVE_4K,
                currentDisplayMode = fullHd,
                supportedDisplayModes = emptySet(),
                native4kPerformanceValidated = false,
            ),
        )
    }

    @Test
    fun fullHdRequestValidatesCallbackAndEglDimensionsBeforeReportingSuccess() {
        val valid = OutputResolutionPolicy.outputBufferFailureReason(
            requestedSurfaceSize = fullHd,
            surfaceCallbackSize = fullHd,
            eglSurfaceSize = fullHd,
            limits = capableLimits,
            processingTarget = fullHd,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 20_000_000L,
        )
        val callbackMismatch = OutputResolutionPolicy.outputBufferFailureReason(
            requestedSurfaceSize = fullHd,
            surfaceCallbackSize = PixelSize(1280, 720),
            eglSurfaceSize = PixelSize(1280, 720),
            limits = capableLimits,
            processingTarget = PixelSize(1280, 720),
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 20_000_000L,
        )
        val eglMismatch = OutputResolutionPolicy.outputBufferFailureReason(
            requestedSurfaceSize = fullHd,
            surfaceCallbackSize = fullHd,
            eglSurfaceSize = PixelSize(1280, 720),
            limits = capableLimits,
            processingTarget = fullHd,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 20_000_000L,
        )
        assertNull(valid)
        assertTrue(callbackMismatch.orEmpty().contains("callback"))
        assertTrue(eglMismatch.orEmpty().contains("EGL"))
    }

    @Test
    fun unvalidatedSurfaceBufferCannotBeReportedAsAnEffectiveResolution() {
        assertEquals(
            EffectiveOutputResolution.UNVERIFIED,
            OutputResolutionPolicy.effectiveResolutionForBuffer(fullHd, "EGL size mismatch"),
        )
        assertEquals(
            EffectiveOutputResolution.P1080,
            OutputResolutionPolicy.effectiveResolutionForBuffer(fullHd, null),
        )
        assertEquals(
            EffectiveOutputResolution.NATIVE_4K,
            OutputResolutionPolicy.effectiveResolutionForBuffer(fourK, null),
        )
    }

    @Test
    fun anUnchangedCallbackSizeCanBeValidatedWithoutWaitingForAnotherSurfaceChangedEvent() {
        assertTrue(OutputResolutionPolicy.canValidateCurrentSurfaceImmediately(fullHd, fullHd))
        assertTrue(OutputResolutionPolicy.canValidateCurrentSurfaceImmediately(fourK, fourK))
        assertFalse(OutputResolutionPolicy.canValidateCurrentSurfaceImmediately(fullHd, fourK))
    }

    @Test
    fun a4kDisplayLabelAloneDoesNotValidateANativeEglSurface() {
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fullHd,
            eglSurfaceSize = fullHd,
            limits = capableLimits,
            processingTarget = fullHd,
            processingTargetAtOutputResolution = false,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertTrue(reason.orEmpty().contains("surface"))
    }

    @Test
    fun missingEglQueryIsNotReportedAsVerifiedNative4k() {
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = null,
            limits = capableLimits,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertTrue(reason.orEmpty().contains("EGL"))
    }

    @Test
    fun native4kRequiresProcessingTextureAndViewportLimits() {
        val insufficient = GlesOutputLimits(
            maxTextureSize = 2048,
            maxRenderbufferSize = 2048,
            maxViewportSize = PixelSize(2048, 2048),
        )
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = insufficient,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertTrue(reason.orEmpty().contains("GLES"))
    }

    @Test
    fun textureBackedNative4kFramebufferDoesNotRequireRenderbufferLimit() {
        val textureOnlyLimits = GlesOutputLimits(
            maxTextureSize = 4096,
            maxRenderbufferSize = 2048,
            maxViewportSize = PixelSize(4096, 4096),
        )
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = textureOnlyLimits,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertNull(reason)
    }

    @Test
    fun aspectFitNative4kTargetCanUseItsActualTextureLimitInsteadOfFullSurfaceWidth() {
        val aspectFitLimits = GlesOutputLimits(
            maxTextureSize = 3072,
            maxRenderbufferSize = 3072,
            maxViewportSize = PixelSize(4096, 4096),
        )
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = aspectFitLimits,
            processingTarget = PixelSize(2880, 2160),
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertNull(reason)
    }

    @Test
    fun native4kRejectsIncompleteFramebufferAndOverBudgetWorkingSet() {
        val incomplete = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = capableLimits,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = false,
            rgbaTextureBytes = 44_582_400L,
        )
        val overBudget = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = capableLimits,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = RenderPolicy.MAX_RGBA_TEXTURE_BYTES + 1,
        )
        assertTrue(incomplete.orEmpty().contains("framebuffer"))
        assertTrue(overBudget.orEmpty().contains("budget"))
    }

    @Test
    fun onlyFullyValidatedSurfaceTargetAndFboPassesNative4kGate() {
        val reason = OutputResolutionPolicy.native4kFailureReason(
            surfaceCallbackSize = fourK,
            eglSurfaceSize = fourK,
            limits = capableLimits,
            processingTarget = fourK,
            processingTargetAtOutputResolution = true,
            framebufferAllocationComplete = true,
            rgbaTextureBytes = 44_582_400L,
        )
        assertNull(reason)
    }

    @Test
    fun outputResolutionSelectorCyclesInTheDocumentedOrder() {
        assertEquals(OutputResolutionMode.P1080, OutputResolutionMode.AUTO.next())
        assertEquals(OutputResolutionMode.NATIVE_4K, OutputResolutionMode.P1080.next())
        assertEquals(OutputResolutionMode.AUTO, OutputResolutionMode.NATIVE_4K.next())
    }
}
