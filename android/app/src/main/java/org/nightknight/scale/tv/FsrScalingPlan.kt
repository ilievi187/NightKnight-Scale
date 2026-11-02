package org.nightknight.scale.tv

internal enum class FsrComparisonStrategy(val id: String, val label: String) {
    AUTO("auto", "AUTO (device-safe)"),
    A_1080P_THEN_SYSTEM_SCALER("a_1080p_system_scaler", "A · FSR → 1080P + compositor"),
    B_FSR_MULTISTAGE("b_fsr_multistage", "B · multistage FSR → 2160P"),
    C_EASU_1080_LINEAR_FINAL("c_easu_1080_linear_2160", "C · EASU → 1080P + linear 2160P"),
    D_1080P_TO_2160P("d_1080p_to_2160p", "D · EASU → 2160P");

    fun next(): FsrComparisonStrategy = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): FsrComparisonStrategy = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

internal data class FsrScalingPlan(
    val requestedStrategy: FsrComparisonStrategy,
    val effectiveStrategy: FsrComparisonStrategy,
    val strategyFallbackReason: String?,
    val outputSurface: PixelSize,
    val processingTarget: PixelSize,
    val easuTarget: PixelSize,
    val easuStages: List<PixelSize>,
    val linearFinalScale: Boolean,
    val systemScalerExpected: Boolean,
    val rcasTarget: PixelSize,
    val passOutputSizes: List<PixelSize>,
    val estimatedRgbaTextureBytes: Long,
    val estimatedRgbaWriteBytesPerFrame: Long,
)

/** Deterministic benchmark planning for A–D. Pixel counters are estimates, not measured bandwidth. */
internal object FsrScalingPlanPolicy {
    fun create(
        strategy: FsrComparisonStrategy,
        source: PixelSize,
        outputSurface: PixelSize,
        geometry: VideoGeometry,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
        maxTextureSize: Int,
        includeInputCopy: Boolean = true,
        includeNoiseReduction: Boolean = false,
        includeEasu: Boolean = true,
        includeRcas: Boolean = true,
    ): FsrScalingPlan {
        val stageLimit = minOf(maxTextureSize, 4096)
        val native4kStrategy = strategy in setOf(
            FsrComparisonStrategy.B_FSR_MULTISTAGE,
            FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL,
            FsrComparisonStrategy.D_1080P_TO_2160P,
        )
        val aspectFitOutput = RenderPolicy.contentViewport(outputSurface, geometry, aspectMode)
        val native4kReady = outputSurface.width >= OutputResolutionPolicy.NATIVE_4K.width &&
            outputSurface.height >= OutputResolutionPolicy.NATIVE_4K.height &&
            aspectFitOutput.width > 0 && aspectFitOutput.height > 0 &&
            stageLimit >= maxOf(aspectFitOutput.width, aspectFitOutput.height)
        val strategyFallbackReason = if (native4kStrategy && !native4kReady) {
            "${strategy.label} requires a 3840×2160 surface and GLES texture limit; running 1080P strategy A instead"
        } else {
            null
        }
        val plannedStrategy = when (strategy) {
            FsrComparisonStrategy.AUTO -> when {
                outputSurface.width <= OutputResolutionPolicy.FULL_HD.width &&
                    outputSurface.height <= OutputResolutionPolicy.FULL_HD.height -> FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER
                source.width >= OutputResolutionPolicy.FULL_HD.width &&
                    source.height >= OutputResolutionPolicy.FULL_HD.height -> FsrComparisonStrategy.D_1080P_TO_2160P
                else -> FsrComparisonStrategy.B_FSR_MULTISTAGE
            }
            else -> strategy
        }
        val effectiveStrategy = if (strategyFallbackReason != null) {
            FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER
        } else {
            plannedStrategy
        }
        val processingSurface = if (effectiveStrategy == FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER) {
            OutputResolutionPolicy.FULL_HD
        } else {
            outputSurface
        }
        val processingTarget = RenderPolicy.targetForGeometry(
            surface = processingSurface,
            geometry = geometry,
            maxTextureSize = stageLimit,
            aspectMode = aspectMode,
            sourceTextureSize = source,
            includeNoiseReduction = includeNoiseReduction,
            includeEasu = includeEasu,
            includeRcas = includeRcas,
        )
        val easuTarget = if (effectiveStrategy == FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL) {
            RenderPolicy.targetForGeometry(
                surface = OutputResolutionPolicy.FULL_HD,
                geometry = geometry,
                maxTextureSize = stageLimit,
                aspectMode = aspectMode,
                sourceTextureSize = source,
                includeNoiseReduction = includeNoiseReduction,
                includeEasu = includeEasu,
                includeRcas = false,
            )
        } else {
            processingTarget
        }
        val stages = if (includeEasu) RenderPolicy.easuStages(source, easuTarget) else emptyList()
        val linearFinal = includeEasu && effectiveStrategy == FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL &&
            easuTarget != processingTarget
        val systemScalerExpected = effectiveStrategy == FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER &&
            outputSurface.width in 1..OutputResolutionPolicy.FULL_HD.width &&
            outputSurface.height in 1..OutputResolutionPolicy.FULL_HD.height
        val passSizes = buildList {
            if (includeInputCopy) add(source)
            if (includeNoiseReduction) add(source)
            addAll(stages)
            if (linearFinal) add(processingTarget)
            if (includeRcas && (stages.isNotEmpty() || linearFinal)) add(processingTarget)
        }
        val allocatedBytes = estimateAllocatedTextureBytes(
            source = source,
            processingTarget = processingTarget,
            easuTarget = easuTarget,
            outputSurface = outputSurface,
            geometry = geometry,
            aspectMode = aspectMode,
            includeNoiseReduction = includeNoiseReduction,
            includeEasu = stages.isNotEmpty(),
            includeLinearFinal = linearFinal,
            includeRcas = includeRcas && (stages.isNotEmpty() || linearFinal),
        )
        val writtenBytes = passSizes.sumOf { it.width.toLong() * it.height * 4L }
        return FsrScalingPlan(
            requestedStrategy = strategy,
            effectiveStrategy = effectiveStrategy,
            strategyFallbackReason = strategyFallbackReason,
            outputSurface = outputSurface,
            processingTarget = processingTarget,
            easuTarget = easuTarget,
            easuStages = stages,
            linearFinalScale = linearFinal,
            systemScalerExpected = systemScalerExpected,
            rcasTarget = processingTarget,
            passOutputSizes = passSizes,
            estimatedRgbaTextureBytes = allocatedBytes,
            estimatedRgbaWriteBytesPerFrame = writtenBytes,
        )
    }

    private fun estimateAllocatedTextureBytes(
        source: PixelSize,
        processingTarget: PixelSize,
        easuTarget: PixelSize,
        outputSurface: PixelSize,
        geometry: VideoGeometry,
        aspectMode: AspectRatioMode,
        includeNoiseReduction: Boolean,
        includeEasu: Boolean,
        includeLinearFinal: Boolean,
        includeRcas: Boolean,
    ): Long {
        fun bytes(size: PixelSize): Long = size.width.toLong() * size.height * 4L
        var total = bytes(source) * if (includeNoiseReduction) 2L else 1L
        if (includeEasu) {
            val stageOutput = if (includeLinearFinal) easuTarget else processingTarget
            total += bytes(stageOutput)
            total += RenderPolicy.easuIntermediateSizes(source, easuTarget).sumOf(::bytes)
        }
        if (includeLinearFinal) total += bytes(processingTarget)
        if (includeRcas && RenderPolicy.requiresRcasIntermediate(processingTarget, outputSurface, geometry, aspectMode)) {
            total += bytes(processingTarget)
        }
        return total
    }
}
