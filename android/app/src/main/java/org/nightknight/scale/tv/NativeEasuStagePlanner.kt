package org.nightknight.scale.tv

import java.util.concurrent.atomic.AtomicLong

/**
 * Calls the optional Rust stage planner only when a render plan is built. GLES state,
 * SurfaceTexture operations, and every shader pass remain on the existing Android GL thread.
 */
internal object NativeEasuStagePlanner {
    private const val LIBRARY_NAME = "nightknight_render_core"
    private const val MAX_STAGE_COUNT = 64

    @Volatile
    private var libraryLoadFailure: String? = null

    @Volatile
    private var plannerFailure: String? = null

    private val libraryLoaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (failure: LinkageError) {
            libraryLoadFailure = failure.javaClass.simpleName
            false
        } catch (failure: SecurityException) {
            libraryLoadFailure = failure.javaClass.simpleName
            false
        }
    }

    private val nativeCallCount = AtomicLong()

    @Volatile
    private var lastNativeCallDurationNs = 0L

    val status: String
        get() = when {
            plannerFailure != null -> "Kotlin fallback (${plannerFailure})"
            libraryLoaded -> "Rust EASU stage planner"
            else -> "Kotlin fallback (${libraryLoadFailure ?: "native library unavailable"})"
        }

    val nativePlannerCalls: Long
        get() = nativeCallCount.get()

    val lastNativePlannerCallMicros: Long
        get() = lastNativeCallDurationNs / 1_000L

    fun easuStages(source: PixelSize, target: PixelSize): List<PixelSize> {
        val native = {
            val candidate = nativeStagesOrNull(source, target)
            if (candidate != null && !isValidSchedule(source, target, candidate)) {
                plannerFailure = "invalid native stage schedule"
                null
            } else {
                candidate
            }
        }
        return selectStages(
            source = source,
            target = target,
            nativePlanner = native,
            fallback = { RenderPolicy.easuStagesFallback(source, target) },
        )
    }

    /** Pure seam for JVM tests of missing-library, linkage, and malformed-result behavior. */
    internal fun selectStages(
        source: PixelSize,
        target: PixelSize,
        nativePlanner: () -> List<PixelSize>?,
        fallback: () -> List<PixelSize> = { RenderPolicy.easuStagesFallback(source, target) },
    ): List<PixelSize> {
        val candidate = try {
            nativePlanner()
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return if (candidate != null && isValidSchedule(source, target, candidate)) candidate else fallback()
    }

    /** Debug/emulator gate: compare native outputs with the retained Kotlin reference policy. */
    fun validateAgainstKotlinFallback(): Boolean {
        if (!libraryLoaded || plannerFailure != null) return false
        val cases = listOf(
            PixelSize(960, 540) to PixelSize(1920, 1080),
            PixelSize(960, 540) to PixelSize(3840, 2160),
            PixelSize(720, 576) to PixelSize(3840, 2160),
            PixelSize(720, 576) to PixelSize(2880, 2160),
            PixelSize(1920, 1080) to PixelSize(3840, 2160),
            PixelSize(1920, 1080) to PixelSize(1920, 1080),
            PixelSize(1920, 1080) to PixelSize(1280, 720),
            PixelSize(0, 540) to PixelSize(1920, 1080),
        )
        for ((source, target) in cases) {
            val native = nativeStagesOrNull(source, target) ?: return false
            val reference = RenderPolicy.easuStagesFallback(source, target)
            if (native != reference) {
                plannerFailure = "native/Kotlin stage mismatch"
                return false
            }
        }
        return true
    }

    private fun nativeStagesOrNull(source: PixelSize, target: PixelSize): List<PixelSize>? {
        if (!libraryLoaded || plannerFailure != null) return null
        val startNs = System.nanoTime()
        nativeCallCount.incrementAndGet()
        return try {
            val dimensions = nativePlanEasuStages(
                source.width,
                source.height,
                target.width,
                target.height,
            ) ?: run {
                plannerFailure = "native planner returned null"
                return null
            }
            if (dimensions.size % 2 != 0 || dimensions.size > MAX_STAGE_COUNT * 2) {
                plannerFailure = "native planner returned malformed dimensions"
                return null
            }
            dimensions.asList().chunked(2).map { pair -> PixelSize(pair[0], pair[1]) }
        } catch (failure: LinkageError) {
            plannerFailure = failure.javaClass.simpleName
            null
        } catch (failure: RuntimeException) {
            plannerFailure = failure.javaClass.simpleName
            null
        } finally {
            lastNativeCallDurationNs = System.nanoTime() - startNs
        }
    }

    private fun isValidSchedule(
        source: PixelSize,
        target: PixelSize,
        stages: List<PixelSize>,
    ): Boolean {
        if (source.width <= 0 || source.height <= 0 || target.width <= 0 || target.height <= 0) {
            return stages.isEmpty()
        }
        if (target.width < source.width || target.height < source.height) return stages == listOf(target)
        if (source == target) return stages.isEmpty()
        if (stages.isEmpty() || stages.size > MAX_STAGE_COUNT) return false

        var previous = source
        for (stage in stages) {
            if (stage.width <= 0 || stage.height <= 0 ||
                stage.width < previous.width || stage.height < previous.height ||
                stage.width > target.width || stage.height > target.height ||
                stage.width.toLong() > previous.width.toLong() * 2L ||
                stage.height.toLong() > previous.height.toLong() * 2L ||
                stage == previous
            ) {
                return false
            }
            previous = stage
        }
        return previous == target
    }

    private external fun nativePlanEasuStages(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray?
}
