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

/**
 * 终止所有画布变换动画 (吸附收敛 / 满屏复位) 并失效其待执行帧, 返回新的写入者令牌。
 *
 * 新手势与任何新动画都必须先经此函数: 令牌递增后, 旧动画即使还有已入队的帧回调,
 * 也会在自检处**整帧丢弃** (而不是"少写一部分"), 因此同一帧内只有一个写入者能改画布
 * 变换, 且取消后不会留下延迟写入或半更新状态。
 */
internal fun CanvasTouchView.cancelCanvasTransformAnimators(): Int {
    fitAnimator?.cancel()
    fitAnimator = null
    snapAnimator?.cancel()
    snapAnimator = null
    return transformWriterGuard.invalidateAll()
}

internal fun CanvasTouchView.animateFitCanvas() {
    val writerToken = cancelCanvasTransformAnimators()
    isInteracting = false
    isTransformActive = false
    val startZoom = canvasZoom
    val startRot = canvasRotation
    val startPanX = canvasPanX
    val startPanY = canvasPanY
    val dRot = RotationSnap.shortestDelta(startRot, 0f)

    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 240L
        interpolator = android.view.animation.DecelerateInterpolator(1.8f)
        addUpdateListener { anim ->
            // 令牌失效 = 新手势或新动画已接管, 本帧整帧丢弃
            if (!transformWriterGuard.isActive(writerToken)) return@addUpdateListener
            val f = anim.animatedFraction
            canvasZoom = startZoom + (1f - startZoom) * f
            canvasRotation = startRot + dRot * f
            canvasPanX = startPanX + (0f - startPanX) * f
            canvasPanY = startPanY + (0f - startPanY) * f
            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (transformWriterGuard.isActive(writerToken)) {
                    canvasZoom = 1f
                    canvasRotation = 0f
                    canvasPanX = 0f
                    canvasPanY = 0f
                    onTransform?.invoke(1f, 0f, 0f, 0f)
                    invalidate()
                }
            }
        })
    }
    fitAnimator = animator
    animator.start()
}

/**
 * 双指松开后, 若当前处于 90° 倍数吸附区, 围绕最后一次双指中心把角度平滑收敛到**精确倍数**。
 *
 * 收敛过程实时用旋转增量补偿平移, 使 [pivotX]/[pivotY] 对应的画布内容在屏幕上保持不动 ——
 * 即"吸附后旋转中心不变、内容不滑移", 且缩放比例全程不变。角差超过吸附区则不收敛,
 * 避免自由旋转时突然被拽走。
 */
internal fun CanvasTouchView.settleRotationToSnap(pivotX: Float, pivotY: Float) {
    val v = vm ?: return
    if (!rotationSnapGesture.isSnapped) return
    val threshold = v.canvasRotationSnapDegrees
    if (!v.canvasRotationEnabled || threshold <= 0f) return
    // 仅在吸附区内才收敛 (isWithinThreshold 已折叠角差, 天然处理 360°/0° 等价)
    if (!RotationSnap.isWithinThreshold(rotationSnapGesture.rawDegrees, threshold)) return

    // 确定要写画布变换了: 立刻终止其它动画并取得独占写入权
    val writerToken = cancelCanvasTransformAnimators()

    val target = RotationSnap.nearestMultiple(rotationSnapGesture.rawDegrees)
    val dSettle = RotationSnap.shortestDelta(canvasRotation, target)
    if (abs(dSettle) < 0.02f) {
        if (canvasRotation != target) {
            canvasRotation = target
            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
            invalidate()
        }
        return
    }

    val startRot = canvasRotation
    val startPanX = canvasPanX
    val startPanY = canvasPanY
    val c0x = viewW / 2f + startPanX
    val c0y = viewH / 2f + startPanY
    // 旋转中心 (双指中心) 相对旧画面中心的向量
    val vx = pivotX - c0x
    val vy = pivotY - c0y

    val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 150L
        interpolator = android.view.animation.DecelerateInterpolator(2f)
        addUpdateListener { anim ->
            // 令牌失效 = 新手势或新动画已接管, 本帧整帧丢弃
            if (!transformWriterGuard.isActive(writerToken)) return@addUpdateListener
            val dTheta = dSettle * anim.animatedFraction
            val rad = Math.toRadians(dTheta.toDouble())
            val cosR = kotlin.math.cos(rad).toFloat()
            val sinR = kotlin.math.sin(rad).toFloat()
            canvasRotation = startRot + dTheta
            // 围绕 pivot 反向补偿平移: 缩放不变 (k=1), 内容不动
            canvasPanX = pivotX - (vx * cosR - vy * sinR) - viewW / 2f
            canvasPanY = pivotY - (vx * sinR + vy * cosR) - viewH / 2f
            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
            invalidate()
        }
    }
    snapAnimator = animator
    animator.start()
}

internal fun CanvasTouchView.dismissPickerOverlay() {
    if (isLongPressPickerActive || (tool != Tool.PICKER && pickerActive?.value == true)) {
        pickerActive?.value = false
        isLongPressPickerActive = false
        longPressPointerId = -1
        pendingLongPressPointerId = -1
        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
        cachedDriver?.feedbackManager?.stopStrokeSound()
    }
}

internal fun CanvasTouchView.finishLongPressPicker(v: PaintViewModel) {
    if (!isLongPressPickerActive) return
    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
    cachedDriver?.feedbackManager?.stopStrokeSound()
    lastSingleFingerTapTimeMs = 0L
    lastSingleFingerTapDrewStroke = false
    longPressPointerId = -1
    pendingLongPressPointerId = -1
    val curCol = pickerCurrentColor?.value
    if (curCol != null && curCol.alpha > 0.05f) {
        val r = (curCol.red * 255).toInt().coerceIn(0, 255)
        val g = (curCol.green * 255).toInt().coerceIn(0, 255)
        val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
        val hex = String.format("#%02X%02X%02X", r, g, b)
        v.updateBrushColor(hex)
        v.showActionToast(context.getString(R.string.canvas_toast_color_picked), R.drawable.ic_picker)
    }
    pickerActive?.value = false
    isLongPressPickerActive = false
    localIsTouching = false
    clearLocalCursorPos()
    invalidate()
}

/** 刷新缓存的视图变换 (参数未变化时内部直接返回, 几乎零开销) */
internal fun CanvasTouchView.ensureViewTransform(forcePublishPose: Boolean = false) {
    val v = vm
    // 瓦片网格生效时位图空间就是网格尺寸 (displayBitmap 此时可能是旧整图或参考窗口快照)
    val grid = v?.displayTiles?.drawable()?.grid
    val bmp = if (grid != null) null else (v?.displayBitmap ?: docBitmap)
    val bmpW = grid?.width ?: bmp?.width ?: v?.docWidth ?: 1
    val bmpH = grid?.height ?: bmp?.height ?: v?.docHeight ?: 1
    val dw = v?.docWidth ?: bmpW
    val dh = v?.docHeight ?: bmpH
    val dirty = viewTransform.update(
        viewW,
        viewH,
        canvasPanX,
        canvasPanY,
        canvasZoom,
        canvasFitScale,
        canvasRotation,
        bmpW,
        bmpH,
        dw,
        dh,
        canvasFlipX,
        canvasFlipY,
    )
    if (dirty || forcePublishPose || realInk.pose == null) publishCanvasPose(bmpW, bmpH, force = forcePublishPose)
}

/** 与 onDraw 同一套位图→屏幕变换的快照, 渲染线程烤真墨时只读它。 */
internal fun CanvasTouchView.publishCanvasPose(bmpW: Int, bmpH: Int, force: Boolean = false) {
    val bw = maxOf(1, bmpW)
    val bh = maxOf(1, bmpH)
    val pose = CanvasPose(
        centerX = viewW / 2f + canvasPanX,
        centerY = viewH / 2f + canvasPanY,
        scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f),
        rotation = canvasRotation,
        signX = if (canvasFlipX) -1f else 1f,
        signY = if (canvasFlipY) -1f else 1f,
        halfW = bw / 2f,
        halfH = bh / 2f,
        bmpW = bw,
        bmpH = bh,
    )
    if (force && !realInk.held) {
        realInk.pose = pose
        return
    }
    val old = realInk.pose
    if (pose != old) {
        // 按住期间 pose 变了 (如 Compose 的 viewW/viewH/翻转在运笔中写进来): 四角屏幕位移 >= 0.5px 或尺寸变化才释放 (plan 四.4).
        if (realInk.held) {
            if (poseNeedsRelease(old, pose)) {
                FbGeomLog.poseRelease("pose_changed", realInk.currentStrokeSeq, android.os.SystemClock.uptimeMillis())
                realInk.releaseAndCatchUp()
                realInk.pose = pose
            }
        } else {
            realInk.pose = pose
        }
    }
}

internal fun CanvasTouchView.screenToDoc(screenPos: Offset): Offset {
    ensureViewTransform()
    viewTransform.screenToDoc(screenPos.x, screenPos.y, pointScratch)
    return Offset(pointScratch[0], pointScratch[1])
}

internal fun CanvasTouchView.docToScreen(docPos: Offset): Offset {
    ensureViewTransform()
    viewTransform.docToScreen(docPos.x, docPos.y, pointScratch)
    return Offset(pointScratch[0], pointScratch[1])
}

/** 视口四角反变换到位图坐标后的包围盒 (供按视口裁剪绘制) */
internal fun CanvasTouchView.visibleBitmapBounds(out: IntArray) {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until 4) {
        val sx = if (i == 0 || i == 2) 0f else viewW.toFloat()
        val sy = if (i < 2) 0f else viewH.toFloat()
        viewTransform.screenToBitmap(sx, sy, pointScratch)
        if (pointScratch[0] < minX) minX = pointScratch[0]
        if (pointScratch[0] > maxX) maxX = pointScratch[0]
        if (pointScratch[1] < minY) minY = pointScratch[1]
        if (pointScratch[1] > maxY) maxY = pointScratch[1]
    }
    out[0] = floor(minX).toInt()
    out[1] = floor(minY).toInt()
    out[2] = ceil(maxX).toInt()
    out[3] = ceil(maxY).toInt()
}
