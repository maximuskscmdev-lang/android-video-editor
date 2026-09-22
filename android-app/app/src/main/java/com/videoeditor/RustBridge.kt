package com.videoeditor

import java.nio.ByteBuffer

/**
 * JNI bridge to rust-core (video_core).
 * Loads libvideo_core.so built via cargo-ndk for arm64-v8a.
 * All YUV buffers must be direct ByteBuffers (allocateDirect).
 */
object RustBridge {
    init {
        try {
            System.loadLibrary("video_core")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("RustBridge", "libvideo_core.so not loaded (dev without NDK): ${e.message}")
        }
    }

    // Keep native methods as external; if .so missing they throw UnsatisfiedLinkError at call time.
    @JvmStatic external fun getVersion(): String
    @JvmStatic external fun validateProject(json: String): Int
    @JvmStatic external fun estimateDurationMs(json: String): Long

    /** Mutates Y/U/V buffers in place applying Filter/Crop subset of ops. opsJson: JSON array of Transform */
    @JvmStatic external fun processFrameYUV420(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        width: Int,
        height: Int,
        opsJson: String
    ): Int

    @JvmStatic external fun getCroppedDimensions(srcW: Int, srcH: Int, x: Int, y: Int, w: Int, h: Int): Long

    @JvmStatic external fun scaleFrameYUV420(
        ySrc: ByteBuffer, uSrc: ByteBuffer, vSrc: ByteBuffer, srcW: Int, srcH: Int,
        yDst: ByteBuffer, uDst: ByteBuffer, vDst: ByteBuffer, dstW: Int, dstH: Int
    ): Int

    @JvmStatic external fun applyFilterRGBA(rgbaBuf: ByteBuffer, width: Int, height: Int, filterJson: String): Int

    // Helpers for Kotlin (no JNI)
    fun croppedWidth(packed: Long): Int = (packed shr 32).toInt()
    fun croppedHeight(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

    fun isAvailable(): Boolean = try { getVersion(); true } catch (_: Throwable) { false }
}
