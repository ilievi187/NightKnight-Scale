package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
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
}
