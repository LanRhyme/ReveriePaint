/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.reverie.paint.R
import com.reverie.paint.core.export.TimelapseExport
import com.reverie.paint.model.Project
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG_TIMELAPSE = "RP_Timelapse"

/** 后台单一抓帧执行器，避免并发把系统拖死 */
private val timelapseExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "RP-TimelapseCaptureThread").apply {
        priority = Thread.NORM_PRIORITY - 1
    }
}

/** 同一时刻只允许一个抓帧任务在跑 */
private val isTimelapseCapturing = AtomicBoolean(false)

/** 当前激活的帧捕获器缓存 */
private var cachedFrameRecorder: FrameRecorder? = null
private var cachedFramesDirPath: String? = null

/**
 * 更新延时摄影录制开关并持久化。
 */
fun PaintViewModel.updateTimelapseRecordingEnabled(enabled: Boolean) {
    timelapseRecordingEnabled = enabled
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE).edit {
        putBoolean("timelapse_recording", enabled)
    }
}

/**
 * 更新延时摄影最小抓帧间隔 (毫秒) 并持久化。
 */
fun PaintViewModel.updateTimelapseMinInterval(intervalMs: Long) {
    timelapseMinIntervalMs = intervalMs.coerceIn(100L, 5000L)
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE).edit {
        putLong("timelapse_min_interval_ms", timelapseMinIntervalMs)
    }
    cachedFrameRecorder = null // 参数变动使捕获器缓存失效重置
}

/**
 * 更新延时摄影最大帧数上限并持久化。
 */
fun PaintViewModel.updateTimelapseMaxFrames(maxFrames: Int) {
    timelapseMaxFrames = maxFrames.coerceIn(100, 20000)
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE).edit {
        putInt("timelapse_max_frames", timelapseMaxFrames)
    }
    cachedFrameRecorder = null
}

/**
 * 获取指定或当前工程的延时摄影帧目录：<工程目录>/frames/<工程名>/
 */
fun PaintViewModel.getTimelapseFramesDir(project: Project? = null): File? {
    val filePath = project?.filePath ?: currentProjectFile ?: return null
    val projFile = File(filePath)
    val parentDir = projFile.parentFile ?: return null
    val projName = projFile.nameWithoutExtension
    return File(parentDir, "frames/$projName")
}

/**
 * 获取指定或当前工程已录制的延时摄影帧总数。
 */
fun PaintViewModel.getTimelapseFrameCount(project: Project? = null): Int {
    val dir = getTimelapseFramesDir(project) ?: return 0
    if (!dir.exists() || !dir.isDirectory) return 0
    val files = dir.listFiles { f ->
        f.isFile && f.name.matches(Regex("""frame_\d{5}\.webp"""))
    }
    return files?.size ?: 0
}

/**
 * 获取指定或当前工程的全部帧文件列表（按播放顺序排序）。
 */
fun PaintViewModel.getTimelapseFrameFiles(project: Project? = null): List<File> {
    val dir = getTimelapseFramesDir(project) ?: return emptyList()
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles { f ->
        f.isFile && f.name.matches(Regex("""frame_\d{5}\.webp"""))
    }?.sortedBy { it.name } ?: emptyList()
}

/**
 * 获取或创建当前工程的 FrameRecorder 实例。
 */
private fun PaintViewModel.getOrCreateFrameRecorder(): FrameRecorder? {
    val framesDir = getTimelapseFramesDir() ?: return null
    if (cachedFrameRecorder != null && cachedFramesDirPath == framesDir.absolutePath) {
        return cachedFrameRecorder
    }
    return FrameRecorder(
        dir = framesDir,
        minIntervalMs = timelapseMinIntervalMs,
        maxFrames = timelapseMaxFrames,
    ).also {
        cachedFrameRecorder = it
        cachedFramesDirPath = framesDir.absolutePath
    }
}

/**
 * 触发一次后台画布抓帧。
 * - 只有在开启录制且处于绘画状态时生效
 * - 排队执行且同一时刻只允许一个在跑 (CAS 防重入)
 * - 耗时超过 500ms 输出 warn 日志
 * - 降采样至长边 ≤1280
 */
fun PaintViewModel.triggerTimelapseCapture(isChanged: Boolean = true) {
    Log.d(TAG_TIMELAPSE, "triggerTimelapseCapture: enabled=$timelapseRecordingEnabled, proj=$currentProjectFile, isChanged=$isChanged")
    if (!timelapseRecordingEnabled || currentProjectFile == null) {
        return
    }

    if (!isTimelapseCapturing.compareAndSet(false, true)) {
        Log.d(TAG_TIMELAPSE, "triggerTimelapseCapture: skipped because previous capture is still running")
        // 前一个抓帧尚未完成，丢弃本次以避免并发堆积
        return
    }

    timelapseExecutor.execute {
        val startUptime = SystemClock.uptimeMillis()
        var tempPng: File? = null
        try {
            val recorder = getOrCreateFrameRecorder() ?: run {
                Log.w(TAG_TIMELAPSE, "triggerTimelapseCapture: getOrCreateFrameRecorder returned null")
                return@execute
            }
            val nowMs = System.currentTimeMillis()
            if (!recorder.shouldCapture(nowMs, isChanged)) {
                Log.d(TAG_TIMELAPSE, "triggerTimelapseCapture: shouldCapture returned false")
                return@execute
            }

            tempPng = File(appContext.cacheDir, "timelapse_snap_${nowMs}_${System.nanoTime() % 1000}.png")
            val saveOk = try {
                ReverieCoreBridge.savePng(tempPng.absolutePath)
            } catch (t: Throwable) {
                Log.e(TAG_TIMELAPSE, "ReverieCoreBridge.savePng failed", t)
                false
            }

            if (saveOk && tempPng.exists() && tempPng.length() > 0L) {
                // 1. 探测原始尺寸并计算降采样 inSampleSize (目标长边 ≤1280)
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempPng.absolutePath, boundsOpts)
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sampleSize = 1
                if (maxDim > 1280) {
                    while ((maxDim / (sampleSize * 2)) >= 1280) {
                        sampleSize *= 2
                    }
                }

                // 2. 降采样解码
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bmp = BitmapFactory.decodeFile(tempPng.absolutePath, decodeOpts)
                if (bmp != null) {
                    val savedFile = recorder.capture(bmp, nowMs, isChanged = true)
                    Log.i(TAG_TIMELAPSE, "Captured frame: count=${recorder.frameCount}, savedFile=${savedFile?.name}")
                    bmp.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG_TIMELAPSE, "Exception during timelapse capture", t)
        } finally {
            tempPng?.let {
                if (it.exists()) it.delete()
            }
            val elapsed = SystemClock.uptimeMillis() - startUptime
            if (elapsed > 500L) {
                Log.w(TAG_TIMELAPSE, "Timelapse capture took ${elapsed}ms > 500ms")
            }
            isTimelapseCapturing.set(false)
        }
    }
}

/**
 * 延时摄影导出参数配置
 *
 * @param format 导出格式: "mp4", "gif", "zip"
 * @param durationSec 目标视频时长 (秒): 15, 30, 60; 若为 0 则为原速
 * @param fps 导出帧率 (MP4/ZIP 默认为 12 或 30, GIF 为 12)
 * @param scale 分辨率缩放比例 (1.0f, 0.5f, 0.25f)
 * @param startFraction 导出起始进度区间 (0f..1f)
 * @param endFraction 导出结束进度区间 (0f..1f)
 * @param showWatermark 是否在导出帧右下角叠加作者水印
 * @param holdEndFrames 是否在结尾追加 1 秒定格帧以展示最终完成作品
 */
data class TimelapseExportOptions(
    val format: String = "mp4",
    val durationSec: Int = 0,
    val fps: Int = 12,
    val scale: Float = 1.0f,
    val startFraction: Float = 0f,
    val endFraction: Float = 1f,
    val showWatermark: Boolean = false,
    val holdEndFrames: Boolean = true,
)

/**
 * 统一执行延时摄影帧序列编码导出 (后台线程执行)。
 */
fun PaintViewModel.exportTimelapse(
    project: Project? = null,
    options: TimelapseExportOptions,
    targetFile: File,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val allFrameFiles = getTimelapseFrameFiles(project)
    if (allFrameFiles.isEmpty()) {
        val msg = getString(R.string.replay_export_err_no_data)
        mainHandler.post { onError(msg) }
        return
    }

    val fmt = options.format.lowercase()
    val scale = options.scale.coerceIn(0.1f, 1.0f)
    val fps = if (fmt == "gif") options.fps.coerceIn(8, 24) else options.fps.coerceIn(12, 60)
    val startFrac = options.startFraction.coerceIn(0f, 0.99f)
    val endFrac = options.endFraction.coerceIn(startFrac + 0.01f, 1f)

    val totalFrames = allFrameFiles.size
    val startIdx = (totalFrames * startFrac).toInt().coerceIn(0, totalFrames - 1)
    val endIdx = (totalFrames * endFrac).toInt().coerceIn(startIdx, totalFrames - 1)
    val selectedFiles = allFrameFiles.subList(startIdx, endIdx + 1)

    if (selectedFiles.isEmpty()) {
        val msg = getString(R.string.replay_export_err_empty_frames)
        mainHandler.post { onError(msg) }
        return
    }

    // 预构建水印文本
    val watermarkText: String =
        if (options.showWatermark) {
            val authorName = authorProfile.name.trim().ifEmpty { authorProfile.nickname.trim() }
            val copyText = authorProfile.copyright.trim()
            val curDocName = project?.name ?: docName
            when {
                authorName.isNotEmpty() -> "© $authorName · ReveriePaint"
                copyText.isNotEmpty() -> copyText
                curDocName.isNotBlank() -> "${curDocName.trim()} · ReveriePaint"
                else -> "ReveriePaint"
            }
        } else {
            ""
        }

    Thread({
        var encodeSession: FrameEncodingSession? = null
        try {
            if (isCancelled()) throw InterruptedException(getString(R.string.replay_export_err_cancelled))

            val firstFile = selectedFiles[0]
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(firstFile.absolutePath, bounds)
            val baseW = if (bounds.outWidth > 0) bounds.outWidth else 1280
            val baseH = if (bounds.outHeight > 0) bounds.outHeight else 720
            val (targetW, targetH) = computeExportDimensions(baseW, baseH, scale, fmt)

            encodeSession = FrameEncodingSession(
                vm = this,
                format = fmt,
                targetFile = targetFile,
                targetW = targetW,
                targetH = targetH,
                fps = fps,
            )
            encodeSession.start()

            // 采样计算
            val targetActiveFrames =
                if (options.durationSec > 0) {
                    max(fps, options.durationSec * fps)
                } else {
                    selectedFiles.size
                }
            val holdFrames = if (options.holdEndFrames) fps else 0
            val totalOutputFrames = targetActiveFrames + holdFrames

            // 水印画笔
            val wmFontSize = max(11f, min(targetW, targetH) * 0.022f)
            val wmPaddingH = wmFontSize * 0.65f
            val wmPaddingV = wmFontSize * 0.38f
            val wmMargin = wmFontSize * 0.75f
            val watermarkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6FFFFFF.toInt()
                textSize = wmFontSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
            }
            val watermarkBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0x66141318
            }
            val wmTextWidth = if (watermarkText.isNotEmpty()) watermarkTextPaint.measureText(watermarkText) else 0f
            val wmBoxRectF = RectF()

            var lastRenderedBmp: Bitmap? = null
            var lastSrcIdx = -1

            val stageAnalyzing = getString(R.string.replay_export_stage_analyzing)
            mainHandler.post { onProgress(0, totalOutputFrames, stageAnalyzing) }

            for (step in 0 until targetActiveFrames) {
                if (isCancelled()) throw InterruptedException(getString(R.string.replay_export_err_cancelled))

                val curFrame = step + 1
                val stageMsg = getString(R.string.replay_export_stage_rendering_frame, curFrame, totalOutputFrames)
                mainHandler.post { onProgress(curFrame, totalOutputFrames, stageMsg) }

                val srcIdx =
                    if (options.durationSec > 0) {
                        ((step.toDouble() / max(1, targetActiveFrames - 1)) * (selectedFiles.size - 1)).roundToInt()
                    } else {
                        step
                    }

                val frameBmp: Bitmap
                if (srcIdx == lastSrcIdx && lastRenderedBmp != null) {
                    frameBmp = lastRenderedBmp
                } else {
                    val rawBmp = BitmapFactory.decodeFile(selectedFiles[srcIdx].absolutePath)
                        ?: continue
                    val scaled =
                        if (rawBmp.width != targetW || rawBmp.height != targetH) {
                            Bitmap.createScaledBitmap(rawBmp, targetW, targetH, true).also {
                                if (it !== rawBmp) rawBmp.recycle()
                            }
                        } else {
                            rawBmp
                        }

                    val workingBmp =
                        if (!scaled.isMutable) {
                            scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
                        } else {
                            scaled
                        }

                    if (watermarkText.isNotEmpty()) {
                        val canvas = Canvas(workingBmp)
                        val boxW = wmTextWidth + wmPaddingH * 2f
                        val boxH = wmFontSize + wmPaddingV * 2f
                        val boxLeft = targetW - boxW - wmMargin
                        val boxTop = targetH - boxH - wmMargin
                        wmBoxRectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
                        val corner = boxH * 0.35f
                        canvas.drawRoundRect(wmBoxRectF, corner, corner, watermarkBgPaint)
                        val textX = boxLeft + wmPaddingH
                        val textY = boxTop + wmPaddingV + wmFontSize * 0.82f
                        canvas.drawText(watermarkText, textX, textY, watermarkTextPaint)
                    }

                    if (lastRenderedBmp != null && lastRenderedBmp !== workingBmp) {
                        lastRenderedBmp?.recycle()
                    }
                    lastRenderedBmp = workingBmp
                    lastSrcIdx = srcIdx
                    frameBmp = workingBmp
                }

                encodeSession.addFrame(frameBmp, isCancelled, curFrame, repeatCount = 1)
            }

            // 结尾定格帧
            if (holdFrames > 0 && lastRenderedBmp != null) {
                if (isCancelled()) throw InterruptedException(getString(R.string.replay_export_err_cancelled))
                val holdMsg = getString(R.string.replay_export_stage_holding_frame, totalOutputFrames, totalOutputFrames)
                mainHandler.post { onProgress(totalOutputFrames, totalOutputFrames, holdMsg) }
                encodeSession.addFrame(lastRenderedBmp, isCancelled, totalOutputFrames, repeatCount = holdFrames)
            }

            lastRenderedBmp?.recycle()

            val finalizingMsg = getString(R.string.replay_export_stage_finalizing)
            mainHandler.post { onProgress(totalOutputFrames, totalOutputFrames, finalizingMsg) }
            encodeSession.finish(isCancelled)

            mainHandler.post { onSuccess(targetFile) }
        } catch (t: Throwable) {
            targetFile.delete()
            val msg =
                if (t is InterruptedException) {
                    getString(R.string.replay_export_err_cancelled)
                } else {
                    getString(R.string.replay_export_err_failed, t.message ?: t.javaClass.simpleName)
                }
            mainHandler.post { onError(msg) }
        } finally {
            encodeSession?.closeQuietly()
        }
    }, "RP-TimelapseAdvancedExport").start()
}

/**
 * 导出延时摄影并保存至系统相册 (MP4 或 GIF)。
 */
fun PaintViewModel.exportTimelapseToGallery(
    project: Project? = null,
    options: TimelapseExportOptions,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit,
) {
    val fmt = options.format.lowercase()
    if (fmt != "gif" && fmt != "mp4") {
        val msg = getString(R.string.replay_export_err_gallery_format)
        mainHandler.post { onError(msg) }
        return
    }
    val isGif = fmt == "gif"
    val projName = project?.name ?: docName
    val safeName = sanitizeExportFileName(projName)
    val fileName = "${safeName}_timelapse_${System.currentTimeMillis()}.$fmt"
    val tempFile = File(appContext.cacheDir, fileName)

    exportTimelapse(
        project = project,
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
 * 导出延时摄影并写入指定 SAF Uri (如 ZIP 或自定义保存位置)。
 */
fun PaintViewModel.exportTimelapseToUri(
    project: Project? = null,
    options: TimelapseExportOptions,
    targetUri: Uri,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val ext = options.format.lowercase()
    val projName = project?.name ?: docName
    val safeName = sanitizeExportFileName(projName)
    val tempFile = File(appContext.cacheDir, "${safeName}_timelapse_${System.currentTimeMillis()}.$ext")

    exportTimelapse(
        project = project,
        options = options,
        targetFile = tempFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = { file ->
            copyExportFileToUriAsync(
                tempFile = file,
                targetUri = targetUri,
                onSuccess = onSuccess,
                onError = onError,
            )
        },
        onError = onError,
    )
}

/**
 * 导出延时摄影并生成供系统分享的临时文件。
 */
fun PaintViewModel.shareTimelapse(
    project: Project? = null,
    options: TimelapseExportOptions,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val ext = options.format.lowercase()
    val shareDir = File(appContext.cacheDir, "share_timelapse")
    shareDir.mkdirs()
    val projName = project?.name ?: docName
    val safeName = sanitizeExportFileName(projName)
    val shareFile = File(shareDir, "${safeName}_timelapse.$ext")
    exportTimelapse(
        project = project,
        options = options,
        targetFile = shareFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onError = onError,
    )
}

/**
 * 获取指定或当前工程的延时摄影帧总占用字节数。
 */
fun PaintViewModel.getTimelapseTotalBytes(project: Project? = null): Long {
    val dir = getTimelapseFramesDir(project) ?: return 0L
    if (!dir.exists() || !dir.isDirectory) return 0L
    var total = 0L
    dir.listFiles()?.forEach { if (it.isFile) total += it.length() }
    return total
}

/**
 * 清理指定或当前工程的延时摄影帧目录并重置捕获器缓存。
 * 返回释放的总字节数。
 */
fun PaintViewModel.clearTimelapseData(project: Project? = null): Long {
    val dir = getTimelapseFramesDir(project) ?: return 0L
    if (!dir.exists() || !dir.isDirectory) return 0L
    var freed = 0L
    try {
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                freed += f.length()
                f.delete()
            }
        }
        dir.delete()
    } catch (e: Exception) {
        Log.e(TAG_TIMELAPSE, "clearTimelapseData failed", e)
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
    return freed
}

/**
 * 格式化字节大小显示。
 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB", kb)
    }
}

