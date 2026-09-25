package com.videoeditor.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

data class ProbeResult(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val mime: String,
    val rotation: Int
)

object AssetProbe {

    fun probe(context: Context, uri: Uri): ProbeResult {
        // Try MediaExtractor via FD first (more accurate for duration)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            val durUs = try { fmt.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
                            val w = try { fmt.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { 0 }
                            val h = try { fmt.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { 0 }
                            val rot = try { fmt.getInteger(MediaFormat.KEY_ROTATION) } catch (_: Exception) { 0 }
                            extractor.release()
                            val durMs = if (durUs > 0) durUs / 1000 else probeViaRetriever(context, uri)?.durationMs ?: 0L
                            return ProbeResult(durMs.coerceAtLeast(1L), w, h, mime, rot)
                        }
                    }
                    extractor.release()
                } catch (_: Exception) {
                    try { extractor.release() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Fallback to retriever
        return probeViaRetriever(context, uri) ?: ProbeResult(10_000L, 0, 0, "video/avc", 0)
    }

    private fun probeViaRetriever(context: Context, uri: Uri): ProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/avc"
            ProbeResult(
                durationMs = (durStr?.toLongOrNull() ?: 10_000L).coerceAtLeast(1L),
                width = wStr?.toIntOrNull() ?: 0,
                height = hStr?.toIntOrNull() ?: 0,
                mime = mime,
                rotation = rotStr?.toIntOrNull() ?: 0
            )
        } catch (_: Exception) { null } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
