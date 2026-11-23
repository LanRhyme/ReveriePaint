/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import com.reverie.paint.perf.FbGeomLog
import kotlin.math.ceil
import kotlin.math.floor

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.drawHoleCursor(canvas: Canvas) {
    if (!realInk.held) return
    val holes = realInk.strokeHoles ?: return
    val bmp = holes.bitmap ?: return
    val r = realInk.cursorR
    if (r <= 0f || bmp.isRecycled) return
    val b = holeCursorBox
    if (!HoldCursorPainter.bounds(realInk.cursorX, realInk.cursorY, holeCursorPainter.half(r), realInk.symCursorX,
            realInk.symCursorY, realInk.symCursorCount, b)
    ) {
        return
    }
    if (!holes.grid.anyExact(b[0], b[1], b[2], b[3])) return
    canvas.saveLayer(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), null)
    // 前缓冲在 UI 避让区外才画光标, 避让区里由下面的常规分支画; 这里同样避开, 免得叠两次。
    val clips = uiClipSnapshot
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        for (i in 0 until clips.count) canvas.clipOutRect(clips.left(i), clips.top(i), clips.right(i), clips.bottom(i))
    }
    holeCursorPainter.drawAll(canvas, realInk.cursorX, realInk.cursorY, r, realInk.cursorStyle, realInk.symCursorX,
        realInk.symCursorY, realInk.symCursorCount)
    canvas.drawBitmap(bmp, 0f, 0f, holeCursorDstIn)
    canvas.restore()
}

/**
 * 按住期间光标框压到孔洞 (或上一次压到) 时让窗口重画这两个框; 其余情况零额外工作。
 * 判定用 O(1) 的保守 [com.reverie.paint.model.HoleMaskGrid.anyInRect]。UI 线程。
 */
internal fun CanvasTouchView.invalidateHoleCursor() {
    val holes = realInk.strokeHoles
    var hit = false
    val b = holeCursorBox
    if (holes != null && realInk.held && realInk.cursorR > 0f &&
        HoldCursorPainter.bounds(realInk.cursorX, realInk.cursorY, holeCursorPainter.half(realInk.cursorR),
            realInk.symCursorX, realInk.symCursorY, realInk.symCursorCount, b)
    ) {
        hit = holes.grid.anyInRect(b[0], b[1], b[2], b[3])
    }
    if (!hit && !holeCursorBoxValid) return
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var bt = Int.MIN_VALUE
    if (holeCursorBoxValid) {
        l = holeCursorL; t = holeCursorT; r = holeCursorR; bt = holeCursorB
    }
    if (hit) {
        l = minOf(l, b[0]); t = minOf(t, b[1]); r = maxOf(r, b[2]); bt = maxOf(bt, b[3])
        holeCursorL = b[0]; holeCursorT = b[1]; holeCursorR = b[2]; holeCursorB = b[3]
    }
    holeCursorBoxValid = hit
    postInvalidateOnAnimation(maxOf(l, 0), maxOf(t, 0), minOf(r, width), minOf(bt, height))
}

internal fun CanvasTouchView.sampleColorAtScreenPos(screenPos: Offset) {
    val v = vm ?: return
    val samplePos = if (v.eyedropperOffsetEnabled) {
        screenPos + Offset(-48f * density, -48f * density)
    } else {
        screenPos
    }
    pickerScreenPos?.value = samplePos
    val docPos = screenToDoc(samplePos)

    // 当前图层模式：直接从底层当前图层的 PaintDevice 采样
    if (v.pickerSampleLayers == 0 || v.pickerCurrentLayerOnly) {
        val ix = docPos.x.toInt()
        val iy = docPos.y.toInt()
        val docW = if (v.docWidth > 0) v.docWidth else (v.displayBitmap?.width ?: 0)
        val docH = if (v.docHeight > 0) v.docHeight else (v.displayBitmap?.height ?: 0)
        if (ix in 0 until docW && iy in 0 until docH) {
            val hex = ReverieCoreBridge.pickColorAt(ix, iy, true)
            if (!hex.isNullOrEmpty()) {
                try {
                    pickerCurrentColor?.value = Color(android.graphics.Color.parseColor(hex))
                } catch (_: Throwable) {
                    pickerCurrentColor?.value = Color.Transparent
                }
            } else {
                pickerCurrentColor?.value = Color.Transparent
            }
        } else {
            pickerCurrentColor?.value = Color.Transparent
        }
        return
    }

    val tiles = v.displayTiles.drawable()
    if (tiles != null) {
        // 瓦片路径下没有整图: 直接从覆盖该像素的瓦片取色 (瓦片坐标 = 文档坐标)
        val ix = docPos.x.toInt()
        val iy = docPos.y.toInt()
        val idx = tiles.grid.tileIndexAt(ix, iy)
        val tile = if (idx >= 0) tiles.tiles.get(idx) else null
        if (tile != null && !tile.isRecycled) {
            val lx = ix - tiles.grid.tileLeft(idx % tiles.grid.cols)
            val ly = iy - tiles.grid.tileTop(idx / tiles.grid.cols)
            if (lx in 0 until tile.width && ly in 0 until tile.height) {
                tile.getPixels(pixelScratch, 0, 1, lx, ly, 1, 1)
                pickerCurrentColor?.value = Color(pixelScratch[0])
            }
        }
        return
    }
    val bmp = v.displayBitmap ?: docBitmap
    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
        val docW = if (v.docWidth > 0) v.docWidth else bmp.width
        val docH = if (v.docHeight > 0) v.docHeight else bmp.height
        val ix = (docPos.x * (bmp.width.toFloat() / docW)).toInt()
        val iy = (docPos.y * (bmp.height.toFloat() / docH)).toInt()
        if (ix in 0 until bmp.width && iy in 0 until bmp.height) {
            // getPixel 每个采样点都是一趟 JNI; 改用复用数组 + getPixels,
            // 拖动吸色期间每个采样点省一次跨语言调用且零分配
            bmp.getPixels(pixelScratch, 0, 1, ix, iy, 1, 1)
            pickerCurrentColor?.value = Color(pixelScratch[0])
        }
    }
}

/** 落笔时的就绪门控快照写进 FbGeomLog (只在 PerfTrace 打开时调用)。 */
internal fun CanvasTouchView.logFrontBufferReady(v: PaintViewModel, held: Boolean) {
    val fb = frontBufferOverlay
    val loc = IntArray(2)
    if (fb != null) fb.getLocationOnScreen(loc)
    val tiles = v.displayTiles.drawable()
    com.reverie.paint.perf.FbGeomLog.strokeReady(
        strokeSeq = realInk.currentStrokeSeq,
        held = held,
        decision = strokeFbDecision,
        mask = strokeFbMask,
        inferredMask = 0,
        gateEnabled = FrontBufferFeatureFlags.readyGate,
        msSinceQuietStart = -1L,
        msUntilFallback = 0L,
        surfaceX = loc[0],
        surfaceY = loc[1],
        surfaceW = fb?.width ?: -1,
        surfaceH = fb?.height ?: -1,
        viewW = width,
        viewH = height,
        stableFrames = 0,
        refreshHz = 0f,
        targetRefreshHz = 0f,
        renderW = v.renderW,
        renderH = v.renderH,
        gridW = tiles?.grid?.width ?: 0,
        gridH = tiles?.grid?.height ?: 0,
        tileGeneration = v.displayTiles.generation,
        bufferInvalid = v.displayBufferInvalid,
        zoom = canvasZoom,
        fitScale = canvasFitScale,
        panX = canvasPanX,
        panY = canvasPanY,
        rotation = canvasRotation,
        renderScheduled = v.renderScheduled,
        renderFollowUp = v.renderFollowUpPending,
        renderInFlight = v.renderInFlight,
        loading = v.isBlockingLoading,
    )
}

/**
 * 落笔时评估一次就绪门控并锁定到 [strokeFbReady]。
 * 真正必要的条件只有: 瓦片已是最终版 (tilesFinal, 不是加载中的低清), 不在加载。
 */
internal fun CanvasTouchView.latchFrontBufferReady(v: PaintViewModel) {
    val tiles = v.displayTiles.drawable()
    val tilesFinal = FrontBufferReadyGate.tilesFinal(
        tiles?.grid?.width ?: 0, tiles?.grid?.height ?: 0, v.renderW, v.renderH, v.displayBufferInvalid,
    )
    val loading = v.isBlockingLoading
    val now = android.os.SystemClock.uptimeMillis()
    strokeFbMask = FrontBufferReadyGate.conditionMask(tilesFinal, loading)
    strokeFbDecision = FrontBufferReadyGate.decide(
        now, v.frontBufferQuietUntilMs, tilesFinal, loading, FrontBufferFeatureFlags.readyGate,
    )
    strokeFbReady = FrontBufferReadyGate.isReady(strokeFbDecision)
}

internal fun CanvasTouchView.armRealInkHold(v: PaintViewModel, hasSymmetry: Boolean, x: Float, y: Float, pressure: Float) {
    // 透光台对位模式: 前缓冲置顶会盖住 ShiftTraceOverlay, 统一不按住真墨,
    // 走 #81 前沿预览 + 主画布实时刷新。透明/不透明画布行为一致。
    if (v.anim.shiftTraceActive) {
        abortAndClearRealInk()
        clearAllEvasionState(v)
        frontBufferOverlay?.clearPreview()
    }
    rebuildUiExclusionSnapshot()
    // 落笔时无条件强制刷新最新位姿，杜绝上一笔 held 期间微动 (<0.5px) 导致 pose 过期
    ensureViewTransform(forcePublishPose = true)
    latchFrontBufferReady(v)
    val has3DVisible = v.layers.any { it.visible && v.active3DLayers.containsKey(it.id) }
    val hasCheckerboard = checkerboardPaint != null
    var allowed = v.frontBufferPredictionEnabled && (!hasSymmetry || v.mirrorGate.isQueueing) &&
        v.displayTiles.drawable() != null && FrontBufferProbe.realInkHoldAllowed() &&
        strokeFbReady && !v.anim.shiftTraceActive &&
        RealInkHoldPolicy.bakeSupported(hasCheckerboard, has3DVisible)
    // compose: 覆盖层 (像素网格/绘图辅助线/动态导引线/光标) 由前缓冲按窗口同一顺序画进去 ——
    // 不挖孔、不建掩码、不让窗口重画、吸附线也按住。只有蚂蚁线可见或画布旋转/镜像时这一笔退回 H-min。
    val modeFlag = FrontBufferFeatureFlags.holeMode
    val composePose = realInk.pose
    val compose = allowed && composePose != null && composeApplies(v, composePose)
    if (compose) {
        realInk.setStrokeCompose(true, composeDecorFor(v, composePose!!))
    } else {
        realInk.setStrokeCompose(false, null)
    }
    // H-1: 覆盖层 (参考线/对称轴/像素网格/选区蚂蚁线...) 在前缓冲上挖孔, 由窗口画孔内。
    val holeMode = if (compose) FrontBufferHolePolicy.MODE_COMPOSE else FrontBufferHolePolicy.holeModeFor(modeFlag)
    var holeArm = if (compose) FrontBufferHolePolicy.ARM_COMPOSE else FrontBufferHolePolicy.ARM_NO_HOLES
    var holeMask: HoleMask? = null
    var holeCov = 0f
    if (!compose && allowed && holeMode != FrontBufferHolePolicy.MODE_OFF) {
        val pose = realInk.pose
        val other = holeOtherOverlayActive(v)
        val mask = if (pose != null && !other) holeMasks.readyFor(v, pose) else null
        val visible = when {
            mask != null -> !mask.isEmpty
            pose != null -> holeMasks.anyDecorVisible(v, pose)
            else -> true
        }
        holeCov = mask?.grid?.coverage ?: 0f
        holeArm = FrontBufferHolePolicy.decideArm(
            holeMode, visible, mask != null, holeCov, FrontBufferFeatureFlags.holeMaxCoverage, other,
        )
        if (holeArm == FrontBufferHolePolicy.ARM_NORMAL) allowed = false
        if (holeArm == FrontBufferHolePolicy.ARM_WITH_MASK) holeMask = mask
        // 掩码过期 (刚平移/缩放完还没建好): 这一笔走常规路径, 抬笔后自动补建。
        if (mask == null && visible && pose != null && !other) holeMasks.scheduleIfStale(v, pose)
    }
    realInk.setStrokeHoles(holeMask, holeMode)
    val t = effTool()
    val cursorMode = if (t == Tool.ERASER) v.eraserCursorMode else v.brushCursorMode
    val cursorWhileDrawing = (cursorMode == 1 || cursorMode == 3) && v.cursorStyleMode != 4
    // 吸附导引线由前缓冲画, 擦旧位置要靠备份层: 可能出现导引线的笔画从首帧起维护备份层。
    // compose 不需要备份层: 旧光标/导引线处按 pose 重烤瓦片恢复。预测笔尖 (关低延迟时没有) 不画。
    val extras = !compose && (cursorWhileDrawing || predictedTipEligible(v) ||
        FrontBufferHolePolicy.snapNeedsExtras(snapGuidePossible(v), FrontBufferFeatureFlags.snapHold))
    val armed = realInk.arm(allowed, extras, uiClipSnapshot)
    if (holeMode != FrontBufferHolePolicy.MODE_OFF) {
        com.reverie.paint.perf.FbGeomLog.holeArm(realInk.currentStrokeSeq, holeMode, holeArm, holeCov)
    }
    holeCursorBoxValid = false
    resetHeldGuide()
    if (com.reverie.paint.core.PerfTrace.enabled) logFrontBufferReady(v, armed)
    if (armed) {
        noteHoldCursor(x, y, pressure)
        if (prevCursorRectValid) {
            val l = floor(prevCursorLeft).toInt()
            val t = floor(prevCursorTop).toInt()
            val r = ceil(prevCursorRight).toInt()
            val b = ceil(prevCursorBottom).toInt()
            invalidate(l, t, r, b)
        }
    }
}

/**
 * 孔洞方案处理不了的覆盖层: 非笔刷类工具的工具覆盖层; 以及辅助绘图的动态吸附线 ——
 * 默认 ([FrontBufferFeatureFlags.snapHold] 关) 走常规路径保证严格同图; 开关打开时按住,
 * 由前缓冲在孔洞外画、窗口在孔洞内画 (见 [drawHeldGuide] / [invalidateHeldGuide])。
 */
internal fun CanvasTouchView.holeOtherOverlayActive(v: PaintViewModel): Boolean {
    val t = effTool()
    if (t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE) return true
    val composePossible = realInk.pose?.let { composeApplies(v, it) } == true
    return FrontBufferHolePolicy.snapForcesNormal(
        snapPossible = snapGuidePossible(v),
        snapHoldEnabled = FrontBufferFeatureFlags.snapHold,
        isCompose = composePossible,
    )
}

/**
 * 当前状态下这一笔能否走 compose: 模式开着、画笔/橡皮/涂抹、没有可见蚂蚁线 (随时间流动, 两表面
 * 无法锁相)、画布轴对齐 (旋转/镜像时窗口的双线性采样前缓冲在 CPU 上复刻不出逐像素一致)。
 */
internal fun CanvasTouchView.composeApplies(v: PaintViewModel, pose: CanvasPose): Boolean {
    val t = effTool()
    val other = t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE
    return FrontBufferHolePolicy.composeEligible(
        FrontBufferFeatureFlags.holeMode,
        antsVisible(v),
        FrontBufferCompose.isAxisAligned(pose),
        other,
        FrontBufferFeatureFlags.composeAnts,
    )
}

/** 选区蚂蚁线此刻是否可见 (与 [FrontBufferDecorRecorder] 同一判据; 运笔时不可能正在框选)。 */
internal fun CanvasTouchView.antsVisible(v: PaintViewModel): Boolean =
    v.selectionOutlinePath != null && CanvasDecorGeometry.selectionOutlineVisible(
        hasSelection = v.hasSelection,
        isTransform = tool == Tool.TRANSFORM,
        isSelecting = false,
        selectionMode = v.selectionMode,
    )

/** compose 的静态覆盖层: 状态没变就用已录的那份 (几次引用比较), 变了才重录。 */
internal fun CanvasTouchView.composeDecorFor(v: PaintViewModel, pose: CanvasPose): FrontBufferDecor? {
    val tr = tool == Tool.TRANSFORM
    val antsPhase = if (antsVisible(v)) {
        (SystemClock.uptimeMillis() % 1200L).toFloat() / 1200f
    } else 0f
    if (composeDecorRecorder.isStale(v, pose, tr)) {
        composeDecor = composeDecorRecorder.record(v, pose, tr, forMask = false, antsPhase = antsPhase)
    }
    return composeDecor
}
