package org.nightknight.scale.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEasuStagePlannerTest {
    private val source = PixelSize(960, 540)
    private val target = PixelSize(3840, 2160)
    private val expected = listOf(PixelSize(1920, 1080), target)

    @Test
    fun acceptsAValidNativeScheduleWithoutCallingTheFallback() {
        var fallbackCalled = false
        val actual = NativeEasuStagePlanner.selectStages(
            source = source,
            target = target,
            nativePlanner = { expected },
            fallback = {
                fallbackCalled = true
                emptyList()
            },
        )

        assertEquals(expected, actual)
        assertFalse(fallbackCalled)
    }

    @Test
    fun missingNativeLibraryUsesTheKotlinSchedule() {
        val actual = NativeEasuStagePlanner.selectStages(
            source = source,
            target = target,
            nativePlanner = { null },
            fallback = { RenderPolicy.easuStagesFallback(source, target) },
        )

        assertEquals(expected, actual)
    }

    @Test
    fun linkageFailureUsesTheKotlinSchedule() {
        val actual = NativeEasuStagePlanner.selectStages(
            source = source,
            target = target,
            nativePlanner = { throw UnsatisfiedLinkError("test native failure") },
            fallback = { RenderPolicy.easuStagesFallback(source, target) },
        )

        assertEquals(expected, actual)
    }

    @Test
    fun kotlinReferenceScheduleAvoidsIntegerOverflowForLargePositiveDimensions() {
        val source = PixelSize(1, 1)
        val target = PixelSize(Int.MAX_VALUE, 1)
        val stages = RenderPolicy.easuStagesFallback(source, target)
        assertEquals(target, stages.lastOrNull())

        var previous = source
        for (stage in stages) {
            assertTrue(stage.width.toLong() <= previous.width.toLong() * 2L)
            previous = stage
        }
    }

    @Test
    fun malformedNativeStagesAreRejectedBeforeRendering() {
        var fallbackCalled = false
        val actual = NativeEasuStagePlanner.selectStages(
            source = source,
            target = target,
            nativePlanner = { listOf(target) },
            fallback = {
                fallbackCalled = true
                RenderPolicy.easuStagesFallback(source, target)
            },
        )

        assertEquals(expected, actual)
        assertTrue(fallbackCalled)
    }
}
