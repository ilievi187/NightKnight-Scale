package org.nightknight.scale.tv

internal enum class RenderMode(
    val id: String,
    val label: String,
    val summary: String,
) {
    BILINEAR(
        id = "bilinear",
        label = "Original · Bilinear",
        summary = "Original output · bilinear upscale only",
    ),
    EASU_ONLY(
        id = "easu_only",
        label = "EASU only",
        summary = "Enhanced output · EASU only, RCAS off",
    ),
    FSR1(
        id = "fsr1",
        label = "FSR 1.0",
        summary = "Enhanced output · AMD FSR 1.0 EASU + RCAS",
    ),
    FSR1_DENOISE(
        id = "fsr1_denoise",
        label = "FSR + NR",
        summary = "Enhanced output · mild edge-aware NR + EASU + RCAS",
    );

    fun nextAlgorithm(): RenderMode = when (this) {
        BILINEAR -> EASU_ONLY
        EASU_ONLY -> FSR1
        FSR1 -> FSR1_DENOISE
        FSR1_DENOISE -> EASU_ONLY
    }

    val usesEasu: Boolean
        get() = this != BILINEAR

    val usesRcas: Boolean
        get() = this == FSR1 || this == FSR1_DENOISE

    val usesNoiseReduction: Boolean
        get() = this == FSR1_DENOISE
}

internal object FilterPolicy {
    /** Mild default intended to suppress low-amplitude noise without aggressive smoothing. */
    const val DEFAULT_DENOISE_STRENGTH = 0.35f
}
