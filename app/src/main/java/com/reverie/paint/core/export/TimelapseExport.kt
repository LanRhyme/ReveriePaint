/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 延时摄影 H.264 MP4 视频编码器。
 * 基于 MediaCodec + MediaMuxer，采用 COLOR_FormatYUV420Flexible 输入。
 * 热路径零分配：RGBA->YUV420 转换复用同一数组缓冲区。
 */
object TimelapseExport {
    private const val TAG = "TimelapseExport"

    /**
     * 将指定目录下的序列帧编码为 MP4 视频文件。
     *
     * @param framesDir 包含 frame_*.webp 的目录
     * @param outFile 导出的目标 MP4 文件
     * @param fps 目标帧率 (默认 12fps)
     * @param onProgress 进度回调 (当前帧, 总帧数)
     * @param isCancelled 是否取消回调
     * @return 成功返回 true，失败删除半成品并返回 false
     */
    fun encode(
        framesDir: File,
        outFile: File,
        fps: Int = 12,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Boolean {
        if (!framesDir.exists() || !framesDir.isDirectory) {
            Log.e(TAG, "framesDir not found: ${framesDir.absolutePath}")
            return false
        }

        val frameFiles = framesDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("""frame_\d{5}\.webp"""))
        }?.sortedBy { it.name } ?: emptyList()

        if (frameFiles.isEmpty()) {
            Log.e(TAG, "No frames found in ${framesDir.absolutePath}")
            return false
        }

        val totalFrames = frameFiles.size

        // 读取首帧尺寸，强制偶数 (H.264 要求)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles[0].absolutePath, boundsOptions)
        var targetWidth = (boundsOptions.outWidth / 2) * 2
        var targetHeight = (boundsOptions.outHeight / 2) * 2

        if (targetWidth <= 0 || targetHeight <= 0) {
            Log.e(TAG, "Invalid dimensions from first frame: ${boundsOptions.outWidth}x${boundsOptions.outHeight}")
            return false
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1

        try {
            if (outFile.exists()) {
                outFile.delete()
            }
            outFile.parentFile?.mkdirs()

            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val targetFps = fps.coerceIn(1, 60)
            val bitRate = (targetWidth * targetHeight * 4).coerceIn(1_500_000, 16_000_000)

            val format = MediaFormat.createVideoFormat(mime, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / targetFps

            // 热路径复用缓冲区：避免每帧分配
            val argbPixels = IntArray(targetWidth * targetHeight)
            val fallbackYuv = ByteArray(targetWidth * targetHeight * 3 / 2)

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw IllegalStateException("Output format changed after muxer started")
                        }
                        val newFormat = codec.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            for (i in 0 until totalFrames) {
                if (isCancelled?.invoke() == true) {
                    throw InterruptedException("Timelapse encoding cancelled by user")
                }

                val frameFile = frameFiles[i]
                var bmp = BitmapFactory.decodeFile(frameFile.absolutePath)
                if (bmp == null) {
                    Log.w(TAG, "Failed to decode frame ${frameFile.name}, skipping")
                    continue
                }

                // 尺寸不符的帧归一化到首帧尺寸
                if (bmp.width != targetWidth || bmp.height != targetHeight) {
                    val scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)
                    bmp.recycle()
                    bmp = scaled
                }

                bmp.getPixels(argbPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
                bmp.recycle()

                // 获取输入 Buffer
                var inputIndex = -1
                var waitCount = 0
                while (inputIndex < 0 && waitCount < 50) {
                    inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex < 0) {
                        drain(false)
                        waitCount++
                    }
                }

                if (inputIndex >= 0) {
                    val image = codec.getInputImage(inputIndex)
                    if (image != null) {
                        fillImageYuv420(image, argbPixels, targetWidth, targetHeight)
                    } else {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            fillBufferYuv420(inputBuffer, fallbackYuv, argbPixels, targetWidth, targetHeight)
                        }
                    }

                    val ptsUs = i * frameDurationUs
                    val inputSize = targetWidth * targetHeight * 3 / 2
                    codec.queueInputBuffer(inputIndex, 0, inputSize, ptsUs, 0)
                }

                drain(false)
                onProgress?.invoke(i + 1, totalFrames)
            }

            // 发送 EOS 信号排空
            var eosIndex = -1
            var eosWait = 0
            while (eosIndex < 0 && eosWait < 50) {
                eosIndex = codec.dequeueInputBuffer(10_000L)
                if (eosIndex < 0) {
                    drain(false)
                    eosWait++
                }
            }

            if (eosIndex >= 0) {
                val ptsUs = totalFrames * frameDurationUs
                codec.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            drain(true)

            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Timelapse encoding failed", e)
            if (outFile.exists()) {
                outFile.delete()
            }
            return false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {}
            try {
                codec?.release()
            } catch (_: Throwable) {}
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Throwable) {}
            try {
                muxer?.release()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 将 ARGB 像素数组写入 MediaCodec 的 Image (兼容各类芯片组的 YUV420Flexible 布局)。
     */
    private fun fillImageYuv420(
        image: Image,
        argb: IntArray,
        w: Int,
        h: Int,
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        // Y 分量填充
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val c = argb[rowOffset + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) ushr 8) + 16
                yBuf.put(y * yRowStride + x * yPixStride, yVal.coerceIn(0, 255).toByte())

                // 2x2 下采样 U/V 分量
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) ushr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) ushr 8) + 128
                    val uvRow = y shr 1
                    val uvCol = x shr 1
                    uBuf.put(uvRow * uRowStride + uvCol * uPixStride, uVal.coerceIn(0, 255).toByte())
                    vBuf.put(uvRow * vRowStride + uvCol * vPixStride, vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    /**
     * 降级方案：若 getInputImage 不可用，按标准 NV12 写入 ByteBuffer。
     */
    private fun fillBufferYuv420(
        dst: ByteBuffer,
        yuvTemp: ByteArray,
        argb: IntArray,
        w: Int,
        h: Int,
    ) {
        var yIdx = 0
        var uvIdx = w * h
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val c = argb[rowOffset + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) ushr 8) + 16
                yuvTemp[yIdx++] = yVal.coerceIn(0, 255).toByte()

                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) ushr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) ushr 8) + 128
                    yuvTemp[uvIdx++] = uVal.coerceIn(0, 255).toByte()
                    yuvTemp[uvIdx++] = vVal.coerceIn(0, 255).toByte()
                }
            }
        }
        dst.clear()
        dst.put(yuvTemp)
    }
}
