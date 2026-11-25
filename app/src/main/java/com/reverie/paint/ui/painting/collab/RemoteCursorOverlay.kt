/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.collab

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.painting.canvas.imageToWidget
import kotlin.math.roundToInt

/**
 * 远端协作者实时光标与画师昵称牌覆盖层 (Magma 协同模式)。
 * 视口 transform 仅在 DrawScope lambda 内读取, 避免平移/缩放/旋转触发 Compose 重组。
 */
@Composable
internal fun RemoteCursorOverlay(
    vm: PaintViewModel,
    zoom: State<Float>,
    rotation: State<Float>,
    panX: State<Float>,
    panY: State<Float>,
    fitScale: Float,
    flipX: Boolean,
    flipY: Boolean,
) {
    if (!vm.collab.isActive) return
    val cursors = vm.collab.remoteCursors
    if (cursors.isEmpty()) return

    val density = LocalDensity.current
    val textPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 10.sp.toPx() }
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val pillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val pillStrokePaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = with(density) { 1.dp.toPx() }
            color = android.graphics.Color.argb(210, 255, 255, 255)
        }
    }
    val pillRect = remember { RectF() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val viewW = size.width.roundToInt().coerceAtLeast(1)
        val viewH = size.height.roundToInt().coerceAtLeast(1)
        val docW = vm.docWidth.coerceAtLeast(1)
        val docH = vm.docHeight.coerceAtLeast(1)
        val tiles = vm.displayTiles.drawable()
        val displayBmp = if (tiles != null) null else vm.displayBitmap?.takeIf { !it.isRecycled }
        val bmpW = tiles?.grid?.width ?: displayBmp?.width ?: docW
        val bmpH = tiles?.grid?.height ?: displayBmp?.height ?: docH

        val curZoom = zoom.value
        val curRot = rotation.value
        val curPanX = panX.value
        val curPanY = panY.value
        val scale = (curZoom * fitScale).coerceAtLeast(0.001f)

        val padH = 6.dp.toPx()
        val padV = 2.5.dp.toPx()
        val cornerR = 8.dp.toPx()
        val dotR = 3.5.dp.toPx()
        val pillGap = 8.dp.toPx()

        for (cursor in cursors) {
            val screenPos = imageToWidget(
                p = Offset(cursor.docX, cursor.docY),
                canvasW = viewW,
                canvasH = viewH,
                panX = curPanX,
                panY = curPanY,
                zoom = curZoom,
                fitScale = fitScale,
                rotation = curRot,
                bmpW = bmpW,
                bmpH = bmpH,
                docW = docW,
                docH = docH,
                flipX = flipX,
                flipY = flipY,
            )

            // 视口裁剪: 完全超出屏幕外的光标跳过绘制
            if (screenPos.x < -120f || screenPos.y < -120f ||
                screenPos.x > size.width + 120f || screenPos.y > size.height + 120f
            ) {
                continue
            }

            val badgeCol = Color(cursor.badgeColorArgb)
            val brushRingRadius = (cursor.brushSize * 0.5f * scale).coerceIn(6.dp.toPx(), 160.dp.toPx())

            // 画笔尺寸外环 (双对比色)
            drawCircle(
                color = Color.White.copy(alpha = if (cursor.isDrawing) 0.9f else 0.65f),
                radius = brushRingRadius + 0.8f,
                center = screenPos,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawCircle(
                color = badgeCol.copy(alpha = if (cursor.isDrawing) 0.95f else 0.78f),
                radius = brushRingRadius,
                center = screenPos,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // 落笔脉冲光晕 + 中心点
            if (cursor.isDrawing) {
                drawCircle(
                    color = badgeCol.copy(alpha = 0.22f),
                    radius = dotR * 2.2f,
                    center = screenPos,
                )
            }
            drawCircle(
                color = Color.White,
                radius = dotR + 1.dp.toPx(),
                center = screenPos,
            )
            drawCircle(
                color = badgeCol,
                radius = dotR,
                center = screenPos,
            )

            // 协作者昵称胶囊标签
            val label = cursor.name.ifBlank { cursor.peerId.take(6) }.take(14)
            val textW = textPaint.measureText(label)
            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            val pillW = textW + padH * 2f
            val pillH = textH + padV * 2f
            val topY = screenPos.y - brushRingRadius - pillGap - pillH
            val finalTopY = if (topY < 8f) screenPos.y + brushRingRadius + pillGap else topY

            pillRect.set(
                screenPos.x - pillW * 0.5f,
                finalTopY,
                screenPos.x + pillW * 0.5f,
                finalTopY + pillH,
            )
            pillPaint.color = cursor.badgeColorArgb
            pillPaint.alpha = 232
            val nativeCanvas = drawContext.canvas.nativeCanvas
            nativeCanvas.drawRoundRect(pillRect, cornerR, cornerR, pillPaint)
            nativeCanvas.drawRoundRect(pillRect, cornerR, cornerR, pillStrokePaint)
            val baseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
            nativeCanvas.drawText(label, pillRect.centerX(), baseline, textPaint)
        }
    }
}
