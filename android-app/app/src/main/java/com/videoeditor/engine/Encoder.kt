package com.videoeditor.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

class Encoder(
    private val outWidth: Int,
    private val outHeight: Int,
    private val bitrate: Int,
    private val fps: Int = 30,
    private val mime: String = "video/avc"
) {
    var codec: MediaCodec? = null
        private set
    var format: MediaFormat? = null
        private set
    var actualWidth: Int = outWidth
        private set
    var actualHeight: Int = outHeight
        private set

    fun init(): MediaCodec {
        actualWidth = outWidth and 1.inv()
        actualHeight = outHeight and 1.inv()
        format = MediaFormat.createVideoFormat(mime, actualWidth, actualHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(mime).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        return codec!!
    }

    fun release() { try { codec?.stop(); codec?.release() } catch (_: Exception) {} }
}
