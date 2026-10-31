package org.nightknight.scale.tv

/** Select a reproducible packaged H.264 sample that stays near a 2×-per-axis EASU upscale. */
internal data class VideoTestSource(
    val assetPath: String,
    val size: PixelSize,
) {
    val label: String
        get() = "${size.width}×${size.height} H.264 / 30 fps"

    companion object {
        fun forTarget(target: PixelSize): VideoTestSource = when {
            target.width >= 3840 && target.height >= 2160 -> source(1920, 1080)
            target.width >= 2560 && target.height >= 1440 -> source(1280, 720)
            target.width >= 1920 && target.height >= 1080 -> source(960, 540)
            else -> source(640, 360)
        }

        private fun source(width: Int, height: Int) = VideoTestSource(
            assetPath = "test-videos/scale-test-${width}x${height}.mp4",
            size = PixelSize(width, height),
        )
    }
}
