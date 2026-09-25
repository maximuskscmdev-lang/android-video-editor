package com.videoeditor.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

class Decoder(private val context: Context, private val uri: Uri) {
    val extractor = MediaExtractor()
    var videoTrackIndex: Int = -1
        private set
    var videoFormat: MediaFormat? = null
        private set
    var durationUs: Long = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var mime: String = ""
        private set

    fun init() {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        } ?: throw IllegalArgumentException("Cannot open $uri")

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = fmt
                mime = m
                width = try { fmt.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { 0 }
                height = try { fmt.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { 0 }
                durationUs = try { fmt.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
                extractor.selectTrack(i)
                break
            }
        }
        check(videoTrackIndex != -1) { "No video track in $uri" }
    }

    fun release() { try { extractor.release() } catch (_: Exception) {} }
}
