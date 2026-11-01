package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun pal4x3ProducesFourByThreeDarAndCorrectPillarboxedViewport() {
        val geo = VideoGeometry.pal4x3(720, 576)
        assertEquals(4.0 / 3.0, geo.displayAspectRatio(), 0.001)
        assertEquals(PixelSize(768, 576), geo.effectiveDisplaySize())
        assertFalse(geo.isPortrait)
        assertFalse(geo.isRotated90or270)
        assertEquals("4:3", geo.aspectLabel())

        // On a 1080p 16:9 TV: 1440x1080 centered with 240px pillarbox bars on left and right
        val viewport1080p = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(240, 0, 1440, 1080), viewport1080p)

        // On a 4K 16:9 TV (TCL 55C645 native panel 3840x2160): 2880x2160 centered with 480px bars
        val viewport4k = RenderPolicy.contentViewport(PixelSize(3840, 2160), geo)
        assertEquals(PixelRect(480, 0, 2880, 2160), viewport4k)
    }

    @Test
    fun pal16x9AnamorphicProducesSixteenByNineDarAndFullscreenViewport() {
        val geo = VideoGeometry.pal16x9(720, 576)
        assertEquals(16.0 / 9.0, geo.displayAspectRatio(), 0.001)
        assertEquals(PixelSize(1024, 576), geo.effectiveDisplaySize())
        assertFalse(geo.isPortrait)
        assertEquals("16:9", geo.aspectLabel())

        // On a 1080p 16:9 TV: fills the 1920x1080 screen with 0 letterbox/pillarbox
        val viewport1080p = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(0, 0, 1920, 1080), viewport1080p)

        // On a 4K 16:9 TV: fills the 3840x2160 screen exactly
        val viewport4k = RenderPolicy.contentViewport(PixelSize(3840, 2160), geo)
        assertEquals(PixelRect(0, 0, 3840, 2160), viewport4k)
    }

    @Test
    fun ntsc4x3ProducesFourByThreeDar() {
        val geo = VideoGeometry.ntsc4x3(720, 480)
        assertEquals(4.0 / 3.0, geo.displayAspectRatio(), 0.001)
        assertEquals(PixelSize(640, 480), geo.effectiveDisplaySize())

        val viewport1080p = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(240, 0, 1440, 1080), viewport1080p)
    }

    @Test
    fun ntsc16x9AnamorphicProducesSixteenByNineDar() {
        val geo = VideoGeometry.ntsc16x9(720, 480)
        assertEquals(16.0 / 9.0, geo.displayAspectRatio(), 0.001)

        val viewport1080p = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(0, 0, 1920, 1080), viewport1080p)
    }

    @Test
    fun portraitVideoUnrotatedPillarboxedCorrectly() {
        val geo = VideoGeometry(codedWidth = 1080, codedHeight = 1920, rotationDegrees = 0)
        assertTrue(geo.isPortrait)
        assertFalse(geo.isRotated90or270)
        assertEquals(PixelSize(1080, 1920), geo.orientedSize)
        assertEquals(9.0 / 16.0, geo.displayAspectRatio(), 0.001)

        // On a 1080p TV: width is 608px (1080 * 1080/1920 = 607.5 -> 608), left is 656px
        val viewport = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(656, 0, 608, 1080), viewport)
    }

    @Test
    fun portraitVideoWith90DegreeRotationTransposesAxesAndPillarboxes() {
        // A smartphone video coded as 1920x1080 with 90° rotation metadata
        val geo = VideoGeometry(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 90)
        assertTrue(geo.isPortrait)
        assertTrue(geo.isRotated90or270)
        assertEquals(PixelSize(1080, 1920), geo.orientedSize)
        assertEquals(1080.0 / 1920.0, geo.displayAspectRatio(), 0.001)

        val viewport = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(656, 0, 608, 1080), viewport)
    }

    @Test
    fun portraitVideoWith270DegreeRotationTransposesAxes() {
        val geo = VideoGeometry(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 270)
        assertTrue(geo.isPortrait)
        assertTrue(geo.isRotated90or270)
        assertEquals(PixelSize(1080, 1920), geo.orientedSize)
        assertEquals(1080.0 / 1920.0, geo.displayAspectRatio(), 0.001)

        val viewport = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(656, 0, 608, 1080), viewport)
    }

    @Test
    fun invertedVideoWith180DegreeRotationMaintainsLandscapeDimensions() {
        val geo = VideoGeometry(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 180)
        assertFalse(geo.isPortrait)
        assertFalse(geo.isRotated90or270)
        assertEquals(PixelSize(1920, 1080), geo.orientedSize)
        assertEquals(16.0 / 9.0, geo.displayAspectRatio(), 0.001)

        val viewport = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo)
        assertEquals(PixelRect(0, 0, 1920, 1080), viewport)
    }

    @Test
    fun aspectRatioOverrideModesForceDesiredAspect() {
        val geo = VideoGeometry.pal4x3(720, 576)

        // Force 16:9 on a 4:3 source
        assertEquals(16.0 / 9.0, geo.displayAspectRatio(AspectRatioMode.FIT_16_9), 0.001)
        val viewport169 = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo, AspectRatioMode.FIT_16_9)
        assertEquals(PixelRect(0, 0, 1920, 1080), viewport169)

        // Force 4:3
        assertEquals(4.0 / 3.0, geo.displayAspectRatio(AspectRatioMode.FIT_4_3), 0.001)

        // Square pixels (raw 720:576 = 5:4 = 1.25)
        assertEquals(720.0 / 576.0, geo.displayAspectRatio(AspectRatioMode.SQUARE_PIXELS), 0.001)
        val viewportRaw = RenderPolicy.contentViewport(PixelSize(1920, 1080), geo, AspectRatioMode.SQUARE_PIXELS)
        assertEquals(PixelRect(285, 0, 1350, 1080), viewportRaw)
    }

    @Test
    fun letterboxingOccursWhenSurfaceIsTallerThanVideo() {
        // 16:9 video on a 4:3 surface (1440x1080)
        val geo = VideoGeometry(codedWidth = 1920, codedHeight = 1080)
        val viewport = RenderPolicy.contentViewport(PixelSize(1440, 1080), geo)
        // 1440 wide, height = 1440 * 9/16 = 810. Top = (1080 - 810)/2 = 135
        assertEquals(PixelRect(0, 135, 1440, 810), viewport)
    }

    @Test
    fun colorimetryLabelsMatchExpectedStandards() {
        val pal = VideoGeometry.pal4x3(720, 576)
        assertEquals("BT.601 PAL", pal.colorStandardLabel)
        assertEquals("Limited range (16-235)", pal.colorRangeLabel)

        val hd = VideoGeometry(1920, 1080, colorStandard = 1, colorRange = 2, colorTransfer = 3)
        assertEquals("BT.709", hd.colorStandardLabel)
        assertEquals("Limited range (16-235)", hd.colorRangeLabel)
        assertEquals("SDR (SMPTE 170M)", hd.colorTransferLabel)
    }
}
