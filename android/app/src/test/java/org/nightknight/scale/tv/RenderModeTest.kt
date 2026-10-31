package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderModeTest {
    @Test
    fun remoteAlgorithmCycleIncludesEasuFsrAndDenoiseVariants() {
        assertEquals(RenderMode.EASU_ONLY, RenderMode.BILINEAR.nextAlgorithm())
        assertEquals(RenderMode.FSR1, RenderMode.EASU_ONLY.nextAlgorithm())
        assertEquals(RenderMode.FSR1_DENOISE, RenderMode.FSR1.nextAlgorithm())
        assertEquals(RenderMode.EASU_ONLY, RenderMode.FSR1_DENOISE.nextAlgorithm())
    }

    @Test
    fun modesSelectTheExpectedPipelineStages() {
        assertFalse(RenderMode.BILINEAR.usesEasu)
        assertFalse(RenderMode.EASU_ONLY.usesRcas)
        assertTrue(RenderMode.FSR1.usesEasu && RenderMode.FSR1.usesRcas)
        assertTrue(RenderMode.FSR1_DENOISE.usesNoiseReduction)
    }

    @Test
    fun denoiseDefaultIsMildAndBounded() {
        assertEquals(0.35f, FilterPolicy.DEFAULT_DENOISE_STRENGTH, 0.0001f)
        assertTrue(FilterPolicy.DEFAULT_DENOISE_STRENGTH in 0.0f..1.0f)
    }
}
