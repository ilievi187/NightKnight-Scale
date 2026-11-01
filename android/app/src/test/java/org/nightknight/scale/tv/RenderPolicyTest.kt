package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPolicyTest {
    @Test
    fun halfResolutionTestSourceIsAtMostFourTimesSmallerByArea() {
        assertEquals(PixelSize(960, 540), RenderPolicy.testSourceFor(PixelSize(1920, 1080)))
        assertEquals(PixelSize(1920, 1080), RenderPolicy.testSourceFor(PixelSize(3840, 2160)))
    }

    @Test
    fun tinySurfaceStillGetsNonZeroTestSource() {
        assertEquals(PixelSize(1, 1), RenderPolicy.testSourceFor(PixelSize(1, 1)))
    }

    @Test
    fun renderTargetRespectsGlesLimitAndKeepsAspectRatio() {
        assertEquals(PixelSize(4096, 2304), RenderPolicy.targetFor(PixelSize(7680, 4320), 4096))
        assertEquals(PixelSize(1920, 1080), RenderPolicy.targetFor(PixelSize(1920, 1080), 4096))
    }

    @Test
    fun processedTargetReachesThe4kSurfaceWhenTheDeviceAllowsIt() {
        assertEquals(
            PixelSize(3840, 2160),
            RenderPolicy.targetForSource(
                surface = PixelSize(3840, 2160),
                source = PixelSize(480, 270),
                maxTextureSize = 4096,
            ),
        )
    }

    @Test
    fun sourceAwareTargetPreservesTheSourceAspectRatio() {
        assertEquals(
            PixelSize(1440, 1080),
            RenderPolicy.targetForSource(
                surface = PixelSize(1920, 1080),
                source = PixelSize(640, 480),
                maxTextureSize = 4096,
            ),
        )
    }

    @Test
    fun fitViewportLetterboxesFourByThreeInsideSixteenByNine() {
        assertEquals(
            PixelRect(240, 0, 1440, 1080),
            RenderPolicy.contentViewport(PixelSize(1920, 1080), PixelSize(640, 480)),
        )
    }

    @Test
    fun easuStagesNeverExceedTwoTimesPerAxisAndReachFourKFromSd() {
        assertEquals(
            listOf(PixelSize(960, 540), PixelSize(1920, 1080), PixelSize(3840, 2160)),
            RenderPolicy.easuStages(PixelSize(480, 270), PixelSize(3840, 2160)),
        )
        assertEquals(
            listOf(PixelSize(3840, 2160)),
            RenderPolicy.easuStages(PixelSize(1920, 1080), PixelSize(3840, 2160)),
        )
    }

    @Test
    fun sourceConstrainedTargetStillFillsSmallDisplaysWhenWithinTheFsrRange() {
        assertEquals(
            PixelSize(1280, 720),
            RenderPolicy.targetForSource(
                surface = PixelSize(1280, 720),
                source = PixelSize(640, 360),
                maxTextureSize = 4096,
            ),
        )
    }

    @Test
    fun estimatedTextureAllocationIncludesSourceOutputAndPingPongTargets() {
        assertEquals(
            13_824_000L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source = PixelSize(640, 360),
                target = PixelSize(1920, 1080),
                surface = PixelSize(1920, 1080),
            ),
        )
    }

    @Test
    fun largeSurfaceTargetIsReducedToTheRgbaTextureWorkingSetBudget() {
        val source = PixelSize(320, 240)
        val surface = PixelSize(4096, 4096)
        val target = RenderPolicy.targetForSource(surface, source, 4096)
        assertEquals(source.width.toFloat() / source.height, target.width.toFloat() / target.height, 0.002f)
        assert(RenderPolicy.estimatedRgbaTextureBytes(source, target, surface) <= RenderPolicy.MAX_RGBA_TEXTURE_BYTES)
    }

    @Test
    fun sharpnessSliderUsesFsrStopsInReverseStrengthOrder() {
        assertEquals(1.0f, RenderPolicy.rcasScaleFor(0), 0.0001f)
        assertEquals(0.03125f, RenderPolicy.rcasScaleFor(20), 0.0001f)
        assertEquals(1.0f, RenderPolicy.rcasScaleFor(-10), 0.0001f)
        assertEquals(0.03125f, RenderPolicy.rcasScaleFor(30), 0.0001f)
    }

    @Test
    fun multipassEasuUsesTheSmallestReusableBufferForEachPingPongSlot() {
        assertEquals(
            listOf(PixelSize(960, 540), PixelSize(1920, 1080)),
            RenderPolicy.easuIntermediateSizes(PixelSize(480, 270), PixelSize(3840, 2160)),
        )
    }

    @Test
    fun rcasCanRenderDirectlyIntoAspectFitViewportUnlessTargetWasBudgetReduced() {
        assertEquals(
            false,
            RenderPolicy.requiresRcasIntermediate(
                target = PixelSize(1440, 1080),
                surface = PixelSize(1920, 1080),
                source = PixelSize(640, 480),
            ),
        )
        assertEquals(
            true,
            RenderPolicy.requiresRcasIntermediate(
                target = PixelSize(1280, 960),
                surface = PixelSize(1920, 1080),
                source = PixelSize(640, 480),
            ),
        )
    }

    @Test
    fun gpuViewportUsesBottomLeftOriginAndKeepsTheLetterboxedImageCentered() {
        assertEquals(
            GlViewport(x = 0, y = 1, width = 1280, height = 720),
            RenderPolicy.glViewportForContent(PixelSize(1280, 721), PixelSize(1920, 1080)),
        )
    }

    @Test
    fun memoryEstimateCountsExactPingPongSizesAndOnlyRequestedNoiseReduction() {
        val source = PixelSize(480, 270)
        val target = PixelSize(3840, 2160)
        val surface = PixelSize(3840, 2160)
        assertEquals(
            44_582_400L,
            RenderPolicy.estimatedRgbaTextureBytes(source, target, surface, includeNoiseReduction = true),
        )
        assertEquals(
            44_064_000L,
            RenderPolicy.estimatedRgbaTextureBytes(source, target, surface, includeNoiseReduction = false),
        )
    }

    @Test
    fun geometryAwareRcasRoutingUsesDisplayAspectAndActualCodedTextureStages() {
        val geometry = VideoGeometry.pal16x9(720, 576)
        val source = geometry.orientedSize
        val surface = PixelSize(1920, 1080)
        val target = RenderPolicy.targetForGeometry(
            surface = surface,
            geometry = geometry,
            maxTextureSize = 4096,
            sourceTextureSize = source,
            includeNoiseReduction = false,
            includeEasu = true,
            includeRcas = true,
        )

        assertEquals(PixelSize(1920, 1080), target)
        assertFalse(RenderPolicy.requiresRcasIntermediate(target, surface, geometry))
        assertTrue(RenderPolicy.requiresRcasIntermediate(target, surface, source))
        assertEquals(
            16_174_080L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source = source,
                target = target,
                surface = surface,
                includeNoiseReduction = false,
                includeEasu = true,
                includeRcas = true,
                rcasIntermediateRequired = false,
            ),
        )
    }

    @Test
    fun geometryAwareGlViewportUsesDisplayAspectAndBottomLeftOrigin() {
        val viewport = RenderPolicy.glViewportForContent(
            PixelSize(1920, 1080),
            VideoGeometry.pal4x3(720, 576),
        )
        assertEquals(GlViewport(x = 240, y = 0, width = 1440, height = 1080), viewport)
    }

    @Test
    fun memoryEstimateIncludesOnlyResourcesRequiredByEachPreset() {
        val source = PixelSize(640, 480)
        val target = PixelSize(1280, 960)
        val surface = PixelSize(1920, 1080)
        assertEquals(
            1_228_800L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source,
                target,
                surface,
                includeNoiseReduction = false,
                includeEasu = false,
                includeRcas = false,
            ),
        )
        assertEquals(
            6_144_000L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source,
                target,
                surface,
                includeNoiseReduction = false,
                includeEasu = true,
                includeRcas = false,
            ),
        )
        assertEquals(
            11_059_200L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source,
                target,
                surface,
                includeNoiseReduction = false,
                includeEasu = true,
                includeRcas = true,
            ),
        )
        assertEquals(
            0L,
            RenderPolicy.estimatedRgbaTextureBytes(
                source,
                target,
                surface,
                includeNoiseReduction = false,
                includeEasu = false,
                includeRcas = false,
                includeSourceTexture = false,
            ),
        )
    }
}
