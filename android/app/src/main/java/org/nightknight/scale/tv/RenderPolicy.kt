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

internal data class GlViewport(val x: Int, val y: Int, val width: Int, val height: Int)

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
    fun targetForSource(
        surface: PixelSize,
        source: PixelSize,
        maxTextureSize: Int,
        includeNoiseReduction: Boolean = true,
        includeEasu: Boolean = true,
        includeRcas: Boolean = true,
    ): PixelSize {
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
            val estimate = estimatedRgbaTextureBytes(
                source,
                candidate,
                surface,
                includeNoiseReduction = includeNoiseReduction,
                includeEasu = includeEasu,
                includeRcas = includeRcas,
            )
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

    /** Exact estimate for simultaneously allocated, app-owned RGBA8 targets in the selected render path.
     * Includes source plus optional NR, EASU output and reusable intermediates, and an RCAS scratch target
     * only when needed. Decoder/OES buffers, driver alignment, UI and compositor memory are excluded.
     */
    fun estimatedRgbaTextureBytes(
        source: PixelSize,
        target: PixelSize,
        surface: PixelSize,
        includeNoiseReduction: Boolean = true,
        includeEasu: Boolean = true,
        includeRcas: Boolean = true,
        includeSourceTexture: Boolean = true,
        rcasIntermediateRequired: Boolean? = null,
    ): Long {
        if (source.width <= 0 || source.height <= 0 || target.width <= 0 || target.height <= 0) return 0L
        fun bytes(size: PixelSize) = size.width.toLong() * size.height * 4L

        var total = if (includeSourceTexture) {
            bytes(source) * if (includeNoiseReduction) 2L else 1L
        } else {
            0L
        }
        if (includeEasu) {
            total += bytes(target)
            if (includeRcas && (rcasIntermediateRequired ?: requiresRcasIntermediate(target, surface, source))) {
                total += bytes(target)
            }
            total += easuIntermediateSizes(source, target).sumOf(::bytes)
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

    /** Select the largest aspect-preserving output under GLES and working-set limits. */
    fun targetForGeometry(
        surface: PixelSize,
        geometry: VideoGeometry,
        maxTextureSize: Int,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
        sourceTextureSize: PixelSize = geometry.orientedSize,
        includeNoiseReduction: Boolean = true,
        includeEasu: Boolean = true,
        includeRcas: Boolean = true,
    ): PixelSize {
        val displaySize = geometry.effectiveDisplaySize(aspectMode)
        if (surface.width <= 0 || surface.height <= 0 || displaySize.width <= 0 || displaySize.height <= 0 ||
            sourceTextureSize.width <= 0 || sourceTextureSize.height <= 0 || maxTextureSize <= 0
        ) {
            return PixelSize(0, 0)
        }
        val scale = minOf(
            surface.width.toFloat() / displaySize.width,
            surface.height.toFloat() / displaySize.height,
            maxTextureSize.toFloat() / displaySize.width,
            maxTextureSize.toFloat() / displaySize.height,
        )
        val ideal = PixelSize(
            max(1, (displaySize.width * scale).roundToInt()),
            max(1, (displaySize.height * scale).roundToInt()),
        )
        if (sourceTextureSize.width > ideal.width || sourceTextureSize.height > ideal.height) return ideal

        var candidate = ideal
        repeat(16) {
            val estimate = estimatedRgbaTextureBytes(
                source = sourceTextureSize,
                target = candidate,
                surface = surface,
                includeNoiseReduction = includeNoiseReduction,
                includeEasu = includeEasu,
                includeRcas = includeRcas,
                rcasIntermediateRequired = requiresRcasIntermediate(candidate, surface, geometry, aspectMode),
            )
            if (estimate <= MAX_RGBA_TEXTURE_BYTES) return candidate
            val reduction = minOf(0.90, sqrt(MAX_RGBA_TEXTURE_BYTES.toDouble() / estimate) * 0.95).toFloat()
            val next = PixelSize(
                max(sourceTextureSize.width, (candidate.width * reduction).roundToInt()),
                max(sourceTextureSize.height, (candidate.height * reduction).roundToInt()),
            )
            if (next == candidate) return candidate
            candidate = next
        }
        return candidate
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

    /** Maximum dimensions needed by the two reusable ping-pong texture slots. */
    fun easuIntermediateSizes(source: PixelSize, target: PixelSize): List<PixelSize> {
        val slots = arrayOfNulls<PixelSize>(2)
        easuStages(source, target).dropLast(1).forEachIndexed { index, stage ->
            val slot = index % slots.size
            val current = slots[slot]
            slots[slot] = if (current == null) {
                stage
            } else {
                PixelSize(max(current.width, stage.width), max(current.height, stage.height))
            }
        }
        return slots.filterNotNull()
    }

    /** Whether RCAS needs a same-sized scratch texture before aspect-fit presentation. */
    fun requiresRcasIntermediate(target: PixelSize, surface: PixelSize, source: PixelSize): Boolean {
        if (target.width <= 0 || target.height <= 0) return true
        val viewport = contentViewport(surface, source)
        return viewport.width <= 0 || viewport.height <= 0 ||
            target != PixelSize(viewport.width, viewport.height)
    }

    /** Geometry-aware RCAS routing, including SAR and user-selected aspect overrides. */
    fun requiresRcasIntermediate(
        target: PixelSize,
        surface: PixelSize,
        geometry: VideoGeometry,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
    ): Boolean {
        if (target.width <= 0 || target.height <= 0) return true
        val viewport = contentViewport(surface, geometry, aspectMode)
        return viewport.width <= 0 || viewport.height <= 0 ||
            target != PixelSize(viewport.width, viewport.height)
    }

    /** Android's GL viewport uses a bottom-left origin; this maps the centered fit rectangle. */
    fun glViewportForContent(surface: PixelSize, source: PixelSize): GlViewport {
        val viewport = contentViewport(surface, source)
        if (viewport.width <= 0 || viewport.height <= 0) return GlViewport(0, 0, 0, 0)
        return GlViewport(
            x = viewport.left,
            y = surface.height - viewport.top - viewport.height,
            width = viewport.width,
            height = viewport.height,
        )
    }

    /** Geometry-aware GL viewport using the selected DAR, not coded pixel ratio. */
    fun glViewportForContent(
        surface: PixelSize,
        geometry: VideoGeometry,
        aspectMode: AspectRatioMode = AspectRatioMode.AUTO,
    ): GlViewport {
        val viewport = contentViewport(surface, geometry, aspectMode)
        if (viewport.width <= 0 || viewport.height <= 0) return GlViewport(0, 0, 0, 0)
        return GlViewport(
            x = viewport.left,
            y = surface.height - viewport.top - viewport.height,
            width = viewport.width,
            height = viewport.height,
        )
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
