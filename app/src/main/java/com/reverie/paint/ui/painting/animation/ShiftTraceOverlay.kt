/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ShiftTraceTarget

/**
 * 透光台位移对比 (Shift & Trace) 硬件加速覆盖层。
 *
 * 位于主画布与辅助覆盖层之间。在不修改文档图层和历史撤销栈的前提下，
 * 读取前一帧/后一帧的全画幅参考位图，应用独立的平移、旋转与缩放矩阵，
 * 并以洋葱皮时间配色（前帧红/后帧蓝绿）与透明度着色叠加，
 * 同时在高亮目标帧外圈绘制虚线标定框与四角标尺指示对位状态。
 */
@Composable
internal fun ShiftTraceOverlay(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    zoom: State<Float>,
    rotation: State<Float>,
    panX: State<Float>,
    panY: State<Float>,
    fitScale: Float,
) {
    if (!vm.anim.shiftTraceActive) return

    val prevBmp = vm.anim.shiftTracePrevBitmap
    val nextBmp = vm.anim.shiftTraceNextBitmap
    val target = vm.anim.shiftTraceTarget
    val prevTf = vm.anim.shiftTracePrevTransform
    val nextTf = vm.anim.shiftTraceNextTransform

    val prevColorInt = vm.anim.onionColorBackward
    val nextColorInt = vm.anim.onionColorForward
    val opacity = vm.anim.onionOpacity.coerceIn(0, 255)
    val tintPercent = vm.anim.onionTint.coerceIn(0, 100)

    val paintPrev = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val paintNext = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val renderW = if (vm.renderW > 0) vm.renderW else vm.docWidth
        val renderH = if (vm.renderH > 0) vm.renderH else vm.docHeight
        if (renderW <= 0 || renderH <= 0) return@Canvas

        val bmpW = renderW.toFloat()
        val bmpH = renderH.toFloat()
        val docW = vm.docWidth.toFloat().coerceAtLeast(1f)
        val docH = vm.docHeight.toFloat().coerceAtLeast(1f)
        val scaleX = bmpW / docW
        val scaleY = bmpH / docH

        val viewScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
        val center = Offset(size.width / 2f + panX.value, size.height / 2f + panY.value)

        withTransform({
            translate(center.x, center.y)
            rotate(rotation.value, pivot = Offset.Zero)
            scale(viewScale, viewScale, pivot = Offset.Zero)
        }) {
            val native = drawContext.canvas.nativeCanvas

            // 1. 绘制后帧 (Next Frame)
            if (nextBmp != null && !nextBmp.isRecycled) {
                paintNext.alpha = opacity
                if (tintPercent > 0) {
                    val tintAlpha = ((tintPercent / 100f) * 255).toInt().coerceIn(0, 255)
                    val tintColor = (nextColorInt and 0x00FFFFFF) or (tintAlpha shl 24)
                    paintNext.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP)
                } else {
                    paintNext.colorFilter = null
                }

                val txBmp = nextTf.translation.x * scaleX
                val tyBmp = nextTf.translation.y * scaleY

                withTransform({
                    translate(txBmp, tyBmp)
                    rotate(nextTf.rotation, pivot = Offset.Zero)
                    scale(nextTf.scale, nextTf.scale, pivot = Offset.Zero)
                }) {
                    native.drawBitmap(nextBmp, -bmpW / 2f, -bmpH / 2f, paintNext)

                    // 若后帧为当前对位目标，绘制指示虚线边界与四角标尺
                    if (target == ShiftTraceTarget.NEXT) {
                        val strokeWidth = 1.5f / viewScale
                        val dashEffect = PathEffect.dashPathEffect(
                            floatArrayOf(12f / viewScale, 8f / viewScale),
                            0f,
                        )
                        val borderColor = Color(nextColorInt)
                        drawRect(
                            color = borderColor.copy(alpha = 0.85f),
                            topLeft = Offset(-bmpW / 2f, -bmpH / 2f),
                            size = Size(bmpW, bmpH),
                            style = Stroke(width = strokeWidth, pathEffect = dashEffect),
                        )
                        val cornerLen = 24f / viewScale
                        val cLeft = -bmpW / 2f
                        val cRight = bmpW / 2f
                        val cTop = -bmpH / 2f
                        val cBottom = bmpH / 2f
                        val solidWidth = strokeWidth * 1.8f
                        drawLine(borderColor, Offset(cLeft, cTop), Offset(cLeft + cornerLen, cTop), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cTop), Offset(cLeft, cTop + cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cTop), Offset(cRight - cornerLen, cTop), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cTop), Offset(cRight, cTop + cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cBottom), Offset(cRight - cornerLen, cBottom), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cBottom), Offset(cRight, cBottom - cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cBottom), Offset(cLeft + cornerLen, cBottom), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cBottom), Offset(cLeft, cBottom - cornerLen), strokeWidth = solidWidth)
                    }
                }
            }

            // 2. 绘制前帧 (Prev Frame)
            if (prevBmp != null && !prevBmp.isRecycled) {
                paintPrev.alpha = opacity
                if (tintPercent > 0) {
                    val tintAlpha = ((tintPercent / 100f) * 255).toInt().coerceIn(0, 255)
                    val tintColor = (prevColorInt and 0x00FFFFFF) or (tintAlpha shl 24)
                    paintPrev.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP)
                } else {
                    paintPrev.colorFilter = null
                }

                val txBmp = prevTf.translation.x * scaleX
                val tyBmp = prevTf.translation.y * scaleY

                withTransform({
                    translate(txBmp, tyBmp)
                    rotate(prevTf.rotation, pivot = Offset.Zero)
                    scale(prevTf.scale, prevTf.scale, pivot = Offset.Zero)
                }) {
                    native.drawBitmap(prevBmp, -bmpW / 2f, -bmpH / 2f, paintPrev)

                    // 若前帧为当前对位目标，绘制指示虚线边界与四角标尺
                    if (target == ShiftTraceTarget.PREV) {
                        val strokeWidth = 1.5f / viewScale
                        val dashEffect = PathEffect.dashPathEffect(
                            floatArrayOf(12f / viewScale, 8f / viewScale),
                            0f,
                        )
                        val borderColor = Color(prevColorInt)
                        drawRect(
                            color = borderColor.copy(alpha = 0.85f),
                            topLeft = Offset(-bmpW / 2f, -bmpH / 2f),
                            size = Size(bmpW, bmpH),
                            style = Stroke(width = strokeWidth, pathEffect = dashEffect),
                        )
                        val cornerLen = 24f / viewScale
                        val cLeft = -bmpW / 2f
                        val cRight = bmpW / 2f
                        val cTop = -bmpH / 2f
                        val cBottom = bmpH / 2f
                        val solidWidth = strokeWidth * 1.8f
                        drawLine(borderColor, Offset(cLeft, cTop), Offset(cLeft + cornerLen, cTop), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cTop), Offset(cLeft, cTop + cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cTop), Offset(cRight - cornerLen, cTop), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cTop), Offset(cRight, cTop + cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cBottom), Offset(cRight - cornerLen, cBottom), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cRight, cBottom), Offset(cRight, cBottom - cornerLen), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cBottom), Offset(cLeft + cornerLen, cBottom), strokeWidth = solidWidth)
                        drawLine(borderColor, Offset(cLeft, cBottom), Offset(cLeft, cBottom - cornerLen), strokeWidth = solidWidth)
                    }
                }
            }
        }
    }
}
