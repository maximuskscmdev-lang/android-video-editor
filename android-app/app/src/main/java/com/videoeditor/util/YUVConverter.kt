package com.videoeditor.util

import android.media.Image
import java.nio.ByteBuffer

/**
 * Converts Android Image (YUV_420_888) to I420 planar direct ByteBuffers for Rust.
 * Also handles COLOR_FormatYUV420Flexible ByteBuffer de-tiling if needed.
 */
object YUVConverter {

    data class I420Buffers(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer, val width: Int, val height: Int)

    fun fromImage(image: Image): I420Buffers {
        val w = image.width
        val h = image.height
        require(w % 2 == 0 && h % 2 == 0) { "Image must be even dims, got ${w}x${h}" }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = ByteBuffer.allocateDirect(w * h)
        val uBuf = ByteBuffer.allocateDirect(w * h / 4)
        val vBuf = ByteBuffer.allocateDirect(w * h / 4)

        // Y plane - may have rowStride > w
        copyPlane(yPlane, w, h, yBuf)
        copyPlane(uPlane, w / 2, h / 2, uBuf)
        copyPlane(vPlane, w / 2, h / 2, vBuf)

        yBuf.rewind(); uBuf.rewind(); vBuf.rewind()
        return I420Buffers(yBuf, uBuf, vBuf, w, h)
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteBuffer) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // Fast path rowStride == width
        if (pixelStride == 1 && rowStride == width) {
            val tmp = ByteArray(width * height)
            buffer.get(tmp)
            out.put(tmp)
        } else {
            // Generic strided copy
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowStride)
                var x = 0
                var outPos = 0
                while (x < width) {
                    out.put(outPos + y * width + x, row[x * pixelStride])
                    x++
                }
            }
            // Above put with index doesn't move position; ensure position set
            out.position(width * height)
        }
        buffer.rewind()
    }

    /** Allocate dst I420 buffers for scale/crop */
    fun allocateI420(w: Int, h: Int): I420Buffers {
        return I420Buffers(
            ByteBuffer.allocateDirect(w * h),
            ByteBuffer.allocateDirect(w * h / 4),
            ByteBuffer.allocateDirect(w * h / 4),
            w, h
        )
    }
}
