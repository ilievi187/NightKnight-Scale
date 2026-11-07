package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRobustnessTest {
    @Test
    fun aspectRatioModeCyclesInOrder() {
        var mode = AspectRatioMode.AUTO
        mode = mode.next()
        assertEquals(AspectRatioMode.FIT_16_9, mode)
        mode = mode.next()
        assertEquals(AspectRatioMode.FIT_4_3, mode)
        mode = mode.next()
        assertEquals(AspectRatioMode.SQUARE_PIXELS, mode)
        mode = mode.next()
        assertEquals(AspectRatioMode.AUTO, mode)
    }

    @Test
    fun aspectRatioModeRestoresSafelyFromId() {
        assertEquals(AspectRatioMode.AUTO, AspectRatioMode.fromId("auto"))
        assertEquals(AspectRatioMode.FIT_16_9, AspectRatioMode.fromId("16_9"))
        assertEquals(AspectRatioMode.FIT_4_3, AspectRatioMode.fromId("4_3"))
        assertEquals(AspectRatioMode.SQUARE_PIXELS, AspectRatioMode.fromId("1_1"))
        assertEquals(AspectRatioMode.AUTO, AspectRatioMode.fromId(null))
        assertEquals(AspectRatioMode.AUTO, AspectRatioMode.fromId("invalid_value"))
    }

    @Test
    fun targetForGeometryPreservesAspectAndAdheresToBudget() {
        val pal43 = VideoGeometry.pal4x3(720, 576)
        val target = RenderPolicy.targetForGeometry(
            surface = PixelSize(1920, 1080),
            geometry = pal43,
            maxTextureSize = 4096,
        )
        // Expected target for 4:3 content fitting 1920x1080 is 1440x1080
        assertEquals(PixelSize(1440, 1080), target)

        val memoryBytes = RenderPolicy.estimatedRgbaTextureBytes(
            source = pal43.orientedSize,
            target = target,
            surface = PixelSize(1920, 1080),
        )
        assertTrue(memoryBytes <= RenderPolicy.MAX_RGBA_TEXTURE_BYTES)
    }

    @Test
    fun targetForGeometryAnamorphicPalFillsSixteenByNine() {
        val pal169 = VideoGeometry.pal16x9(720, 576)
        val target = RenderPolicy.targetForGeometry(
            surface = PixelSize(1920, 1080),
            geometry = pal169,
            maxTextureSize = 4096,
        )
        // 16:9 anamorphic PAL scales to 1920x1080
        assertEquals(PixelSize(1920, 1080), target)
    }

    @Test
    fun easuStagesForPalAnamorphicAreMultiStageAndBounded() {
        // From 720x576 to 1920x1080:
        // Stage 1: min(1920, 720*2) = 1440, min(1080, 576*2) = 1080 -> 1440x1080
        // Stage 2: min(1920, 1440*2) = 1920, min(1080, 1080*2) = 1080 -> 1920x1080
        val stages = RenderPolicy.easuStages(PixelSize(720, 576), PixelSize(1920, 1080))
        assertEquals(2, stages.size)
        assertEquals(PixelSize(1440, 1080), stages[0])
        assertEquals(PixelSize(1920, 1080), stages[1])

        // Verify no stage exceeds 2x per axis
        var prev = PixelSize(720, 576)
        for (stage in stages) {
            assertTrue(stage.width <= prev.width * 2)
            assertTrue(stage.height <= prev.height * 2)
            prev = stage
        }
    }

    @Test
    fun easuStagesForPalStandardFourByThreeIsSingleStage() {
        // From 720x576 to 1440x1080:
        // 1440 <= 720*2, 1080 <= 576*2 -> exactly 1 stage
        val stages = RenderPolicy.easuStages(PixelSize(720, 576), PixelSize(1440, 1080))
        assertEquals(1, stages.size)
        assertEquals(PixelSize(1440, 1080), stages[0])
    }

    @Test
    fun zeroOrNegativeDimensionsReturnEmptyViewportGracefully() {
        val emptySurface = PixelSize(0, 0)
        val validGeo = VideoGeometry(1920, 1080)
        assertEquals(PixelRect(0, 0, 0, 0), RenderPolicy.contentViewport(emptySurface, validGeo))

        val validSurface = PixelSize(1920, 1080)
        val zeroGeo = VideoGeometry(0, 0)
        assertEquals(PixelRect(0, 0, 0, 0), RenderPolicy.contentViewport(validSurface, zeroGeo))

        val negTarget = RenderPolicy.targetForGeometry(validSurface, zeroGeo, 4096)
        assertEquals(PixelSize(0, 0), negTarget)
    }

    @Test
    fun videoGeometrySummaryFormatsReliably() {
        val pal43 = VideoGeometry.pal4x3(720, 576)
        val summary = pal43.summary()
        assertTrue(summary.contains("720×576"))
        assertTrue(summary.contains("SAR 16:15"))
        assertTrue(summary.contains("DAR 4:3"))

        val portrait = VideoGeometry(1920, 1080, rotationDegrees = 90)
        val portSummary = portrait.summary()
        assertTrue(portSummary.contains("1920×1080"))
        assertTrue(portSummary.contains("[90°]"))
        assertTrue(portSummary.contains("DAR 9:16 portrait"))
    }
}
