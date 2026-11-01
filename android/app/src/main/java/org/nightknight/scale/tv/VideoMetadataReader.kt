package org.nightknight.scale.tv

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Deterministic metadata extraction from video assets, local files, and content URIs.
 * Reads metadata exposed by MediaMetadataRetriever/MediaExtractor; it does not parse bitstreams itself.
 */
internal object VideoMetadataReader {
    private const val TAG = "NKScaleTV"

    data class VideoInfo(
        val geometry: VideoGeometry,
        val mimeType: String = "video/mp4",
        val durationMs: Long = 0L,
        val frameRate: Float = 0f,
        val hasSampleAspectRatioMetadata: Boolean = false,
        val hasDisplayAspectRatioMetadata: Boolean = false,
    )

    fun probeUri(context: Context, uri: Uri): VideoInfo? {
        val primary = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                extractFromRetriever(retriever)
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "metadata=probe-uri-failed uri=$uri", e)
        }.getOrNull()
        val secondary = probeWithExtractor(context, uri)
        return when {
            primary != null && secondary != null -> mergeInfo(primary, secondary)
            primary != null -> primary
            else -> secondary
        }
    }

    fun probeFile(file: File): VideoInfo? {
        if (!file.exists() || !file.canRead()) return null
        val primary = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                extractFromRetriever(retriever)
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "metadata=probe-file-failed path=${file.absolutePath}", e)
        }.getOrNull()
        val secondary = probeWithExtractor(file)
        return when {
            primary != null && secondary != null -> mergeInfo(primary, secondary)
            primary != null -> primary
            else -> secondary
        }
    }

    fun probeAsset(context: Context, assetPath: String): VideoInfo? {
        return runCatching {
            val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    extractFromRetriever(retriever)
                } finally {
                    runCatching { retriever.release() }
                }
            } finally {
                runCatching { afd.close() }
            }
        }.onFailure { e ->
            Log.w(TAG, "metadata=probe-asset-failed asset=$assetPath", e)
        }.getOrNull()
    }

    private fun extractFromRetriever(retriever: MediaMetadataRetriever): VideoInfo {
        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

        val width = widthStr?.toIntOrNull() ?: 0
        val height = heightStr?.toIntOrNull() ?: 0
        val rotation = rotationStr?.toIntOrNull() ?: 0
        val duration = durationStr?.toLongOrNull() ?: 0L

        var colorStandard = 0
        var colorTransfer = 0
        var colorRange = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()?.let {
                colorStandard = it
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()?.let {
                colorTransfer = it
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_RANGE)?.toIntOrNull()?.let {
                colorRange = it
            }
        }

        // Infer PAL/NTSC SAR if standard resolution
        val (sarW, sarH) = when {
            VideoGeometry.isPalResolution(width, height) -> VideoGeometry.PAL_4_3_SAR
            VideoGeometry.isNtscResolution(width, height) -> VideoGeometry.NTSC_4_3_SAR
            else -> Pair(1, 1)
        }

        val geometry = VideoGeometry(
            codedWidth = width,
            codedHeight = height,
            rotationDegrees = rotation,
            sarWidth = sarW,
            sarHeight = sarH,
            colorStandard = colorStandard,
            colorTransfer = colorTransfer,
            colorRange = colorRange,
        )

        return VideoInfo(
            geometry = geometry,
            mimeType = mime,
            durationMs = duration,
        )
    }

    private fun probeWithExtractor(context: Context, uri: Uri): VideoInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            extractFromExtractor(extractor)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun probeWithExtractor(file: File): VideoInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractFromExtractor(extractor)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun intOrNull(format: MediaFormat, key: String): Int? =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null

    private fun intOrDefault(format: MediaFormat, key: String): Int = intOrNull(format, key) ?: 0

    /** Derive visible DAR from Android's inclusive crop rectangle and SAR when available. */
    internal fun deriveDisplayAspectRatio(
        width: Int,
        height: Int,
        sarWidth: Int = 1,
        sarHeight: Int = 1,
        cropLeft: Int? = null,
        cropTop: Int? = null,
        cropRight: Int? = null,
        cropBottom: Int? = null,
        displayWidth: Int = 0,
        displayHeight: Int = 0,
    ): Double? {
        if (displayWidth > 0 && displayHeight > 0) {
            return (displayWidth.toDouble() / displayHeight).takeIf { it.isFinite() && it > 0.0 }
        }
        if (width <= 0 || height <= 0) return null
        val hasCropMetadata = cropLeft != null || cropTop != null || cropRight != null || cropBottom != null
        if (!hasCropMetadata) return null

        val left = (cropLeft ?: 0).coerceIn(0, width - 1)
        val top = (cropTop ?: 0).coerceIn(0, height - 1)
        val right = (cropRight ?: (width - 1)).coerceIn(0, width - 1)
        val bottom = (cropBottom ?: (height - 1)).coerceIn(0, height - 1)
        if (right < left || bottom < top) return null

        val visibleWidth = right - left + 1
        val visibleHeight = bottom - top + 1
        val safeSarWidth = sarWidth.takeIf { it > 0 } ?: 1
        val safeSarHeight = sarHeight.takeIf { it > 0 } ?: 1
        return (visibleWidth.toDouble() * safeSarWidth / (visibleHeight.toDouble() * safeSarHeight))
            .takeIf { it.isFinite() && it > 0.0 }
    }

    private fun extractFromExtractor(extractor: MediaExtractor): VideoInfo? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                val width = intOrDefault(format, MediaFormat.KEY_WIDTH)
                val height = intOrDefault(format, MediaFormat.KEY_HEIGHT)
                val rotation = intOrDefault(format, "rotation-degrees")
                val sarWidthValue = intOrNull(format, "sar-width")
                val sarHeightValue = intOrNull(format, "sar-height")
                val hasSarMetadata = sarWidthValue != null && sarHeightValue != null && sarWidthValue > 0 && sarHeightValue > 0
                val sarW = if (hasSarMetadata) sarWidthValue!! else 1
                val sarH = if (hasSarMetadata) sarHeightValue!! else 1
                val displayWidth = intOrDefault(format, "display-width")
                val displayHeight = intOrDefault(format, "display-height")
                val darRatio = deriveDisplayAspectRatio(
                    width = width,
                    height = height,
                    sarWidth = sarW,
                    sarHeight = sarH,
                    cropLeft = intOrNull(format, "crop-left"),
                    cropTop = intOrNull(format, "crop-top"),
                    cropRight = intOrNull(format, "crop-right"),
                    cropBottom = intOrNull(format, "crop-bottom"),
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                )
                val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        .getOrElse { runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f) }
                        .takeIf { it.isFinite() && it > 0f } ?: 0f
                } else {
                    0f
                }

                val colorStandard = intOrDefault(format, MediaFormat.KEY_COLOR_STANDARD)
                val colorTransfer = intOrDefault(format, MediaFormat.KEY_COLOR_TRANSFER)
                val colorRange = intOrDefault(format, MediaFormat.KEY_COLOR_RANGE)

                val geometry = VideoGeometry(
                    codedWidth = width,
                    codedHeight = height,
                    rotationDegrees = rotation,
                    sarWidth = sarW,
                    sarHeight = sarH,
                    sarIsExplicit = hasSarMetadata,
                    darRatio = darRatio,
                    colorStandard = colorStandard,
                    colorTransfer = colorTransfer,
                    colorRange = colorRange,
                )
                return VideoInfo(
                    geometry = geometry,
                    mimeType = mime,
                    frameRate = frameRate,
                    hasSampleAspectRatioMetadata = hasSarMetadata,
                    hasDisplayAspectRatioMetadata = darRatio != null,
                )
            }
        }
        return null
    }

    internal fun mergeInfo(primary: VideoInfo, secondary: VideoInfo): VideoInfo {
        val primGeo = primary.geometry
        val secGeo = secondary.geometry
        val useSecondarySar = secondary.hasSampleAspectRatioMetadata
        val useSecondaryDar = secondary.hasDisplayAspectRatioMetadata

        val sarW = if (useSecondarySar) secGeo.sarWidth else primGeo.sarWidth
        val sarH = if (useSecondarySar) secGeo.sarHeight else primGeo.sarHeight
        val dar = if (useSecondaryDar) secGeo.darRatio else primGeo.darRatio
        val colStd = if (secGeo.colorStandard != 0) secGeo.colorStandard else primGeo.colorStandard
        val colTrans = if (secGeo.colorTransfer != 0) secGeo.colorTransfer else primGeo.colorTransfer
        val colRange = if (secGeo.colorRange != 0) secGeo.colorRange else primGeo.colorRange
        val rot = if (primGeo.rotationDegrees != 0) primGeo.rotationDegrees else secGeo.rotationDegrees
        val width = primGeo.codedWidth.takeIf { it > 0 } ?: secGeo.codedWidth
        val height = primGeo.codedHeight.takeIf { it > 0 } ?: secGeo.codedHeight
        val frameRate = secondary.frameRate.takeIf { it > 0f } ?: primary.frameRate

        return primary.copy(
            geometry = primGeo.copy(
                codedWidth = width,
                codedHeight = height,
                sarWidth = sarW,
                sarHeight = sarH,
                sarIsExplicit = primGeo.sarIsExplicit || useSecondarySar,
                darRatio = dar,
                rotationDegrees = rot,
                colorStandard = colStd,
                colorTransfer = colTrans,
                colorRange = colRange,
            ),
            frameRate = frameRate,
            hasSampleAspectRatioMetadata = primary.hasSampleAspectRatioMetadata || useSecondarySar,
            hasDisplayAspectRatioMetadata = primary.hasDisplayAspectRatioMetadata || useSecondaryDar,
        )
    }
}
