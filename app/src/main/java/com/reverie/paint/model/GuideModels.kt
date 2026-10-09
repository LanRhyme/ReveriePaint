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
    CONTOUR("轮廓"),
    QUADRILATERAL("四边形"),
    CURVE("曲线"),
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
 * 对称绘制镜像笔画采样点
 */
data class SymStrokeSample(val x: Float, val y: Float, val pressure: Double = 1.0)

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
    val arcSweepRad: Float = 0f,
    val contourCurved: Boolean = false,
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
    val symmetryRotationDeg: Float = 0f, // 对称轴旋转角度 (度数)
    val perspectiveVanishingPoints: List<Point2D> = emptyList(), // 1~3 点透视点 (画布物理坐标)
    val perspectiveRayCount: Int = 12, // 透视灭点放射线密度 (6~24)
) {
    /**
     * 计算输入点对应的对称镜像分支点列表 (支持对称轴任意角度旋转)
     */
    fun computeSymmetricPoints(docPt: Point2D, docWidth: Int, docHeight: Int): List<Point2D> {
        if (mode != GuideMode.SYMMETRY) return emptyList()
        if (!docPt.x.isFinite() || !docPt.y.isFinite()) return emptyList()
        val scX = symmetryCenterX.takeIf { it.isFinite() }?.coerceIn(0.01f, 0.99f) ?: 0.5f
        val scY = symmetryCenterY.takeIf { it.isFinite() }?.coerceIn(0.01f, 0.99f) ?: 0.5f
        val cx = docWidth * scX
        val cy = docHeight * scY

        val rotRad = (symmetryRotationDeg % 360f) * (PI.toFloat() / 180f)
        val cosR = cos(rotRad)
        val sinR = sin(rotRad)

        // 旋转至对称轴局部对齐坐标系 (绕中心点逆时针旋转 -rotRad)
        val dx = docPt.x - cx
        val dy = docPt.y - cy
        val lx = dx * cosR + dy * sinR
        val ly = -dx * sinR + dy * cosR

        val localBranches = when (symmetryType) {
            SymmetryType.VERTICAL -> listOf(Point2D(-lx, ly))
            SymmetryType.HORIZONTAL -> listOf(Point2D(lx, -ly))
            SymmetryType.QUADRANT -> listOf(
                Point2D(-lx, ly),
                Point2D(lx, -ly),
                Point2D(-lx, -ly),
            )
            SymmetryType.RADIAL -> {
                val r = hypot(lx, ly)
                val baseAngle = atan2(ly, lx)
                val branches = ArrayList<Point2D>(7)
                for (k in 1..7) {
                    val ang = baseAngle + k * (2f * PI.toFloat() / 8f)
                    branches.add(Point2D(r * cos(ang), r * sin(ang)))
                }
                branches
            }
        }

        // 旋转回文档坐标系 (绕中心点顺时针旋转 +rotRad)
        return localBranches.map { lp ->
            val wx = cx + (lp.x * cosR - lp.y * sinR)
            val wy = cy + (lp.x * sinR + lp.y * cosR)
            Point2D(wx, wy)
        }.filter { it.x.isFinite() && it.y.isFinite() }
    }
}

/**
 * 画布内富文本排版配置
 */
data class TypographyConfig(
    val text: String = "点击编辑文字",
    val fontFamilyName: String = "bundled:lxgw_wenkai",
    val fontPath: String? = null,
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
    val snapEnabled: Boolean = true,
    val isVertical: Boolean = false,
    val verticalRtl: Boolean = true, // 竖排换列方向: true 为从右向左 (传统中文), false 为从左向右
)

/**
 * QuickShape 算法引擎：基于离散采样点的高精度几何图元拟合
 */
object QuickShapeFitter {
    fun fit(rawPoints: List<Point2D>, recognizeArcs: Boolean = false, relaxed: Boolean = false,
            recognizeQuadrilaterals: Boolean = false, recognizeCurves: Boolean = false): QuickShapeResult? =
        QuickShapeRecognition.fit(rawPoints, recognizeArcs, relaxed, recognizeQuadrilaterals, recognizeCurves)
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
