package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrScalingPlanTest {
    private val source540 = PixelSize(960, 540)
    private val surface1080 = PixelSize(1920, 1080)
    private val surface2160 = PixelSize(3840, 2160)

    @Test
    fun comparisonStrategyCyclesThroughAutoAndAllFourPipelines() {
        assertEquals(FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER, FsrComparisonStrategy.AUTO.next())
        assertEquals(FsrComparisonStrategy.B_FSR_MULTISTAGE, FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER.next())
        assertEquals(FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL, FsrComparisonStrategy.B_FSR_MULTISTAGE.next())
        assertEquals(FsrComparisonStrategy.D_1080P_TO_2160P, FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL.next())
        assertEquals(FsrComparisonStrategy.AUTO, FsrComparisonStrategy.D_1080P_TO_2160P.next())
    }

    @Test
    fun native4kOnlyStrategiesReportAndUseTheSafe1080FallbackOnA1080Surface() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.B_FSR_MULTISTAGE,
            source = source540,
            outputSurface = surface1080,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
        )
        assertEquals(FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER, plan.effectiveStrategy)
        assertEquals(surface1080, plan.processingTarget)
        assertTrue(plan.strategyFallbackReason!!.contains("3840×2160"))
    }

    @Test
    fun strategyAUsesFsrAt1080AndLeavesTheFinalDisplayScaleToAndroid() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER,
            source = source540,
            outputSurface = surface1080,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
        )
        assertEquals(PixelSize(1920, 1080), plan.processingTarget)
        assertEquals(listOf(surface1080), plan.easuStages)
        assertFalse(plan.linearFinalScale)
        assertTrue(plan.systemScalerExpected)
        assertEquals(plan.processingTarget, plan.rcasTarget)
    }

    @Test
    fun strategyAOnA4kWindowDoesNotClaimThatAndroidPerformsTheFinalScale() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.A_1080P_THEN_SYSTEM_SCALER,
            source = source540,
            outputSurface = surface2160,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
        )
        assertFalse(plan.systemScalerExpected)
    }

    @Test
    fun qualityPlanIncludesSourceResolutionNoiseReductionWrite() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.B_FSR_MULTISTAGE,
            source = source540,
            outputSurface = surface2160,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
            includeNoiseReduction = true,
        )
        assertEquals(source540, plan.passOutputSizes[0])
        assertEquals(source540, plan.passOutputSizes[1])
        assertEquals(PixelSize(1920, 1080), plan.passOutputSizes[2])
    }

    @Test
    fun strategyBUsesTwoFsrStagesFrom540pTo2160pAndRcasAtTheFinalTarget() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.B_FSR_MULTISTAGE,
            source = source540,
            outputSurface = surface2160,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
        )
        assertEquals(listOf(PixelSize(1920, 1080), surface2160), plan.easuStages)
        assertEquals(surface2160, plan.processingTarget)
        assertEquals(surface2160, plan.rcasTarget)
        assertFalse(plan.linearFinalScale)
    }

    @Test
    fun strategyCUsesEasuTo1080ThenASeparateLinearFinalPassAndRcasAt2160() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.C_EASU_1080_LINEAR_FINAL,
            source = source540,
            outputSurface = surface2160,
            geometry = VideoGeometry.fromSize(source540),
            maxTextureSize = 4096,
        )
        assertEquals(surface1080, plan.easuTarget)
        assertEquals(listOf(surface1080), plan.easuStages)
        assertEquals(surface2160, plan.processingTarget)
        assertTrue(plan.linearFinalScale)
        assertEquals(surface2160, plan.rcasTarget)
    }

    @Test
    fun strategyDUsesOneEasuStageFrom1080pTo2160p() {
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.D_1080P_TO_2160P,
            source = PixelSize(1920, 1080),
            outputSurface = surface2160,
            geometry = VideoGeometry.fromSize(PixelSize(1920, 1080)),
            maxTextureSize = 4096,
        )
        assertEquals(listOf(surface2160), plan.easuStages)
        assertEquals(surface2160, plan.rcasTarget)
        assertFalse(plan.linearFinalScale)
    }

    @Test
    fun palAnamorphic576pKeepsAspectFitAndEachEasuStageWithinTwoTimesPerAxis() {
        val geometry = VideoGeometry.pal16x9(720, 576)
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.B_FSR_MULTISTAGE,
            source = geometry.orientedSize,
            outputSurface = surface2160,
            geometry = geometry,
            maxTextureSize = 4096,
        )
        assertEquals(surface2160, plan.processingTarget)
        var previous = geometry.orientedSize
        for (stage in plan.easuStages) {
            assertTrue(stage.width <= previous.width * 2)
            assertTrue(stage.height <= previous.height * 2)
            previous = stage
        }
        assertEquals(plan.processingTarget, previous)
    }

    @Test
    fun native4kFourByThreeStrategyUsesTheAspectFitTargetWhenTextureLimitIs3072() {
        val geometry = VideoGeometry.pal4x3(720, 576)
        val plan = FsrScalingPlanPolicy.create(
            strategy = FsrComparisonStrategy.B_FSR_MULTISTAGE,
            source = geometry.orientedSize,
            outputSurface = surface2160,
            geometry = geometry,
            maxTextureSize = 3072,
        )
        assertEquals(FsrComparisonStrategy.B_FSR_MULTISTAGE, plan.effectiveStrategy)
        assertEquals(PixelSize(2880, 2160), plan.processingTarget)
        assertEquals(plan.processingTarget, plan.easuStages.last())
    }
}
