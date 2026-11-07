package org.nightknight.scale.tv

import kotlin.math.log10
import kotlin.math.sqrt

internal data class EasuOutputDifference(
    val comparedChannels: Int,
    val meanAbsoluteError: Float,
    val maximumAbsoluteError: Int,
    val psnrDb: Float,
    val maximumErrorByteOffset: Int,
    val maximumErrorChannelIndex: Int,
    val redMeanAbsoluteError: Float,
    val greenMeanAbsoluteError: Float,
    val blueMeanAbsoluteError: Float,
    val alphaMeanAbsoluteError: Float,
)

/** Pixel-domain check for direct OES EASU against the RGBA-copy reference path. */
internal object EasuQualityComparison {
    private const val MAX_MEAN_ABSOLUTE_ERROR = 0.25f
    private const val MAX_CHANNEL_ERROR = 4
    private const val PEAK_CHANNEL_VALUE = 255.0

    fun compare(reference: ByteArray, candidate: ByteArray): EasuOutputDifference? {
        if (reference.isEmpty() || reference.size != candidate.size || reference.size % 4 != 0) return null
        var absoluteErrorTotal = 0L
        var squaredErrorTotal = 0.0
        var maximumAbsoluteError = 0
        var maximumErrorByteOffset = 0
        val channelErrorTotals = LongArray(4)
        for (index in reference.indices) {
            val delta = (reference[index].toInt() and 0xff) - (candidate[index].toInt() and 0xff)
            val absoluteError = kotlin.math.abs(delta)
            absoluteErrorTotal += absoluteError
            squaredErrorTotal += delta.toDouble() * delta.toDouble()
            channelErrorTotals[index % 4] += absoluteError
            if (absoluteError > maximumAbsoluteError) {
                maximumAbsoluteError = absoluteError
                maximumErrorByteOffset = index
            }
        }
        val pixelCount = reference.size / 4
        val mse = squaredErrorTotal / reference.size
        val psnrDb = if (mse == 0.0) {
            Float.POSITIVE_INFINITY
        } else {
            (20.0 * log10(PEAK_CHANNEL_VALUE / sqrt(mse))).toFloat()
        }
        return EasuOutputDifference(
            comparedChannels = reference.size,
            meanAbsoluteError = absoluteErrorTotal.toFloat() / reference.size,
            maximumAbsoluteError = maximumAbsoluteError,
            psnrDb = psnrDb,
            maximumErrorByteOffset = maximumErrorByteOffset,
            maximumErrorChannelIndex = maximumErrorByteOffset % 4,
            redMeanAbsoluteError = channelErrorTotals[0].toFloat() / pixelCount,
            greenMeanAbsoluteError = channelErrorTotals[1].toFloat() / pixelCount,
            blueMeanAbsoluteError = channelErrorTotals[2].toFloat() / pixelCount,
            alphaMeanAbsoluteError = channelErrorTotals[3].toFloat() / pixelCount,
        )
    }

    fun isEquivalent(difference: EasuOutputDifference): Boolean =
        difference.meanAbsoluteError <= MAX_MEAN_ABSOLUTE_ERROR &&
            difference.maximumAbsoluteError <= MAX_CHANNEL_ERROR
}
