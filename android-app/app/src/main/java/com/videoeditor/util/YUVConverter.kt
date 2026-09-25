package com.videoeditor.util

import android.media.Image
import java.nio.ByteBuffer

object YUVConverter {

    data class I420Buffers(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer, val width: Int, val height: Int)

    fun fromImage(image: Image): I420Buffers {
        val w = image.width
        val h = image.height
        require(w % 2 == 0 && h % 2 == 0) { "Image must be even dims, got ${w}x${h}" }
        val yBuf = ByteBuffer.allocateDirect(w * h)
        val uBuf = ByteBuffer.allocateDirect(w * h / 4)
        val vBuf = ByteBuffer.allocateDirect(w * h / 4)
        copyPlane(image.planes[0], w, h, yBuf)
        copyPlane(image.planes[1], w / 2, h / 2, uBuf)
        copyPlane(image.planes[2], w / 2, h / 2, vBuf)
        yBuf.rewind(); uBuf.rewind(); vBuf.rewind()
        return I420Buffers(yBuf, uBuf, vBuf, w, h)
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteBuffer) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width) {
            val tmp = ByteArray(width * height)
            buffer.get(tmp)
            out.put(tmp)
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowStride)
                for (x in 0 until width) {
                    out.put(y * width + x, row[x * pixelStride])
                }
            }
            out.position(width * height)
        }
        buffer.rewind()
    }

    fun allocateI420(w: Int, h: Int): I420Buffers {
        require(w % 2 == 0 && h % 2 == 0) { "I420 dims must be even" }
        return I420Buffers(
            ByteBuffer.allocateDirect(w * h),
            ByteBuffer.allocateDirect(w * h / 4),
            ByteBuffer.allocateDirect(w * h / 4),
            w, h
        )
    }
}
