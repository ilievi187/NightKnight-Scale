package org.nightknight.scale.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoActivityPolicyTest {
    @Test
    fun recentSourceFrameCallbacksAllowFallbackEvaluation() {
        assertTrue(
            VideoActivityPolicy.hasRecentFrameCallback(
                lastCallbackNs = 100L,
                nowNs = 200L,
                maxIdleNs = 100L,
            ),
        )
    }

    @Test
    fun staleOrMissingSourceCallbacksDoNotAllowFallbackEvaluation() {
        assertFalse(VideoActivityPolicy.hasRecentFrameCallback(0L, 200L, 100L))
        assertFalse(VideoActivityPolicy.hasRecentFrameCallback(100L, 201L, 100L))
    }

    @Test
    fun playbackPauseTransitionRequestsFramePacingReset() {
        val transition = VideoActivityPolicy.playbackTransition(currentlyPlaying = true)
        assertFalse(transition.nextIsPlaying)
        assertTrue(transition.resetFramePacingWindows)
    }

    @Test
    fun playbackResumeTransitionRequestsFramePacingReset() {
        val transition = VideoActivityPolicy.playbackTransition(currentlyPlaying = false)
        assertTrue(transition.nextIsPlaying)
        assertTrue(transition.resetFramePacingWindows)
    }

    @Test
    fun invalidWindowOrFutureCallbackTimestampIsInactive() {
        assertFalse(VideoActivityPolicy.hasRecentFrameCallback(100L, 100L, 0L))
        assertFalse(VideoActivityPolicy.hasRecentFrameCallback(201L, 200L, 100L))
    }
}
