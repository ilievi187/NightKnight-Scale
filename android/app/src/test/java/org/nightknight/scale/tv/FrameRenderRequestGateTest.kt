package org.nightknight.scale.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRenderRequestGateTest {
    @Test
    fun multipleDecodedFramesCoalesceUntilTheRendererStarts() {
        val gate = FrameRenderRequestGate()
        assertTrue(gate.markFramePending())
        assertFalse(gate.markFramePending())
        assertFalse(gate.markFramePending())

        gate.onRenderStarted()

        assertTrue(gate.markFramePending())
    }

    @Test
    fun resettingTheGateAllowsAFrameAfterSurfaceRecreation() {
        val gate = FrameRenderRequestGate()
        assertTrue(gate.markFramePending())
        gate.reset()
        assertTrue(gate.markFramePending())
    }
}
