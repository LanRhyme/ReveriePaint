/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.reverie.paint.R
import com.reverie.paint.core.export.AnimatedGifEncoder
import com.reverie.paint.core.export.Mp4VideoEncoder
import com.reverie.paint.core.export.PngSequenceZipEncoder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 清理导出文件名中的非法路径字符。
 */
internal fun sanitizeExportFileName(raw: String): String {
    return raw.ifBlank { "ReveriePaint" }
        .trim()
        .replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]"), "_")
        .ifBlank { "ReveriePaint" }
}

/**
 * 统一计算导出目标分辨率 (动画导出与回放导出共用，UI 预览标签与底层编码器共用):
 * - MP4: 宽高对齐 16 的倍数，且限制在 3840x2160 以内确保硬件 H.264 编码器兼容。
 * - GIF: 限制长边不超过 1920，防止超大画布 100% 导出时 LZW 量化与 ARGB_8888 缓冲触发 OOM。
 * - ZIP: 限制长边不超过 3840，防止 8K 画布导出序列帧时触发 OOM。
 */
internal fun computeExportDimensions(
    baseW: Int,
    baseH: Int,
    scale: Float,
    format: String,
): Pair<Int, Int> {
    val safeW = max(2, baseW)
    val safeH = max(2, baseH)
    val safeScale = scale.coerceIn(0.1f, 1.0f)
    var targetW = max(2, (safeW * safeScale).roundToInt())
    var targetH = max(2, (safeH * safeScale).roundToInt())

    when (format.lowercase()) {
        "mp4" -> {
            targetW = max(16, (targetW / 16) * 16)
            targetH = max(16, (targetH / 16) * 16)
            if (targetW > 3840 || targetH > 2160) {
                val ratio = minOf(3840f / targetW, 2160f / targetH)
                targetW = max(16, ((targetW * ratio).toInt() / 16) * 16)
                targetH = max(16, ((targetH * ratio).toInt() / 16) * 16)
            }
        }
        "gif" -> {
            val maxEdge = max(targetW, targetH)
            if (maxEdge > 1920) {
                val ratio = 1920f / maxEdge
                targetW = max(2, (targetW * ratio).roundToInt())
                targetH = max(2, (targetH * ratio).roundToInt())
            }
        }
        else -> {
            val maxEdge = max(targetW, targetH)
            if (maxEdge > 3840) {
                val ratio = 3840f / maxEdge
                targetW = max(2, (targetW * ratio).roundToInt())
                targetH = max(2, (targetH * ratio).roundToInt())
            }
        }
    }
    return targetW to targetH
}

/**
 * 公共多格式帧编码会话 (封装 GIF / MP4 / PNG-ZIP 的初始化、逐帧写入、收尾校验与异常清理)。
 * 供动画导出 (PaintViewModelAnimationExport) 与回放导出 (PaintViewModelReplayExport) 共同复用。
 */
internal class FrameEncodingSession(
    private val vm: PaintViewModel,
    val format: String,
    val targetFile: File,
    val targetW: Int,
    val targetH: Int,
    val fps: Int,
    private val loopGif: Boolean = true,
    private val transparentGif: Boolean = false,
) {
    private var gifEncoder: AnimatedGifEncoder? = null
    private var gifFos: FileOutputStream? = null
    private var mp4Encoder: Mp4VideoEncoder? = null
    private var zipEncoder: PngSequenceZipEncoder? = null

    fun start() {
        when (format) {
            "gif" -> {
                val encoder = AnimatedGifEncoder()
                encoder.setFramerate(fps)
                encoder.setRepeat(if (loopGif) 0 else -1)
                if (transparentGif) {
                    encoder.setTransparent(0)
                }
                val fos = FileOutputStream(targetFile)
                gifFos = fos
                if (!encoder.start(fos)) {
                    throw RuntimeException(vm.getString(R.string.replay_export_err_encoder_start, "GIF"))
                }
                gifEncoder = encoder
            }
            "mp4" -> {
                val encoder = Mp4VideoEncoder()
                if (!encoder.start(targetFile, targetW, targetH, fps)) {
                    throw RuntimeException(vm.getString(R.string.replay_export_err_encoder_start, "MP4"))
                }
                mp4Encoder = encoder
            }
            "zip" -> {
                val encoder = PngSequenceZipEncoder()
                if (!encoder.start(targetFile)) {
                    throw RuntimeException(vm.getString(R.string.replay_export_err_encoder_start, "ZIP"))
                }
                zipEncoder = encoder
            }
            else -> throw IllegalArgumentException(vm.getString(R.string.replay_export_err_unsupported_format, format))
        }
    }

    fun addFrame(
        bitmap: Bitmap,
        isCancelled: () -> Boolean,
        frameNumber: Int,
        repeatCount: Int = 1,
    ) {
        if (isCancelled()) {
            throw InterruptedException(vm.getString(R.string.replay_export_err_cancelled))
        }
        val ok =
            when (format) {
                "gif" -> gifEncoder?.addFrame(bitmap, isCancelled, repeatCount) ?: false
                "mp4" -> mp4Encoder?.addFrame(bitmap, isCancelled, repeatCount) ?: false
                "zip" -> zipEncoder?.addFrame(bitmap, isCancelled, repeatCount) ?: false
                else -> false
            }
        if (!ok) {
            if (isCancelled()) {
                throw InterruptedException(vm.getString(R.string.replay_export_err_cancelled))
            }
            throw RuntimeException(vm.getString(R.string.replay_export_err_encode_frame, frameNumber))
        }
    }

    fun finish(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw InterruptedException(vm.getString(R.string.replay_export_err_cancelled))
        }
        val finishOk =
            when (format) {
                "gif" -> {
                    val ok = gifEncoder?.finish() ?: false
                    gifFos?.close()
                    gifFos = null
                    ok
                }
                "mp4" -> mp4Encoder?.finish(isCancelled) ?: false
                "zip" -> zipEncoder?.finish() ?: false
                else -> false
            }
        if (!finishOk || !targetFile.exists() || targetFile.length() <= 0L) {
            targetFile.delete()
            throw RuntimeException(vm.getString(R.string.replay_export_err_finalize))
        }
    }

    fun closeQuietly() {
        try {
            gifFos?.close()
        } catch (_: Exception) {
        }
        gifFos = null
        mp4Encoder?.abort()
        mp4Encoder = null
        zipEncoder?.abort()
        zipEncoder = null
    }
}

/**
 * 在后台 I/O 线程将临时导出文件写入 Android MediaStore 系统相册 (严禁在主线程 copyTo 阻塞 UI):
 * - API 29+ (Android 10+): 使用 RELATIVE_PATH + IS_PENDING 原子发布。
 * - API 23..28 (Android 6..9): 校验 WRITE_EXTERNAL_STORAGE 权限，写入公共媒体目录并设置 DATA 字段与 MediaScanner。
 */
internal fun PaintViewModel.saveExportFileToGalleryAsync(
    tempFile: File,
    fileName: String,
    isGif: Boolean,
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit,
) {
    val mimeType = if (isGif) "image/gif" else "video/mp4"
    val relDir = if (isGif) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DCIM

    Thread(
        {
            var legacyTargetFile: File? = null
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val granted =
                        ContextCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        tempFile.delete()
                        val msg = getString(R.string.replay_export_err_storage_permission)
                        mainHandler.post { onError(msg) }
                        return@Thread
                    }
                }

                val resolver = appContext.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/ReveriePaint")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        } else {
                            @Suppress("DEPRECATION")
                            val pubDir = File(Environment.getExternalStoragePublicDirectory(relDir), "ReveriePaint")
                            pubDir.mkdirs()
                            val dst = File(pubDir, fileName)
                            legacyTargetFile = dst
                            tempFile.inputStream().use { input ->
                                dst.outputStream().use { out ->
                                    input.copyTo(out)
                                }
                            }
                            @Suppress("DEPRECATION")
                            put(MediaStore.MediaColumns.DATA, dst.absolutePath)
                        }
                    }

                val tableUri =
                    if (isGif) {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }

                val uri = resolver.insert(tableUri, values)
                if (uri != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.openOutputStream(uri)?.use { out ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        } ?: throw RuntimeException(getString(R.string.replay_export_err_gallery_create))

                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    tempFile.delete()

                    try {
                        val scanPath =
                            legacyTargetFile?.absolutePath ?: run {
                                val queryPath = try {
                                    @Suppress("DEPRECATION")
                                    val pathQuery = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                                    pathQuery?.use { c ->
                                        if (c.moveToFirst()) c.getString(0) else null
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                                queryPath ?: File(Environment.getExternalStoragePublicDirectory(relDir), "ReveriePaint/$fileName").absolutePath
                            }
                        if (scanPath != null) {
                            MediaScannerConnection.scanFile(
                                appContext,
                                arrayOf(scanPath),
                                arrayOf(mimeType),
                                null,
                            )
                        }
                    } catch (_: Exception) {
                    }

                    mainHandler.post { onSuccess(uri) }
                } else {
                    tempFile.delete()
                    legacyTargetFile?.delete()
                    val msg = getString(R.string.replay_export_err_gallery_create)
                    mainHandler.post { onError(msg) }
                }
            } catch (e: Exception) {
                tempFile.delete()
                legacyTargetFile?.delete()
                val msg = getString(R.string.replay_export_err_gallery_save, e.message ?: e.javaClass.simpleName)
                mainHandler.post { onError(msg) }
            }
        },
        "reverie-gallery-io",
    ).start()
}

/**
 * 在后台 I/O 线程将临时导出文件拷贝至 SAF 文档 Uri，避免主线程大文件拷贝导致 ANR。
 */
internal fun PaintViewModel.copyExportFileToUriAsync(
    tempFile: File,
    targetUri: Uri,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    Thread(
        {
            try {
                val stream =
                    appContext.contentResolver.openOutputStream(targetUri)
                        ?: throw RuntimeException(getString(R.string.replay_export_err_finalize))
                stream.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                tempFile.delete()
                mainHandler.post { onSuccess() }
            } catch (e: Exception) {
                tempFile.delete()
                val msg = getString(R.string.replay_export_err_failed, e.message ?: e.javaClass.simpleName)
                mainHandler.post { onError(msg) }
            }
        },
        "reverie-saf-io",
    ).start()
}

/**
 * 动画导出参数与配置。
 */
internal data class AnimationExportOptions(
    val format: String, // "gif", "mp4", "zip"
    val scale: Float = 1.0f, // 1.0f, 0.5f, 0.25f
    val startFrame: Int = 0,
    val endFrame: Int = 0,
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
        val s = options.startFrame.coerceAtLeast(0)
        val e = options.endFrame.coerceAtLeast(s)
        var frameList = (s..e).toList()

        if (frameList.isEmpty()) {
            val msg = getString(R.string.replay_export_err_empty_frames)
            mainHandler.post { onError(msg) }
            return@runCore
        }

        // 对于 MP4 格式，若有效动画时长过短 (如 < 1.0 秒)，许多播放器及相册无法正常缓冲或循环展示
        // 自动循环倍增帧序列至至少 1.0 秒，保证 MP4 生成具有合法可播时长
        if (fmt == "mp4" && anim.framerate > 0) {
            val minFrames = anim.framerate
            if (frameList.size < minFrames) {
                val repeatTimes = (minFrames + frameList.size - 1) / frameList.size
                val expandedList = mutableListOf<Int>()
                repeat(repeatTimes) {
                    expandedList.addAll(frameList)
                }
                frameList = expandedList
            }
        }

        val totalFrames = frameList.size

        // 查找背景层并处理透明背景
        val bgLayer = layers.firstOrNull { it.isBackground } ?: layers.firstOrNull()
        val bgIndex = bgLayer?.index ?: -1
        val wasBgVisible = if (bgIndex >= 0) ReverieCoreBridge.layerVisible(bgIndex) else false

        if (transparentBg && bgIndex >= 0 && wasBgVisible) {
            ReverieCoreBridge.setLayerVisible(bgIndex, false)
        }

        val w = max(2, coreW)
        val h = max(2, coreH)
        val (targetW, targetH) = computeExportDimensions(w, h, scale, fmt)
        val needScale = targetW != w || targetH != h

        var fullFrameBmp: Bitmap? = null
        var scaledFrameBmp: Bitmap? = null
        val encodeSession =
            FrameEncodingSession(
                vm = this,
                format = fmt,
                targetFile = targetFile,
                targetW = targetW,
                targetH = targetH,
                fps = anim.framerate.coerceAtLeast(1),
                loopGif = anim.loopPlayback,
                transparentGif = transparentBg,
            )

        try {
            fullFrameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scaledFrameBmp =
                if (needScale) {
                    Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                } else {
                    null
                }
            val scaleCanvas = scaledFrameBmp?.let { Canvas(it) }
            val scalePaint = if (needScale) Paint(Paint.FILTER_BITMAP_FLAG) else null
            val srcRect = Rect(0, 0, w, h)
            val dstRect = Rect(0, 0, targetW, targetH)

            // 参考画面只用于作画，所有动画格式都输出实际帧内容。
            ReverieCoreBridge.setOnionSkinSuppressed(true)
            encodeSession.start()

            for (i in frameList.indices) {
                if (isCancelled()) {
                    throw InterruptedException(getString(R.string.replay_export_err_cancelled))
                }
                val frameTime = frameList[i]
                val stageMsg = getString(R.string.replay_export_stage_rendering_frame, i + 1, totalFrames)
                mainHandler.post {
                    onProgress(i + 1, totalFrames, stageMsg)
                }

                // 切换动画帧并收敛
                ReverieCoreBridge.setAnimationCurrentTime(frameTime, false)
                ReverieCoreBridge.renderToBuffer(fullFrameBmp, true, null)

                val outBitmap: Bitmap =
                    if (needScale && scaledFrameBmp != null && scaleCanvas != null) {
                        scaleCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
                        scaleCanvas.drawBitmap(fullFrameBmp, srcRect, dstRect, scalePaint)
                        scaledFrameBmp
                    } else {
                        fullFrameBmp
                    }

                encodeSession.addFrame(outBitmap, isCancelled, frameTime)
            }

            val finalizeMsg = getString(R.string.replay_export_stage_finalizing)
            mainHandler.post {
                onProgress(totalFrames, totalFrames, finalizeMsg)
            }

            encodeSession.finish(isCancelled)
            mainHandler.post { onSuccess(targetFile) }
        } catch (e: InterruptedException) {
            targetFile.delete()
            val msg = getString(R.string.replay_export_err_cancelled)
            mainHandler.post { onError(msg) }
        } catch (e: Exception) {
            targetFile.delete()
            val msg = getString(R.string.replay_export_err_failed, e.message ?: e.javaClass.simpleName)
            mainHandler.post { onError(msg) }
        } finally {
            encodeSession.closeQuietly()
            fullFrameBmp?.recycle()
            scaledFrameBmp?.recycle()
            // 恢复背景层可见性
            if (transparentBg && bgIndex >= 0 && wasBgVisible) {
                ReverieCoreBridge.setLayerVisible(bgIndex, true)
            }
            // 恢复原始帧号
            ReverieCoreBridge.setAnimationCurrentTime(originalTime, false)
            ReverieCoreBridge.setOnionSkinSuppressed(anim.isPlaying || anim.shiftTraceActive)
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
        val msg = getString(R.string.replay_export_err_gallery_format)
        mainHandler.post { onError(msg) }
        return
    }
    val isGif = fmt == "gif"
    val safeName = sanitizeExportFileName(docName)
    val fileName = "${safeName}_anim_${System.currentTimeMillis()}.$fmt"
    val tempFile = File(appContext.cacheDir, fileName)

    exportAnimation(
        options = options,
        targetFile = tempFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = { file ->
            val savingMsg = getString(R.string.replay_export_stage_saving_gallery)
            onProgress(1, 1, savingMsg)
            saveExportFileToGalleryAsync(
                tempFile = file,
                fileName = fileName,
                isGif = isGif,
                onSuccess = onSuccess,
                onError = onError,
            )
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
    val safeName = sanitizeExportFileName(docName)
    val shareFile = File(shareDir, "${safeName}_anim.$ext")
    exportAnimation(
        options = options,
        targetFile = shareFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onError = onError,
    )
}
