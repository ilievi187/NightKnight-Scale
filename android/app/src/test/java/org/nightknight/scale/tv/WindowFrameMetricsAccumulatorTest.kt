package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowFrameMetricsAccumulatorTest {
    @Test
    fun summaryReportsWindowMetricsSeparatelyAndTracksDroppedReports() {
        val accumulator = WindowFrameMetricsAccumulator()
        accumulator.record(totalDurationNs = 16_000_000L, gpuDurationNs = 4_000_000L, vsyncTimestampNs = 100_000_000L, droppedReports = 1)
        accumulator.record(totalDurationNs = 20_000_000L, gpuDurationNs = -1L, vsyncTimestampNs = 116_700_000L, droppedReports = 2)

        val summary = accumulator.snapshot()
        assertEquals(2, summary.samples)
        assertEquals(3L, summary.droppedReports)
        assertEquals(18.0f, summary.averageTotalDurationMs ?: -1f, 0.001f)
        assertEquals(20.0f, summary.maximumTotalDurationMs ?: -1f, 0.001f)
        assertEquals(4.0f, summary.averageGpuDurationMs ?: -1f, 0.001f)
        assertEquals(16.7f, summary.averageVsyncIntervalMs ?: -1f, 0.001f)
    }

    @Test
    fun unavailableMetricsRemainNullInsteadOfBecomingZero() {
        val accumulator = WindowFrameMetricsAccumulator()
        accumulator.record(totalDurationNs = -1L, gpuDurationNs = -1L, vsyncTimestampNs = -1L, droppedReports = 0)
        val summary = accumulator.snapshot()
        assertEquals(0, summary.samples)
        assertNull(summary.averageTotalDurationMs)
        assertNull(summary.averageGpuDurationMs)
        assertNull(summary.averageVsyncIntervalMs)
    }
}
