package com.videoeditor.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

class MuxerWrapper(private val outPath: String) {
    private var muxer: MediaMuxer? = null
    private var videoTrackIdx: Int = -1
    private var started = false

    fun init() {
        muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun addVideoTrack(format: MediaFormat): Int {
        check(muxer != null) { "Muxer not init" }
        videoTrackIdx = muxer!!.addTrack(format)
        tryStart()
        return videoTrackIdx
    }

    private fun tryStart() {
        if (!started && videoTrackIdx != -1) {
            muxer!!.start()
            started = true
        }
    }

    fun writeVideoSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started) muxer!!.writeSampleData(videoTrackIdx, buf, info)
    }

    fun isStarted(): Boolean = started

    fun release() {
        try { if (started) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        started = false
    }
}
