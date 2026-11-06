/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.reverie.paint.R
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import android.os.SystemClock
import com.reverie.paint.core.export.ExportProfiler
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 导出脏区增量渲染总开关 (默认开启; 若遇异常可设为 false 一键降级回全量渲染) */
internal var exportIncremental: Boolean = true

/**
 * 回放导出参数配置 (复用 FrameEncodingSession 与 saveExportFileToGalleryAsync)。
 *
 * @param format 导出格式: "mp4", "gif", "zip"
 * @param durationSec 目标视频时长 (秒): 15, 30, 60; 若为 0 则按当前回放倍速 (ReplaySession.speed) 计算时长
 * @param fps 导出帧率 (默认 MP4/ZIP 30fps, GIF 15fps)
 * @param scale 分辨率缩放比例 (1.0f, 0.5f, 0.25f)
 * @param startFraction 导出起始进度区间 (0f..1f)
 * @param endFraction 导出结束进度区间 (0f..1f)
 * @param showBrushCursor 是否在导出帧上绘制零分配笔刷光标轨迹圆环 (不污染 C++ 引擎图层)
 * @param showWatermark 是否在导出帧右下角叠加 AuthorProfile 作者签名水印
 * @param holdEndFrames 是否在结尾追加 1 秒定格帧以展示最终完成作品
 */
internal data class ReplayExportOptions(
    val format: String = "mp4",
    val durationSec: Int = 30,
    val fps: Int = 30,
    val scale: Float = 1.0f,
    val startFraction: Float = 0f,
    val endFraction: Float = 1f,
    val showBrushCursor: Boolean = true,
    val showWatermark: Boolean = true,
    val holdEndFrames: Boolean = true,
)

/**
 * 统一的缩时采样检查点判定逻辑 (预扫描与正式编码共用，保证边界与步进完全一致)。
 */
private fun isReplayVisualCheckpoint(
    type: Int,
    moveCounter: IntArray,
): Boolean {
    return when (type) {
        STROKE_MOVE -> {
            moveCounter[0]++
            moveCounter[0] % 4 == 0
        }
        STROKE_END, LAYER_OP, TOOL_OP, FILTER, FILTER_LUT -> {
            moveCounter[0] = 0
            true
        }
        else -> false
    }
}

/**
 * 纯字节流扫描统计 [startMs, endMs] 区间内的可视检查点总数:
 * 通过 skipReplayEventPayload 仅移动 RecordingReader 游标，不调用任何 JNI/C++ 引擎接口，
 * 避免预统计阶段重复执行笔画渲染与滤镜计算。
 */
private fun countVisualCheckpointsInRange(
    s: ReplaySession,
    startMs: Long,
    endMs: Long,
    isCancelled: () -> Boolean,
): Int {
    val r = s.reader
    r.pos = 0
    var simMs = 0L
    var visualCount = 0
    var scanned = 0
    val moveCounter = IntArray(1)
    while (r.remaining() > 0) {
        if ((scanned++ and 0xFFF) == 0 && isCancelled()) {
            throw InterruptedException("cancelled")
        }
        val type = r.u8()
        val dt = r.varint()
        simMs += ReplayTimeline.effectiveDt(dt, s.idleCapMs)
        if (s.totalMs > 0 && simMs > endMs && endMs < s.totalMs) {
            break
        }
        skipReplayEventPayload(type, r, s.version)
        if (simMs < startMs) {
            continue
        }
        if (isReplayVisualCheckpoint(type, moveCounter)) {
            visualCount++
        }
    }
    return max(1, visualCount)
}

/**
 * 离线渲染并导出绘画回放 (Timelapse) 至指定文件。
 *
 * 性能与架构保证:
 * 1. 严格在 reverie-render 线程 (runCore(render = false)) 串行执行，绝不阻塞 UI 线程。
 * 2. 预扫描仅解析事件字节偏移 (零 JNI 调用)，导出过程仅执行 1 次 C++ 重放。
 * 3. 复用 FrameEncodingSession 统一管理 GIF / MP4 / ZIP 编码生命周期。
 * 4. 笔刷光标轨迹圆环与作者签名水印均在缩放后的 outCanvas 上用预分配 Paint 合成，不污染 C++ 图层或撤销栈。
 */
internal fun PaintViewModel.exportReplay(
    options: ReplayExportOptions,
    targetFile: File,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val s = replaySession
    if (s == null || s.eventCount <= 0) {
        val msg = getString(R.string.replay_export_err_no_data)
        mainHandler.post { onError(msg) }
        return
    }
    pauseReplay()
    val savedProgress = s.progress.coerceIn(0f, 1f)
    val fmt = options.format.lowercase()
    val scale = options.scale.coerceIn(0.1f, 1.0f)
    val fps = if (fmt == "gif") options.fps.coerceIn(8, 24) else options.fps.coerceIn(15, 60)
    val startFrac = options.startFraction.coerceIn(0f, 0.99f)
    val endFrac = options.endFraction.coerceIn(startFrac + 0.01f, 1f)

    // 热路径外预构建一次水印文本
    val watermarkText: String =
        if (options.showWatermark) {
            val authorName = authorProfile.name.trim().ifEmpty { authorProfile.nickname.trim() }
            val copyText = authorProfile.copyright.trim()
            when {
                authorName.isNotEmpty() -> "© $authorName · ReveriePaint"
                copyText.isNotEmpty() -> copyText
                docName.isNotBlank() -> "${docName.trim()} · ReveriePaint"
                else -> "ReveriePaint"
            }
        } else {
            ""
        }

    runCore(render = false) {
        if (replaySession !== s) {
            val msg = getString(R.string.replay_export_err_no_data)
            mainHandler.post { onError(msg) }
            return@runCore
        }
        val baseW = max(2, s.docW)
        val baseH = max(2, s.docH)
        val (targetW, targetH) = computeExportDimensions(baseW, baseH, scale, fmt)

        var fullFrameBmp: Bitmap? = null
        var outFrameBmp: Bitmap? = null
        val encodeSession =
            FrameEncodingSession(
                vm = this,
                format = fmt,
                targetFile = targetFile,
                targetW = targetW,
                targetH = targetH,
                fps = fps,
                loopGif = true,
                transparentGif = false,
            )

        try {
            val analyzingMsg = getString(R.string.replay_export_stage_analyzing)
            mainHandler.post {
                onProgress(0, 100, analyzingMsg)
            }

            fullFrameBmp = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888)
            val outBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            outFrameBmp = outBmp
            val outCanvas = Canvas(outBmp)
            val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val srcRect = Rect(0, 0, baseW, baseH)
            val dstRectF = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())

            // 预分配笔刷光标与水印 Paint (循环内零分配)
            val cursorOuterPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, min(targetW, targetH) * 0.0045f)
                    color = 0xAA000000.toInt()
                }
            val cursorInnerPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(1.5f, min(targetW, targetH) * 0.003f)
                    color = 0xEEFFFFFF.toInt()
                }
            val cursorDotPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0xFFFFFFFF.toInt()
                }

            val wmFontSize = max(11f, min(targetW, targetH) * 0.022f)
            val wmPaddingH = wmFontSize * 0.65f
            val wmPaddingV = wmFontSize * 0.38f
            val wmMargin = wmFontSize * 0.75f
            val watermarkTextPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xE6FFFFFF.toInt()
                    textSize = wmFontSize
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
                }
            val watermarkBgPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0x66141318
                }
            val wmTextWidth = if (watermarkText.isNotEmpty()) watermarkTextPaint.measureText(watermarkText) else 0f
            val wmBoxRectF = RectF()

            val startMs = (s.totalMs * startFrac).toLong()
            val endMs = if (endFrac >= 0.999f) s.totalMs else (s.totalMs * endFrac).toLong()
            val rangeSpanRatio = (endFrac - startFrac).coerceIn(0.05f, 1f)

            // 纯字节流预统计可视检查点数 (零 JNI 开销)
            val totalVisualSteps = countVisualCheckpointsInRange(s, startMs, endMs, isCancelled)
            val desiredActiveFrames =
                if (options.durationSec > 0) {
                    max(fps, options.durationSec * fps)
                } else {
                    val estSec =
                        if (s.totalMs > 0) {
                            (s.totalMs * rangeSpanRatio / 1000f / s.speed.coerceAtLeast(0.5f))
                        } else {
                            15f
                        }
                    (estSec * fps).roundToInt().coerceIn(fps, 1800)
                }
            val activeFrames = min(totalVisualSteps, desiredActiveFrames).coerceAtLeast(1)
            val holdFrames = if (options.holdEndFrames) max(1, fps) else 0
            val totalOutputFrames = activeFrames + holdFrames

            // 重置文档到初始状态 (仅执行一次，抑制初始帧实时屏显调度)
            resetReplayDocLocked(s, scheduleInitialRender = false)
            val r = s.reader
            r.pos = 0
            s.currentMs = 0L

            val profiler = ExportProfiler()
            profiler.start()
            val exportStartRealtimeMs = SystemClock.elapsedRealtime()
            var lastProgressPostTimeMs = 0L

            fun postProgressThrottled(curFrame: Int, totalFrames: Int, stageMsg: String, force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (force || now - lastProgressPostTimeMs >= 100L || curFrame >= totalFrames) {
                    lastProgressPostTimeMs = now
                    val elapsedMs = now - exportStartRealtimeMs
                    val etaSec = if (curFrame > 0 && curFrame < totalFrames && elapsedMs > 500L) {
                        val remainFrames = totalFrames - curFrame
                        val msPerFrame = elapsedMs.toDouble() / curFrame
                        ((remainFrames * msPerFrame) / 1000.0).roundToInt().coerceAtLeast(1)
                    } else {
                        0
                    }
                    val fullMsg = if (etaSec > 0) {
                        getString(R.string.replay_export_stage_rendering_frame_eta, curFrame, totalFrames, etaSec)
                    } else {
                        stageMsg
                    }
                    mainHandler.post {
                        onProgress(curFrame, totalFrames, fullMsg)
                    }
                }
            }

            val outDirtyRect = IntArray(4)
            var forceFullNext = true // 首帧、尺寸变动或结构事件强制全量渲染

            encodeSession.start()

            fun captureAndEncodeFrame(
                frameIdx: Int,
                drawCursorOverlay: Boolean,
                repeatCount: Int = 1,
            ) {
                if (isCancelled()) {
                    throw InterruptedException(getString(R.string.replay_export_err_cancelled))
                }
                val curDocW = max(1, coreW)
                val curDocH = max(1, coreH)
                var srcBmp = fullFrameBmp
                if (srcBmp == null || srcBmp.width != curDocW || srcBmp.height != curDocH) {
                    srcBmp?.recycle()
                    srcBmp = Bitmap.createBitmap(curDocW, curDocH, Bitmap.Config.ARGB_8888)
                    fullFrameBmp = srcBmp
                    forceFullNext = true
                }

                val forceFullThisFrame = forceFullNext || !exportIncremental || frameIdx == 1
                forceFullNext = false

                profiler.measureRender {
                    profiler.renderCalls++
                    if (forceFullThisFrame) {
                        profiler.renderFull++
                        ReverieCoreBridge.beginRenderTiles(outDirtyRect)
                    } else {
                        profiler.renderIncremental++
                    }
                    val ok = ReverieCoreBridge.renderToBuffer(srcBmp, forceFullThisFrame, outDirtyRect)
                    if (!ok) profiler.renderSkips++
                    ok
                }

                // 100% 原始尺寸直传门禁：无光标、无水印、尺寸严格相等且画面不透明 (避免透明底预乘反转引起亮边)
                val isZeroCopyEligible = !options.showBrushCursor &&
                    watermarkText.isEmpty() &&
                    targetW == curDocW && targetH == curDocH &&
                    !srcBmp.hasAlpha()

                if (isZeroCopyEligible) {
                    profiler.measureEncode {
                        encodeSession.addFrame(srcBmp, isCancelled, frameIdx, repeatCount)
                    }
                } else {
                    profiler.measureComposite {
                        // 1. 等比居中缩放画布至目标尺寸 (兼容回放中途 T_CROP 裁切画布)
                        outCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
                        srcRect.set(0, 0, curDocW, curDocH)
                        val fitScale = min(targetW.toFloat() / curDocW, targetH.toFloat() / curDocH)
                        val drawW = curDocW * fitScale
                        val drawH = curDocH * fitScale
                        val left = (targetW - drawW) * 0.5f
                        val top = (targetH - drawH) * 0.5f
                        dstRectF.set(left, top, left + drawW, top + drawH)
                        outCanvas.drawBitmap(srcBmp, srcRect, dstRectF, scalePaint)

                        // 2. 零分配叠加笔刷光标轨迹圆环 (区分画笔 / 橡皮擦 / 涂抹，仅在激活且非定格帧时绘制)
                        if (drawCursorOverlay && options.showBrushCursor && s.cursorActive) {
                            val cx = left + s.cursorX * fitScale
                            val cy = top + s.cursorY * fitScale
                            val pressureScale = 0.35f + 0.65f * s.cursorPressure.coerceIn(0.1f, 1f)
                            val maxRadius = min(targetW, targetH) * 0.22f
                            val radius =
                                (s.cursorBrushSize * 0.5f * fitScale * pressureScale)
                                    .coerceIn(min(4f, maxRadius), maxRadius)
                            outCanvas.drawCircle(cx, cy, radius + 1.5f, cursorOuterPaint)
                            val ringColor =
                                when {
                                    s.cursorIsEraser -> 0xEE7DD3FC.toInt() // 橡皮擦: 天蓝色环
                                    s.cursorToolMode == 3 -> 0xFFFBBF24.toInt() // 涂抹: 琥珀色环
                                    else -> 0xEEFFFFFF.toInt() // 画笔: 纯白内环
                                }
                            cursorInnerPaint.color = ringColor
                            outCanvas.drawCircle(cx, cy, radius, cursorInnerPaint)
                            cursorDotPaint.color =
                                when {
                                    s.cursorIsEraser -> 0xCC7DD3FC.toInt()
                                    s.cursorToolMode == 3 -> 0xCCFBBF24.toInt()
                                    else -> s.cursorColorArgb
                                }
                            outCanvas.drawCircle(cx, cy, max(2.2f, radius * 0.18f), cursorDotPaint)
                        }

                        // 3. 零分配叠加右下角作者签名水印
                        if (watermarkText.isNotEmpty()) {
                            val boxW = wmTextWidth + wmPaddingH * 2f
                            val boxH = wmFontSize + wmPaddingV * 2f
                            val boxLeft = targetW - boxW - wmMargin
                            val boxTop = targetH - boxH - wmMargin
                            wmBoxRectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
                            val corner = boxH * 0.35f
                            outCanvas.drawRoundRect(wmBoxRectF, corner, corner, watermarkBgPaint)
                            val textX = boxLeft + wmPaddingH
                            val textY = boxTop + wmPaddingV + wmFontSize * 0.82f
                            outCanvas.drawText(watermarkText, textX, textY, watermarkTextPaint)
                        }
                    }

                    profiler.measureEncode {
                        encodeSession.addFrame(outBmp, isCancelled, frameIdx, repeatCount)
                    }
                }
            }

            // 单一统一事件循环: [0, startMs) 静默快进画布状态，[startMs, endMs] 采样编码，彻底消除区间边界 off-by-one
            var visualStep = 0
            val moveCounter = IntArray(1)
            var emittedFrames = 0

            while (r.remaining() > 0) {
                if (isCancelled()) {
                    throw InterruptedException(getString(R.string.replay_export_err_cancelled))
                }
                val type = r.u8()
                val dt = r.varint()
                s.currentMs += ReplayTimeline.effectiveDt(dt, s.idleCapMs)
                if (s.totalMs > 0 && s.currentMs > endMs && endMs < s.totalMs) {
                    break
                }
                profiler.measureDispatch {
                    dispatchReplayLocked(s, type, r, render = false)
                }
                // 结构性事件强制下一帧全量渲染，避免局部脏区遗漏全局图层/滤镜变化
                if (type == LAYER_OP || type == TOOL_OP || type == FILTER || type == FILTER_LUT) {
                    forceFullNext = true
                }
                if (s.currentMs < startMs) {
                    continue
                }
                if (isReplayVisualCheckpoint(type, moveCounter)) {
                    visualStep++
                    val targetEmitted = ((visualStep.toLong() * activeFrames) / totalVisualSteps).toInt()
                    while (emittedFrames < targetEmitted && emittedFrames < activeFrames) {
                        emittedFrames++
                        val curFrame = emittedFrames
                        val stageMsg = getString(R.string.replay_export_stage_rendering_frame, curFrame, totalOutputFrames)
                        postProgressThrottled(curFrame, totalOutputFrames, stageMsg)
                        captureAndEncodeFrame(curFrame, drawCursorOverlay = true)
                    }
                }
            }

            // 闭合末尾笔画并写入最终成图帧及定格帧 (定格帧隐藏笔刷光标以展示干净成图)
            s.cursorActive = false
            ReverieCoreBridge.touchStrokeEnd()
            val remainingHoldFrames = totalOutputFrames - emittedFrames
            if (remainingHoldFrames > 0) {
                emittedFrames = totalOutputFrames
                val holdMsg = getString(R.string.replay_export_stage_holding_frame, totalOutputFrames, totalOutputFrames)
                postProgressThrottled(totalOutputFrames, totalOutputFrames, holdMsg, force = true)
                captureAndEncodeFrame(totalOutputFrames, drawCursorOverlay = false, repeatCount = remainingHoldFrames)
            }

            val finalizeMsg = getString(R.string.replay_export_stage_finalizing)
            mainHandler.post {
                onProgress(totalOutputFrames, totalOutputFrames, finalizeMsg)
            }

            if (isCancelled()) {
                throw InterruptedException(getString(R.string.replay_export_err_cancelled))
            }
            profiler.measureFinish {
                encodeSession.finish(isCancelled)
            }
            profiler.finish(options.format, targetW, targetH, totalOutputFrames, targetFile.length())
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
            if (t is Error) throw t
        } finally {
            encodeSession.closeQuietly()
            fullFrameBmp?.recycle()
            outFrameBmp?.recycle()
            // 关键残影防护：无条件强制显示缓冲全量重绘，杜绝增量导出对显示缓冲与瓦片网格留下的局部脏区残影
            displayBufferInvalid = true
            // 恢复导出前的回放进度画面；若导出期间已退出回放，session 已被 stop()
            // 且快照被删除，此时再 seek 会走 newDocument 兜底并清空当前文档
            if (replaySession === s) {
                seekLocked(s, savedProgress)
            }
        }
    }
}

/**
 * 导出绘画回放并直接保存至系统相册 (复用后台 saveExportFileToGalleryAsync，不阻塞主线程)。
 */
internal fun PaintViewModel.exportReplayToGallery(
    options: ReplayExportOptions,
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
    val safeName = sanitizeExportFileName(docName)
    val fileName = "${safeName}_replay_${System.currentTimeMillis()}.$fmt"
    val tempFile = File(appContext.cacheDir, fileName)

    exportReplay(
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
 * 导出绘画回放并在后台 I/O 线程写入 SAF 文档 Uri (用于 ZIP 序列帧等自定义路径保存，防止主线程 ANR)。
 */
internal fun PaintViewModel.exportReplayToUri(
    options: ReplayExportOptions,
    targetUri: Uri,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val ext = options.format.lowercase()
    val safeName = sanitizeExportFileName(docName)
    val tempFile = File(appContext.cacheDir, "${safeName}_replay_${System.currentTimeMillis()}.$ext")

    exportReplay(
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
 * 导出绘画回放并生成用于系统分享的临时文件。
 */
internal fun PaintViewModel.shareReplay(
    options: ReplayExportOptions,
    isCancelled: () -> Boolean = { false },
    onProgress: (current: Int, total: Int, stage: String) -> Unit = { _, _, _ -> },
    onSuccess: (File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val ext = options.format.lowercase()
    val shareDir = File(appContext.cacheDir, "share_replay")
    shareDir.mkdirs()
    val safeName = sanitizeExportFileName(docName)
    val shareFile = File(shareDir, "${safeName}_replay.$ext")
    exportReplay(
        options = options,
        targetFile = shareFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
        onSuccess = onSuccess,
        onError = onError,
    )
}
