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

        val muxerWrapper = MuxerWrapper(outPath)
        var encoder: MediaCodec? = null
        var muxerStarted = false
        var cumulativePtsUs = 0L
        // For correct PTS per clip, we need to know offset per clip's start in timeline
        // cumulativeTrimDurationMs tracks timeline pts offset
        var cumulativeTimelineMs = 0L

        try {
            // Validate via Rust if available (project json minimal)
            try {
                val projJson = toProjectJson(timeline, config)
                if (RustBridge.isAvailable()) {
                    val v = RustBridge.validateProject(projJson)
                    if (v != 0) Log.w("VideoEngine", "Rust validate returned $v, continuing anyway")
                }
            } catch (e: Throwable) { Log.w("VideoEngine", "Rust validate skip: $e") }

            muxerWrapper.init()
            encoder = Encoder(config.outWidth, config.outHeight, config.bitrate, config.fps).init()
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
                                    // Scale to export dims if needed
                                    val toEncode: YUVConverter.I420Buffers = if (i420.width != config.outWidth || i420.height != config.outHeight) {
                                        val dst = YUVConverter.allocateI420(config.outWidth, config.outHeight)
                                        try {
                                            if (RustBridge.isAvailable()) {
                                                RustBridge.scaleFrameYUV420(i420.y, i420.u, i420.v, i420.width, i420.height, dst.y, dst.u, dst.v, config.outWidth, config.outHeight)
                                                dst
                                            } else i420
                                        } catch (_: Throwable) { i420 }
                                    } else i420

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

    private fun toProjectJson(timeline: TimelineState, config: ExportConfig): String {
        val res = when (config.outWidth) { 1280 -> "720p"; 3840 -> "4K"; else -> "1080p" }
        val clipsJson = timeline.clips.joinToString(",") { c ->
            """{"path":"${c.uri}","trim_start_ms":${c.trimStartMs},"trim_end_ms":${c.trimEndMs},"transforms":[],"volume":1.0}"""
        }
        return """{"clips":[${clipsJson}],"output_width":${config.outWidth},"output_height":${config.outHeight},"resolution":"${res}","fps":${config.fps},"bitrate":${config.bitrate}}"""
    }
}
