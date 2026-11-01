package org.nightknight.scale.tv

/** Enables direct SurfaceTexture sampling only after the optional pixel comparison passed. */
internal object EasuExternalInputPolicy {
    fun shouldUseDirectInput(
        programAvailable: Boolean,
        outputEquivalent: Boolean,
        externalTextureAvailable: Boolean,
        mode: RenderMode,
    ): Boolean =
        programAvailable &&
            outputEquivalent &&
            externalTextureAvailable &&
            mode.usesEasu &&
            !mode.usesNoiseReduction
}
