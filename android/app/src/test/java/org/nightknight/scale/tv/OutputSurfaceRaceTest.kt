package org.nightknight.scale.tv

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputSurfaceRaceTest {
    @Test
    fun old1080SurfaceDoesNotRequestFallbackWhile4kResizeIsPending() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo()
        }
        val renderer = FsrDemoRenderer(context, { _, _ -> })
        setField(renderer, "surfaceSize", OutputResolutionPolicy.FULL_HD)
        setField(renderer, "eglSurfaceSize", OutputResolutionPolicy.FULL_HD)
        setField(renderer, "targetSize", OutputResolutionPolicy.FULL_HD)
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        renderer.validateOutputResolutionForCurrentSurface()
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
        assertFalse(renderer.snapshot().surfaceFallbackRequested)
    }

    @Test
    fun matching4kCallbackStillFailsWhenEglIsUnavailable() {
        val renderer = renderer()
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "targetSize", OutputResolutionPolicy.NATIVE_4K)
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        renderer.validateOutputResolutionForCurrentSurface()
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
        org.junit.Assert.assertTrue(renderer.snapshot().surfaceFallbackRequested)
        org.junit.Assert.assertTrue(renderer.snapshot().outputResolutionFallbackReason.orEmpty().contains("EGL"))
    }

    @Test
    fun late4kCallbackAfter1080RequestCannotReport4kOrRequest4kFallback() {
        val renderer = renderer()
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.FULL_HD, "timed out")
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        renderer.validateOutputResolutionForCurrentSurface()
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
        assertFalse(renderer.snapshot().surfaceFallbackRequested)
        assertEquals("timed out", renderer.snapshot().outputResolutionFallbackReason)
    }

    @Test
    fun deadlineDoesNotResetWhenAnOldSizeCallbackArrives() {
        assertFalse(shouldTimeoutNative4kSurface(OutputResolutionPolicy.NATIVE_4K, false, false, 4_999L))
        org.junit.Assert.assertTrue(shouldTimeoutNative4kSurface(OutputResolutionPolicy.NATIVE_4K, false, false, 5_001L))
        assertFalse(shouldTimeoutNative4kSurface(OutputResolutionPolicy.NATIVE_4K, true, false, 5_001L))
        assertFalse(shouldTimeoutNative4kSurface(OutputResolutionPolicy.FULL_HD, false, false, 5_001L))
        assertFalse(shouldTimeoutNative4kSurface(OutputResolutionPolicy.NATIVE_4K, false, true, 5_001L))
    }

    @Test
    fun verificationLossGetsFreshDeadlineButRepeatedMismatchDoesNotExtendIt() {
        val deadline = OutputSurfaceDeadline()
        deadline.start(0L)
        assertFalse(deadline.expired(true, 10_000L))
        assertFalse(deadline.expired(false, 10_001L))
        assertFalse(deadline.expired(false, 15_000L))
        org.junit.Assert.assertTrue(deadline.expired(false, 15_002L))
    }

    @Test
    fun fallbackWaitIsBoundedEvenAfterLate4kCallback() {
        val deadline = OutputSurfaceDeadline()
        deadline.start(1_000L)
        assertFalse(deadline.expired(false, 6_000L))
        org.junit.Assert.assertTrue(deadline.expired(false, 6_001L))
    }

    private fun renderer(): FsrDemoRenderer {
        val context = object : ContextWrapper(null) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo()
        }
        return FsrDemoRenderer(context, { _, _ -> })
    }

    private fun setField(renderer: FsrDemoRenderer, name: String, value: Any) {
        FsrDemoRenderer::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(renderer, value)
        }
    }
}
