package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPerformancePolicyTest {
    @Test
    fun qualityFallsBackOneProcessingStepAfterTwoSustainedOverBudgetWindows() {
        val policy = RuntimeRenderFallbackPolicy()

        assertNull(policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = 30f, averageRendererIntervalMs = 40f))
        assertEquals(
            RenderMode.FSR1,
            policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = 30f, averageRendererIntervalMs = 40f),
        )
        assertNull(policy.observe(RenderMode.FSR1, sourceVideoFps = 30f, averageRendererIntervalMs = 40f))
        assertEquals(
            RenderMode.EASU_ONLY,
            policy.observe(RenderMode.FSR1, sourceVideoFps = 30f, averageRendererIntervalMs = 40f),
        )
        assertNull(policy.observe(RenderMode.BILINEAR, sourceVideoFps = 30f, averageRendererIntervalMs = 100f))
    }

    @Test
    fun withinBudgetWindowResetsTheConsecutiveOverBudgetCount() {
        val policy = RuntimeRenderFallbackPolicy()

        assertNull(policy.observe(RenderMode.FSR1_DENOISE, 30f, 40f))
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, 30f, 35f))
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, 30f, 40f))
        assertEquals(RenderMode.FSR1, policy.observe(RenderMode.FSR1_DENOISE, 30f, 40f))
    }

    @Test
    fun unknownOrInvalidSourceRateNeverTriggersAnAutomaticFallback() {
        val policy = RuntimeRenderFallbackPolicy()
        repeat(5) {
            assertNull(policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = null, averageRendererIntervalMs = 500f))
        }
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = 0f, averageRendererIntervalMs = 500f))
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = Float.NaN, averageRendererIntervalMs = 500f))
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, sourceVideoFps = 30f, averageRendererIntervalMs = Float.NaN))
    }

    @Test
    fun resetClearsFallbackHistoryAfterUserChangesPreset() {
        val policy = RuntimeRenderFallbackPolicy()
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, 30f, 40f))
        policy.reset()
        assertNull(policy.observe(RenderMode.FSR1_DENOISE, 30f, 40f))
    }

    @Test
    fun cpuSubmitWindowReportsAverageAndMaximumWithoutBlockingForGpuCompletion() {
        val window = CpuSubmitWindow()
        window.record(2_000_000L)
        window.record(4_000_000L)
        window.record(1_000_000L)
        window.record(-1L)

        val sample = window.drain()
        assertEquals(3, sample?.samples)
        assertEquals(2.3333f, sample?.averageMs ?: 0f, 0.001f)
        assertEquals(4f, sample?.maximumMs ?: 0f, 0.0001f)
        assertNull(window.drain())
    }

    @Test
    fun submitWindowDoesNotCreateSamplesForZeroDurations() {
        val window = CpuSubmitWindow()
        window.record(0L)
        assertNull(window.drain())
        assertTrue(window.drain() == null)
    }
}
