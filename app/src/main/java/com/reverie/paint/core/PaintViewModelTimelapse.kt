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
import com.reverie.paint.model.TimelapseStorage
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
        priority = Thread.MIN_PRIORITY
    }
}

/** 同一时刻只允许一个抓帧任务在跑 */
private val isTimelapseCapturing = AtomicBoolean(false)
/** 当正在抓帧时又有新的变化产生，标记在当前抓帧结束后自动补抓一帧 */
private val pendingTimelapseCapture = AtomicBoolean(false)

/** 标记当前笔触是否处于触控绘制中 */
@Volatile
internal var isStylusTouching: Boolean = false

/** 上次成功触发捕获的时间戳 (毫秒) */
private var lastCaptureTriggerTimeMs: Long = 0L

/** 节流窗口补齐收尾帧 Runnable 引用 */
private var pendingIdleCaptureRunnable: Runnable? = null

/** 当前激活的帧捕获器缓存 */
private var cachedFrameRecorder: FrameRecorder? = null
private var cachedFramesDirPath: String? = null

/**
 * 重置延时摄影录制器缓存
 */
internal fun PaintViewModel.resetTimelapseRecorderCache() {
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 彻底清理指定或当前工程的延时摄影帧目录并重置录制器
 */
internal fun PaintViewModel.purgeTimelapseFrames(project: Project? = null) {
    getTimelapseFramesDir(project)?.let { framesDir ->
        if (framesDir.exists()) {
            framesDir.deleteRecursively()
        }
    }
    resetTimelapseRecorderCache()
}

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
 * 更新延时摄影分辨率长边规格 (像素) 并持久化。
 */
fun PaintViewModel.updateTimelapseResolution(resolution: Int) {
    timelapseResolution = resolution.coerceIn(480, 3840)
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE).edit {
        putInt("timelapse_resolution", timelapseResolution)
    }
    cachedFrameRecorder = null
}

/**
 * 更新延时摄影 WebP 压缩画质 (50~100) 并持久化。
 */
fun PaintViewModel.updateTimelapseQuality(quality: Int) {
    timelapseQuality = quality.coerceIn(50, 100)
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE).edit {
        putInt("timelapse_quality", timelapseQuality)
    }
    cachedFrameRecorder = null
}

/**
 * 延时摄影专属私有存储根目录：<filesDir>/timelapse/
 * 彻底独立于工程目录，杜绝在主页画廊中产生异常 frames 画集。
 */
internal fun PaintViewModel.timelapseRootDir(): File {
    val dir = File(appContext.filesDir, "timelapse")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/** 只读访问延时摄影根目录 (不触发 mkdirs), 供 UI 判断"有没有帧"。 */
fun PaintViewModel.filesDirForTimelapse(): File = File(appContext.filesDir, "timelapse")

/**
 * 获取指定或当前工程的延时摄影帧目录：<filesDir>/timelapse/<dirName>/
 * 具备自动画集层级隔离、剥离草稿标记与旧路径迁移能力
 */
fun PaintViewModel.getTimelapseFramesDir(project: Project? = null): File? {
    val filePath = project?.filePath ?: currentProjectFile ?: return null
    val projectName = project?.name ?: docName.ifBlank { null }
    val masterFilePath = project?.masterFilePath?.takeIf { it.isNotBlank() }
    return TimelapseStorage.resolveTimelapseFramesDir(
        filesDir = appContext.filesDir,
        filePath = filePath,
        projectName = projectName,
        masterFilePath = masterFilePath,
    )
}

/**
 * 获取指定文件路径的延时摄影帧目录
 */
fun PaintViewModel.getTimelapseFramesDir(file: File): File? {
    return TimelapseStorage.resolveTimelapseFramesDir(
        filesDir = appContext.filesDir,
        filePath = file.absolutePath,
        projectName = file.nameWithoutExtension,
    )
}

/**
 * 全局扫描工程目录，将历史残留的 projects/frames/ 及子画集内的 frames 迁移至独立私有目录，
 * 并递归清理旧 frames 文件夹，彻底根除主页画廊中出现异常 frames 画集的问题。
 */
fun PaintViewModel.migrateLegacyTimelapseStorage() {
    // 只在进程内跑一次: buildProjectList 每次刷新都会调用它, 并发进入时一个线程刚删完
    // 目录、另一个线程才走到 deleteRecursively(), 会踩中 Kotlin 的
    // "rootDir must be verified to be directory beforehand" 断言直接崩主线程。
    if (!timelapseMigrationDone.compareAndSet(false, true)) return
    try {
        migrateLegacyTimelapseStorageLocked()
    } catch (t: Throwable) {
        android.util.Log.w("ReveriePaint", "timelapse legacy migration failed", t)
    }
}

private val timelapseMigrationDone = java.util.concurrent.atomic.AtomicBoolean(false)

private fun PaintViewModel.migrateLegacyTimelapseStorageLocked() {
    val rootDir = projectDir()
    val targetRoot = timelapseRootDir()

    fun migrateFramesDir(framesDir: File) {
        if (!framesDir.exists() || !framesDir.isDirectory) return
        framesDir.listFiles()?.forEach { sub ->
            if (sub.isDirectory) {
                val dest = File(targetRoot, sub.name)
                if (!dest.exists()) {
                    sub.renameTo(dest)
                } else {
                    sub.listFiles()?.forEach { f ->
                        val df = File(dest, f.name)
                        if (!df.exists()) f.renameTo(df) else f.delete()
                    }
                    sub.delete()
                }
            } else {
                sub.delete()
            }
        }
        runCatching { framesDir.deleteRecursively() }
            .onFailure { android.util.Log.w("ReveriePaint", "frames dir cleanup skipped: ${it.message}") }
    }

    // 根目录 frames
    migrateFramesDir(File(rootDir, "frames"))

    // 子画集内的 frames
    rootDir.listFiles()?.forEach { entry ->
        if (entry.isDirectory && entry.name != "frames") {
            migrateFramesDir(File(entry, "frames"))
        }
    }
}

/**
 * 重命名工程时同步迁移延时摄影数据目录 (基于文件路径感知画集前缀)
 */
fun PaintViewModel.renameTimelapseData(oldFile: File, newFile: File) {
    val oldDir = getTimelapseFramesDir(oldFile)
    val newDir = getTimelapseFramesDir(newFile)
    if (oldDir != null && newDir != null && oldDir.exists() && oldDir.absolutePath != newDir.absolutePath) {
        if (!newDir.exists()) {
            oldDir.renameTo(newDir)
        } else {
            oldDir.listFiles()?.forEach { f ->
                val df = File(newDir, f.name)
                if (!df.exists()) f.renameTo(df) else f.delete()
            }
            oldDir.deleteRecursively()
        }
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 重命名画集时同步迁移其下所有工程的延时摄影帧目录前缀
 */
fun PaintViewModel.renameTimelapseFolder(oldFolderName: String, newFolderName: String) {
    val cleanOld = oldFolderName.trim()
    val cleanNew = newFolderName.trim()
    if (cleanOld.isEmpty() || cleanNew.isEmpty() || cleanOld == cleanNew) return
    val root = timelapseRootDir()
    val prefix = "${cleanOld}__"
    root.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.name.startsWith(prefix)) {
            val suffix = dir.name.removePrefix(prefix)
            val newDir = File(root, "${cleanNew}__$suffix")
            if (!newDir.exists()) {
                dir.renameTo(newDir)
            } else {
                dir.listFiles()?.forEach { f ->
                    val df = File(newDir, f.name)
                    if (!df.exists()) f.renameTo(df) else f.delete()
                }
                dir.deleteRecursively()
            }
        }
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 彻底清理指定画集下所有工程的延时摄影帧目录
 */
fun PaintViewModel.cleanTimelapseFolder(folderName: String) {
    val clean = folderName.trim()
    if (clean.isEmpty()) return
    val root = timelapseRootDir()
    val prefix = "${clean}__"
    root.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.name.startsWith(prefix)) {
            dir.deleteRecursively()
        }
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 重命名工程时同步迁移延时摄影数据目录 (保留按纯工程名调用的兼容能力)
 */
fun PaintViewModel.renameTimelapseData(oldName: String, newName: String) {
    val cleanOld = oldName.trim()
    val cleanNew = newName.trim()
    if (cleanOld.isEmpty() || cleanNew.isEmpty() || cleanOld == cleanNew) return
    val root = timelapseRootDir()
    val oldDir = File(root, cleanOld)
    val newDir = File(root, cleanNew)
    if (oldDir.exists() && oldDir.isDirectory) {
        if (!newDir.exists()) {
            oldDir.renameTo(newDir)
        } else {
            oldDir.listFiles()?.forEach { f ->
                val df = File(newDir, f.name)
                if (!df.exists()) f.renameTo(df) else f.delete()
            }
            oldDir.deleteRecursively()
        }
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 删除工程时彻底清理对应延时摄影数据目录
 */
fun PaintViewModel.deleteTimelapseData(project: Project) {
    val dir = getTimelapseFramesDir(project)
    if (dir?.exists() == true) {
        dir.deleteRecursively()
    }
    // 兜底清理可能遗留的旧扁平目录 (仅当根目录下不存在同名正规工程时才清理)
    val projFile = File(project.filePath)
    val cleanName = TimelapseStorage.cleanProjectName(project.name.ifBlank { projFile.nameWithoutExtension })
    if (cleanName.isNotEmpty()) {
        val rootDir = projectDir()
        val rootHasSameProject = File(rootDir, "$cleanName.revp").exists() || File(rootDir, "$cleanName.kra").exists()
        if (!rootHasSameProject) {
            val flatDir = File(timelapseRootDir(), cleanName)
            if (flatDir.exists() && flatDir.absolutePath != dir?.absolutePath) {
                flatDir.deleteRecursively()
            }
        }
    }
    cachedFrameRecorder = null
    cachedFramesDirPath = null
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
        minIntervalMs = effectiveTimelapseIntervalMs(),
        maxFrames = timelapseMaxFrames,
        quality = timelapseQuality,
    ).also {
        cachedFrameRecorder = it
        cachedFramesDirPath = framesDir.absolutePath
    }
}

/**
 * 触摸落笔通知：仅更新触控状态，不打断或取消上一步已完成笔迹的后台入帧
 */
fun PaintViewModel.onStylusStrokeStarted() {
    isStylusTouching = true
}

/**
 * 触摸抬笔通知
 */
fun PaintViewModel.onStylusStrokeEnded() {
    isStylusTouching = false
}

/**
 * 立即冲刷任何处于防抖等待队列中的快照任务 (在保存工程或进入回放前调用，确保最新笔迹入帧)
 */
fun PaintViewModel.flushPendingTimelapseCapture() {
    pendingIdleCaptureRunnable?.let {
        mainHandler.removeCallbacks(it)
        pendingIdleCaptureRunnable = null
        executeTimelapseCaptureNow(force = true)
    }
}

/**
 * 重置延时摄影运行时捕获状态
 */
fun PaintViewModel.resetTimelapseCaptureState() {
    isStylusTouching = false
    pendingIdleCaptureRunnable?.let {
        mainHandler.removeCallbacks(it)
        pendingIdleCaptureRunnable = null
    }
    isTimelapseCapturing.set(false)
    pendingTimelapseCapture.set(false)
    lastCaptureTriggerTimeMs = 0L
    cachedFrameRecorder = null
    cachedFramesDirPath = null
}

/**
 * 当前文档的抓帧计划 (目标尺寸 + 有效最小间隔), 见 [com.reverie.paint.model.TimelapseCapturePolicy]。
 * 文档尺寸未知或非法时返回 null, 调用方回退到用户设置。
 */
internal fun PaintViewModel.timelapseCapturePlan(): com.reverie.paint.model.TimelapseCapturePolicy.Plan? =
    com.reverie.paint.model.TimelapseCapturePolicy.plan(
        docW = docWidth,
        docH = docHeight,
        requestedMaxDim = timelapseResolution.coerceIn(480, 3840),
        userMinIntervalMs = timelapseMinIntervalMs,
    )

/** 当前文档的有效抓帧最小间隔 (用户设置只会被拉长, 见 [TimelapseCapturePolicy.minIntervalMs])。 */
internal fun PaintViewModel.effectiveTimelapseIntervalMs(): Long =
    timelapseCapturePlan()?.minIntervalMs ?: timelapseMinIntervalMs.coerceIn(50L, 5000L)

/**
 * 触发一次延时摄影抓帧任务 (Procreate 式逐笔自适应捕获)。
 * - 每次抬笔 (touchEnd)、撤销/重做或图层改动后立即响应
 * - 由用户配置的最小抓帧间隔 (timelapseMinIntervalMs, 默认 200ms) 统一节流
 * - 节流期外：立即发起快照并压入后台线程编码
 * - 节流期内：调度轻量定时器，保证收尾终帧必定补齐，不遗漏最新笔画
 */
fun PaintViewModel.triggerTimelapseCapture(isChanged: Boolean = true, force: Boolean = false) {
    if (!timelapseRecordingEnabled || currentProjectFile == null) {
        return
    }

    if (force) {
        pendingIdleCaptureRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingIdleCaptureRunnable = null
        }
        executeTimelapseCaptureNow(force = true)
        return
    }

    if (!isChanged) return

    val nowUptime = SystemClock.uptimeMillis()
    val elapsed = nowUptime - lastCaptureTriggerTimeMs
    // 大画布上抓帧单帧成本很高 (见 TimelapseCapturePolicy), 有效间隔会按成本被拉长。
    val minInterval = effectiveTimelapseIntervalMs()

    if (elapsed >= minInterval) {
        pendingIdleCaptureRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingIdleCaptureRunnable = null
        }
        executeTimelapseCaptureNow(force = false)
    } else {
        // 处于节流窗口：调度补齐收尾帧
        if (pendingIdleCaptureRunnable == null) {
            val remainDelay = (minInterval - elapsed).coerceAtLeast(10L)
            val runnable = Runnable {
                pendingIdleCaptureRunnable = null
                executeTimelapseCaptureNow(force = false)
            }
            pendingIdleCaptureRunnable = runnable
            mainHandler.postDelayed(runnable, remainDelay)
        }
    }
}

/**
 * 真正执行抓帧操作
 */
private fun PaintViewModel.executeTimelapseCaptureNow(force: Boolean) {
    if (!timelapseRecordingEnabled || currentProjectFile == null) {
        return
    }

    if (!isTimelapseCapturing.compareAndSet(false, true)) {
        pendingTimelapseCapture.set(true)
        return
    }

    val nowMs = System.currentTimeMillis()
    val recorder = getOrCreateFrameRecorder()
    if (recorder == null || (!force && !recorder.shouldCapture(nowMs, isChanged = true))) {
        isTimelapseCapturing.set(false)
        return
    }

    val docW = docWidth
    val docH = docHeight
    if (docW <= 0 || docH <= 0) {
        isTimelapseCapturing.set(false)
        return
    }

    // 抓帧尺寸不再直接取用户设置的长边: 引擎侧 createThumbnail 的成本 ∝ **输出像素**
    // (最近邻随机访问, ~13ns/px), 65MP 画布按 2160 抓一帧实测 95ms, 而且它跑在渲染线程上。
    // TimelapseCapturePolicy 按"单帧输出 ≤ 1080p 级 + 占空比 ≤ 1/8"给出目标尺寸与有效间隔。
    val plan = timelapseCapturePlan()
    val targetW = plan?.width ?: (if (docW >= docH) timelapseResolution.coerceIn(480, 3840) else 2)
    val targetH = plan?.height ?: (if (docW >= docH) 2 else timelapseResolution.coerceIn(480, 3840))

    // 记录抓帧时的工程: 后台压缩期间用户可能切工程/删工程数据, 落盘前要再确认一次
    val projectPathAtSchedule = currentProjectFile

    lastCaptureTriggerTimeMs = SystemClock.uptimeMillis()
    runCore(render = false) {
        // 位图在引擎 lambda 内部分配: 原先在 runCore 之前分配, 一旦 lambda 没被执行
        // (引擎队列异常/文档已销毁) 这块 ARGB_8888 (可达数 MB) 就没人回收。
        val captureBitmap = try {
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            Log.e(TAG_TIMELAPSE, "Failed to allocate capture bitmap", e)
            isTimelapseCapturing.set(false)
            return@runCore
        }
        val renderStart = SystemClock.uptimeMillis()
        val renderOk = try {
            ReverieCoreBridge.renderDocumentThumb(captureBitmap)
        } catch (t: Throwable) {
            Log.e(TAG_TIMELAPSE, "ReverieCoreBridge.renderDocumentThumb failed", t)
            false
        }
        val renderElapsed = SystemClock.uptimeMillis() - renderStart
        if (renderElapsed > 30L) {
            Log.w(TAG_TIMELAPSE, "ReverieCoreBridge.renderDocumentThumb took ${renderElapsed}ms")
        }

        if (renderOk) {
            timelapseExecutor.execute {
                val startUptime = SystemClock.uptimeMillis()
                try {
                    if (currentProjectFile != projectPathAtSchedule) {
                        Log.i(TAG_TIMELAPSE, "skip frame: project switched during capture")
                        return@execute
                    }
                    val savedFile = recorder.capture(captureBitmap, nowMs, isChanged = true, force = force)
                    if (savedFile != null) {
                        Log.d(TAG_TIMELAPSE, "Captured frame: count=${recorder.frameCount}, savedFile=${savedFile.name}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG_TIMELAPSE, "Exception during timelapse capture processing", t)
                } finally {
                    captureBitmap.recycle()
                    val elapsed = SystemClock.uptimeMillis() - startUptime
                    if (elapsed > 300L) {
                        Log.w(TAG_TIMELAPSE, "Timelapse capture processing took ${elapsed}ms > 300ms")
                    }
                    isTimelapseCapturing.set(false)
                    if (pendingTimelapseCapture.compareAndSet(true, false)) {
                        mainHandler.post {
                            triggerTimelapseCapture(isChanged = true, force = false)
                        }
                    }
                }
            }
        } else {
            captureBitmap.recycle()
            isTimelapseCapturing.set(false)
            if (pendingTimelapseCapture.compareAndSet(true, false)) {
                mainHandler.post {
                    triggerTimelapseCapture(isChanged = true, force = false)
                }
            }
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
 * 对指定或当前工程的延时摄影帧执行抽稀瘦身（减半）。
 * 返回 Pair(瘦身后帧数, 释放的字节数)。
 */
fun PaintViewModel.compactTimelapseData(project: Project? = null): Pair<Int, Long> {
    val dir = getTimelapseFramesDir(project) ?: return 0 to 0L
    if (!dir.exists() || !dir.isDirectory) return 0 to 0L
    val beforeBytes = getTimelapseTotalBytes(project)

    val recorder = getOrCreateFrameRecorder()
    val newCount = recorder?.decimate() ?: FrameRecorder.decimateDir(dir)
    val afterBytes = getTimelapseTotalBytes(project)
    val freed = (beforeBytes - afterBytes).coerceAtLeast(0L)

    cachedFrameRecorder = null
    cachedFramesDirPath = null
    return newCount to freed
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

