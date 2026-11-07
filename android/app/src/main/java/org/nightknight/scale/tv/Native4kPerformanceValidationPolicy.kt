package org.nightknight.scale.tv

/** Requires consecutive source-active renderer windows before AUTO may trust a 4K profile. */
internal class Native4kPerformanceValidationPolicy(
    private val frameBudgetMargin: Float = 1.03f,
    private val stableWindowsRequired: Int = 5,
) {
    private var stableWindows = 0

    var isValidated: Boolean = false
        private set

    init {
        require(frameBudgetMargin >= 1f && frameBudgetMargin.isFinite())
        require(stableWindowsRequired > 0)
    }

    /** Returns true once, on the window that completes validation. */
    @Synchronized
    fun observe(
        native4kSurfaceVerified: Boolean,
        sourceActive: Boolean,
        sourceVideoFps: Float?,
        averageRendererIntervalMs: Float?,
    ): Boolean {
        val fps = sourceVideoFps?.takeIf { it > 0f && it.isFinite() }
        val intervalMs = averageRendererIntervalMs?.takeIf { it > 0f && it.isFinite() }
        if (!native4kSurfaceVerified || !sourceActive || fps == null || intervalMs == null) {
            stableWindows = 0
            return false
        }

        val withinBudget = intervalMs <= (1_000f / fps) * frameBudgetMargin
        if (!withinBudget) {
            stableWindows = 0
            return false
        }
        if (isValidated) return false

        stableWindows += 1
        if (stableWindows < stableWindowsRequired) return false
        isValidated = true
        return true
    }

    @Synchronized
    fun reset() {
        stableWindows = 0
        isValidated = false
    }
}
