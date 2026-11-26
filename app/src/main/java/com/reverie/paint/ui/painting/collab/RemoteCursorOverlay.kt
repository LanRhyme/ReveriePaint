/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.collab

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.CollabCursorDisplayMode
import com.reverie.paint.ui.painting.canvas.imageToWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val PING_ANIM_DURATION_MS = 2500f

/**
 * 远端协作者实时光标、画师昵称牌与画布坐标脉冲标记 (Ping) 覆盖层 (Magma 协同模式)。
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
    val mode = vm.collab.cursorDisplayMode
    val cursors = if (mode == CollabCursorDisplayMode.HIDDEN) emptyList() else vm.collab.remoteCursors
    val pings = vm.collab.canvasPings
    if (cursors.isEmpty() && pings.isEmpty()) return

    var pingNowMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val hasPings = pings.isNotEmpty()
    LaunchedEffect(hasPings) {
        while (isActive && vm.collab.canvasPings.isNotEmpty()) {
            pingNowMs = SystemClock.uptimeMillis()
            delay(32L)
        }
    }

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

            if (mode == CollabCursorDisplayMode.FULL) {
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
            }

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

            if (mode == CollabCursorDisplayMode.FULL) {
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

        // 画布协同坐标脉冲标记 (Ping)
        if (pings.isNotEmpty()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val minPingR = 10.dp.toPx()
            val maxPingR = 72.dp.toPx()
            for (ping in pings) {
                val age = (pingNowMs - ping.createdAtUptimeMs).coerceAtLeast(0L).toFloat()
                if (age >= PING_ANIM_DURATION_MS) continue
                val progress = (age / PING_ANIM_DURATION_MS).coerceIn(0f, 1f)
                val fadeAlpha = (1f - progress).coerceIn(0f, 1f)
                val screenPos = imageToWidget(
                    p = Offset(ping.docX, ping.docY),
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
                val col = Color(ping.badgeColorArgb)
                val outerR = minPingR + (maxPingR - minPingR) * progress
                drawCircle(
                    color = col.copy(alpha = fadeAlpha * 0.85f),
                    radius = outerR,
                    center = screenPos,
                    style = Stroke(width = 3.dp.toPx()),
                )
                val innerProg = ((progress - 0.18f) / 0.82f).coerceIn(0f, 1f)
                if (innerProg > 0f) {
                    val innerR = minPingR + (maxPingR * 0.72f - minPingR) * innerProg
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - innerProg) * 0.85f),
                        radius = innerR,
                        center = screenPos,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                drawCircle(
                    color = col.copy(alpha = fadeAlpha),
                    radius = 5.dp.toPx(),
                    center = screenPos,
                )

                val pingLabel = ping.senderName.ifBlank { ping.peerId.take(6) }.take(12)
                val textW = textPaint.measureText(pingLabel)
                val fm = textPaint.fontMetrics
                val textH = fm.descent - fm.ascent
                val pillW = textW + padH * 2f
                val pillH = textH + padV * 2f
                val topY = (screenPos.y - 16.dp.toPx() - pillH).coerceAtLeast(8f)
                pillRect.set(
                    screenPos.x - pillW * 0.5f,
                    topY,
                    screenPos.x + pillW * 0.5f,
                    topY + pillH,
                )
                pillPaint.color = ping.badgeColorArgb
                pillPaint.alpha = (220 * fadeAlpha).roundToInt().coerceIn(0, 255)
                nativeCanvas.drawRoundRect(pillRect, cornerR, cornerR, pillPaint)
                pillStrokePaint.alpha = (200 * fadeAlpha).roundToInt().coerceIn(0, 255)
                nativeCanvas.drawRoundRect(pillRect, cornerR, cornerR, pillStrokePaint)
                val baseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
                textPaint.alpha = (255 * fadeAlpha).roundToInt().coerceIn(0, 255)
                nativeCanvas.drawText(pingLabel, pillRect.centerX(), baseline, textPaint)
                textPaint.alpha = 255
                pillStrokePaint.alpha = 210
            }
        }
    }
}
