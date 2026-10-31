package org.nightknight.scale.tv

/** User-facing profiles map directly to existing, distinct GLES paths. */
internal enum class UpscalePreset(
    val id: String,
    val label: String,
    val renderMode: RenderMode,
    val defaultSharpness: Int,
) {
    OFF("off", "OFF", RenderMode.BILINEAR, 8),
    QUALITY("quality", "QUALITY", RenderMode.FSR1_DENOISE, 8),
    BALANCED("balanced", "BALANCED", RenderMode.FSR1, 8),
    PERFORMANCE("performance", "PERFORMANCE", RenderMode.EASU_ONLY, 8);

    companion object {
        fun fromId(id: String?): UpscalePreset = entries.firstOrNull { it.id == id } ?: BALANCED
        fun fromRenderMode(mode: RenderMode): UpscalePreset = entries.first { it.renderMode == mode }
    }
}
