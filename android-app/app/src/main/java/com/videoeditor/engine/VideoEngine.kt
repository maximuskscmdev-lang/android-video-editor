package com.videoeditor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.util.Log
import com.videoeditor.RustBridge
import com.videoeditor.timeline.TimelineClip
import com.videoeditor.timeline.TimelineState
import com.videoeditor.util.YUVConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Timeline-aware exporter: stitches trimmed clips sequentially.
 * Each clip is decoded from trimStartMs..trimEndMs, scaled to out dims, encoded.
 * Single encoder/muxer session ensures monotonically increasing PTS.
 */
class VideoEngine(private val context: Context) {

    val progress = MutableStateFlow(0)
    val isRunning = MutableStateFlow(false)

    data class ExportConfig(
        val outWidth: Int = 1920,
        val outHeight: Int = 1080,
        val bitrate: Int = 10_000_000,
        val fps: Int = 30,
        val filterJson: String = "[]"
    )

    suspend fun export(
        timeline: TimelineState,
        outPath: String,
        config: ExportConfig = ExportConfig(),
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (timeline.clips.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Timeline empty"))
        isRunning.value = true
        val totalDurationMs = timeline.totalDurationMs.coerceAtLeast(1L)
        var cumulativeFrames = 0
        val estimatedTotalFrames = (totalDurationMs / 1000f * config.fps).toInt().coerceAtLeast(1)

        // ViewportRes is single source for preview + export (option a)
        val outWidth = timeline.viewportRes.width
        val outHeight = timeline.viewportRes.height
        val outBitrate = timeline.viewportRes.bitrate
        val fps = config.fps

        val muxerWrapper = MuxerWrapper(outPath)
        var encoder: MediaCodec? = null
        var muxerStarted = false
        var cumulativePtsUs = 0L
        var cumulativeTimelineMs = 0L

        try {
            try {
                val projJson = toProjectJson(timeline, config)
                if (RustBridge.isAvailable()) {
                    val v = RustBridge.validateProject(projJson)
                    if (v != 0) Log.w("VideoEngine", "Rust validate returned $v, continuing anyway")
                }
            } catch (e: Throwable) { Log.w("VideoEngine", "Rust validate skip: $e") }

            muxerWrapper.init()
            encoder = Encoder(outWidth, outHeight, outBitrate, fps).init()
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()

            for ((clipIndex, clip) in timeline.clips.withIndex()) {
                val clipTrimMs = clip.trimmedDurationMs
                if (clipTrimMs <= 0) {
                    cumulativeTimelineMs += clipTrimMs
                    continue
                }
                val trimStartUs = clip.trimStartMs * 1000L
                val trimEndUs = clip.trimEndMs * 1000L

                // Decoder per clip
                val decoderInfo = Decoder(context, clip.uri)
                try { decoderInfo.init() } catch (e: Exception) {
                    Log.e("VideoEngine", "Decoder init failed for ${clip.displayName}", e)
                    continue
                }
                val mime = decoderInfo.mime
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(decoderInfo.videoFormat, null, null, 0)
                decoder.start()
                val extractor = decoderInfo.extractor
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var sawInputEOS = false
                var sawDecoderEOS = false
                var clipSawEOS = false

                // Drain loop for this clip
                while (!clipSawEOS) {
                    // Feed decoder input
                    if (!sawInputEOS) {
                        val inIdx = decoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(buf, 0)
                            val sampleTime = extractor.sampleTime
                            val beyondTrim = sampleTime == -1L || sampleTime > trimEndUs || sampleSize < 0
                            if (beyondTrim || sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain decoder output
                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                        outIdx >= 0 -> {
                            val isEOS = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            // Our trim end may be before EOS; treat beyond trim as EOS
                            val ptsInTrim = bufferInfo.presentationTimeUs in trimStartUs..trimEndUs || isEOS
                            if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs <= trimEndUs) {
                                val image = try { decoder.getOutputImage(outIdx) } catch (_: Exception) { null }
                                if (image != null) {
                                    val i420 = YUVConverter.fromImage(image)
                                    image.close()
                                    // Rust filter if requested
                                    if (RustBridge.isAvailable() && config.filterJson != "[]") {
                                        try { RustBridge.processFrameYUV420(i420.y, i420.u, i420.v, i420.width, i420.height, config.filterJson) } catch (e: Exception) { Log.w("VideoEngine", "Rust filter $e") }
                                    }
                                    // Viewport-aware + per-clip transform (move/resize). Rotation preview-only for now.
                                    val tf = clip.transform
                                    val needsTransform = tf.offsetXFraction != 0f || tf.offsetYFraction != 0f || tf.scale != 1f || tf.rotationDeg != 0f
                                    val toEncode: YUVConverter.I420Buffers = run {
                                        if (!needsTransform && i420.width == outWidth && i420.height == outHeight) {
                                            i420
                                        } else {
                                            // Scaled size
                                            val scaledW = if (tf.scale != 1f) (i420.width * tf.scale).toInt().coerceIn(2, outWidth).let { it and 1.inv() } else i420.width
                                            val scaledH = if (tf.scale != 1f) (i420.height * tf.scale).toInt().coerceIn(2, outHeight).let { it and 1.inv() } else i420.height
                                            val scaledSrc: YUVConverter.I420Buffers = if (scaledW != i420.width || scaledH != i420.height) {
                                                val tmp = YUVConverter.allocateI420(scaledW, scaledH)
                                                try {
                                                    if (RustBridge.isAvailable()) {
                                                        RustBridge.scaleFrameYUV420(i420.y, i420.u, i420.v, i420.width, i420.height, tmp.y, tmp.u, tmp.v, scaledW, scaledH)
                                                        tmp
                                                    } else i420
                                                } catch (_: Throwable) { i420 }
                                            } else i420

                                            // If no offset/rotation and scaled already matches viewport, just use scaledSrc
                                            if (tf.offsetXFraction == 0f && tf.offsetYFraction == 0f && tf.rotationDeg == 0f && scaledSrc.width == outWidth && scaledSrc.height == outHeight) {
                                                scaledSrc
                                            } else {
                                                if (tf.rotationDeg != 0f) Log.w("VideoEngine", "Rotation ${tf.rotationDeg}° live preview only, export composites with scale/offset for now")
                                                val dst = YUVConverter.allocateI420(outWidth, outHeight).also { fillBlack(it) }
                                                val offX = ((outWidth - scaledSrc.width) / 2 + (tf.offsetXFraction * outWidth).toInt()).coerceIn(0, outWidth - scaledSrc.width)
                                                val offY = ((outHeight - scaledSrc.height) / 2 + (tf.offsetYFraction * outHeight).toInt()).coerceIn(0, outHeight - scaledSrc.height)
                                                compositeI420(dst, scaledSrc, offX, offY)
                                                dst
                                            }
                                        }
                                    }

                                    // PTS: timeline pts = cumulativeTimelineMs*1000 + (decoderPts - trimStartUs)
                                    val relativePtsUs = (bufferInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0L)
                                    val timelinePtsUs = cumulativeTimelineMs * 1000L + relativePtsUs

                                    // Queue to encoder
                                    val encInIdx = encoder.dequeueInputBuffer(10_000)
                                    if (encInIdx >= 0) {
                                        val encBuf = encoder.getInputBuffer(encInIdx)!!
                                        encBuf.clear()
                                        encBuf.put(toEncode.y); encBuf.put(toEncode.u); encBuf.put(toEncode.v)
                                        encBuf.flip()
                                        encoder.queueInputBuffer(encInIdx, 0, encBuf.remaining(), timelinePtsUs, 0)
                                        cumulativeFrames++
                                        val pct = (cumulativeFrames * 100 / estimatedTotalFrames).coerceIn(0, 99)
                                        progress.value = pct; onProgress(pct)
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (isEOS) sawDecoderEOS = true
                            // If we've reached trimEnd, treat as EOS for this clip
                            if (bufferInfo.presentationTimeUs >= trimEndUs) {
                                sawDecoderEOS = true
                                sawInputEOS = true
                            }
                        }
                        else -> {
                            // No output available (TRY_AGAIN_LATER) - nothing to do
                        }
                    }

                    // Drain encoder output
                    val encOut = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                val fmt = encoder.outputFormat
                                muxerWrapper.addVideoTrack(fmt)
                                muxerStarted = true
                            }
                        }
                        encOut >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(encOut)!!
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encBuf.position(bufferInfo.offset)
                                encBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxerWrapper.writeVideoSample(encBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encOut, false)
                            // Don't set sawOutputEOS per clip; encoder continues across clips
                        }
                    }

                    // Exit clip loop when decoder EOS and no more input
                    if (sawDecoderEOS && sawInputEOS) {
                        // Check if encoder has drained? We'll continue to drain encoder in next loop iteration
                        // For simplicity, break clip loop after decoder EOS; encoder data will be drained in next iterations
                        // Need to ensure we drain encoder before next clip: do extra drain iterations
                        // Break after one more encoder drain attempt fails
                        clipSawEOS = true
                    }
                }

                decoder.stop(); decoder.release()
                decoderInfo.release()
                cumulativeTimelineMs += clipTrimMs
                cumulativePtsUs = cumulativeTimelineMs * 1000L

                // Ensure encoder output drained before next clip to avoid PTS gap?
                // Drain a bit
                repeat(5) {
                    val eid = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (eid == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                        muxerWrapper.addVideoTrack(encoder.outputFormat); muxerStarted = true
                    } else if (eid >= 0) {
                        val b = encoder.getOutputBuffer(eid)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            b.position(bufferInfo.offset); b.limit(bufferInfo.offset + bufferInfo.size)
                            muxerWrapper.writeVideoSample(b, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(eid, false)
                    }
                }

                Log.i("VideoEngine", "Clip $clipIndex ${clip.displayName} done, trimmed ${clipTrimMs}ms")
            }

            // Signal EOS to encoder
            val eosIdx = encoder.dequeueInputBuffer(10_000)
            if (eosIdx >= 0) {
                encoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain remaining encoder output
            var encoderEOS = false
            while (!encoderEOS) {
                val out = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) { muxerWrapper.addVideoTrack(encoder.outputFormat); muxerStarted = true }
                    }
                    out >= 0 -> {
                        val b = encoder.getOutputBuffer(out)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            b.position(bufferInfo.offset); b.limit(bufferInfo.offset + bufferInfo.size)
                            muxerWrapper.writeVideoSample(b, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(out, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEOS = true
                    }
                    else -> {
                        // No output available, small delay to avoid busy spin
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEOS = true
                        else Thread.sleep(5)
                    }
                }
                if (!encoderEOS && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEOS = true
            }

            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            muxerWrapper.release()

            progress.value = 100; onProgress(100)
            Result.success(outPath)
        } catch (e: Exception) {
            Log.e("VideoEngine", "export failed", e)
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxerWrapper.release() } catch (_: Exception) {}
            Result.failure(e)
        } finally {
            isRunning.value = false
        }
    }

    private fun fillBlack(dst: YUVConverter.I420Buffers) {
        // Y black = 16, U/V neutral = 128
        for (i in 0 until dst.y.capacity()) dst.y.put(i, 16.toByte())
        for (i in 0 until dst.u.capacity()) dst.u.put(i, 128.toByte())
        for (i in 0 until dst.v.capacity()) dst.v.put(i, 128.toByte())
        dst.y.rewind(); dst.u.rewind(); dst.v.rewind()
    }

    private fun compositeI420(dst: YUVConverter.I420Buffers, src: YUVConverter.I420Buffers, offX: Int, offY: Int) {
        // Y plane
        val dstW = dst.width; val srcW = src.width; val srcH = src.height
        for (y in 0 until srcH) {
            val dy = offY + y
            if (dy !in 0 until dst.height) continue
            for (x in 0 until srcW) {
                val dx = offX + x
                if (dx !in 0 until dstW) continue
                dst.y.put(dy * dstW + dx, src.y.get(y * srcW + x))
            }
        }
        // U/V planes 1/2 subsampled, offsets /2
        val dstWU = dstW / 2; val srcWU = srcW / 2; val srcHU = srcH / 2
        val offXU = offX / 2; val offYU = offY / 2
        for (y in 0 until srcHU) {
            val dy = offYU + y
            if (dy !in 0 until dst.height / 2) continue
            for (x in 0 until srcWU) {
                val dx = offXU + x
                if (dx !in 0 until dstWU) continue
                dst.u.put(dy * dstWU + dx, src.u.get(y * srcWU + x))
                dst.v.put(dy * dstWU + dx, src.v.get(y * srcWU + x))
            }
        }
        dst.y.rewind(); dst.u.rewind(); dst.v.rewind()
    }

    private fun toProjectJson(timeline: TimelineState, config: ExportConfig): String {
        val vw = timeline.viewportRes.width
        val vh = timeline.viewportRes.height
        val br = timeline.viewportRes.bitrate
        val res = when (vw) { 1280 -> "720p"; 3840 -> "4K"; else -> "${vh}p" }
        val clipsJson = timeline.clips.joinToString(",") { c ->
            """{"path":"${c.uri}","trim_start_ms":${c.trimStartMs},"trim_end_ms":${c.trimEndMs},"transform":{"x":${c.transform.offsetXFraction},"y":${c.transform.offsetYFraction},"scale":${c.transform.scale},"rot":${c.transform.rotationDeg}},"volume":1.0}"""
        }
        return """{"clips":[${clipsJson}],"output_width":${vw},"output_height":${vh},"resolution":"${res}","fps":${config.fps},"bitrate":${br}}"""
    }
}
