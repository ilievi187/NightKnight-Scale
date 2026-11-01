package org.nightknight.scale.tv

/** Prevents renderer-idle periods (for example, paused playback) from triggering quality fallback. */
internal object VideoActivityPolicy {
    fun hasRecentFrameCallback(lastCallbackNs: Long, nowNs: Long, maxIdleNs: Long): Boolean =
        lastCallbackNs > 0L &&
            maxIdleNs > 0L &&
            nowNs >= lastCallbackNs &&
            nowNs - lastCallbackNs <= maxIdleNs
}
