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
 * Probes container and bitstream metadata (dimensions, rotation, SAR, and colorimetry).
 */
internal object VideoMetadataReader {
    private const val TAG = "NKScaleTV"

    data class VideoInfo(
        val geometry: VideoGeometry,
        val mimeType: String = "video/mp4",
        val durationMs: Long = 0L,
        val frameRate: Float = 0f,
    )

    fun probeUri(context: Context, uri: Uri): VideoInfo? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val info = extractFromRetriever(retriever)
            retriever.release()

            // Augment with bitstream SAR/colorimetry from MediaExtractor if available
            val extractorInfo = probeWithExtractor(context, uri)
            if (extractorInfo != null) {
                mergeInfo(info, extractorInfo)
            } else {
                info
            }
        }.onFailure { e ->
            Log.w(TAG, "metadata=probe-uri-failed uri=$uri", e)
        }.getOrNull()
    }

    fun probeFile(file: File): VideoInfo? {
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val info = extractFromRetriever(retriever)
            retriever.release()

            val extractorInfo = probeWithExtractor(file)
            if (extractorInfo != null) {
                mergeInfo(info, extractorInfo)
            } else {
                info
            }
        }.onFailure { e ->
            Log.w(TAG, "metadata=probe-file-failed path=${file.absolutePath}", e)
        }.getOrNull()
    }

    fun probeAsset(context: Context, assetPath: String): VideoInfo? {
        return runCatching {
            val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val info = extractFromRetriever(retriever)
            retriever.release()
            afd.close()
            info
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

    private fun extractFromExtractor(extractor: MediaExtractor): VideoInfo? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
                val sarW = if (format.containsKey("sar-width")) format.getInteger("sar-width") else 1
                val sarH = if (format.containsKey("sar-height")) format.getInteger("sar-height") else 1

                val colorStandard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
                val colorTransfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                val colorRange = if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) format.getInteger(MediaFormat.KEY_COLOR_RANGE) else 0

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
                return VideoInfo(geometry = geometry, mimeType = mime)
            }
        }
        return null
    }

    private fun mergeInfo(primary: VideoInfo, secondary: VideoInfo): VideoInfo {
        val primGeo = primary.geometry
        val secGeo = secondary.geometry

        val sarW = if (secGeo.sarWidth > 1) secGeo.sarWidth else primGeo.sarWidth
        val sarH = if (secGeo.sarHeight > 1) secGeo.sarHeight else primGeo.sarHeight
        val colStd = if (secGeo.colorStandard != 0) secGeo.colorStandard else primGeo.colorStandard
        val colTrans = if (secGeo.colorTransfer != 0) secGeo.colorTransfer else primGeo.colorTransfer
        val colRange = if (secGeo.colorRange != 0) secGeo.colorRange else primGeo.colorRange
        val rot = if (primGeo.rotationDegrees != 0) primGeo.rotationDegrees else secGeo.rotationDegrees

        return primary.copy(
            geometry = primGeo.copy(
                sarWidth = sarW,
                sarHeight = sarH,
                rotationDegrees = rot,
                colorStandard = colStd,
                colorTransfer = colTrans,
                colorRange = colRange,
            ),
        )
    }
}
