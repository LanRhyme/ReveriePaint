/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.*

/**
 * 绘图参考线与辅助模式
 */
enum class GuideMode(val title: String) {
    OFF("关闭"),
    GRID_2D("2D 网格"),
    ISOMETRIC("等距网格"),
    PERSPECTIVE("透视参考"),
    SYMMETRY("对称镜像"),
}

/**
 * 对称镜像模式
 */
enum class SymmetryType(val title: String) {
    VERTICAL("垂直对称"),
    HORIZONTAL("水平对称"),
    QUADRANT("四分象限"),
    RADIAL("径向(8瓣)"),
}

/**
 * QuickShape 识别图元类型
 */
enum class QuickShapeType(val title: String) {
    NONE("无"),
    LINE("直线"),
    ARC("圆弧"),
    CIRCLE("正圆"),
    ELLIPSE("椭圆"),
    RECTANGLE("矩形"),
    TRIANGLE("三角形"),
}

/**
 * 纯 Kotlin 2D 坐标点，隔离 Android 框架依赖以支持单元测试
 */
data class Point2D(val x: Float, val y: Float) {
    fun distanceTo(other: Point2D): Float = hypot(x - other.x, y - other.y)
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Float): Point2D = Point2D(x * scalar, y * scalar)
    operator fun div(scalar: Float): Point2D = Point2D(x / scalar, y / scalar)
}

/**
 * QuickShape 几何拟合结果
 */
data class QuickShapeResult(
    val type: QuickShapeType,
    val points: List<Point2D>,
    val center: Point2D = Point2D(0f, 0f),
    val radiusX: Float = 0f,
    val radiusY: Float = 0f,
    val rotationRad: Float = 0f,
)

/**
 * 绘图参考线配置
 */
data class DrawingGuideConfig(
    val mode: GuideMode = GuideMode.OFF,
    val assistedDrawing: Boolean = true,
    val gridSize: Float = 48f,
    val opacity: Float = 0.5f,
    val colorHex: String = "#88A0B0",
    val symmetryType: SymmetryType = SymmetryType.VERTICAL,
    val symmetryCenterX: Float = 0.5f, // 归一化画布相对坐标
    val symmetryCenterY: Float = 0.5f,
    val perspectiveVanishingPoints: List<Point2D> = emptyList(), // 1~3 点透视点 (画布物理坐标)
)

/**
 * 画布内富文本排版配置
 */
data class TypographyConfig(
    val text: String = "点击编辑文字",
    val fontFamilyName: String = "系统默认",
    val fontSize: Float = 48f,
    val letterSpacingSp: Float = 0f, // Kerning / 字间距
    val lineHeightMultiplier: Float = 1.2f, // Leading / 行间距倍数
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isAllCaps: Boolean = false,
    val alignment: Int = 0, // 0: 左对齐, 1: 居中, 2: 右对齐
    val textColor: String = "#FFFFFF",
    val posX: Float = 100f,
    val posY: Float = 100f,
    val boxWidth: Float = 400f,
    val rotationDeg: Float = 0f,
)

/**
 * QuickShape 算法引擎：基于离散采样点的高精度几何图元拟合
 */
object QuickShapeFitter {

    private const val MIN_POINTS = 6
    private const val MIN_PATH_LENGTH = 30f

    fun fit(rawPoints: List<Point2D>): QuickShapeResult? {
        if (rawPoints.size < MIN_POINTS) return null

        // 1. 计算总弧长与端点距离
        var totalLength = 0f
        for (i in 1 until rawPoints.size) {
            totalLength += rawPoints[i - 1].distanceTo(rawPoints[i])
        }
        if (totalLength < MIN_PATH_LENGTH) return null

        val start = rawPoints.first()
        val end = rawPoints.last()
        val directDist = start.distanceTo(end)
        val linearity = directDist / totalLength

        // 2. 直线拟合判定 (端点距离与总路径长度之比 >= 0.88)
        if (linearity >= 0.88f) {
            val snappedEnd = snapAngle(start, end)
            return QuickShapeResult(
                type = QuickShapeType.LINE,
                points = listOf(start, snappedEnd),
                center = Point2D((start.x + snappedEnd.x) / 2f, (start.y + snappedEnd.y) / 2f),
            )
        }

        // 3. 闭合路径判定
        val isClosed = (directDist / totalLength < 0.22f) || directDist < 45f

        // 计算几何质心与包围盒
        var sumX = 0f
        var sumY = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in rawPoints) {
            sumX += p.x
            sumY += p.y
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
        }
        val center = Point2D(sumX / rawPoints.size, sumY / rawPoints.size)
        val boxW = maxX - minX
        val boxH = maxY - minY
        val aspect = if (boxH > 0.001f) boxW / boxH else 1f

        if (isClosed) {
            // 计算到中心的径向距离均值与方差
            var meanR = 0f
            val radList = FloatArray(rawPoints.size)
            for (i in rawPoints.indices) {
                val r = rawPoints[i].distanceTo(center)
                radList[i] = r
                meanR += r
            }
            meanR /= rawPoints.size

            var varR = 0f
            for (r in radList) {
                val diff = r - meanR
                varR += diff * diff
            }
            val stdR = sqrt(varR / rawPoints.size)
            val coeffVariation = if (meanR > 0.001f) stdR / meanR else 1f
            // 计算多边形面积 (Shoelace formula) 与周长以得到圆度商 (Circularity Quotient)
            var shoelace = 0f
            for (i in rawPoints.indices) {
                val j = (i + 1) % rawPoints.size
                shoelace += (rawPoints[i].x * rawPoints[j].y - rawPoints[j].x * rawPoints[i].y)
            }
            val area = abs(shoelace) / 2f
            val circularity = if (totalLength > 1f) (4f * PI.toFloat() * area) / (totalLength * totalLength) else 0f

            // 3.1 正圆判定 (圆度商 Q >= 0.86 且长宽比接近 1)
            if (circularity >= 0.86f && aspect in 0.80f..1.25f && coeffVariation < 0.18f) {
                val r = (boxW + boxH) / 4f
                return QuickShapeResult(
                    type = QuickShapeType.CIRCLE,
                    points = listOf(
                        Point2D(center.x, center.y - r),
                        Point2D(center.x + r, center.y),
                        Point2D(center.x, center.y + r),
                        Point2D(center.x - r, center.y),
                    ),
                    center = center,
                    radiusX = r,
                    radiusY = r,
                )
            }

            // 3.2 椭圆判定 (圆度商较高但长宽比偏离 1)
            if (circularity in 0.70f..0.95f && (aspect < 0.80f || aspect > 1.25f) && coeffVariation < 0.32f) {
                val rx = boxW / 2f
                val ry = boxH / 2f
                return QuickShapeResult(
                    type = QuickShapeType.ELLIPSE,
                    points = listOf(
                        Point2D(center.x, center.y - ry),
                        Point2D(center.x + rx, center.y),
                        Point2D(center.x, center.y + ry),
                        Point2D(center.x - rx, center.y),
                    ),
                    center = center,
                    radiusX = rx,
                    radiusY = ry,
                )
            }

            // 3.3 拐点多边形化 (三角形 / 矩形识别)
            val simplified = rdpSimplify(rawPoints, epsilon = totalLength * 0.05f)
            val cornerCount = simplified.size - 1

            if (cornerCount == 3) {
                return QuickShapeResult(
                    type = QuickShapeType.TRIANGLE,
                    points = simplified.take(3),
                    center = center,
                )
            } else if (cornerCount == 4 || circularity < 0.84f) {
                return QuickShapeResult(
                    type = QuickShapeType.RECTANGLE,
                    points = listOf(
                        Point2D(minX, minY),
                        Point2D(maxX, minY),
                        Point2D(maxX, maxY),
                        Point2D(minX, maxY),
                    ),
                    center = center,
                    radiusX = boxW / 2f,
                    radiusY = boxH / 2f,
                )
            }
        } else {
            // 4. 开曲线：光滑圆弧拟合 (计算外接圆并插值 32 个均匀采样点)
            if (linearity in 0.35f..0.88f) {
                val midIndex = rawPoints.size / 2
                val apex = rawPoints[midIndex]
                val a = start
                val b = apex
                val c = end
                val d = 2f * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
                if (abs(d) > 0.001f) {
                    val ux = ((a.x * a.x + a.y * a.y) * (b.y - c.y) + (b.x * b.x + b.y * b.y) * (c.y - a.y) + (c.x * c.x + c.y * c.y) * (a.y - b.y)) / d
                    val uy = ((a.x * a.x + a.y * a.y) * (c.x - b.x) + (b.x * b.x + b.y * b.y) * (a.x - c.x) + (c.x * c.x + c.y * c.y) * (b.x - a.x)) / d
                    val center = Point2D(ux, uy)
                    val r = hypot(a.x - ux, a.y - uy)
                    if (r in 5f..15000f) {
                        val angA = atan2(a.y - uy, a.x - ux)
                        val angB = atan2(b.y - uy, b.x - ux)
                        val angC = atan2(c.y - uy, c.x - ux)
                        var span = angC - angA
                        while (span > PI.toFloat()) span -= 2f * PI.toFloat()
                        while (span < -PI.toFloat()) span += 2f * PI.toFloat()
                        var apexOffset = angB - angA
                        while (apexOffset > PI.toFloat()) apexOffset -= 2f * PI.toFloat()
                        while (apexOffset < -PI.toFloat()) apexOffset += 2f * PI.toFloat()
                        if ((span > 0 && apexOffset < 0) || (span < 0 && apexOffset > 0)) {
                            span = if (span > 0) span - 2f * PI.toFloat() else span + 2f * PI.toFloat()
                        }
                        val arcPoints = ArrayList<Point2D>(33)
                        val segments = 32
                        for (i in 0..segments) {
                            val t = i.toFloat() / segments
                            val ang = angA + span * t
                            arcPoints.add(Point2D(ux + r * cos(ang), uy + r * sin(ang)))
                        }
                        return QuickShapeResult(
                            type = QuickShapeType.ARC,
                            points = arcPoints,
                            center = center,
                            radiusX = r,
                            radiusY = r,
                        )
                    }
                }
            }
        }

        // 默认回退为平滑折线/直线
        return QuickShapeResult(
            type = QuickShapeType.LINE,
            points = listOf(start, end),
            center = Point2D((start.x + end.x) / 2f, (start.y + end.y) / 2f),
        )
    }

    private fun snapAngle(start: Point2D, end: Point2D): Point2D {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dist = hypot(dx, dy)
        if (dist < 1f) return end

        var angleDeg = (atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f
        val snapTargets = floatArrayOf(0f, 30f, 45f, 60f, 90f, 120f, 135f, 150f, 180f, 210f, 225f, 240f, 270f, 300f, 315f, 330f, 360f)
        val snapTolerance = 5.0f

        for (target in snapTargets) {
            if (abs(angleDeg - target) <= snapTolerance || abs(angleDeg - (target - 360f)) <= snapTolerance) {
                val rad = target * PI.toFloat() / 180f
                return Point2D(start.x + dist * cos(rad), start.y + dist * sin(rad))
            }
        }
        return end
    }

    /**
     * Ramer-Douglas-Peucker 轨迹点多边形化简化
     */
    private fun rdpSimplify(points: List<Point2D>, epsilon: Float): List<Point2D> {
        if (points.size <= 2) return points

        var maxDist = 0f
        var maxIndex = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], start, end)
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }

        return if (maxDist > epsilon) {
            val left = rdpSimplify(points.subList(0, maxIndex + 1), epsilon)
            val right = rdpSimplify(points.subList(maxIndex, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(pt: Point2D, lineStart: Point2D, lineEnd: Point2D): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val lineLen = hypot(dx, dy)
        if (lineLen < 0.0001f) return pt.distanceTo(lineStart)
        return abs(dy * pt.x - dx * pt.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x) / lineLen
    }
}

/**
 * 智能色板提取器：中位切分法 (Median Cut) 与感知去重色彩聚类算法
 */
object ColorQuantizer {

    private data class VBox(
        var rMin: Int, var rMax: Int,
        var gMin: Int, var gMax: Int,
        var bMin: Int, var bMax: Int,
        val pixels: MutableList<Int>,
    ) {
        val volume: Int get() = (rMax - rMin + 1) * (gMax - gMin + 1) * (bMax - bMin + 1)
        val count: Int get() = pixels.size

        fun updateBounds() {
            if (pixels.isEmpty()) return
            rMin = 255; rMax = 0
            gMin = 255; gMax = 0
            bMin = 255; bMax = 0
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                rMin = minOf(rMin, r); rMax = maxOf(rMax, r)
                gMin = minOf(gMin, g); gMax = maxOf(gMax, g)
                bMin = minOf(bMin, b); bMax = maxOf(bMax, b)
            }
        }

        fun averageColor(): Int {
            if (pixels.isEmpty()) return 0
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (p in pixels) {
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }
            val n = pixels.size
            return ((sumR / n).toInt() shl 16) or ((sumG / n).toInt() shl 8) or (sumB / n).toInt()
        }
    }

    /**
     * 从像素数组提取 30 个具有高感知表现力的主题色
     */
    fun extractPalette(pixels: IntArray, targetCount: Int = 30): List<String> {
        val validPixels = ArrayList<Int>(minOf(pixels.size, 10000))
        val step = maxOf(1, pixels.size / 10000)
        for (i in pixels.indices step step) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            if (a >= 64) {
                validPixels.add(p and 0xFFFFFF)
            }
        }
        if (validPixels.isEmpty()) return emptyList()

        val initialBox = VBox(0, 255, 0, 255, 0, 255, validPixels).apply { updateBounds() }
        val boxes = java.util.PriorityQueue<VBox>(targetCount) { a, b ->
            b.count.compareTo(a.count)
        }
        boxes.add(initialBox)

        while (boxes.size < targetCount) {
            val box = boxes.poll() ?: break
            if (box.count <= 1 || box.volume <= 1) {
                boxes.add(box)
                break
            }

            val rRange = box.rMax - box.rMin
            val gRange = box.gMax - box.gMin
            val bRange = box.bMax - box.bMin
            val maxRange = maxOf(rRange, gRange, bRange)

            // 按最长轴排序并从中位处分割
            when (maxRange) {
                rRange -> box.pixels.sortBy { (it shr 16) and 0xFF }
                gRange -> box.pixels.sortBy { (it shr 8) and 0xFF }
                else -> box.pixels.sortBy { it and 0xFF }
            }

            val mid = box.pixels.size / 2
            val p1 = box.pixels.subList(0, mid).toMutableList()
            val p2 = box.pixels.subList(mid, box.pixels.size).toMutableList()

            val box1 = VBox(0, 255, 0, 255, 0, 255, p1).apply { updateBounds() }
            val box2 = VBox(0, 255, 0, 255, 0, 255, p2).apply { updateBounds() }

            boxes.add(box1)
            boxes.add(box2)
        }

        // 聚类均值提炼与色彩去重
        val rawColors = boxes.map { it.averageColor() }
        val distinctColors = mutableListOf<Int>()

        for (c in rawColors) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            var isDistinct = true
            for (exist in distinctColors) {
                val er = (exist shr 16) and 0xFF
                val eg = (exist shr 8) and 0xFF
                val eb = exist and 0xFF
                // 欧几里得感知色差阈值
                val dist = (r - er) * (r - er) + (g - eg) * (g - eg) + (b - eb) * (b - eb)
                if (dist < 220) { // 过于接近的颜色进行合并过滤
                    isDistinct = false
                    break
                }
            }
            if (isDistinct) {
                distinctColors.add(c)
            }
        }

        // 按色相 (Hue) 与明度 (Luminance) 排序，呈现 Procreate 式渐变美感色板
        return distinctColors.sortedBy { c ->
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            var h = 0f
            if (delta > 0.0001f) {
                h = when (max) {
                    r -> ((g - b) / delta) % 6f
                    g -> (b - r) / delta + 2f
                    else -> (r - g) / delta + 4f
                } * 60f
                if (h < 0f) h += 360f
            }
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            h * 10f + luma
        }.map { String.format("#%06X", it and 0xFFFFFF) }
    }
}
