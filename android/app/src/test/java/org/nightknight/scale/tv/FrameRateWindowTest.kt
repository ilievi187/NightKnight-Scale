package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateWindowTest {
    @Test
    fun computesCadenceFromTimestampIntervalsInsteadOfCountingFramesAsIntervals() {
        val window = FrameRateWindow()
        var measured: Float? = null
        for (index in 0..31) {
            val timestampNs = 1_000_000_000L + index * 33_333_333L
            measured = window.record(timestampNs) ?: measured
        }
        assertEquals(30.0f, measured!!, 0.02f)
    }

    @Test
    fun doesNotPublishRateUntilTheMeasurementWindowHasElapsed() {
        val window = FrameRateWindow(windowDurationNs = 1_000_000_000L)
        assertNull(window.record(1_000_000_000L))
        assertNull(window.record(1_500_000_000L))
    }

    @Test
    fun ignoresInvalidAndOutOfOrderTimestamps() {
        val window = FrameRateWindow()
        assertNull(window.record(0L))
        assertNull(window.record(1_000_000_000L))
        assertNull(window.record(999_000_000L))
    }
}
