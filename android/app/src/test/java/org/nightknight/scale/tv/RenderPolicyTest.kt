package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPolicyTest {
    @Test
    fun halfResolutionTestSourceIsAtMostFourTimesSmallerByArea() {
        assertEquals(PixelSize(960, 540), RenderPolicy.testSourceFor(PixelSize(1920, 1080)))
        assertEquals(PixelSize(1920, 1080), RenderPolicy.testSourceFor(PixelSize(3840, 2160)))
    }

    @Test
    fun tinySurfaceStillGetsNonZeroTestSource() {
        assertEquals(PixelSize(1, 1), RenderPolicy.testSourceFor(PixelSize(1, 1)))
    }

    @Test
    fun renderTargetRespectsGlesLimitAndKeepsAspectRatio() {
        assertEquals(PixelSize(4096, 2304), RenderPolicy.targetFor(PixelSize(7680, 4320), 4096))
        assertEquals(PixelSize(1920, 1080), RenderPolicy.targetFor(PixelSize(1920, 1080), 4096))
    }

    @Test
    fun sharpnessSliderUsesFsrStopsInReverseStrengthOrder() {
        assertEquals(1.0f, RenderPolicy.rcasScaleFor(0), 0.0001f)
        assertEquals(0.03125f, RenderPolicy.rcasScaleFor(20), 0.0001f)
        assertEquals(1.0f, RenderPolicy.rcasScaleFor(-10), 0.0001f)
        assertEquals(0.03125f, RenderPolicy.rcasScaleFor(30), 0.0001f)
    }
}
