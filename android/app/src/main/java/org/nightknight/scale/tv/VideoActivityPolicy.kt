package org.nightknight.scale.tv

internal data class PlaybackPacingTransition(
    val nextIsPlaying: Boolean,
    val resetFramePacingWindows: Boolean,
)

/** Prevents renderer-idle periods (for example, paused playback) from triggering quality fallback. */
internal object VideoActivityPolicy {
    fun hasRecentFrameCallback(lastCallbackNs: Long, nowNs: Long, maxIdleNs: Long): Boolean =
        lastCallbackNs > 0L &&
            maxIdleNs > 0L &&
            nowNs >= lastCallbackNs &&
            nowNs - lastCallbackNs <= maxIdleNs

    fun playbackTransition(currentlyPlaying: Boolean): PlaybackPacingTransition =
        PlaybackPacingTransition(
            nextIsPlaying = !currentlyPlaying,
            resetFramePacingWindows = true,
        )
}
