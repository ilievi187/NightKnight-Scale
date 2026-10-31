package org.nightknight.scale.tv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class PixelSize(val width: Int, val height: Int) {
    override fun toString(): String = "${width}×${height}"
}

internal object RenderPolicy {
    /** A half-width/half-height chart keeps the EASU demo within its 4x area range. */
    fun testSourceFor(target: PixelSize) = PixelSize(
        max(1, target.width / 2),
        max(1, target.height / 2),
    )

    /** RCAS sharpness is expressed in stops: zero is strongest, each 4 steps halves it. */
    fun rcasScaleFor(sliderProgress: Int): Float =
        2.0f.pow(-(sliderProgress.coerceIn(0, 20) / 4.0f))

    /** Keep EASU's per-axis scale at or below 2× (4× area) for arbitrary decoded inputs. */
    fun targetForSource(surface: PixelSize, source: PixelSize, maxTextureSize: Int): PixelSize {
        if (surface.width <= 0 || surface.height <= 0 || source.width <= 0 || source.height <= 0 || maxTextureSize <= 0) {
            return PixelSize(0, 0)
        }
        val scale = minOf(
            1.0f,
            maxTextureSize.toFloat() / surface.width,
            maxTextureSize.toFloat() / surface.height,
            (source.width * 2f) / surface.width,
            (source.height * 2f) / surface.height,
        )
        return PixelSize(
            max(1, (surface.width * scale).roundToInt()),
            max(1, (surface.height * scale).roundToInt()),
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
