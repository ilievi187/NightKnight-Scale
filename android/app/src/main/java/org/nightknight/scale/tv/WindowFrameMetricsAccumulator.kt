package org.nightknight.scale.tv

import java.util.ArrayDeque

internal data class WindowFrameMetricsSummary(
    val samples: Int,
    val droppedReports: Long,
    val averageTotalDurationMs: Float?,
    val maximumTotalDurationMs: Float?,
    val averageGpuDurationMs: Float?,
    val averageVsyncIntervalMs: Float?,
)

/** Rolling Android app-window FrameMetrics; its values are not per-shader or panel scanout timing. */
internal class WindowFrameMetricsAccumulator(private val capacity: Int = 120) {
    private data class Sample(
        val totalDurationNs: Long,
        val gpuDurationNs: Long,
        val vsyncTimestampNs: Long,
        val droppedReports: Int,
    )

    private val samples = ArrayDeque<Sample>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun record(totalDurationNs: Long, gpuDurationNs: Long, vsyncTimestampNs: Long, droppedReports: Int) {
        if (totalDurationNs <= 0L && gpuDurationNs <= 0L && vsyncTimestampNs <= 0L && droppedReports <= 0) return
        samples.addLast(Sample(totalDurationNs, gpuDurationNs, vsyncTimestampNs, droppedReports.coerceAtLeast(0)))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun snapshot(): WindowFrameMetricsSummary {
        val values = samples.toList()
        val totalDurations = values.mapNotNull { it.totalDurationNs.takeIf { duration -> duration > 0L } }
        val gpuDurations = values.mapNotNull { it.gpuDurationNs.takeIf { duration -> duration > 0L } }
        val vsyncTimestamps = values.mapNotNull { it.vsyncTimestampNs.takeIf { timestamp -> timestamp > 0L } }
        val vsyncIntervals = vsyncTimestamps.zipWithNext { previous, current -> current - previous }
            .filter { it > 0L }
        fun averageMs(durations: List<Long>): Float? =
            durations.takeIf { it.isNotEmpty() }?.let { it.average().div(1_000_000.0).toFloat() }

        return WindowFrameMetricsSummary(
            samples = totalDurations.size,
            droppedReports = values.sumOf { it.droppedReports.toLong() },
            averageTotalDurationMs = averageMs(totalDurations),
            maximumTotalDurationMs = totalDurations.maxOrNull()?.div(1_000_000.0)?.toFloat(),
            averageGpuDurationMs = averageMs(gpuDurations),
            averageVsyncIntervalMs = averageMs(vsyncIntervals),
        )
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }
}
