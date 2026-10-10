/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool

/**
 * Brush cursor ring in its OWN Canvas layer above the image canvas.
 * cursorScreenPos / livePressure change at input rate while drawing; sharing
 * one Canvas with the (full-screen) image draw meant every pointer move
 * re-executed the whole overlay draw including the big drawImage - on top of
 * the per-render displayRevision redraws. Now cursor moves only invalidate
 * this (visually tiny) layer.
 */
@Composable
internal fun BrushCursorOverlay(
    vm: PaintViewModel,
    tool: Tool,
    zoom: Float,
    fitScale: Float,
    liquifyBrushSize: Float = 60f,
    cursorScreenPos: androidx.compose.runtime.MutableState<Offset?>,
    isCursorHovering: androidx.compose.runtime.MutableState<Boolean>,
    isCursorTouching: androidx.compose.runtime.MutableState<Boolean>,
    livePressure: androidx.compose.runtime.MutableState<Float>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val isEraser = tool == Tool.ERASER
        val cursorMode = if (isEraser) vm.eraserCursorMode else vm.brushCursorMode
        // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
        val shouldShow = when (cursorMode) {
            1 -> isCursorTouching.value
            2 -> isCursorHovering.value
            3 -> isCursorTouching.value || isCursorHovering.value
            else -> false
        }
        // Liquify hides the system cursor too (hideSystemCursorForTool) and
        // has its own brush size - without the ring here the tool would have
        // NO visible cursor at all
        val isDrawTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY
        if (shouldShow && cursorScreenPos.value != null && isDrawTool && vm.cursorStyleMode != 4) {
            val curPos = cursorScreenPos.value!!
            val scale = (zoom * fitScale).coerceAtLeast(0.001f)
            val pressureScale = if (isCursorTouching.value) livePressure.value.coerceIn(0.08f, 1f) else 1f
            val cursorBrushSize = if (tool == Tool.LIQUIFY) liquifyBrushSize else vm.brushSize.toFloat()
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureScale).toFloat().coerceAtLeast(2f)

            when (vm.cursorStyleMode) {
                0 -> { // 圆形 (Brush Outline Ring - Krita dual-contrast circle)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = brushRadiusScreen + 0.8f,
                        center = curPos,
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = brushRadiusScreen,
                        center = curPos,
                        style = Stroke(width = 1.0.dp.toPx())
                    )
                }
                1 -> { // 十字准星 (Crosshair - thin intersecting lines)
                    val len = 9.dp.toPx()
                    // Black outline shadow
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(curPos.x - len, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.8.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y + len), strokeWidth = 1.8.dp.toPx())
                    // Crisp white foreground
                    drawLine(Color.White, Offset(curPos.x - len, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.0.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y + len), strokeWidth = 1.0.dp.toPx())
                }
                2 -> { // 点 (Precise Dot)
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius = 3.5.dp.toPx(), center = curPos)
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = curPos)
                }
                3 -> {} // 无 (No Cursor)
                4 -> {} // 系统指针 (System Cursor Pointer - handled by native pointerIcon)
                5 -> { // 圆 + 十字准星 (Circle + Crosshair combined)
                    // 1. Draw Circle Ring
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = brushRadiusScreen + 0.8f,
                        center = curPos,
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = brushRadiusScreen,
                        center = curPos,
                        style = Stroke(width = 1.0.dp.toPx())
                    )
                    // 2. Draw Center Crosshair
                    val len = 6.dp.toPx()
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(curPos.x - len, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.8.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y + len), strokeWidth = 1.8.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x - len, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.0.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y + len), strokeWidth = 1.0.dp.toPx())
                }
            }
        }
    }
}
