/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * 基于 Android MediaCodec + MediaMuxer 的 H.264 MP4 硬件视频编码器。
 * 采用 EGLSurface 输入管线，自动适配各种硬件平台与芯片组，GPU 硬件加速合成并支持偶数像素尺寸裁剪。
 */
internal class Mp4VideoEncoder {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var eglHelper: EglHelper? = null

    private var videoTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    private var currentOutputFile: File? = null

    private var width = 0
    private var height = 0
    private var fps = 12
    private var frameDurationNs = 0L
    private var currentPtsNs = 0L

    /**
     * 初始化并启动编码器。
     * @param outputFile 目标 MP4 文件
     * @param targetWidth 期望宽度 (自动按 16 字节对齐以适配全平台硬件芯片)
     * @param targetHeight 期望高度 (自动按 16 字节对齐以适配全平台硬件芯片)
     * @param targetFps 帧率
     */
    fun start(
        outputFile: File,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
    ): Boolean {
        currentOutputFile = outputFile
        // H.264 编码器要求偶数尺寸，推荐 16 字节对齐保证移动端芯片兼容
        var w = (targetWidth / 16) * 16
        var h = (targetHeight / 16) * 16
        if (w <= 0) w = (targetWidth / 2) * 2
        if (h <= 0) h = (targetHeight / 2) * 2
        if (w > 3840 || h > 2160) {
            val scale = minOf(3840f / w, 2160f / h)
            w = ((w * scale).toInt() / 16) * 16
            h = ((h * scale).toInt() / 16) * 16
        }
        width = max(16, w)
        height = max(16, h)

        fps = targetFps.coerceIn(1, 120)
        frameDurationNs = 1_000_000_000L / fps
        currentPtsNs = 0L
        muxerStarted = false
        videoTrackIndex = -1

        try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                // 码率设定：保证画质细腻
                val bitRate = (width * height * 4).coerceIn(1_500_000, 16_000_000)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 关键帧间隔 1 秒
            }

            val mediaCodec = MediaCodec.createEncoderByType(mime)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec.createInputSurface()
            mediaCodec.start()

            codec = mediaCodec
            inputSurface = surface

            eglHelper = EglHelper(surface, width, height)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    /** 提交单帧位图 */
    fun addFrame(bitmap: Bitmap, isCancelled: () -> Boolean = { false }): Boolean {
        if (isCancelled()) return false
        val helper = eglHelper ?: return false
        try {
            helper.drawBitmap(bitmap)
            helper.setPresentationTime(currentPtsNs)
            helper.swapBuffers()
            drainEncoder(endOfStream = false, isCancelled = isCancelled)
            currentPtsNs += frameDurationNs
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 结束编码并封装 MP4 文件 */
    fun finish(isCancelled: () -> Boolean = { false }): Boolean {
        val mediaCodec = codec ?: return false
        val mediaMuxer = muxer ?: return false
        var success = false
        try {
            mediaCodec.signalEndOfInputStream()
            drainEncoder(endOfStream = true, isCancelled = isCancelled)
            if (muxerStarted) {
                mediaMuxer.stop()
                muxerStarted = false
                success = currentOutputFile?.let { it.exists() && it.length() > 0L } ?: false
            }
        } catch (e: Exception) {
            success = false
        } finally {
            release()
        }
        return success
    }

    private fun drainEncoder(endOfStream: Boolean, isCancelled: () -> Boolean) {
        val mediaCodec = codec ?: return
        val mediaMuxer = muxer ?: return

        val timeoutUs = if (endOfStream) 10_000L else 1_000L
        var emptyCount = 0
        while (true) {
            if (isCancelled()) throw InterruptedException("用户取消导出")
            val status = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                emptyCount++
                if (emptyCount >= 20) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw IllegalStateException("格式重复变更")
                }
                val newFormat = mediaCodec.outputFormat
                videoTrackIndex = mediaMuxer.addTrack(newFormat)
                mediaMuxer.start()
                muxerStarted = true
            } else if (status >= 0) {
                val encodedData = mediaCodec.getOutputBuffer(status) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw IllegalStateException("Muxer 尚未就绪")
                    }
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                }
                mediaCodec.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun release() {
        try {
            eglHelper?.release()
        } catch (_: Exception) {}
        eglHelper = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        currentOutputFile = null
    }

    /**
     * EGL 与 OpenGL ES 2.0 离屏纹理绘制封装。
     */
    private class EglHelper(
        surface: Surface,
        private val width: Int,
        private val height: Int,
    ) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var programId = 0
        private var textureId = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uTextureLoc = 0

        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        init {
            // 顶点坐标 (全屏 Quad)
            val vCoords = floatArrayOf(
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f,
            )
            vertexBuffer = ByteBuffer.allocateDirect(vCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(vCoords)
                    position(0)
                }

            // 纹理坐标 (垂直翻转以对齐 Bitmap 原点与 OpenGL 原点)
            val tCoords = floatArrayOf(
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f,
            )
            texCoordBuffer = ByteBuffer.allocateDirect(tCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(tCoords)
                    position(0)
                }

            initEgl(surface)
            initGl()
        }

        private fun initEgl(surface: Surface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("无法获取 EGLDisplay")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw RuntimeException("EGL 初始化失败")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: throw RuntimeException("无法匹配 EGLConfig")

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("创建 EGLContext 失败")

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("创建 EGLSurface 失败")

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw RuntimeException("eglMakeCurrent 失败")
            }
        }

        private fun initGl() {
            val vShaderCode = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """.trimIndent()

            val fShaderCode = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent()

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShaderCode)

            programId = GLES20.glCreateProgram()
            GLES20.glAttachShader(programId, vShader)
            GLES20.glAttachShader(programId, fShader)
            GLES20.glLinkProgram(programId)

            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")

            val texs = IntArray(1)
            GLES20.glGenTextures(1, texs, 0)
            textureId = texs[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glViewport(0, 0, width, height)
        }

        fun drawBitmap(bitmap: Bitmap) {
            GLES20.glClearColor(1f, 1f, 1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun setPresentationTime(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }

        private fun loadShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}
