/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.reverie.paint.BuildConfig
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.core.stylus.UniversalKalmanPredictor
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.reverie.paint.perf.FbGeomLog
import com.reverie.paint.perf.PerfHud
import com.reverie.paint.ui.painting.brush.BrushTipDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.scheduleSnapshotRebuild() {
    if (snapshotRebuildPending) return
    snapshotRebuildPending = true
    if (!post(snapshotRebuildRunnable)) snapshotRebuildPending = false
}

/**
 * 重建 UI 裁剪快照。仅当 tracker.version 变化或 [force] 时真正构建;
 * 每笔 DOWN / arm / onSizeChanged 调用 (UI 线程, 非逐事件热路径)。
 */
internal fun CanvasTouchView.rebuildUiExclusionSnapshot(force: Boolean = false) {
    val v = vm
    if (v == null) {
        if (uiClipSnapshot !== UiClipSnapshot.EMPTY) {
            uiClipSnapshot = UiClipSnapshot.EMPTY
            lastUiClipVersion = -1
        }
        if (realInk.held) realInk.releaseAndCatchUp()
        return
    }
    val version = v.panelOcclusion.version
    if (!force && version == lastUiClipVersion) return
    // tracker 是裁剪状态的唯一真身。
    val snapshot = v.panelOcclusion.snapshot()
    lastUiClipVersion = version
    uiClipSnapshot = snapshot
    // tracker 可能因 owner 接管/移除而清掉渐隐位: 同步清掉 Compose 侧残留状态。
    for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
        val slot = UiPanelSlot.EVADABLE_ARRAY[i]
        if (!v.panelOcclusion.isEvaded(slot) && v.slotEvading[slot.ordinal].value) {
            v.slotEvading[slot.ordinal].value = false
        }
    }
    if (realInk.held) {
        val oldClips = realInk.uiClips
        if (snapshot != oldClips) {
            val delta = clipDelta(oldClips, snapshot)
            val inkBounds = realInk.publishedInkBounds
            ensureViewTransform()
            viewTransform.docToScreen(v.smoothedStrokeX, v.smoothedStrokeY, pointScratch)
            val headScreenX = pointScratch[0]
            val headScreenY = pointScratch[1]
            val headRadius = effectiveBrushScreenRadius(v, v.smoothedStrokePressure.toFloat())
            val headRect = ClipRect(
                headScreenX - headRadius,
                headScreenY - headRadius,
                headScreenX + headRadius,
                headScreenY + headRadius,
            )
            val action = clipChangeAction(delta, inkBounds, headRect)
            FbGeomLog.clipRebuild(realInk.currentStrokeSeq, version, delta.size, inkBounds != null, action == ClipChangeAction.REBAKE)
            when (action) {
                ClipChangeAction.KEEP_UPDATE -> {
                    realInk.updateActiveUiClips(snapshot)
                }
                ClipChangeAction.REBAKE -> {
                    // 相交重烤: 保持按住, 把差集区域与已画墨的相交部分在原层按新 clips 重新烘焙 (plan 四.2/四.3)
                    realInk.updateClipsAndRebake(snapshot, delta)
                }
            }
        } else {
            realInk.updateActiveUiClips(snapshot)
        }
    }
}

internal fun CanvasTouchView.effectiveBrushScreenRadius(v: PaintViewModel, pressure: Float): Float {
    val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
    val size = if (effTool() == Tool.ERASER && tool != Tool.ERASER) {
        v.getToolEffectiveSize("eraser").toFloat()
    } else {
        v.brushSize.toFloat()
    }
    val pFrac = if (v.brushPressureEnabled) pressureFractionCached(pressure) else 1f
    return (size * scale * 0.5f * pFrac).coerceAtLeast(0.75f)
}

/**
 * 50% 双向滞后评估：对 9 个可避让槽位逐个独立判定。
 *
 * - 包络到面板本体距离 <= 32dp + brushR：进入 50%；
 * - 距离 > 40dp + brushR：立即恢复 100%，不等抬笔；
 * - 中间滞后带保持上一状态，防边界抖动。
 *
 * 240Hz `ACTION_MOVE` 热路径：纯标量运算，零分配。
 */
internal fun CanvasTouchView.evaluateUiEvasion50(
    v: PaintViewModel,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    brushRadiusPx: Float,
) {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
    if (!brushRadiusPx.isFinite() || brushRadiusPx < 0f) return
    if (!v.autoHidePanelsOnStroke) return
    val enterRadiusPx = EVADE_ENTER_DP * density + brushRadiusPx
    val exitRadiusPx = EVADE_EXIT_DP * density + brushRadiusPx
    for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
        val slot = UiPanelSlot.EVADABLE_ARRAY[i]
        val shouldEvade = v.panelOcclusion.evaluateSlotEvasion(
            slot, left, top, right, bottom, enterRadiusPx, exitRadiusPx,
        )
        v.setSlotEvading(slot, shouldEvade)
    }
}

/**
 * 立即清除全部避让状态（12 条强制复位路径共用）。
 *
 * 50% 渐隐只影响 Compose 透明度，绝不撤除 [uiClipSnapshot] 中的孔径裁剪。
 */
internal fun CanvasTouchView.clearAllEvasionState(v: PaintViewModel) {
    removeCallbacks(clearEvasionAfterUpRunnable)
    v.clearAllSlotEvasion()
}

internal fun CanvasTouchView.scheduleClearEvasionAfterUp() {
    removeCallbacks(clearEvasionAfterUpRunnable)
    postDelayed(clearEvasionAfterUpRunnable, POST_UP_CONTINUATION_MS)
}

internal fun CanvasTouchView.abortAndClearRealInk() {
    realInk.release()
    realInk.cancelCatchUp()
    frontBufferOverlay?.clearInk()
}

/**
 * 针对光标移动进行局部脏区失效重绘，避免整屏 4K 全量重绘。
 * 自动计算 (旧光标包围盒 ∪ 新光标包围盒) 的最小并集矩形。
 * 标量 Float 重载消除在 120Hz/240Hz 运笔热路径上的 Offset? 装箱分配。
 */
internal fun CanvasTouchView.invalidateCursor(newX: Float, newY: Float, cursorRadiusPx: Float = 0f) {
    val viewW = width
    val viewH = height
    if (viewW <= 0 || viewH <= 0) {
        invalidate()
        return
    }

    val v = vm
    // 对称模式、绘图变换交互中或视口缩放平移交互中走全量刷新
    if (v != null && v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
        invalidate()
        return
    }
    if (isInteracting || isTransformActive) {
        invalidate()
        return
    }

    val effectiveR = if (cursorRadiusPx > 0f) {
        cursorRadiusPx
    } else {
        val bSize = v?.brushSize?.toFloat() ?: 20f
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        (bSize * scale * 0.5f).coerceIn(8f * density, 60f * density)
    }
    val margin = 4f * density

    var dirtyL = Float.MAX_VALUE
    var dirtyT = Float.MAX_VALUE
    var dirtyR = -Float.MAX_VALUE
    var dirtyB = -Float.MAX_VALUE

    if (prevCursorRectValid) {
        dirtyL = minOf(dirtyL, prevCursorLeft)
        dirtyT = minOf(dirtyT, prevCursorTop)
        dirtyR = maxOf(dirtyR, prevCursorRight)
        dirtyB = maxOf(dirtyB, prevCursorBottom)
    }

    val hasNewPos = !newX.isNaN() && !newY.isNaN()
    if (hasNewPos) {
        val newL = newX - effectiveR - margin
        val newT = newY - effectiveR - margin
        val newR = newX + effectiveR + margin
        val newB = newY + effectiveR + margin

        dirtyL = minOf(dirtyL, newL)
        dirtyT = minOf(dirtyT, newT)
        dirtyR = maxOf(dirtyR, newR)
        dirtyB = maxOf(dirtyB, newB)

        prevCursorLeft = newL
        prevCursorTop = newT
        prevCursorRight = newR
        prevCursorBottom = newB
        prevCursorRectValid = true
    } else {
        prevCursorRectValid = false
    }

    if (dirtyR <= dirtyL || dirtyB <= dirtyT) {
        return
    }

    val l = dirtyL.toInt().coerceIn(0, viewW)
    val t = dirtyT.toInt().coerceIn(0, viewH)
    val r = dirtyR.toInt().coerceIn(0, viewW)
    val b = dirtyB.toInt().coerceIn(0, viewH)
    if (r <= l || b <= t) {
        return
    }

    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        invalidate(l, t, r, b)
    } else {
        postInvalidate(l, t, r, b)
    }
}

internal fun CanvasTouchView.invalidateCursor(newPos: Offset?, cursorRadiusPx: Float = 0f) {
    if (newPos != null) {
        invalidateCursor(newPos.x, newPos.y, cursorRadiusPx)
    } else {
        invalidateCursor(Float.NaN, Float.NaN, cursorRadiusPx)
    }
}

internal fun CanvasTouchView.isHoverOverUi(x: Float, y: Float): Boolean {
    val v = vm ?: return false
    if (v.brushStudioOpen || v.moreSettingsOpen) return true
    // Any full-screen overlay panel (brush/layers/color/settings/more)
    // must restore the system pointer; PaintingPage mirrors its local
    // panel booleans into this field via CanvasView's update block.
    if (overlayPanelsOpen) return true

    return v.panelOcclusion.hitTest(x, y)
}
