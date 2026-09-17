/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.reverie.paint.core.export.AnimatedGifEncoder
import com.reverie.paint.core.export.Mp4VideoEncoder
import com.reverie.paint.core.export.PngSequenceZipEncoder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 动画导出参数与配置。
 */
internal data class AnimationExportOptions(
    val format: String, // "gif", "mp4", "zip"
    val scale: Float = 1.0f, // 1.0f, 0.5f, 0.25f
    val rangeMode: String = "all", // "all", "range"
    val transparentBg: Boolean = false,
)

/**
 * 导出动画至指定文件。
 * 所有关键帧渲染在 reverie-render 线程串行执行，严格遵守双缓冲与热路径复用约束。
 */
internal fun PaintViewModel.exportAnimation(
    options: AnimationExportOptions,
    targetFile: File,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val fmt = options.format.lowercase()
    val scale = options.scale.coerceIn(0.1f, 1.0f)
    val transparentBg = options.transparentBg && fmt != "mp4" // MP4 不支持 Alpha 通道

    runCore(render = false) {
        val originalTime = anim.currentTime
        val totalLength = anim.length
        val (startFrame, endFrame) = if (options.rangeMode == "range") {
            val s = anim.playbackStart.coerceAtLeast(0)
            val e = anim.playbackEnd.coerceIn(0, max(0, totalLength - 1))
            if (s <= e) s to e else 0 to max(0, totalLength - 1)
        } else {
            0 to max(0, totalLength - 1)
        }

        val frameList = (startFrame..endFrame).toList()
        val totalFrames = frameList.size
        if (totalFrames <= 0) {
            mainHandler.post { onError("导出帧数为空") }
            return@runCore
        }

        // 查找背景层并处理透明背景
        val bgLayer = layers.firstOrNull { it.isBackground } ?: layers.firstOrNull()
        val bgIndex = bgLayer?.index ?: -1
        val wasBgVisible = if (bgIndex >= 0) ReverieCoreBridge.layerVisible(bgIndex) else false

        if (transparentBg && bgIndex >= 0 && wasBgVisible) {
            ReverieCoreBridge.setLayerVisible(bgIndex, false)
        }

        val w = coreW
        val h = coreH
        var targetW = max(2, (w * scale).roundToInt())
        var targetH = max(2, (h * scale).roundToInt())

        if (fmt == "mp4") {
            // H.264 视频尺寸必须为偶数
            if (targetW % 2 != 0) targetW -= 1
            if (targetH % 2 != 0) targetH -= 1
        }

        val fullFrameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val needScale = targetW != w || targetH != h
        val scaledFrameBmp = if (needScale) {
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        } else null
        val scaleCanvas = scaledFrameBmp?.let { Canvas(it) }
        val scalePaint = if (needScale) Paint(Paint.FILTER_BITMAP_FLAG) else null
        val srcRect = Rect(0, 0, w, h)
        val dstRect = Rect(0, 0, targetW, targetH)

        var gifEncoder: AnimatedGifEncoder? = null
        var gifFos: FileOutputStream? = null
        var mp4Encoder: Mp4VideoEncoder? = null
        var zipEncoder: PngSequenceZipEncoder? = null

        try {
            when (fmt) {
                "gif" -> {
                    val encoder = AnimatedGifEncoder()
                    encoder.setFramerate(anim.framerate)
                    encoder.setRepeat(if (anim.loopPlayback) 0 else -1)
                    if (transparentBg) {
                        encoder.setTransparent(0)
                    }
                    val fos = FileOutputStream(targetFile)
                    gifFos = fos
                    if (!encoder.start(fos)) {
                        throw RuntimeException("启动 GIF 编码器失败")
                    }
                    gifEncoder = encoder
                }

                "mp4" -> {
                    val encoder = Mp4VideoEncoder()
                    if (!encoder.start(targetFile, targetW, targetH, anim.framerate)) {
                        throw RuntimeException("启动 MP4 视频编码器失败")
                    }
                    mp4Encoder = encoder
                }

                "zip" -> {
                    val encoder = PngSequenceZipEncoder()
                    if (!encoder.start(targetFile)) {
                        throw RuntimeException("启动 PNG 序列帧 Zip 编码器失败")
                    }
                    zipEncoder = encoder
                }

                else -> throw IllegalArgumentException("不支持的动画导出格式: $fmt")
            }

            for (i in frameList.indices) {
                if (isCancelled()) {
                    throw InterruptedException("用户取消导出")
                }

                val frameTime = frameList[i]
                mainHandler.post {
                    onProgress(i + 1, totalFrames, "正在渲染第 ${i + 1}/$totalFrames 帧...")
                }

                // 切换动画帧并收敛
                ReverieCoreBridge.setAnimationCurrentTime(frameTime, false)
                ReverieCoreBridge.renderToBuffer(fullFrameBmp, true, null)

                val outBitmap: Bitmap = if (needScale && scaledFrameBmp != null && scaleCanvas != null) {
                    scaleCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    scaleCanvas.drawBitmap(fullFrameBmp, srcRect, dstRect, scalePaint)
                    scaledFrameBmp
                } else {
                    fullFrameBmp
                }

                when (fmt) {
                    "gif" -> {
                        if (!gifEncoder!!.addFrame(outBitmap)) {
                            throw RuntimeException("添加 GIF 帧失败 (帧 $frameTime)")
                        }
                    }

                    "mp4" -> {
                        if (!mp4Encoder!!.addFrame(outBitmap)) {
                            throw RuntimeException("添加视频帧失败 (帧 $frameTime)")
                        }
                    }

                    "zip" -> {
                        if (!zipEncoder!!.addFrame(outBitmap)) {
                            throw RuntimeException("写入序列帧失败 (帧 $frameTime)")
                        }
                    }
                }
            }

            mainHandler.post {
                onProgress(totalFrames, totalFrames, "正在封装文件...")
            }

            val finishOk = when (fmt) {
                "gif" -> {
                    val ok = gifEncoder?.finish() ?: false
                    gifFos?.close()
                    gifFos = null
                    ok
                }
                "mp4" -> mp4Encoder?.finish() ?: false
                "zip" -> zipEncoder?.finish() ?: false
                else -> false
            }

            if (!finishOk) {
                throw RuntimeException("文件封装完成阶段失败")
            }

            mainHandler.post { onSuccess(targetFile) }
        } catch (e: InterruptedException) {
            targetFile.delete()
            mainHandler.post { onError("已取消导出") }
        } catch (e: Exception) {
            targetFile.delete()
            mainHandler.post { onError("导出失败: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            try {
                gifFos?.close()
            } catch (_: Exception) {}
            fullFrameBmp.recycle()
            scaledFrameBmp?.recycle()

            // 恢复背景层可见性
            if (transparentBg && bgIndex >= 0 && wasBgVisible) {
                ReverieCoreBridge.setLayerVisible(bgIndex, true)
            }
            // 恢复原始帧号
            ReverieCoreBridge.setAnimationCurrentTime(originalTime, false)
            displayBufferInvalid = true

            mainHandler.post {
                anim.currentTime = originalTime
                syncLayersFromNative()
            }
        }
    }
}

/**
 * 将导出的动画 (GIF / MP4) 存入 Android MediaStore 系统相册。
 */
internal fun PaintViewModel.exportAnimationToGallery(
    options: AnimationExportOptions,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit = {},
) {
    val fmt = options.format.lowercase()
    if (fmt != "gif" && fmt != "mp4") {
        onError("仅 GIF 动图与 MP4 视频支持直接存入相册")
        return
    }

    val isGif = fmt == "gif"
    val mimeType = if (isGif) "image/gif" else "video/mp4"
    val ext = fmt
    val fileName = "${docName}_anim_${System.currentTimeMillis()}.$ext"
    val tempFile = File(appContext.cacheDir, fileName)

    exportAnimation(
        options = options,
        targetFile = tempFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = { file ->
            try {
                val resolver = appContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val subDir = if (isGif) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$subDir/ReveriePaint")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val tableUri = if (isGif) {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                val uri = resolver.insert(tableUri, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    tempFile.delete()
                    onSuccess(uri)
                } else {
                    onError("无法创建系统媒体库文件")
                }
            } catch (e: Exception) {
                onError("保存至相册失败: ${e.message}")
            }
        },
        onError = onError,
    )
}

/**
 * 导出动画并通过系统分享菜单发送至其他应用。
 */
internal fun PaintViewModel.shareAnimation(
    options: AnimationExportOptions,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val ext = options.format.lowercase()
    val shareDir = File(appContext.cacheDir, "share_anim")
    shareDir.mkdirs()
    val shareFile = File(shareDir, "${docName}_anim.$ext")

    exportAnimation(
        options = options,
        targetFile = shareFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onError = onError,
    )
}
