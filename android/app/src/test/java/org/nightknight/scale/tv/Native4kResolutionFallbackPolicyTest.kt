package org.nightknight.scale.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Native4kResolutionFallbackPolicyTest {
    @Test
    fun unverifiedSurfaceCannotAccumulateNative4kBufferFallbackWindows() {
        val policy = Native4kResolutionFallbackPolicy(consecutiveOverBudgetWindows = 2)
        assertFalse(
            policy.observe(
                native4kSurfaceVerified = false,
                currentMode = RenderMode.BILINEAR,
                sourceActive = true,
                sourceVideoFps = 30f,
                averageRendererIntervalMs = 40f,
            ),
        )
        assertFalse(
            policy.observe(
                native4kSurfaceVerified = false,
                currentMode = RenderMode.BILINEAR,
                sourceActive = true,
                sourceVideoFps = 30f,
                averageRendererIntervalMs = 40f,
            ),
        )
        assertFalse(
            policy.observe(
                native4kSurfaceVerified = true,
                currentMode = RenderMode.BILINEAR,
                sourceActive = true,
                sourceVideoFps = 30f,
                averageRendererIntervalMs = 40f,
            ),
        )
        assertTrue(
            policy.observe(
                native4kSurfaceVerified = true,
                currentMode = RenderMode.BILINEAR,
                sourceActive = true,
                sourceVideoFps = 30f,
                averageRendererIntervalMs = 40f,
            ),
        )
    }

    @Test
    fun lowersTheNative4kSurfaceOnlyAfterRepeatedActiveOverBudgetWindows() {
        val policy = Native4kResolutionFallbackPolicy(consecutiveOverBudgetWindows = 2)
        assertFalse(policy.observe(true, RenderMode.FSR1, true, 30f, 40f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, false, 30f, 40f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, 40f))
        assertTrue(policy.observe(true, RenderMode.BILINEAR, true, 30f, 40f))
    }

    @Test
    fun noFallbackWithoutNative4kOrKnownActiveSourceRate() {
        val policy = Native4kResolutionFallbackPolicy(consecutiveOverBudgetWindows = 1)
        assertFalse(policy.observe(false, RenderMode.BILINEAR, true, 30f, 60f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, false, 30f, 60f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, null, 60f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, null))
    }

    @Test
    fun withinBudgetWindowClearsTheOverloadHysteresis() {
        val policy = Native4kResolutionFallbackPolicy(consecutiveOverBudgetWindows = 2)
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, 40f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, 30f))
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, 40f))
        assertTrue(policy.observe(true, RenderMode.BILINEAR, true, 30f, 40f))
    }

    @Test
    fun fourKBufferFallbackUsesTheSameNearThirtyFpsBudget() {
        val policy = Native4kResolutionFallbackPolicy(consecutiveOverBudgetWindows = 2)
        assertFalse(policy.observe(true, RenderMode.BILINEAR, true, 30f, 35f))
        assertTrue(policy.observe(true, RenderMode.BILINEAR, true, 30f, 35f))
    }
}
