package org.nightknight.scale.tv

/** Select a reproducible packaged H.264 sample that stays near a 2×-per-axis EASU upscale. */
internal data class VideoTestSource(
    val assetPath: String,
    val size: PixelSize,
    val frameRate: Float = 30.0f,
) {
    val label: String
        get() = "${size.width}×${size.height} H.264 / ${frameRate.toInt()} fps"

    companion object {
        fun fromProfileId(profileId: String?): VideoTestSource? = when (profileId) {
            "960x540-25" -> source(960, 540, 25f, "test-videos/scale-test-960x540-25fps.mp4")
            "960x540-50" -> source(960, 540, 50f, "test-videos/scale-test-960x540-50fps.mp4")
            "960x540-60" -> source(960, 540, 60f, "test-videos/scale-test-960x540-60fps.mp4")
            "720x576-4x3-25" -> source(720, 576, 25f, "test-videos/scale-test-720x576-4x3-25fps.mp4")
            "720x576-16x9-25" -> source(720, 576, 25f, "test-videos/scale-test-720x576-16x9-25fps.mp4")
            else -> fromDimensions(profileId)
        }

        fun fromDimensions(dimensions: String?): VideoTestSource? = when (dimensions) {
            "640x360" -> source(640, 360)
            "960x540" -> source(960, 540)
            "1280x720" -> source(1280, 720)
            "720x576" -> source(720, 576, 25f)
            "1920x1080" -> source(1920, 1080)
            else -> null
        }

        fun forTarget(target: PixelSize): VideoTestSource = when {
            target.width >= 3840 && target.height >= 2160 -> source(1920, 1080)
            target.width >= 2560 && target.height >= 1440 -> source(1280, 720)
            target.width >= 1920 && target.height >= 1080 -> source(960, 540)
            else -> source(640, 360)
        }

        private fun source(width: Int, height: Int, frameRate: Float = 30f, path: String? = null) = VideoTestSource(
            assetPath = path ?: "test-videos/scale-test-${width}x${height}.mp4",
            size = PixelSize(width, height),
            frameRate = frameRate,
        )
    }
}
