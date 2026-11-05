package org.nightknight.scale.tv

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputSurfaceRaceTest {
    @Test
    fun snapshotDoesNotReadPartwayThroughLockedResolutionTransition() {
        val renderer = renderer()
        val writerEntered = java.util.concurrent.CountDownLatch(1)
        val finishWriter = java.util.concurrent.CountDownLatch(1)
        val readerStarted = java.util.concurrent.CountDownLatch(1)
        val readerDone = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<RendererSnapshot>()
        val writer = Thread {
            synchronized(renderer) {
                setField(renderer, "requestedOutputResolution", OutputResolutionMode.NATIVE_4K)
                writerEntered.countDown()
                check(finishWriter.await(5, java.util.concurrent.TimeUnit.SECONDS))
                setField(renderer, "outputResolutionFallbackReason", "new request")
            }
        }
        val reader = Thread {
            readerStarted.countDown()
            result.set(renderer.snapshot())
            readerDone.countDown()
        }
        writer.start()
        try {
            org.junit.Assert.assertTrue(writerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            reader.start()
            org.junit.Assert.assertTrue(readerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (reader.state != Thread.State.BLOCKED && readerDone.count != 0L && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("snapshot must wait for the resolution transition", Thread.State.BLOCKED, reader.state)
        } finally {
            finishWriter.countDown()
            writer.join(5000)
            if (reader.state != Thread.State.NEW) reader.join(5000)
        }
        assertEquals(OutputResolutionMode.NATIVE_4K, result.get().requestedOutputResolution)
        assertEquals("new request", result.get().outputResolutionFallbackReason)
    }

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

    @Test
    fun pausedTimeDoesNotConsumeSurfaceDeadline() {
        val deadline = OutputSurfaceDeadline()
        deadline.start(0L)
        deadline.pause(1_000L)
        deadline.resume(20_000L)
        assertFalse(deadline.expired(false, 24_000L))
        org.junit.Assert.assertTrue(deadline.expired(false, 24_001L))
    }

    @Test
    fun ordinary1080RequestGetsBoundedUnverifiedDiagnostic() {
        val deadline = OutputSurfaceDeadline()
        deadline.start(0L)
        assertFalse(shouldReportUnverified1080(deadline, EffectiveOutputResolution.UNVERIFIED, 5_000L))
        org.junit.Assert.assertTrue(shouldReportUnverified1080(deadline, EffectiveOutputResolution.UNVERIFIED, 5_001L))
        assertFalse(shouldReportUnverified1080(deadline, EffectiveOutputResolution.P1080, 5_001L))
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
