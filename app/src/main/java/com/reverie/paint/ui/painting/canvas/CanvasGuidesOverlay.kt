/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.model.CanvasDecorGeometry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.GuideMode
import com.reverie.paint.model.PERSPECTIVE_RAY_COUNT_MAX
import com.reverie.paint.model.PERSPECTIVE_RAY_COUNT_MIN
import com.reverie.paint.model.Perspective3DMath
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.Point3D
import com.reverie.paint.model.SymmetryType
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
                // 可见性看 mode 与独立辅助线开关; 前缓冲重放同一判据。
                if (CanvasDecorGeometry.guidesVisible(guide) && vm.drawingGuideLinesVisible) {
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
                            if (guide.perspective3d) {
                                // 限制在画布范围内渲染 (画布矩形之外不渲染)
                                clipRect(left = -halfW, top = -halfH, right = halfW, bottom = halfH) {
                                    // ---- 3D 空间透视参考模式 (自由旋转视角，地面网格 + 参考立方体) ----

                                    // 1. 地面网格 (XZ 面) - 无限延伸 + 远景深度虚化
                                    if (guide.perspectiveShowGround) {
                                        val n = guide.perspectiveGridDensity.coerceIn(4, 24)
                                        val step = (12.0f / n).coerceIn(0.5f, 2.5f)
                                        val maxExtent = 36.0f
                                        val halfSteps = (maxExtent / step).toInt().coerceIn(12, 40)
                                        val fadeNear = 9.0f
                                        val fadeFar = 32.0f

                                        fun drawFadedSegment(x1: Float, z1: Float, x2: Float, z2: Float, isMajor: Boolean) {
                                            val p1 = Perspective3DMath.projectWithDepth(
                                                x1, 0f, z1,
                                                guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg,
                                                guide.perspectiveFovDeg, 9.0f, docW, docH
                                            )
                                            val p2 = Perspective3DMath.projectWithDepth(
                                                x2, 0f, z2,
                                                guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg,
                                                guide.perspectiveFovDeg, 9.0f, docW, docH
                                            )
                                            if (p1 == null || p2 == null) return

                                            val avgZ = (p1.camZ + p2.camZ) * 0.5f
                                            if (avgZ >= fadeFar) return

                                            val alphaFactor = if (avgZ <= fadeNear) 1.0f else {
                                                val t = ((fadeFar - avgZ) / (fadeFar - fadeNear)).coerceIn(0f, 1f)
                                                t * t
                                            }
                                            if (alphaFactor < 0.02f) return

                                            val baseAlpha = if (isMajor) (guideCol.alpha * 1.3f).coerceIn(0.1f, 1f) else (guideCol.alpha * 0.5f).coerceIn(0.04f, 1f)
                                            val segAlpha = (baseAlpha * alphaFactor).coerceIn(0f, 1f)
                                            val segStroke = if (isMajor) gStroke.width * 1.3f else gStroke.width * 0.85f

                                            drawLine(
                                                guideCol.copy(alpha = segAlpha),
                                                Offset(p1.x, p1.y),
                                                Offset(p2.x, p2.y),
                                                strokeWidth = segStroke,
                                            )
                                        }

                                        for (i in -halfSteps..halfSteps) {
                                            val coord = i * step
                                            val isMajor = (i == 0)
                                            var segX = -maxExtent
                                            val segStep = step * 2f
                                            while (segX < maxExtent) {
                                                val nextX = minOf(maxExtent, segX + segStep)
                                                drawFadedSegment(segX, coord, nextX, coord, isMajor)
                                                segX = nextX
                                            }
                                            var segZ = -maxExtent
                                            while (segZ < maxExtent) {
                                                val nextZ = minOf(maxExtent, segZ + segStep)
                                                drawFadedSegment(coord, segZ, coord, nextZ, isMajor)
                                                segZ = nextZ
                                            }
                                        }
                                    }

                                    // 2. 原点立体参考立方体 (提供 Z/Y 轴深度与垂直第三灭点感知)
                                    if (guide.perspectiveShowCube) {
                                        val s = 1.0f
                                        val edges = listOf(
                                            Triple(Point3D(-s, 0f, -s), Point3D(s, 0f, -s), false),
                                            Triple(Point3D(s, 0f, -s), Point3D(s, 0f, s), false),
                                            Triple(Point3D(s, 0f, s), Point3D(-s, 0f, s), false),
                                            Triple(Point3D(-s, 0f, s), Point3D(-s, 0f, -s), false),
                                            Triple(Point3D(-s, 2f * s, -s), Point3D(s, 2f * s, -s), false),
                                            Triple(Point3D(s, 2f * s, -s), Point3D(s, 2f * s, s), false),
                                            Triple(Point3D(s, 2f * s, s), Point3D(-s, 2f * s, s), false),
                                            Triple(Point3D(-s, 2f * s, s), Point3D(-s, 2f * s, -s), false),
                                            Triple(Point3D(-s, 0f, -s), Point3D(-s, 2f * s, -s), true),
                                            Triple(Point3D(s, 0f, -s), Point3D(s, 2f * s, -s), true),
                                            Triple(Point3D(s, 0f, s), Point3D(s, 2f * s, s), true),
                                            Triple(Point3D(-s, 0f, s), Point3D(-s, 2f * s, s), true),
                                        )
                                        val vertCol = Color(0xFF78C896).copy(alpha = (guideCol.alpha * 1.3f).coerceIn(0.15f, 1f))
                                        val horizCol = Color(0xFFDCA86A).copy(alpha = (guideCol.alpha * 1.3f).coerceIn(0.15f, 1f))

                                        for (edge in edges) {
                                            val p1 = Perspective3DMath.project(
                                                edge.first.x, edge.first.y, edge.first.z,
                                                guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg,
                                                guide.perspectiveFovDeg, 9.0f, docW, docH
                                            )
                                            val p2 = Perspective3DMath.project(
                                                edge.second.x, edge.second.y, edge.second.z,
                                                guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg,
                                                guide.perspectiveFovDeg, 9.0f, docW, docH
                                            )
                                            if (p1 != null && p2 != null) {
                                                val col = if (edge.third) vertCol else horizCol
                                                drawLine(col, Offset(p1.x, p1.y), Offset(p2.x, p2.y), strokeWidth = gStroke.width * 0.95f)
                                            }
                                        }

                                        // 原点三色坐标轴 (X 红, Y 绿, Z 蓝，纤细化)
                                        val o = Perspective3DMath.project(0f, 0f, 0f, guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg, guide.perspectiveFovDeg, 9.0f, docW, docH)
                                        val ax = Perspective3DMath.project(s * 1.8f, 0f, 0f, guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg, guide.perspectiveFovDeg, 9.0f, docW, docH)
                                        val ay = Perspective3DMath.project(0f, s * 2.8f, 0f, guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg, guide.perspectiveFovDeg, 9.0f, docW, docH)
                                        val az = Perspective3DMath.project(0f, 0f, s * 1.8f, guide.perspectiveYawDeg, guide.perspectivePitchDeg, guide.perspectiveRollDeg, guide.perspectiveFovDeg, 9.0f, docW, docH)
                                        if (o != null) {
                                            if (ax != null) drawLine(Color(0xFFE06C75), Offset(o.x, o.y), Offset(ax.x, ax.y), strokeWidth = gStroke.width * 1.2f)
                                            if (ay != null) drawLine(Color(0xFF98C379), Offset(o.x, o.y), Offset(ay.x, ay.y), strokeWidth = gStroke.width * 1.2f)
                                            if (az != null) drawLine(Color(0xFF61AFEF), Offset(o.x, o.y), Offset(az.x, az.y), strokeWidth = gStroke.width * 1.2f)
                                        }
                                    }
                                }
                            } else {
                                val pts = if (guide.perspectiveVanishingPoints.isEmpty()) {
                                    listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                                } else guide.perspectiveVanishingPoints

                                val scX = bmp.width.toFloat() / maxOf(1, vm.docWidth)
                                val scY = bmp.height.toFloat() / maxOf(1, vm.docHeight)

                                // 无穷远延伸基准距离: 保证在超大缩放或灭点远离画布时依然贯穿全视口
                                val infiniteLen = maxOf(docW, docH) * 50f + 500_000f

                                // Horizon line: 双向贯穿延伸至无穷远
                                if (pts.size >= 2) {
                                    val vp0 = Offset(pts[0].x * scX - halfW, pts[0].y * scY - halfH)
                                    val vp1 = Offset(pts[1].x * scX - halfW, pts[1].y * scY - halfH)
                                    val hdx = vp1.x - vp0.x
                                    val hdy = vp1.y - vp0.y
                                    val hDist = hypot(hdx, hdy)
                                    if (hDist > 0.001f) {
                                        val dirX = hdx / hDist
                                        val dirY = hdy / hDist
                                        val pStart = Offset(vp0.x - dirX * infiniteLen, vp0.y - dirY * infiniteLen)
                                        val pEnd = Offset(vp1.x + dirX * infiniteLen, vp1.y + dirY * infiniteLen)
                                        drawLine(Morandi.accent.copy(alpha = 0.8f), pStart, pEnd, strokeWidth = gStroke.width * 1.5f)
                                    } else {
                                        drawLine(Morandi.accent.copy(alpha = 0.8f), Offset(-infiniteLen, vp0.y), Offset(infiniteLen, vp0.y), strokeWidth = gStroke.width * 1.5f)
                                    }
                                } else if (pts.size == 1) {
                                    val vpy = pts[0].y * scY - halfH
                                    drawLine(Morandi.accent.copy(alpha = 0.5f), Offset(-infiniteLen, vpy), Offset(infiniteLen, vpy), strokeWidth = gStroke.width)
                                }

                                val rayCount = guide.perspectiveRayCount.coerceIn(2, 360)
                                for (vp in pts) {
                                    val vpx = vp.x * scX - halfW
                                    val vpy = vp.y * scY - halfH
                                    val vpOffset = Offset(vpx, vpy)
                                    val extLen = infiniteLen + hypot(vpx, vpy) * 2f
                                    for (ri in 0 until rayCount) {
                                        val angle = (ri.toFloat() / rayCount) * 2f * PI.toFloat()
                                        drawLine(guideCol, vpOffset, vpOffset + Offset(cos(angle) * extLen, sin(angle) * extLen), strokeWidth = gStroke.width)
                                    }
                                    if (vm.drawingGuidePanelOpen) {
                                        drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / (zoom * fitScale), center = vpOffset)
                                    }
                                    drawCircle(Morandi.accent, radius = (if (vm.drawingGuidePanelOpen) 7.dp else 5.dp).toPx() / (zoom * fitScale), center = vpOffset)
                                    drawCircle(Color.White, radius = 3.dp.toPx() / (zoom * fitScale), center = vpOffset)
                                }
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

                            fun drawRotatedRay(dirX: Float, dirY: Float) {
                                val rx = dirX * cosR - dirY * sinR
                                val ry = dirX * sinR + dirY * cosR
                                drawLine(
                                    symCol,
                                    Offset(cx, cy),
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
                                    val rays = guide.symmetryRayCount.coerceIn(
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN,
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX,
                                    )
                                    if (guide.symmetryRotational) {
                                        val step = 2f * PI.toFloat() / rays.toFloat()
                                        for (k in 0 until rays) {
                                            val ang = k * step
                                            drawRotatedRay(sin(ang), cos(ang))
                                        }
                                    } else {
                                        val step = PI.toFloat() / rays.toFloat()
                                        for (k in 0 until rays) {
                                            val ang = k * step
                                            drawRotatedLine(sin(ang), cos(ang))
                                        }
                                    }
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
