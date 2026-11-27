/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.model.CanvasDecorGeometry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AssistedDrawingGeometry
import com.reverie.paint.model.FisheyeGuideGeometryCache
import com.reverie.paint.model.GuideMode
import com.reverie.paint.model.DrawingGuideHandleGeometry
import com.reverie.paint.model.PERSPECTIVE_RAY_COUNT_MAX
import com.reverie.paint.model.PERSPECTIVE_RAY_COUNT_MIN
import com.reverie.paint.model.Perspective3DMath
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.Point3D
import com.reverie.paint.model.SymmetryType
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private fun DrawScope.drawFisheyeCurves(
    curves: List<FloatArray>,
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    strokeWidth: Float,
) {
    for (curve in curves) {
        var i = 0
        while (i + 3 < curve.size) {
            drawLine(
                color = color,
                start = Offset(centerX + curve[i] * radius, centerY + curve[i + 1] * radius),
                end = Offset(centerX + curve[i + 2] * radius, centerY + curve[i + 3] * radius),
                strokeWidth = strokeWidth,
            )
            i += 2
        }
    }
}

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
                            val scX = docW / maxOf(1, vm.docWidth)
                            val scY = docH / maxOf(1, vm.docHeight)
                            val step = (guide.gridSize * scX).coerceAtLeast(16f)
                            val originDoc = DrawingGuideHandleGeometry.gridOriginDocPos(vm.docWidth, vm.docHeight, guide)
                            val ox = originDoc.x * scX - halfW
                            val oy = originDoc.y * scY - halfH
                            val maxRadius = hypot(docW, docH) + step
                            val axes = AssistedDrawingGeometry.grid2dAxes(guide.gridRotationDeg)
                            val u = axes[0]
                            val v = axes[1]
                            val count = ceil(maxRadius / step).toInt().coerceAtMost(300)
                            clipRect(left = -halfW, top = -halfH, right = halfW, bottom = halfH) {
                                for (k in -count..count) {
                                    val shift = k * step
                                    val ux = ox + u.x * shift
                                    val uy = oy + u.y * shift
                                    drawLine(
                                        guideCol,
                                        Offset(ux - v.x * maxRadius, uy - v.y * maxRadius),
                                        Offset(ux + v.x * maxRadius, uy + v.y * maxRadius),
                                        strokeWidth = gStroke.width,
                                    )
                                    val vx = ox + v.x * shift
                                    val vy = oy + v.y * shift
                                    drawLine(
                                        guideCol,
                                        Offset(vx - u.x * maxRadius, vy - u.y * maxRadius),
                                        Offset(vx + u.x * maxRadius, vy + u.y * maxRadius),
                                        strokeWidth = gStroke.width,
                                    )
                                }
                            }
                            if (vm.drawingGuidePanelOpen) {
                                val currentScale = (zoom * fitScale).coerceAtLeast(0.001f)
                                val originCenter = Offset(ox, oy)
                                val rotDoc = DrawingGuideHandleGeometry.gridRotationHandleDocPos(vm.docWidth, vm.docHeight, guide)
                                val rotCenter = Offset(rotDoc.x * scX - halfW, rotDoc.y * scY - halfH)
                                drawLine(Morandi.accent.copy(alpha = 0.45f), originCenter, rotCenter, strokeWidth = gStroke.width * 1.3f)
                                drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Morandi.accent, radius = 6.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Color.White, radius = 2.5.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Morandi.accent.copy(alpha = 0.25f), radius = 13.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 7.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Color.White, radius = 3.5.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 1.5.dp.toPx() / currentScale, center = rotCenter)
                            }
                        }
                        GuideMode.ISOMETRIC -> {
                            val scX = docW / maxOf(1, vm.docWidth)
                            val scY = docH / maxOf(1, vm.docHeight)
                            val step = (guide.gridSize * scX).coerceAtLeast(24f)
                            val perpStep = step * AssistedDrawingGeometry.ISO_COS_30
                            val originDoc = DrawingGuideHandleGeometry.gridOriginDocPos(vm.docWidth, vm.docHeight, guide)
                            val ox = originDoc.x * scX - halfW
                            val oy = originDoc.y * scY - halfH
                            val maxRadius = hypot(docW, docH) + step
                            val axes = AssistedDrawingGeometry.isometricAxes(guide.gridRotationDeg)
                            val count = ceil(maxRadius / perpStep).toInt().coerceAtMost(240)
                            clipRect(left = -halfW, top = -halfH, right = halfW, bottom = halfH) {
                                for (axis in axes) {
                                    val nx = -axis.y
                                    val ny = axis.x
                                    for (k in -count..count) {
                                        val px = ox + nx * (k * perpStep)
                                        val py = oy + ny * (k * perpStep)
                                        drawLine(
                                            guideCol,
                                            Offset(px - axis.x * maxRadius, py - axis.y * maxRadius),
                                            Offset(px + axis.x * maxRadius, py + axis.y * maxRadius),
                                            strokeWidth = gStroke.width,
                                        )
                                    }
                                }
                            }
                            if (vm.drawingGuidePanelOpen) {
                                val currentScale = (zoom * fitScale).coerceAtLeast(0.001f)
                                val originCenter = Offset(ox, oy)
                                val rotDoc = DrawingGuideHandleGeometry.gridRotationHandleDocPos(vm.docWidth, vm.docHeight, guide)
                                val rotCenter = Offset(rotDoc.x * scX - halfW, rotDoc.y * scY - halfH)
                                drawLine(Morandi.accent.copy(alpha = 0.45f), originCenter, rotCenter, strokeWidth = gStroke.width * 1.3f)
                                drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Morandi.accent, radius = 6.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Color.White, radius = 2.5.dp.toPx() / currentScale, center = originCenter)
                                drawCircle(Morandi.accent.copy(alpha = 0.25f), radius = 13.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 7.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Color.White, radius = 3.5.dp.toPx() / currentScale, center = rotCenter)
                                drawCircle(Morandi.accent, radius = 1.5.dp.toPx() / currentScale, center = rotCenter)
                            }
                        }
                        GuideMode.PERSPECTIVE -> {
                            if (guide.perspectiveFisheye) {
                                // 180° 鱼眼网格: 两组从四个主消失点出发的球面方向曲线,
                                // 不构造有限房间、不设 near/far, 边界 z=0 由数学模型直接投影。
                                clipRect(left = -halfW, top = -halfH, right = halfW, bottom = halfH) {
                                    val centerX = guide.fisheyeCenterX * docW - halfW
                                    val centerY = guide.fisheyeCenterY * docH - halfH
                                    val radius = guide.fisheyeRadius.coerceIn(0.02f, 1.5f) * minOf(docW, docH)
                                    if (radius > 2f) {
                                        val screenRadius = radius * zoom * fitScale
                                        val mesh = FisheyeGuideGeometryCache.get(
                                            guide.fisheyeHorizontalCount,
                                            guide.fisheyeVerticalCount,
                                            screenRadius,
                                        )
                                        drawFisheyeCurves(
                                            mesh.horizontal,
                                            centerX, centerY, radius, guideCol, gStroke.width,
                                        )
                                        drawFisheyeCurves(
                                            mesh.vertical,
                                            centerX, centerY, radius, guideCol, gStroke.width,
                                        )
                                        run {
                                            val rayCount = guide.fisheyeRayCount.coerceIn(4, 48)
                                            val rayColor = guideCol.copy(alpha = guideCol.alpha * 0.48f)
                                            // 直径曲线已从显示中移除, 水平/垂直方向的直线只由射线提供,
                                            // 因此这里不再跳过 0/90/180/270 度的射线。
                                            for (i in 0 until rayCount) {
                                                val angle = (2.0 * PI * i / rayCount).toFloat()
                                                drawLine(
                                                    color = rayColor,
                                                    start = Offset(centerX, centerY),
                                                    end = Offset(centerX + cos(angle) * radius, centerY + sin(angle) * radius),
                                                    strokeWidth = gStroke.width,
                                                )
                                            }
                                        }
                                        // 边界圆只画一次; 它不是横/纵曲线数量的一部分。
                                        drawCircle(
                                            color = guideCol.copy(alpha = (guideCol.alpha * 1.5f).coerceAtMost(1f)),
                                            radius = radius,
                                            center = Offset(centerX, centerY),
                                            style = Stroke(width = gStroke.width * 1.6f),
                                        )
                                        // 手柄: 球心圆点 / 边界控制点
                                        drawCircle(Morandi.accent, radius = 4.dp.toPx(), center = Offset(centerX, centerY))
                                        drawCircle(
                                            Morandi.accent,
                                            radius = 4.dp.toPx(),
                                            center = Offset(centerX + radius, centerY),
                                        )
                                        drawCircle(
                                            Morandi.accent.copy(alpha = 0.35f),
                                            radius = 8.dp.toPx(),
                                            center = Offset(centerX + radius, centerY),
                                            style = Stroke(width = 1.dp.toPx()),
                                        )
                                    }
                                }
                            } else if (guide.perspective3d) {
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

                                    // 3. 3D 轨道旋转控制柄 (仅面板展开时绘制, 与 CanvasTouchView 共享几何坐标)
                                    if (vm.drawingGuidePanelOpen) {
                                        val scX = bmp.width.toFloat() / maxOf(1, vm.docWidth)
                                        val scY = bmp.height.toFloat() / maxOf(1, vm.docHeight)
                                        val orbitDoc = DrawingGuideHandleGeometry.perspective3dOrbitHandleDocPos(
                                            vm.docWidth,
                                            vm.docHeight,
                                            guide,
                                        )
                                        val orbitCenter = Offset(orbitDoc.x * scX - halfW, orbitDoc.y * scY - halfH)
                                        val currentScale = zoom * fitScale
                                        drawCircle(Morandi.accent.copy(alpha = 0.30f), radius = 12.dp.toPx() / currentScale, center = orbitCenter)
                                        drawCircle(Morandi.accent, radius = 6.5.dp.toPx() / currentScale, center = orbitCenter)
                                        drawCircle(Color.White, radius = 3.dp.toPx() / currentScale, center = orbitCenter)
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
                                SymmetryType.ROTATIONAL -> {
                                    val rays = guide.symmetryRayCount.coerceIn(
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN,
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX,
                                    )
                                    val step = 2f * PI.toFloat() / rays.toFloat()
                                    for (k in 0 until rays) {
                                        val ang = k * step
                                        drawRotatedRay(sin(ang), cos(ang))
                                    }
                                }
                                SymmetryType.KALEIDOSCOPE -> {
                                    val rays = guide.symmetryRayCount.coerceIn(
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN,
                                        com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX,
                                    )
                                    val step = PI.toFloat() / rays.toFloat()
                                    for (k in 0 until rays) {
                                        val ang = k * step
                                        drawRotatedLine(sin(ang), cos(ang))
                                    }
                                }
                            }

                            if (vm.drawingGuidePanelOpen) {
                                // 1. 中心平移控制柄
                                drawCircle(Morandi.accent.copy(alpha = 0.35f), radius = 12.dp.toPx() / currentScale, center = Offset(cx, cy))
                                drawCircle(Morandi.accent, radius = 6.dp.toPx() / currentScale, center = Offset(cx, cy))
                                drawCircle(Color.White, radius = 2.5.dp.toPx() / currentScale, center = Offset(cx, cy))

                                // 2. 轴向旋转控制柄 (沿对称主轴分布)
                                val rotDoc = DrawingGuideHandleGeometry.symmetryRotationHandleDocPos(
                                    vm.docWidth,
                                    vm.docHeight,
                                    guide,
                                )
                                val rotCenter = Offset(rotDoc.x * scX - halfW, rotDoc.y * scY - halfH)

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
    animProgress: Float = 1f,
    modePulseProgress: Float = 0f,
    sampleLayersMode: Int = 1,
    modeLabel: String = "",
    loupeTextPaint: android.graphics.Paint? = null,
) {
    if (animProgress <= 0.001f) return

    val alpha = animProgress.coerceIn(0f, 1f)
    val baseScale = (0.55f + 0.45f * animProgress).coerceAtLeast(0f)
    val modePulseScale = 1f + 0.09f * modePulseProgress.coerceIn(0f, 1f)
    val loupeScale = baseScale * modePulseScale
    val crosshairScale = animProgress.coerceIn(0f, 1f)

    val outerRadius = 45.dp.toPx()
    val innerRadius = 28.dp.toPx()
    val ringThickness = outerRadius - innerRadius
    val ringRadius = (outerRadius + innerRadius) / 2f
    val margin = outerRadius + 8.dp.toPx()

    val isLoupeBelow = pickerScreenPos.y - 80.dp.toPx() - outerRadius < 12.dp.toPx()
    val offsetY = if (isLoupeBelow) {
        80.dp.toPx()
    } else {
        -80.dp.toPx()
    }
    val rawLoupeCenter = pickerScreenPos + Offset(0f, offsetY)
    val clampedX = rawLoupeCenter.x.coerceIn(margin, size.width - margin)
    val clampedY = rawLoupeCenter.y.coerceIn(margin, size.height - margin)
    val loupeCenter = Offset(clampedX, clampedY)

    // 1. 模式切换脉冲光圈波纹 (向外扩散淡出)
    if (modePulseProgress > 0.001f) {
        val rippleDist = 18.dp.toPx() * (1f - modePulseProgress)
        val rippleAlpha = modePulseProgress * 0.45f * alpha
        drawCircle(
            color = Color.White.copy(alpha = rippleAlpha),
            radius = outerRadius + rippleDist,
            center = loupeCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
    }

    withTransform({
        scale(scaleX = loupeScale, scaleY = loupeScale, pivot = loupeCenter)
    }) {
        // 0. Ambient Luminous Aura (动态自发光光谱柔晕: 提取当前采样色的柔光外溢)
        val effectiveAuraColor = if (pickerCurrentColor.alpha > 0.05f) {
            pickerCurrentColor
        } else {
            Color.White
        }
        val auraGlowRadius = outerRadius + 14.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.Transparent,
                (innerRadius / auraGlowRadius).coerceIn(0f, 0.7f) to effectiveAuraColor.copy(alpha = 0.32f * alpha),
                (outerRadius / auraGlowRadius).coerceIn(0f, 0.88f) to effectiveAuraColor.copy(alpha = 0.16f * alpha),
                1.0f to Color.Transparent,
                center = loupeCenter,
                radius = auraGlowRadius,
            ),
            radius = auraGlowRadius,
            center = loupeCenter,
        )

        // Outer drop shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f * alpha),
            radius = outerRadius + 4.dp.toPx(),
            center = loupeCenter
        )

        // Top half ring: Reference / Previous color
        drawArc(
            color = pickerInitialColor.copy(alpha = pickerInitialColor.alpha * alpha),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
            size = Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(width = ringThickness)
        )

        // Bottom half ring: Current sampled color
        if (pickerCurrentColor.alpha > 0.02f) {
            drawArc(
                color = pickerCurrentColor.copy(alpha = pickerCurrentColor.alpha * alpha),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = ringThickness)
            )
        } else {
            // Transparent indication (e.g. current layer has no pixels here)
            drawArc(
                color = Color.Gray.copy(alpha = 0.35f * alpha),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = ringThickness)
            )
        }

        // Crystal glass rim sheen (外环顶部与下部的水晶微光反射光弧)
        drawArc(
            color = Color.White.copy(alpha = 0.40f * alpha),
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(loupeCenter.x - outerRadius, loupeCenter.y - outerRadius),
            size = Size(outerRadius * 2, outerRadius * 2),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.25f * alpha),
            startAngle = 40f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(loupeCenter.x - innerRadius, loupeCenter.y - innerRadius),
            size = Size(innerRadius * 2, innerRadius * 2),
            style = Stroke(width = 1.0.dp.toPx()),
        )

        // Outer border line
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f * alpha),
            radius = outerRadius,
            center = loupeCenter,
            style = Stroke(width = 1.5.dp.toPx())
        )
        // Inner border line
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f * alpha),
            radius = innerRadius,
            center = loupeCenter,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Center crosshair inside the loupe
        val crosshairInner = 6.dp.toPx()
        drawLine(
            color = Color.Black.copy(alpha = 0.7f * alpha),
            start = Offset(loupeCenter.x - crosshairInner, loupeCenter.y),
            end = Offset(loupeCenter.x + crosshairInner, loupeCenter.y),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.7f * alpha),
            start = Offset(loupeCenter.x, loupeCenter.y - crosshairInner),
            end = Offset(loupeCenter.x, loupeCenter.y + crosshairInner),
            strokeWidth = 1.5.dp.toPx()
        )
    }

    // 2. 悬浮吸色模式胶囊标签 (紧随放大镜外侧，带微弹动效)
    if (modeLabel.isNotEmpty() && loupeTextPaint != null) {
        val textWidth = loupeTextPaint.measureText(modeLabel)
        val badgeW = (textWidth + 34.dp.toPx()).coerceAtLeast(76.dp.toPx())
        val badgeH = 22.dp.toPx()
        val badgeCenterY = if (isLoupeBelow) {
            loupeCenter.y + outerRadius + 16.dp.toPx()
        } else {
            loupeCenter.y - outerRadius - 16.dp.toPx()
        }
        val clampedBadgeX = loupeCenter.x.coerceIn(badgeW / 2 + 8.dp.toPx(), size.width - badgeW / 2 - 8.dp.toPx())
        val clampedBadgeY = badgeCenterY.coerceIn(badgeH / 2 + 8.dp.toPx(), size.height - badgeH / 2 - 8.dp.toPx())
        val badgeCenter = Offset(clampedBadgeX, clampedBadgeY)
        val badgeScale = baseScale * (1f + 0.12f * modePulseProgress.coerceIn(0f, 1f))

        withTransform({
            scale(scaleX = badgeScale, scaleY = badgeScale, pivot = badgeCenter)
        }) {
            // 胶囊背景阴影与毛玻璃底色
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.35f * alpha),
                topLeft = Offset(badgeCenter.x - badgeW / 2 - 1.dp.toPx(), badgeCenter.y - badgeH / 2 - 1.dp.toPx()),
                size = Size(badgeW + 2.dp.toPx(), badgeH + 2.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(badgeH / 2 + 1.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xEE222224).copy(alpha = 0.92f * alpha),
                topLeft = Offset(badgeCenter.x - badgeW / 2, badgeCenter.y - badgeH / 2),
                size = Size(badgeW, badgeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(badgeH / 2),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f * alpha),
                topLeft = Offset(badgeCenter.x - badgeW / 2, badgeCenter.y - badgeH / 2),
                size = Size(badgeW, badgeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(badgeH / 2),
                style = Stroke(width = 1.dp.toPx()),
            )

            // 图层模式微型指示图标: 当前图层为单层圆角矩形，全部图层为双层叠放矩形
            val iconX = badgeCenter.x - badgeW / 2 + 12.dp.toPx()
            val iconY = badgeCenter.y
            val iconW = 9.dp.toPx()
            val iconH = 6.dp.toPx()
            if (sampleLayersMode == 0) {
                // 单图层 (当前图层)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f * alpha),
                    topLeft = Offset(iconX - iconW / 2, iconY - iconH / 2),
                    size = Size(iconW, iconH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            } else {
                // 多图层 (全部图层)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f * alpha),
                    topLeft = Offset(iconX - iconW / 2 - 1.5.dp.toPx(), iconY - iconH / 2 - 1.5.dp.toPx()),
                    size = Size(iconW, iconH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    style = Stroke(width = 1f.dp.toPx()),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f * alpha),
                    topLeft = Offset(iconX - iconW / 2 + 1.5.dp.toPx(), iconY - iconH / 2 + 1.5.dp.toPx()),
                    size = Size(iconW, iconH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            // 模式文本文字 (居中偏右，避让图标)
            loupeTextPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            val fontMetrics = loupeTextPaint.fontMetrics
            val textBaselineY = badgeCenter.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
            val textCenterX = badgeCenter.x - badgeW / 2 + 20.dp.toPx() + textWidth / 2f
            drawContext.canvas.nativeCanvas.drawText(
                modeLabel,
                textCenterX,
                textBaselineY,
                loupeTextPaint,
            )
        }
    }

    // Crosshair at the target touch point on the canvas
    withTransform({
        scale(scaleX = crosshairScale, scaleY = crosshairScale, pivot = pickerScreenPos)
    }) {
        val crossLen = 14.dp.toPx()
        drawLine(
            color = Color.Black.copy(alpha = 0.5f * alpha),
            start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
            end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
            strokeWidth = 3.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
            end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.5f * alpha),
            start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
            end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
            strokeWidth = 3.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
            end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}
