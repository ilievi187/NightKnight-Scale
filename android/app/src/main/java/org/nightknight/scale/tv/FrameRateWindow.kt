package org.nightknight.scale.tv

/** Rolling rate estimate for timestamped events; it reports cadence, not display presentation. */
internal class FrameRateWindow(
    private val windowDurationNs: Long = 1_000_000_000L,
) {
    private var windowStartNs = 0L
    private var lastTimestampNs = 0L
    private var sampleCount = 0L

    init {
        require(windowDurationNs > 0L)
    }

    @Synchronized
    fun record(timestampNs: Long): Float? {
        if (timestampNs <= 0L || (lastTimestampNs != 0L && timestampNs <= lastTimestampNs)) return null
        lastTimestampNs = timestampNs
        if (windowStartNs == 0L) {
            windowStartNs = timestampNs
            sampleCount = 1L
            return null
        }

        sampleCount += 1L
        val elapsedNs = timestampNs - windowStartNs
        if (elapsedNs < windowDurationNs || sampleCount < 2L) return null

        val intervals = sampleCount - 1L
        val framesPerSecond = intervals * 1_000_000_000.0 / elapsedNs
        windowStartNs = timestampNs
        sampleCount = 1L
        return framesPerSecond.toFloat()
    }

    @Synchronized
    fun reset() {
        windowStartNs = 0L
        lastTimestampNs = 0L
        sampleCount = 0L
    }
}
