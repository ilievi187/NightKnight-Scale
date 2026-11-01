package org.nightknight.scale.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasuExternalInputPolicyTest {
    @Test
    fun compiledButUnvalidatedExternalInputStaysDisabled() {
        assertFalse(
            EasuExternalInputPolicy.shouldUseDirectInput(
                programAvailable = true,
                outputEquivalent = false,
                externalTextureAvailable = true,
                mode = RenderMode.FSR1,
            ),
        )
    }

    @Test
    fun equivalentValidatedInputCanServeEasuModesWithoutNoiseReduction() {
        for (mode in listOf(RenderMode.EASU_ONLY, RenderMode.FSR1)) {
            assertTrue(
                EasuExternalInputPolicy.shouldUseDirectInput(
                    programAvailable = true,
                    outputEquivalent = true,
                    externalTextureAvailable = true,
                    mode = mode,
                ),
            )
        }
    }

    @Test
    fun unavailableResourcesAndNoiseReductionModeRejectDirectInput() {
        val modes = RenderMode.entries
        for (mode in modes) {
            assertFalse(
                EasuExternalInputPolicy.shouldUseDirectInput(
                    programAvailable = false,
                    outputEquivalent = true,
                    externalTextureAvailable = true,
                    mode = mode,
                ),
            )
            assertFalse(
                EasuExternalInputPolicy.shouldUseDirectInput(
                    programAvailable = true,
                    outputEquivalent = true,
                    externalTextureAvailable = false,
                    mode = mode,
                ),
            )
        }
        assertFalse(
            EasuExternalInputPolicy.shouldUseDirectInput(
                programAvailable = true,
                outputEquivalent = true,
                externalTextureAvailable = true,
                mode = RenderMode.FSR1_DENOISE,
            ),
        )
    }
}
