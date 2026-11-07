package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMetadataReaderTest {
    @Test
    fun extractorExplicitSquarePixelSarOverridesResolutionHeuristic() {
        val inferredPal = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry(720, 576, sarWidth = 16, sarHeight = 15),
        )
        val extractorInfo = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry(720, 576, sarWidth = 1, sarHeight = 1, sarIsExplicit = true),
            frameRate = 25f,
            hasSampleAspectRatioMetadata = true,
        )

        val merged = VideoMetadataReader.mergeInfo(inferredPal, extractorInfo)

        assertEquals(1, merged.geometry.sarWidth)
        assertEquals(1, merged.geometry.sarHeight)
        assertEquals(720.0 / 576.0, merged.geometry.displayAspectRatio(), 0.001)
        assertEquals(25f, merged.frameRate, 0.001f)
    }

    @Test
    fun missingExtractorSarKeepsTheRetrieverFallbackInsteadOfImplyingMetadata() {
        val inferredPal = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry(720, 576, sarWidth = 16, sarHeight = 15),
        )
        val extractorInfo = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry(720, 576, sarWidth = 1, sarHeight = 1),
        )

        val merged = VideoMetadataReader.mergeInfo(inferredPal, extractorInfo)

        assertEquals(16, merged.geometry.sarWidth)
        assertEquals(15, merged.geometry.sarHeight)
        assertEquals(4.0 / 3.0, merged.geometry.displayAspectRatio(), 0.001)
    }

    @Test
    fun extractorCropRectangleDerivesVisibleDisplayAspectRatio() {
        val dar = VideoMetadataReader.deriveDisplayAspectRatio(
            width = 1920,
            height = 1088,
            sarWidth = 1,
            sarHeight = 1,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 1919,
            cropBottom = 1079,
        )

        assertEquals(16.0 / 9.0, dar!!, 0.001)
    }

    @Test
    fun extractorCropAspectUsesExplicitSampleAspectRatio() {
        val dar = VideoMetadataReader.deriveDisplayAspectRatio(
            width = 720,
            height = 576,
            sarWidth = 16,
            sarHeight = 15,
            cropLeft = 8,
            cropTop = 0,
            cropRight = 711,
            cropBottom = 575,
        )

        assertEquals((704.0 * 16.0) / (576.0 * 15.0), dar!!, 0.001)
    }

    @Test
    fun absentCropAndDisplayDimensionsDoNotInventExplicitDar() {
        val dar = VideoMetadataReader.deriveDisplayAspectRatio(
            width = 720,
            height = 576,
            sarWidth = 16,
            sarHeight = 15,
        )

        assertEquals(null, dar)
    }

    @Test
    fun extractorDarOverridesResolutionFallbackWhenPresent() {
        val fallback = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry.pal4x3(720, 576),
        )
        val extractorInfo = VideoMetadataReader.VideoInfo(
            geometry = VideoGeometry(720, 576, darRatio = 16.0 / 9.0),
            hasDisplayAspectRatioMetadata = true,
        )

        val merged = VideoMetadataReader.mergeInfo(fallback, extractorInfo)

        assertEquals(16.0 / 9.0, merged.geometry.displayAspectRatio(), 0.001)
    }
}
