/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.GuideMode
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.SymmetryType
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawDrawingGuides(
    vm: PaintViewModel,
    bmpWidth: Float,
    bmpHeight: Float,
    zoom: Float,
    fitScale: Float,
) {
    val bmp = object {
        val width = bmpWidth.toInt()
        val height = bmpHeight.toInt()
    }
                // ---- 绘图辅助与参考线渲染 (Drawing Guides & Symmetry) ----
                val guide = vm.drawingGuide
                if (guide.mode != GuideMode.OFF) {
                    val guideCol = parseColor(guide.colorHex).copy(alpha = guide.opacity)
                    val gStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx() / (zoom * fitScale))
                    val docW = bmp.width.toFloat()
                    val docH = bmp.height.toFloat()
                    val halfW = docW / 2f
                    val halfH = docH / 2f

                    when (guide.mode) {
                        GuideMode.GRID_2D -> {
                            val step = (guide.gridSize * (bmp.width.toFloat() / maxOf(1, vm.docWidth))).coerceAtLeast(16f)
                            var gx = -halfW + (step - (-halfW % step))
                            while (gx < halfW) {
                                drawLine(guideCol, Offset(gx, -halfH), Offset(gx, halfH), strokeWidth = gStroke.width)
                                gx += step
                            }
                            var gy = -halfH + (step - (-halfH % step))
                            while (gy < halfH) {
                                drawLine(guideCol, Offset(-halfW, gy), Offset(halfW, gy), strokeWidth = gStroke.width)
                                gy += step
                            }
                        }
                        GuideMode.ISOMETRIC -> {
                            val step = (guide.gridSize * (bmp.width.toFloat() / maxOf(1, vm.docWidth))).coerceAtLeast(24f)
                            val tan30 = 0.57735f
                            var gx = -halfW
                            while (gx < halfW) {
                                drawLine(guideCol, Offset(gx, -halfH), Offset(gx, halfH), strokeWidth = gStroke.width)
                                gx += step
                            }
                            var offset = -halfH - halfW * tan30
                            while (offset < halfH + halfW * tan30) {
                                drawLine(guideCol, Offset(-halfW, offset - halfW * tan30), Offset(halfW, offset + halfW * tan30), strokeWidth = gStroke.width)
                                drawLine(guideCol, Offset(-halfW, offset + halfW * tan30), Offset(halfW, offset - halfW * tan30), strokeWidth = gStroke.width)
                                offset += step
                            }
                        }
                        GuideMode.PERSPECTIVE -> {
                            val pts = if (guide.perspectiveVanishingPoints.isEmpty()) {
                                listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                            } else guide.perspectiveVanishingPoints

                            val scX = bmp.width.toFloat() / maxOf(1, vm.docWidth)
                            val scY = bmp.height.toFloat() / maxOf(1, vm.docHeight)

                            // Horizon line
                            if (pts.size >= 2) {
                                val vp0 = Offset(pts[0].x * scX - halfW, pts[0].y * scY - halfH)
                                val vp1 = Offset(pts[1].x * scX - halfW, pts[1].y * scY - halfH)
                                drawLine(Morandi.accent.copy(alpha = 0.8f), vp0, vp1, strokeWidth = gStroke.width * 1.5f)
                            } else if (pts.size == 1) {
                                val vpy = pts[0].y * scY - halfH
                                drawLine(Morandi.accent.copy(alpha = 0.5f), Offset(-halfW, vpy), Offset(halfW, vpy), strokeWidth = gStroke.width)
                            }

                            for (vp in pts) {
                                val vpx = vp.x * scX - halfW
                                val vpy = vp.y * scY - halfH
                                val vpOffset = Offset(vpx, vpy)
                                val rayCount = guide.perspectiveRayCount.coerceIn(6, 24)
                                for (ri in 0 until rayCount) {
                                    val angle = (ri.toFloat() / rayCount) * 2f * PI.toFloat()
                                    val rayLen = maxOf(docW, docH) * 2.5f
                                    drawLine(guideCol, vpOffset, vpOffset + Offset(cos(angle) * rayLen, sin(angle) * rayLen), strokeWidth = gStroke.width)
                                }
                                if (vm.drawingGuidePanelOpen) {
                                    drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / (zoom * fitScale), center = vpOffset)
                                }
                                drawCircle(Morandi.accent, radius = (if (vm.drawingGuidePanelOpen) 7.dp else 5.dp).toPx() / (zoom * fitScale), center = vpOffset)
                                drawCircle(Color.White, radius = 3.dp.toPx() / (zoom * fitScale), center = vpOffset)
                            }
                        }
                        GuideMode.SYMMETRY -> {
                            // Reference lines remain visible even when assisted drawing is disabled.
                            val scX = bmp.width.toFloat() / maxOf(1, vm.docWidth)
                            val scY = bmp.height.toFloat() / maxOf(1, vm.docHeight)
                            val cx = (vm.docWidth * guide.symmetryCenterX) * scX - halfW
                            val cy = (vm.docHeight * guide.symmetryCenterY) * scY - halfH
                            val symCol = Morandi.accent.copy(alpha = 0.85f)
                            val currentScale = zoom * fitScale
                            val symStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.5.dp.toPx() / currentScale,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                            )

                            val rotRad = (guide.symmetryRotationDeg % 360f) * (PI.toFloat() / 180f)
                            val cosR = cos(rotRad)
                            val sinR = sin(rotRad)
                            val dMax = maxOf(docW, docH) * 2.5f

                            fun drawRotatedLine(dirX: Float, dirY: Float) {
                                val rx = dirX * cosR - dirY * sinR
                                val ry = dirX * sinR + dirY * cosR
                                drawLine(
                                    symCol,
                                    Offset(cx - rx * dMax, cy - ry * dMax),
                                    Offset(cx + rx * dMax, cy + ry * dMax),
                                    strokeWidth = symStroke.width,
                                    pathEffect = symStroke.pathEffect,
                                )
                            }

                            when (guide.symmetryType) {
                                SymmetryType.VERTICAL -> {
                                    drawRotatedLine(0f, 1f)
                                }
                                SymmetryType.HORIZONTAL -> {
                                    drawRotatedLine(1f, 0f)
                                }
                                SymmetryType.QUADRANT -> {
                                    drawRotatedLine(0f, 1f)
                                    drawRotatedLine(1f, 0f)
                                }
                                SymmetryType.RADIAL -> {
                                    drawRotatedLine(0f, 1f)
                                    drawRotatedLine(1f, 0f)
                                    val diag = 0.70710678f
                                    drawRotatedLine(diag, diag)
                                    drawRotatedLine(-diag, diag)
                                }
                            }

                            if (vm.drawingGuidePanelOpen) {
                                // 1. 中心平移控制柄
                                drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / currentScale, center = Offset(cx, cy))
                                drawCircle(Morandi.accent, radius = 6.dp.toPx() / currentScale, center = Offset(cx, cy))
                                drawCircle(Color.White, radius = 2.5.dp.toPx() / currentScale, center = Offset(cx, cy))

                                // 2. 轴向旋转控制柄 (沿对称主轴分布)
                                val rotHandleDist = minOf(docW, docH) * 0.35f * scX
                                val rotHandleX = cx - sinR * rotHandleDist
                                val rotHandleY = cy + cosR * rotHandleDist
                                val rotCenter = Offset(rotHandleX, rotHandleY)

                                drawCircle(Morandi.accent.copy(alpha = 0.25f), radius = 13.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 7.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Color.White, radius = 3.5.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 1.5.dp.toPx() / currentScale, center = rotCenter)
                            } else {
                                drawCircle(Morandi.accent, radius = 4.dp.toPx() / currentScale, center = Offset(cx, cy))
                            }
                        }
                        else -> Unit
                    }
                }

}

internal fun DrawScope.drawColorLoupe(
    pickerActive: Boolean,
    pickerScreenPos: Offset,
    pickerInitialColor: Color,
    pickerCurrentColor: Color,
) {
            // Draw PaintWorld-style Color Loupe when picker is active
            if (pickerActive) {
                val loupeCenter = pickerScreenPos + Offset(0f, -80.dp.toPx())
                val outerRadius = 45.dp.toPx()
                val innerRadius = 28.dp.toPx()
                val ringThickness = outerRadius - innerRadius
                val ringRadius = (outerRadius + innerRadius) / 2f

                // Outer drop shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = outerRadius + 4.dp.toPx(),
                    center = loupeCenter
                )

                // Top half ring: Reference / Previous color
                drawArc(
                    color = pickerInitialColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Bottom half ring: Current sampled color
                drawArc(
                    color = pickerCurrentColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Outer border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = outerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
                // Inner border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = innerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )

                // Center crosshair inside the loupe
                val crosshairInner = 6.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x - crosshairInner, loupeCenter.y),
                    end = Offset(loupeCenter.x + crosshairInner, loupeCenter.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x, loupeCenter.y - crosshairInner),
                    end = Offset(loupeCenter.x, loupeCenter.y + crosshairInner),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Crosshair at the target touch point on the canvas
                val crossLen = 14.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
                    end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
                    end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
                    end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
                    end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

}
