package com.videoeditor.engine

import android.media.MediaExtractor
import android.media.MediaFormat

class Decoder(private val path: String) {
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
        extractor.setDataSource(path)
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = fmt
                mime = m
                width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                extractor.selectTrack(i)
                break
            }
        }
        check(videoTrackIndex != -1) { "No video track in $path" }
    }

    fun seekTo(us: Long) { extractor.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }

    fun release() { extractor.release() }
}
