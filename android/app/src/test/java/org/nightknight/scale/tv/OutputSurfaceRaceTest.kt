package org.nightknight.scale.tv

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputSurfaceRaceTest {
    @Test
    fun validatedOldCadenceCannotRepublishAfterNew1080Request() {
        val renderer = renderer()
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "native4kSurfaceVerified", true)
        setField(renderer, "mode", RenderMode.FSR1)
        setField(renderer, "effectiveMode", RenderMode.FSR1)
        setField(renderer, "sourceVideoFps", 30f)
        setField(renderer, "averageFrameIntervalMs", 30f)
        repeat(4) { renderer.updateNative4kPerformanceValidation(true) }
        val policy = FsrDemoRenderer::class.java.getDeclaredField("native4kPerformanceValidationPolicy")
            .apply { isAccessible = true }.get(renderer) as Native4kPerformanceValidationPolicy
        assertFalse(policy.isValidated)
        assertFalse(renderer.snapshot().native4kPerformanceValidatedForAuto)

        val observed = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requestStarted = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        renderer.afterNative4kCadenceObservation = {
            check(policy.isValidated)
            observed.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val oldEvaluation = Thread { runCatching { renderer.updateNative4kPerformanceValidation(true) }.exceptionOrNull()?.let(failure::set) }
        val newRequest = Thread {
            requestStarted.countDown()
            runCatching { renderer.requestOutputResolution(OutputResolutionMode.P1080, OutputResolutionPolicy.FULL_HD) }
                .exceptionOrNull()?.let(failure::set)
        }
        oldEvaluation.start()
        try {
            check(observed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            newRequest.start()
            check(requestStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (newRequest.isAlive && newRequest.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("new request must wait for old observation and publication", Thread.State.BLOCKED, newRequest.state)
        } finally {
            release.countDown()
            oldEvaluation.join(5000)
            if (newRequest.state != Thread.State.NEW) newRequest.join(5000)
        }
        assertFalse(oldEvaluation.isAlive)
        assertFalse(newRequest.isAlive)
        failure.get()?.let { throw AssertionError("worker failed", it) }
        val state = renderer.snapshot()
        assertEquals(OutputResolutionMode.P1080, state.requestedOutputResolution)
        assertFalse(state.native4kPerformanceValidatedForAuto)
        assertFalse(policy.isValidated)
    }

    @Test
    fun cadenceObservationAndResetSerializeWithOutputRequests() {
        val renderer = renderer()
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { started.countDown(); renderer.updateNative4kPerformanceValidation(true) }
        synchronized(renderer) {
            worker.start()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("cadence observation must serialize with requests", Thread.State.BLOCKED, worker.state)
            renderer.requestOutputResolution(OutputResolutionMode.P1080, OutputResolutionPolicy.FULL_HD)
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertFalse(renderer.snapshot().native4kPerformanceValidatedForAuto)
    }

    @Test
    fun cadenceResetAlsoSerializesWithObservation() {
        val renderer = renderer()
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { started.countDown(); renderer.resetNative4kPerformanceValidation() }
        synchronized(renderer) {
            worker.start()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("cadence reset must serialize with observation", Thread.State.BLOCKED, worker.state)
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun contextInitializationDoesNotClearNewFallback() {
        val renderer = renderer()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        renderer.afterContextVerificationInvalidation = {
            check(!Thread.holdsLock(renderer))
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val worker = Thread { runCatching { renderer.onSurfaceCreated(null, null) } }
        worker.start()
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
            setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
            renderer.validateOutputResolutionForCurrentSurface()
            org.junit.Assert.assertTrue(renderer.snapshot().surfaceFallbackRequested)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        org.junit.Assert.assertTrue("new fallback must survive context initialization", renderer.snapshot().surfaceFallbackRequested)
        val latched = FsrDemoRenderer::class.java.getDeclaredField("native4kFallbackLatched").apply { isAccessible = true }.getBoolean(renderer)
        org.junit.Assert.assertTrue("new fallback latch must survive context initialization", latched)
    }
    @Test
    fun denoiseAllocationDoesNotHoldMonitorAndNewRequestWins() {
        val renderer = renderer()
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "eglSurfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "native4kSurfaceVerified", true)
        setField(renderer, "sourceVideoFps", 30f)
        setField(renderer, "lastFallbackSourceFps", 30f)
        setField(renderer, "averageFrameIntervalMs", 60f)
        setField(renderer, "mode", RenderMode.FSR1_DENOISE)
        setField(renderer, "sourceSize", OutputResolutionPolicy.FULL_HD)
        setField(renderer, "targetSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "easuProgram", 1)
        setField(renderer, "rcasProgram", 1)
        setField(renderer, "denoiseProgram", 1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean()
        renderer.beforeDenoiseAllocation = {
            held.set(Thread.holdsLock(renderer))
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val worker = Thread { renderer.updateRuntimeFallbackForFrameBudget() }
        worker.start()
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("GLES allocation must not hold renderer monitor", held.get())
            renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.FULL_HD, "new request")
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(renderer.snapshot().surfaceFallbackRequested)
        assertEquals("new request", renderer.snapshot().outputResolutionFallbackReason)
    }
    @Test
    fun equivalentAspectChangeRevalidatesExistingSurface() {
        val renderer = renderer()
        val geometry = VideoGeometry(1920, 1080)
        val source = OutputResolutionPolicy.FULL_HD
        val plan = FsrScalingPlanPolicy.create(
            FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER, source, source, geometry, maxTextureSize = 4096,
        )
        setField(renderer, "surfaceSize", source)
        setField(renderer, "eglSurfaceSize", source)
        setField(renderer, "sourceSize", source)
        setField(renderer, "videoGeometry", geometry)
        setField(renderer, "maxTextureSize", 4096)
        setField(renderer, "maxRenderbufferSize", 4096)
        setField(renderer, "maxViewportSize", PixelSize(4096, 4096))
        setField(renderer, "mode", RenderMode.BILINEAR)
        renderer.requestOutputResolution(OutputResolutionMode.P1080, source)
        renderer.applyScalingPlan(plan)
        renderer.setAspectRatioMode(AspectRatioMode.FIT_16_9)
        assertEquals(EffectiveOutputResolution.P1080, renderer.snapshot().effectiveOutputResolution)
    }

    @Test
    fun surfaceChangeInvalidationResetsCadencePolicy() {
        val renderer = renderer()
        val policy = FsrDemoRenderer::class.java.getDeclaredField("native4kPerformanceValidationPolicy")
            .apply { isAccessible = true }.get(renderer) as Native4kPerformanceValidationPolicy
        repeat(5) { policy.observe(true, true, 30f, 30f) }
        setField(renderer, "maxTextureSize", 0)
        renderer.onSurfaceChanged(null, 1920, 1080)
        assertFalse(policy.isValidated)
    }

    @Test
    fun changedScalingPlanAllowsFreshCadenceValidation() {
        val renderer = renderer()
        val policy = FsrDemoRenderer::class.java.getDeclaredField("native4kPerformanceValidationPolicy")
            .apply { isAccessible = true }.get(renderer) as Native4kPerformanceValidationPolicy
        repeat(5) { policy.observe(true, true, 30f, 30f) }
        org.junit.Assert.assertTrue(policy.isValidated)
        val plan = FsrScalingPlanPolicy.create(
            FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER,
            OutputResolutionPolicy.FULL_HD, OutputResolutionPolicy.NATIVE_4K,
            VideoGeometry(1920, 1080), maxTextureSize = 4096,
        )
        renderer.applyScalingPlan(plan)
        assertFalse(policy.isValidated)
        repeat(4) { assertFalse(policy.observe(true, true, 30f, 30f)) }
        org.junit.Assert.assertTrue(policy.observe(true, true, 30f, 30f))
    }

    @Test
    fun contextCreationInvalidatesVerificationEvenIfGlInitializationFails() {
        val renderer = renderer()
        setField(renderer, "effectiveOutputResolution", EffectiveOutputResolution.P1080)
        setField(renderer, "native4kSurfaceVerified", true)
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { started.countDown(); runCatching { renderer.onSurfaceCreated(null, null) } }
        synchronized(renderer) {
            worker.start()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, worker.state)
        }
        worker.join(5000)
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
        assertFalse(renderer.snapshot().native4kSurfaceVerified)
    }

    @Test
    fun applyingChangedTargetInvalidatesPreviousVerification() {
        val renderer = renderer()
        val plan = FsrScalingPlanPolicy.create(
            FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER,
            OutputResolutionPolicy.FULL_HD, OutputResolutionPolicy.NATIVE_4K,
            VideoGeometry(1920, 1080), maxTextureSize = 4096,
        )
        setField(renderer, "targetSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "effectiveOutputResolution", EffectiveOutputResolution.P1080)
        setField(renderer, "native4kSurfaceVerified", true)
        renderer.applyScalingPlan(plan)
        assertEquals(plan.processingTarget, renderer.snapshot().processedTarget)
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
        assertFalse(renderer.snapshot().native4kSurfaceVerified)
    }

    @Test
    fun eglRecheckPublishesUnavailableSurfaceAsUnverified() {
        val renderer = renderer()
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "eglSurfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "effectiveOutputResolution", EffectiveOutputResolution.P1080)
        setField(renderer, "native4kSurfaceVerified", true)
        renderer.validateOutputResolutionForCurrentSurface()
        val state = renderer.snapshot()
        assertEquals(null, state.eglSurface)
        assertEquals(EffectiveOutputResolution.UNVERIFIED, state.effectiveOutputResolution)
        assertFalse(state.native4kSurfaceVerified)
    }

    @Test
    fun eglPublicationSerializesWithSnapshots() {
        val renderer = renderer()
        setField(renderer, "eglSurfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "effectiveOutputResolution", EffectiveOutputResolution.P1080)
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { started.countDown(); renderer.publishEglSurfaceSize(null) }
        synchronized(renderer) {
            worker.start()
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, worker.state)
            assertEquals(OutputResolutionPolicy.NATIVE_4K, renderer.snapshot().eglSurface)
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(null, renderer.snapshot().eglSurface)
        assertEquals(EffectiveOutputResolution.UNVERIFIED, renderer.snapshot().effectiveOutputResolution)
    }

    @Test
    fun performanceFallbackCannotRaceANewOutputRequest() {
        val renderer = renderer()
        renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "eglSurfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "native4kSurfaceVerified", true)
        setField(renderer, "sourceVideoFps", 30f)
        setField(renderer, "lastFallbackSourceFps", 30f)
        setField(renderer, "averageFrameIntervalMs", 60f)
        setField(renderer, "runtimeEffectiveMode", RenderMode.BILINEAR)
        val policy = FsrDemoRenderer::class.java.getDeclaredField("native4kResolutionFallbackPolicy").apply { isAccessible = true }.get(renderer) as Native4kResolutionFallbackPolicy
        policy.observe(true, RenderMode.BILINEAR, true, 30f, 60f)
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { started.countDown(); renderer.updateRuntimeFallbackForFrameBudget() }
        try {
            synchronized(renderer) {
                worker.start()
                check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("fallback evaluation must serialize with output requests", Thread.State.BLOCKED, worker.state)
                renderer.requestOutputResolution(OutputResolutionMode.NATIVE_4K, OutputResolutionPolicy.FULL_HD, "new request")
            }
        } finally {
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(renderer.snapshot().surfaceFallbackRequested)
        assertEquals("new request", renderer.snapshot().outputResolutionFallbackReason)
    }

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
    fun surfaceChangeInvalidatesPreviousVerificationBeforeInvalidTargetReturns() {
        val renderer = renderer()
        setField(renderer, "surfaceSize", OutputResolutionPolicy.NATIVE_4K)
        setField(renderer, "effectiveOutputResolution", EffectiveOutputResolution.P1080)
        setField(renderer, "native4kSurfaceVerified", true)
        setField(renderer, "maxTextureSize", 0)
        renderer.onSurfaceChanged(null, 1920, 1080)
        val state = renderer.snapshot()
        assertEquals(EffectiveOutputResolution.UNVERIFIED, state.effectiveOutputResolution)
        assertFalse(state.native4kSurfaceVerified)
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
