package com.videoeditor.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

class MuxerWrapper(private val outPath: String) {
    private var muxer: MediaMuxer? = null
    private var videoTrackIdx: Int = -1
    private var audioTrackIdx: Int = -1
    private var started = false

    fun init(): MediaMuxer {
        muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return muxer!!
    }

    fun addVideoTrack(format: MediaFormat): Int {
        videoTrackIdx = muxer!!.addTrack(format)
        tryStart()
        return videoTrackIdx
    }

    fun addAudioTrack(format: MediaFormat): Int {
        audioTrackIdx = muxer!!.addTrack(format)
        tryStart()
        return audioTrackIdx
    }

    private fun tryStart() {
        if (!started && videoTrackIdx != -1) { // start when video ready; audio optional
            muxer!!.start(); started = true
        }
    }

    fun writeVideoSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started) muxer!!.writeSampleData(videoTrackIdx, buf, info)
    }

    fun writeAudioSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started && audioTrackIdx != -1) muxer!!.writeSampleData(audioTrackIdx, buf, info)
    }

    fun release() {
        try { if (started) muxer?.stop() } catch (_: Exception) {}
        muxer?.release()
    }
}
