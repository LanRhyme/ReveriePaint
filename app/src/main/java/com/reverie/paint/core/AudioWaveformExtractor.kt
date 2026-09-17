/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * 音频波形抽取与时间轴声音擦除 (Audio Scrubbing) 调度器。
 */
internal object AudioWaveformExtractor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrubPlayer: MediaPlayer? = null
    private var lastScrubTimeMs = -1L
    private val pauseScrubRunnable = Runnable {
        runCatching {
            if (scrubPlayer?.isPlaying == true) {
                scrubPlayer?.pause()
            }
        }
    }

    /**
     * 异步提取指定音频文件的归一化振幅波形 (0f ~ 1f)。
     * 目标采样数 targetSamples 通常按动画总帧数或固定点数计算。
     */
    fun extractWaveform(
        audioFile: File,
        targetSamples: Int = 200,
        onReady: (List<Float>) -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val samples = decodeAmplitudes(audioFile, max(50, targetSamples))
            mainHandler.post { onReady(samples) }
        }
    }

    private fun decodeAmplitudes(file: File, targetSamples: Int): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return emptyList()

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmPeaks = ArrayList<Int>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val timeoutUs = 5000L

            var currentPeak = 0
            var sampleCountInBucket = 0
            val bucketSize = 400

            while (!sawOutputEOS && pcmPeaks.size < 5000) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                sawInputEOS = true
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuf.hasRemaining()) {
                            val v = abs(shortBuf.get().toInt())
                            if (v > currentPeak) currentPeak = v
                            sampleCountInBucket++
                            if (sampleCountInBucket >= bucketSize) {
                                pcmPeaks.add(currentPeak)
                                currentPeak = 0
                                sampleCountInBucket = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            if (pcmPeaks.isEmpty()) return emptyList()

            // 重新重采样至 targetSamples
            val maxPeak = max(1, pcmPeaks.maxOrNull() ?: 1)
            val result = ArrayList<Float>(targetSamples)
            val step = pcmPeaks.size.toFloat() / targetSamples.toFloat()
            for (i in 0 until targetSamples) {
                val idx = (i * step).toInt().coerceIn(0, pcmPeaks.size - 1)
                result.add((pcmPeaks[idx].toFloat() / maxPeak.toFloat()).coerceIn(0f, 1f))
            }
            return result
        } catch (e: Throwable) {
            android.util.Log.e("RP_Waveform", "decode error", e)
            return emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 声音擦除 (Audio Scrubbing): 当手指拖拽时间轴到特定帧时发声
     */
    fun scrub(context: Context, audioFile: File?, timeMs: Long) {
        if (audioFile == null || !audioFile.exists()) return
        if (abs(timeMs - lastScrubTimeMs) < 40L) return
        lastScrubTimeMs = timeMs

        mainHandler.removeCallbacks(pauseScrubRunnable)
        try {
            var player = scrubPlayer
            if (player == null) {
                player = MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    isLooping = false
                    prepare()
                }
                scrubPlayer = player
            }
            player.seekTo(timeMs.toInt())
            if (!player.isPlaying) {
                player.start()
            }
            // 发声 75ms 后停止
            mainHandler.postDelayed(pauseScrubRunnable, 75L)
        } catch (e: Throwable) {
            android.util.Log.e("RP_Scrub", "scrub failed", e)
        }
    }

    fun release() {
        mainHandler.removeCallbacks(pauseScrubRunnable)
        runCatching {
            scrubPlayer?.stop()
            scrubPlayer?.release()
        }
        scrubPlayer = null
    }
}
