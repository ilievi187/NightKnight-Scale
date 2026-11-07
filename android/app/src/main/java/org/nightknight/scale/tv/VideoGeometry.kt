package org.nightknight.scale.tv

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Aspect ratio override options for TV remote control.
 */
internal enum class AspectRatioMode(val id: String, val label: String) {
    AUTO("auto", "Auto (SAR/DAR)"),
    FIT_16_9("16_9", "16:9 Widescreen"),
    FIT_4_3("4_3", "4:3 Standard"),
    SQUARE_PIXELS("1_1", "1:1 Square (Raw)");

    fun next(): AspectRatioMode = when (this) {
        AUTO -> FIT_16_9
        FIT_16_9 -> FIT_4_3
        FIT_4_3 -> SQUARE_PIXELS
        SQUARE_PIXELS -> AUTO
    }

    companion object {
        fun fromId(id: String?): AspectRatioMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Encapsulates video coded dimensions, Sample Aspect Ratio (SAR / non-square pixels),
 * rotation metadata, container DAR, and colorimetry.
 *
 * Implements deterministic Display Aspect Ratio (DAR) and letterbox/pillarbox geometry.
 */
internal data class VideoGeometry(
    val codedWidth: Int,
    val codedHeight: Int,
    val rotationDegrees: Int = 0,
    val sarWidth: Int = 1,
    val sarHeight: Int = 1,
    val darRatio: Double? = null,
    val colorStandard: Int = 0,
    val colorTransfer: Int = 0,
    val colorRange: Int = 0,
    val sarIsExplicit: Boolean = false,
) {
    val normalizedRotation: Int = ((rotationDegrees % 360) + 360) % 360

    val isRotated90or270: Boolean
        get() = normalizedRotation == 90 || normalizedRotation == 270

    val isPortrait: Boolean
        get() = displayAspectRatio(AspectRatioMode.AUTO) < 1.0

    /**
     * Compute the Display Aspect Ratio (width / height) based on SAR, container DAR,
     * rotation, or explicit aspect ratio override.
     */
    fun displayAspectRatio(mode: AspectRatioMode = AspectRatioMode.AUTO): Double {
        if (codedWidth <= 0 || codedHeight <= 0) return 1.0
        val baseAspect: Double = when (mode) {
            AspectRatioMode.FIT_16_9 -> 16.0 / 9.0
            AspectRatioMode.FIT_4_3 -> 4.0 / 3.0
            AspectRatioMode.SQUARE_PIXELS -> codedWidth.toDouble() / codedHeight.toDouble()
            AspectRatioMode.AUTO -> {
                when {
                    darRatio != null && darRatio.isFinite() && darRatio > 0.0 -> darRatio
                    sarWidth > 0 && sarHeight > 0 && (sarIsExplicit || sarWidth != 1 || sarHeight != 1) -> {
                        (codedWidth.toDouble() * sarWidth) / (codedHeight.toDouble() * sarHeight)
                    }
                    isPalResolution(codedWidth, codedHeight) -> 4.0 / 3.0
                    isNtscResolution(codedWidth, codedHeight) -> 4.0 / 3.0
                    else -> codedWidth.toDouble() / codedHeight.toDouble()
                }
            }
        }
        return if (isRotated90or270) 1.0 / baseAspect else baseAspect
    }

    /**
     * Effective unrotated/oriented source dimensions for GLES buffer allocation.
     * When video has 90° or 270° rotation, oriented width and height are transposed.
     */
    val orientedSize: PixelSize
        get() {
            if (codedWidth <= 0 || codedHeight <= 0) return PixelSize(0, 0)
            return if (isRotated90or270) {
                PixelSize(codedHeight, codedWidth)
            } else {
                PixelSize(codedWidth, codedHeight)
            }
        }

    /**
     * Effective display size that incorporates non-square pixels (SAR) and rotation,
     * maintaining square pixels for downstream rendering and viewport calculation.
     */
    fun effectiveDisplaySize(mode: AspectRatioMode = AspectRatioMode.AUTO): PixelSize {
        if (codedWidth <= 0 || codedHeight <= 0) return PixelSize(0, 0)
        val aspect = displayAspectRatio(mode)
        return if (isRotated90or270) {
            val baseHeight = codedWidth
            val baseWidth = max(1, (baseHeight * aspect).roundToInt())
            PixelSize(baseWidth, baseHeight)
        } else {
            val baseHeight = codedHeight
            val baseWidth = max(1, (baseHeight * aspect).roundToInt())
            PixelSize(baseWidth, baseHeight)
        }
    }

    /**
     * Human-readable aspect ratio label.
     */
    fun aspectLabel(mode: AspectRatioMode = AspectRatioMode.AUTO): String {
        val aspect = displayAspectRatio(mode)
        return when {
            kotlin.math.abs(aspect - (16.0 / 9.0)) < 0.02 -> "16:9"
            kotlin.math.abs(aspect - (4.0 / 3.0)) < 0.02 -> "4:3"
            kotlin.math.abs(aspect - (9.0 / 16.0)) < 0.02 -> "9:16 portrait"
            kotlin.math.abs(aspect - (3.0 / 4.0)) < 0.02 -> "3:4 portrait"
            kotlin.math.abs(aspect - 1.0) < 0.02 -> "1:1 square"
            aspect > 1.0 -> "${"%.2f".format(aspect)}:1"
            else -> "1:${"%.2f".format(1.0 / aspect)}"
        }
    }

    /**
     * Human-readable color standard label (ITU-R BT.601, BT.709, BT.2020).
     */
    val colorStandardLabel: String
        get() = when (colorStandard) {
            1 -> "BT.709"
            2 -> "BT.601 PAL"
            4 -> "BT.601 NTSC"
            6 -> "BT.2020"
            else -> "Not reported"
        }

    /**
     * Human-readable color range label.
     */
    val colorRangeLabel: String
        get() = when (colorRange) {
            1 -> "Full range (0-255)"
            2 -> "Limited range (16-235)"
            else -> "Not reported"
        }

    /**
     * Human-readable color transfer label.
     */
    val colorTransferLabel: String
        get() = when (colorTransfer) {
            1 -> "Linear"
            2 -> "Gamma 2.2"
            3 -> "SDR (SMPTE 170M)"
            6 -> "ST 2084 (PQ HDR)"
            7 -> "HLG (BT.2100)"
            else -> "Not reported"
        }

    /**
     * Summary description for TV UI and diagnostics.
     */
    fun summary(mode: AspectRatioMode = AspectRatioMode.AUTO): String = buildString {
        append("${codedWidth}×${codedHeight}")
        if (isRotated90or270 || normalizedRotation == 180) {
            append(" [${normalizedRotation}°]")
        }
        if (sarIsExplicit || sarWidth != 1 || sarHeight != 1) {
            append(" SAR ${sarWidth}:${sarHeight}")
        }
        append(" · DAR ${aspectLabel(mode)}")
        if (mode != AspectRatioMode.AUTO) {
            append(" (${mode.label})")
        }
    }

    companion object {
        val PAL_4_3_SAR = Pair(16, 15)
        val PAL_16_9_SAR = Pair(64, 45)
        val NTSC_4_3_SAR = Pair(8, 9)
        val NTSC_16_9_SAR = Pair(32, 27)

        fun isPalResolution(width: Int, height: Int): Boolean =
            (width == 720 || width == 704) && height == 576

        fun isNtscResolution(width: Int, height: Int): Boolean =
            (width == 720 || width == 704) && height == 480

        fun fromSize(size: PixelSize): VideoGeometry =
            VideoGeometry(codedWidth = size.width, codedHeight = size.height)

        fun pal4x3(width: Int = 720, height: Int = 576): VideoGeometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = 0,
            sarWidth = 16,
            sarHeight = 15,
            sarIsExplicit = true,
            colorStandard = 2, // BT.601 PAL
            colorRange = 2,    // Limited range
        )

        fun pal16x9(width: Int = 720, height: Int = 576): VideoGeometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = 0,
            sarWidth = 64,
            sarHeight = 45,
            sarIsExplicit = true,
            colorStandard = 2, // BT.601 PAL
            colorRange = 2,    // Limited range
        )

        fun ntsc4x3(width: Int = 720, height: Int = 480): VideoGeometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = 0,
            sarWidth = 8,
            sarHeight = 9,
            sarIsExplicit = true,
            colorStandard = 4, // BT.601 NTSC
            colorRange = 2,    // Limited range
        )

        fun ntsc16x9(width: Int = 720, height: Int = 480): VideoGeometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = 0,
            sarWidth = 32,
            sarHeight = 27,
            sarIsExplicit = true,
            colorStandard = 4, // BT.601 NTSC
            colorRange = 2,    // Limited range
        )

        fun portrait(width: Int, height: Int, rotationDegrees: Int = 0): VideoGeometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = rotationDegrees,
        )
    }
}
