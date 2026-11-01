package org.nightknight.scale.tv

internal data class CpuSubmitSummary(
    val samples: Int,
    val averageMs: Float,
    val maximumMs: Float,
)

internal data class RenderCpuTimings(
    val frameSubmit: CpuSubmitSummary?,
    val inputUpdate: CpuSubmitSummary?,
    val denoise: CpuSubmitSummary?,
    val easu: CpuSubmitSummary?,
    val rcas: CpuSubmitSummary?,
    val present: CpuSubmitSummary?,
)

/** Single-threaded rolling accumulator for CPU wall time around GLES submission; driver calls may still stall. */
internal class CpuSubmitWindow {
    private var sampleCount = 0
    private var totalNanoseconds = 0L
    private var maximumNanoseconds = 0L

    fun record(durationNanoseconds: Long) {
        if (durationNanoseconds <= 0L) return
        sampleCount += 1
        totalNanoseconds += durationNanoseconds
        maximumNanoseconds = maxOf(maximumNanoseconds, durationNanoseconds)
    }

    fun drain(): CpuSubmitSummary? {
        if (sampleCount == 0) return null
        val result = CpuSubmitSummary(
            samples = sampleCount,
            averageMs = totalNanoseconds / sampleCount / 1_000_000.0f,
            maximumMs = maximumNanoseconds / 1_000_000.0f,
        )
        sampleCount = 0
        totalNanoseconds = 0L
        maximumNanoseconds = 0L
        return result
    }
}

/**
 * Degrades one processing stage only after consecutive renderer-interval windows exceed
 * the known source frame budget. This is a render-loop heuristic, not a GPU timer or display FPS.
 */
internal class RuntimeRenderFallbackPolicy(
    private val frameBudgetMargin: Float = 1.10f,
    private val consecutiveOverBudgetWindows: Int = 2,
) {
    private var overBudgetWindows = 0

    init {
        require(frameBudgetMargin >= 1.0f && frameBudgetMargin.isFinite())
        require(consecutiveOverBudgetWindows > 0)
    }

    fun observe(
        currentMode: RenderMode,
        sourceVideoFps: Float?,
        averageRendererIntervalMs: Float?,
    ): RenderMode? {
        if (currentMode == RenderMode.BILINEAR) {
            overBudgetWindows = 0
            return null
        }
        val fps = sourceVideoFps?.takeIf { it > 0f && it.isFinite() }
        val intervalMs = averageRendererIntervalMs?.takeIf { it > 0f && it.isFinite() }
        if (fps == null || intervalMs == null) {
            overBudgetWindows = 0
            return null
        }

        val sourceFrameBudgetMs = 1_000f / fps
        if (intervalMs <= sourceFrameBudgetMs * frameBudgetMargin) {
            overBudgetWindows = 0
            return null
        }

        overBudgetWindows += 1
        if (overBudgetWindows < consecutiveOverBudgetWindows) return null
        overBudgetWindows = 0
        return currentMode.fasterFallback()
    }

    fun reset() {
        overBudgetWindows = 0
    }

    private fun RenderMode.fasterFallback(): RenderMode? = when (this) {
        RenderMode.FSR1_DENOISE -> RenderMode.FSR1
        RenderMode.FSR1 -> RenderMode.EASU_ONLY
        RenderMode.EASU_ONLY -> RenderMode.BILINEAR
        RenderMode.BILINEAR -> null
    }
}
