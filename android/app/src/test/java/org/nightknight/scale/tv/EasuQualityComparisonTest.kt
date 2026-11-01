package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasuQualityComparisonTest {
    @Test
    fun identicalOutputBuffersAreEquivalent() {
        val pixels = byteArrayOf(0, 32, 127, 255.toByte(), 8, 64, 192.toByte(), 255.toByte())

        val difference = EasuQualityComparison.compare(pixels, pixels.copyOf())

        assertNotNull(difference)
        assertEquals(0f, difference!!.meanAbsoluteError, 0f)
        assertEquals(0, difference.maximumAbsoluteError)
        assertTrue(EasuQualityComparison.isEquivalent(difference))
    }

    @Test
    fun boundedOneLevelQuantizationDifferencesRemainEquivalent() {
        val reference = byteArrayOf(100, 100, 100, 255.toByte())
        val candidate = byteArrayOf(101, 100, 100, 255.toByte())

        val difference = EasuQualityComparison.compare(reference, candidate)

        assertNotNull(difference)
        assertEquals(0.25f, difference!!.meanAbsoluteError, 0.0001f)
        assertEquals(1, difference.maximumAbsoluteError)
        assertTrue(EasuQualityComparison.isEquivalent(difference))
    }

    @Test
    fun excessivePixelDifferencesAreNotEquivalent() {
        val reference = byteArrayOf(0, 0, 0, 255.toByte())
        val candidate = byteArrayOf(20, 0, 0, 255.toByte())

        val difference = EasuQualityComparison.compare(reference, candidate)

        assertNotNull(difference)
        assertFalse(EasuQualityComparison.isEquivalent(difference!!))
    }

    @Test
    fun mismatchedOrEmptyBuffersCannotBeCompared() {
        assertNull(EasuQualityComparison.compare(byteArrayOf(0), byteArrayOf(0, 1)))
        assertNull(EasuQualityComparison.compare(byteArrayOf(), byteArrayOf()))
    }
}
