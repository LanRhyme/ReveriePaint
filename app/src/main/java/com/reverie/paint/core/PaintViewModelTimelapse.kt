/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * 导出延时摄影视频并保存至系统相册 Movies/ReveriePaint。
 */
fun PaintViewModel.exportTimelapseVideo(
    project: Project? = null,
    fps: Int = 12,
    onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit,
    isCancelled: () -> Boolean = { false },
) {
    val framesDir = getTimelapseFramesDir(project)
    if (framesDir == null || !framesDir.exists() || !framesDir.isDirectory) {
        onError(getString(R.string.timelapse_export_err_no_frames))
        return
    }

    val frameCount = getTimelapseFrameCount(project)
    if (frameCount == 0) {
        onError(getString(R.string.timelapse_export_err_no_frames))
        return
    }

    val projName = project?.name ?: docName
    val tempMp4 = File(appContext.cacheDir, "timelapse_export_${System.currentTimeMillis()}.mp4")

    Thread({
        try {
            val ok = TimelapseExport.encode(
                framesDir = framesDir,
                outFile = tempMp4,
                fps = fps,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )

            if (!ok || !tempMp4.exists() || tempMp4.length() <= 0L) {
                tempMp4.delete()
                val msg = getString(R.string.timelapse_export_err_failed)
                mainHandler.post { onError(msg) }
                return@Thread
            }

            // 保存到系统相册 Movies/ReveriePaint (复用 PaintViewModelAnimationExport.kt 中的方法)
            val fileName = "${projName}_timelapse.mp4"
            saveExportFileToGalleryAsync(
                tempFile = tempMp4,
                fileName = fileName,
                isGif = false,
                onSuccess = onSuccess,
                onError = onError,
            )
        } catch (t: Throwable) {
            tempMp4.delete()
            val msg = t.message ?: getString(R.string.timelapse_export_err_failed)
            mainHandler.post { onError(msg) }
        }
    }, "RP-TimelapseExportThread").start()
}
