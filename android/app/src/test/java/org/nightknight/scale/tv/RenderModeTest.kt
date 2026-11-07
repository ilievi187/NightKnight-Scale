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
    fun namedPresetsSelectDifferentRealRenderPipelines() {
        assertEquals(RenderMode.BILINEAR, UpscalePreset.OFF.renderMode)
        assertEquals(RenderMode.FSR1_DENOISE, UpscalePreset.QUALITY.renderMode)
        assertEquals(RenderMode.FSR1, UpscalePreset.BALANCED.renderMode)
        assertEquals(RenderMode.EASU_ONLY, UpscalePreset.PERFORMANCE.renderMode)
        assertEquals(4, UpscalePreset.entries.map { it.renderMode }.toSet().size)
    }

    @Test
    fun presetIdsRoundTripAndUnknownValuesFallBackToBalanced() {
        UpscalePreset.entries.forEach { preset ->
            assertEquals(preset, UpscalePreset.fromId(preset.id))
        }
        assertEquals(UpscalePreset.BALANCED, UpscalePreset.fromId("unknown"))
        assertEquals(UpscalePreset.BALANCED, UpscalePreset.fromId(null))
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
