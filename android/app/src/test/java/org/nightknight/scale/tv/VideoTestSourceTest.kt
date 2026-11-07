package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoTestSourceTest {
    @Test
    fun selectsHalfDimensionVideoForCommonTvOutputModes() {
        assertEquals(PixelSize(640, 360), VideoTestSource.forTarget(PixelSize(1280, 720)).size)
        assertEquals(PixelSize(960, 540), VideoTestSource.forTarget(PixelSize(1920, 1080)).size)
        assertEquals(PixelSize(1280, 720), VideoTestSource.forTarget(PixelSize(2560, 1440)).size)
        assertEquals(PixelSize(1920, 1080), VideoTestSource.forTarget(PixelSize(3840, 2160)).size)
    }

    @Test
    fun selectedAssetPathMatchesSourceDimensions() {
        assertEquals(
            "test-videos/scale-test-1920x1080.mp4",
            VideoTestSource.forTarget(PixelSize(3840, 2160)).assetPath,
        )
    }

    @Test
    fun debugProfilesCanSelectPackaged540pOr1080pIndependentlyOfOutputSize() {
        assertEquals(PixelSize(960, 540), VideoTestSource.fromDimensions("960x540")?.size)
        assertEquals(PixelSize(1920, 1080), VideoTestSource.fromDimensions("1920x1080")?.size)
        assertEquals(25f, VideoTestSource.fromDimensions("720x576")?.frameRate)
        assertEquals(null, VideoTestSource.fromDimensions("3840x2160"))
    }

    @Test
    fun packagedProfilesCoverPalAnamorphicGeometryAnd25To60FpsCadence() {
        assertEquals(25f, VideoTestSource.fromProfileId("960x540-25")?.frameRate)
        assertEquals(50f, VideoTestSource.fromProfileId("960x540-50")?.frameRate)
        assertEquals(60f, VideoTestSource.fromProfileId("960x540-60")?.frameRate)
        val pal4x3 = VideoTestSource.fromProfileId("720x576-4x3-25")
        val pal16x9 = VideoTestSource.fromProfileId("720x576-16x9-25")
        assertEquals(PixelSize(720, 576), pal4x3?.size)
        assertEquals(PixelSize(720, 576), pal16x9?.size)
        assertEquals(25f, pal4x3?.frameRate)
        assertEquals(25f, pal16x9?.frameRate)
        assertFalse(pal4x3?.assetPath == pal16x9?.assetPath)
    }
}
