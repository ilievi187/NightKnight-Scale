package org.nightknight.scale.tv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PixelSize(val width: Int, val height: Int) {
    override fun toString(): String = "${width}×${height}"
}

internal data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

internal object RenderPolicy {
    /** Soft working-set budget for app-owned RGBA8 render targets; source allocations are irreducible. */
    const val MAX_RGBA_TEXTURE_BYTES = 128L * 1024L * 1024L

    /** A half-width/half-height chart keeps the EASU demo within its 4x area range. */
    fun testSourceFor(target: PixelSize) = PixelSize(
        max(1, target.width / 2),
        max(1, target.height / 2),
    )

    /** RCAS sharpness is expressed in stops: zero is strongest, each 4 steps halves it. */
    fun rcasScaleFor(sliderProgress: Int): Float =
        2.0f.pow(-(sliderProgress.coerceIn(0, 20) / 4.0f))

    /** Select the largest aspect-preserving output that fits display and GLES texture limits. */
    fun targetForSource(surface: PixelSize, source: PixelSize, maxTextureSize: Int): PixelSize {
        if (surface.width <= 0 || surface.height <= 0 || source.width <= 0 || source.height <= 0 || maxTextureSize <= 0) {
            return PixelSize(0, 0)
        }
        val scale = minOf(
            surface.width.toFloat() / source.width,
            surface.height.toFloat() / source.height,
            maxTextureSize.toFloat() / source.width,
            maxTextureSize.toFloat() / source.height,
        )
        val ideal = PixelSize(
            max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()),
        )
        if (source.width > ideal.width || source.height > ideal.height) return ideal

        var candidate = ideal
        repeat(16) {
            val estimate = estimatedRgbaTextureBytes(source, candidate, surface)
            if (estimate <= MAX_RGBA_TEXTURE_BYTES) return candidate
            val reduction = minOf(0.90, sqrt(MAX_RGBA_TEXTURE_BYTES.toDouble() / estimate) * 0.95).toFloat()
            val next = PixelSize(
                max(source.width, (candidate.width * reduction).roundToInt()),
                max(source.height, (candidate.height * reduction).roundToInt()),
            )
            if (next == candidate) return candidate
            candidate = next
        }
        return candidate
    }

    /**
     * Estimate simultaneously allocated RGBA8 render-target bytes, including source+NR,
     * output, optional separate RCAS target, and two reusable EASU intermediate textures.
     * Decoder/OES buffers, driver alignment, UI and compositor memory are excluded.
     */
    fun estimatedRgbaTextureBytes(source: PixelSize, target: PixelSize, surface: PixelSize): Long {
        if (source.width <= 0 || source.height <= 0 || target.width <= 0 || target.height <= 0) return 0L
        fun bytes(size: PixelSize) = size.width.toLong() * size.height * 4L
        var total = bytes(source) * 2L + bytes(target)
        if (target != surface) total += bytes(target)
        val intermediateStages = easuStages(source, target).dropLast(1)
        if (intermediateStages.isNotEmpty()) {
            val intermediateSize = PixelSize(
                intermediateStages.maxOf { it.width },
                intermediateStages.maxOf { it.height },
            )
            total += bytes(intermediateSize) * minOf(2, intermediateStages.size)
        }
        return total
    }

    /** Center the source in the display without stretching or cropping it. */
    fun contentViewport(surface: PixelSize, source: PixelSize): PixelRect {
        if (surface.width <= 0 || surface.height <= 0 || source.width <= 0 || source.height <= 0) {
            return PixelRect(0, 0, 0, 0)
        }
        val scale = minOf(surface.width.toFloat() / source.width, surface.height.toFloat() / source.height)
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return PixelRect((surface.width - width) / 2, (surface.height - height) / 2, width, height)
    }

    /** Center the video geometry (SAR, rotation, and DAR mode) in the display with exact DAR aspect-fitting. */
    fun contentViewport(
        surface: PixelSize,
        geometry: VideoGeometry,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
    ): PixelRect {
        if (surface.width <= 0 || surface.height <= 0 || geometry.codedWidth <= 0 || geometry.codedHeight <= 0) {
            return PixelRect(0, 0, 0, 0)
        }
        val dar = geometry.displayAspectRatio(aspectMode)
        val surfaceAspect = surface.width.toDouble() / surface.height.toDouble()
        val width: Int
        val height: Int
        if (dar >= surfaceAspect) {
            width = surface.width
            height = max(1, (surface.width / dar).roundToInt())
        } else {
            height = surface.height
            width = max(1, (surface.height * dar).roundToInt())
        }
        return PixelRect((surface.width - width) / 2, (surface.height - height) / 2, width, height)
    }

    /** Select the largest aspect-preserving output for a video geometry. */
    fun targetForGeometry(
        surface: PixelSize,
        geometry: VideoGeometry,
        maxTextureSize: Int,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
    ): PixelSize {
        if (surface.width <= 0 || surface.height <= 0 || geometry.codedWidth <= 0 || geometry.codedHeight <= 0 || maxTextureSize <= 0) {
            return PixelSize(0, 0)
        }
        val displaySize = geometry.effectiveDisplaySize(aspectMode)
        return targetForSource(surface, displaySize, maxTextureSize)
    }

    /** Generate an EASU chain with no stage exceeding 2× per axis. */
    fun easuStages(source: PixelSize, target: PixelSize): List<PixelSize> {
        if (source.width <= 0 || source.height <= 0 || target.width <= 0 || target.height <= 0) return emptyList()
        if (target.width < source.width || target.height < source.height) return listOf(target)
        val stages = mutableListOf<PixelSize>()
        var current = source
        while (current != target) {
            val next = PixelSize(minOf(target.width, current.width * 2), minOf(target.height, current.height * 2))
            if (next == current) return emptyList()
            stages += next
            current = next
        }
        return stages
    }

    /** Keep the intermediate texture within the device's reported GLES limit. */
    fun targetFor(surface: PixelSize, maxTextureSize: Int): PixelSize {
        if (surface.width <= 0 || surface.height <= 0 || maxTextureSize <= 0) {
            return PixelSize(0, 0)
        }
        val scale = min(
            1.0f,
            min(maxTextureSize.toFloat() / surface.width, maxTextureSize.toFloat() / surface.height),
        )
        return PixelSize(
            max(1, (surface.width * scale).roundToInt()),
            max(1, (surface.height * scale).roundToInt()),
        )
    }
}
