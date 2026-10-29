/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 漫画分格工具的纯 Kotlin 几何/数据模型 (无 Android 依赖, 可单测)。
 *
 * 坐标一律使用 [Point2D] (文档像素坐标); 分格多边形的角点按周长顺序存储, 支持 3..12 角凸多边形。
 * 历史命名保留为 [PanelQuad]; 语义已泛化为凸多边形, 凹多边形行为不保证 (与 [PanelQuad.insetBorder]
 * 不做拓扑修复口径一致)。本文件只做几何与元数据, 不含 UI/引擎逻辑。
 */

private const val DEGENERATE_AREA_EPS = 1e-6f // 面积低于此值视为退化 (contains/内缩结果判定)
private const val SIGNED_AREA_EPS = 1e-6f // 整体有向面积近零视为无内外定向
private const val MIN_EDGE_EPS = 1e-6f // 边长近零无法定义法向
private const val PARALLEL_EPS = 1e-9f // 相邻两条内移线近平行, 交点无意义
private const val POINT_ON_LINE_EPS = 1e-4f // 顶点距切线小于此值视为在切线上或切线贴边
private const val HULL_DEDUP_TOL2 = 1e-8f // 凸包输入去重容差平方 (1e-4 * 1e-4)

private fun closeEnough(p: Point2D, q: Point2D, tol2: Float): Boolean {
    val dx = p.x - q.x
    val dy = p.y - q.y
    return dx * dx + dy * dy <= tol2
}

private fun signedAreaOf(pts: List<Point2D>): Float {
    var sum = 0f
    val n = pts.size
    for (i in 0 until n) {
        val a = pts[i]
        val b = pts[(i + 1) % n]
        sum += a.x * b.y - b.x * a.y
    }
    return sum / 2f
}

/** 内移直线 (点向式), [origin] 已沿边法向偏移 width。 */
private class InsetLine(val origin: Point2D, val dir: Point2D)

/** 两条内移直线的交点; 近平行时退回 l2 起点 (已是边起点沿其法向偏移 width 的点)。 */
private fun intersectInset(l1: InsetLine, l2: InsetLine): Point2D {
    val denom = l1.dir.x * l2.dir.y - l1.dir.y * l2.dir.x
    if (abs(denom) < PARALLEL_EPS) return l2.origin
    val t = ((l2.origin.x - l1.origin.x) * l2.dir.y - (l2.origin.y - l1.origin.y) * l2.dir.x) / denom
    return l1.origin + l1.dir * t
}

/** 切弦单边内缩辅助: 仅切弦整条边沿自身内部方向平移 halfGap, 其两端点沿相邻原边界边重新求交。 */
private fun insetHalfCutChord(pts: List<Point2D>, halfGap: Float): List<Point2D>? {
    val k = pts.size
    if (k < PanelQuad.MIN_VERTICES) return null
    val a = pts[k - 1] // 切弦起点
    val b = pts[0] // 切弦终点
    val d = b - a
    val len = hypot(d.x, d.y)
    if (!len.isFinite() || len < MIN_EDGE_EPS) return null
    val s = signedAreaOf(pts)
    if (!s.isFinite() || abs(s) < SIGNED_AREA_EPS) return null
    val norm = if (s > 0f) Point2D(-d.y / len, d.x / len) else Point2D(d.y / len, -d.x / len)
    val chordLine = InsetLine(a + norm * halfGap, d)

    val p = pts[k - 2]
    val prevDir = a - p
    if (hypot(prevDir.x, prevDir.y) < MIN_EDGE_EPS) return null
    val prevLine = InsetLine(a, prevDir)
    val newA = intersectInset(prevLine, chordLine)

    val q = pts[1]
    val nextDir = q - b
    if (hypot(nextDir.x, nextDir.y) < MIN_EDGE_EPS) return null
    val nextLine = InsetLine(b, nextDir)
    val newB = intersectInset(chordLine, nextLine)

    if (!newA.x.isFinite() || !newA.y.isFinite() ||
        !newB.x.isFinite() || !newB.y.isFinite()
    ) return null

    val res = pts.toMutableList()
    res[k - 1] = newA
    res[0] = newB
    return res
}

/** 极简轴对齐包围盒 (纯 JVM, 不引入 Android RectF)。 */
data class PanelBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: Point2D get() = Point2D((left + right) / 2f, (top + bottom) / 2f)

    fun contains(p: Point2D): Boolean = p.x >= left && p.x <= right && p.y >= top && p.y <= bottom
}

/**
 * 分格多边形: 3..12 个角点 (init 校验, 推荐通过工厂 [PanelQuad.of] 构造)。
 *
 * 历史命名保留为 PanelQuad; 语义已泛化为凸多边形 (3..12 角), 角点按周长顺序存储,
 * 凹多边形行为不保证 (与 [insetBorder] 不做拓扑修复口径一致)。
 */
data class PanelQuad(val corners: List<Point2D>) {
    init {
        require(corners.size in MIN_VERTICES..MAX_VERTICES) {
            "PanelQuad 需要 $MIN_VERTICES..$MAX_VERTICES 个角点, 实际 ${corners.size}"
        }
    }

    /** 鞋带公式绝对值 / 2。 */
    fun area(): Float = abs(signedArea())

    /** 面积非有限或低于 [minArea] 视为退化。 */
    fun isDegenerate(minArea: Float): Boolean = !area().isFinite() || area() < minArea

    /** 顶点均值中心 (凹多边形时可能在形外, 仅作手柄/标注锚点用)。 */
    fun centroid(): Point2D {
        var sx = 0f
        var sy = 0f
        for (c in corners) {
            sx += c.x
            sy += c.y
        }
        val n = corners.size.toFloat()
        return Point2D(sx / n, sy / n)
    }

    fun bounds(): PanelBounds {
        var l = corners[0].x
        var t = corners[0].y
        var r = l
        var b = t
        for (i in 1 until corners.size) {
            val c = corners[i]
            if (c.x < l) l = c.x
            if (c.y < t) t = c.y
            if (c.x > r) r = c.x
            if (c.y > b) b = c.y
        }
        return PanelBounds(l, t, r, b)
    }

    /**
     * 射线法点包含测试 (偶奇规则), 支持凹多边形。
     * 面积 < 1e-6 的退化 quad 或含非有限坐标一律 false; 边界点归属不保证。
     */
    fun contains(p: Point2D): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        if (area() < DEGENERATE_AREA_EPS) return false
        var inside = false
        val n = corners.size
        var j = n - 1
        for (i in 0 until n) {
            val a = corners[j]
            val b = corners[i]
            if ((b.y > p.y) != (a.y > p.y) &&
                p.x < (a.x - b.x) * (p.y - b.y) / (a.y - b.y) + b.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 边框内缩: 每条边沿内法向平移 [width], 新角点取相邻两条内移直线的交点 (内外定向由有向面积
     * 决定, 对凸/凹多边形一致)。width<=0、非有限、原 quad 无定向或结果退化/非有限时返回原样
     * (调用方据此判断无法内缩)。深凹多边形大幅内缩可能自交, 本方法不做拓扑修复。
     */
    fun insetBorder(width: Float): PanelQuad {
        if (!width.isFinite() || width <= 0f) return this
        val signed = signedArea()
        if (!signed.isFinite() || abs(signed) < SIGNED_AREA_EPS) return this
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return this
        val n = corners.size
        val lines = ArrayList<InsetLine>(n)
        for (e in 0 until n) {
            val a = corners[e]
            val b = corners[(e + 1) % n]
            val d = b - a
            val len = hypot(d.x, d.y)
            if (len < MIN_EDGE_EPS) return this // 零长边无法定义法向
            val norm = if (signed > 0f) Point2D(-d.y / len, d.x / len) else Point2D(d.y / len, -d.x / len)
            lines.add(InsetLine(a + norm * width, d))
        }
        val out = ArrayList<Point2D>(n)
        for (i in 0 until n) {
            out.add(intersectInset(lines[(i + n - 1) % n], lines[i]))
        }
        val inset = PanelQuad(out)
        if (inset.corners.any { !it.x.isFinite() || !it.y.isFinite() }) return this
        return if (inset.area() < DEGENERATE_AREA_EPS) this else inset
    }

    /** 整体平移 (delta 非有限时原样返回)。 */
    fun movedBy(delta: Point2D): PanelQuad {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return this
        return PanelQuad(corners.map { it + delta })
    }

    /**
     * 查找与 [other] 共享的边: 本格边 i 的两端点与 other 某条边两端点 (允许反向) 全部在
     * [tolerance] 内重合 (距离平方比较, 不开方)。无共享边返回 null。
     */
    fun sharedEdge(other: PanelQuad, tolerance: Float): Int? {
        if (tolerance < 0f || !tolerance.isFinite()) return null
        val tol2 = tolerance * tolerance
        val n = corners.size
        val m = other.corners.size
        for (i in 0 until n) {
            val a0 = corners[i]
            val a1 = corners[(i + 1) % n]
            for (j in 0 until m) {
                val b0 = other.corners[j]
                val b1 = other.corners[(j + 1) % m]
                val forward = closeEnough(a0, b0, tol2) && closeEnough(a1, b1, tol2)
                val reverse = closeEnough(a0, b1, tol2) && closeEnough(a1, b0, tol2)
                if (forward || reverse) return i
            }
        }
        return null
    }

    /**
     * 把边 [edgeIndex] 的两个角点沿 [delta] 平移 (拖动该条边); 结果面积 < minSize² (含翻转为
     * 负有向面积时取绝对值) 或参数非法时返回 null (调用方拒绝本次拖动)。
     */
    fun draggedEdge(edgeIndex: Int, delta: Point2D, minSize: Float): PanelQuad? {
        val n = corners.size
        if (edgeIndex !in 0 until n) return null
        if (!delta.x.isFinite() || !delta.y.isFinite()) return null
        if (!minSize.isFinite() || minSize < 0f) return null
        val moved = corners.toMutableList()
        moved[edgeIndex] = moved[edgeIndex] + delta
        moved[(edgeIndex + 1) % n] = moved[(edgeIndex + 1) % n] + delta
        val result = PanelQuad(moved)
        return if (result.area() < minSize * minSize) null else result
    }

    /** 鞋带公式有向面积 (正值表示逆时针/当前坐标系内部在行进左侧)。 */
    fun signedArea(): Float = signedAreaOf(corners)

    /**
     * 用 [lineStart]→[lineEnd] 所在的无限直线切割本多边形。
     *
     * 求直线与多边形边界的交点: 恰好 2 个才可切; 顶点恰在切线上、切线贴边、交点多于 2、
     * 任一交点非有限时返回 null (宁可不切不错切)。
     * [halfGap] > 0 且有限时, 把每一半的切弦沿各自内部方向平移 halfGap; 任一半角点数 <3 或退化
     * (面积 < DEGENERATE_AREA_EPS) 返回 null。halfGap <= 0 或非有限时不内缩。
     *
     * @return 切割出的两个凸多边形对 (一侧, 另一侧), 失败返回 null。
     */
    fun cutByLine(lineStart: Point2D, lineEnd: Point2D, halfGap: Float): Pair<PanelQuad, PanelQuad>? {
        if (!lineStart.x.isFinite() || !lineStart.y.isFinite() ||
            !lineEnd.x.isFinite() || !lineEnd.y.isFinite()
        ) return null
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        if (area() < DEGENERATE_AREA_EPS) return null

        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val lineLen = hypot(dx, dy)
        if (!lineLen.isFinite() || lineLen < MIN_EDGE_EPS) return null

        val n = corners.size
        val fValues = FloatArray(n)
        for (i in 0 until n) {
            val c = corners[i]
            val f = dx * (c.y - lineStart.y) - dy * (c.x - lineStart.x)
            if (abs(f) / lineLen <= POINT_ON_LINE_EPS) return null
            fValues[i] = f
        }

        class Crossing(val edgeIndex: Int, val point: Point2D)
        val crossings = ArrayList<Crossing>(2)
        for (i in 0 until n) {
            val fA = fValues[i]
            val fB = fValues[(i + 1) % n]
            if ((fA > 0f) != (fB > 0f)) {
                val a = corners[i]
                val b = corners[(i + 1) % n]
                val t = fA / (fA - fB)
                val ip = Point2D(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                if (!ip.x.isFinite() || !ip.y.isFinite()) return null
                crossings.add(Crossing(i, ip))
            }
        }

        if (crossings.size != 2) return null

        val e1 = crossings[0].edgeIndex
        val i1 = crossings[0].point
        val e2 = crossings[1].edgeIndex
        val i2 = crossings[1].point

        // 两个交点把多边形分成两个凸多边形:
        // Half 1: I1 -> corners[e1+1..e2] -> I2
        val corners1 = ArrayList<Point2D>(n + 2)
        corners1.add(i1)
        var cur = (e1 + 1) % n
        while (cur != (e2 + 1) % n) {
            corners1.add(corners[cur])
            cur = (cur + 1) % n
        }
        corners1.add(i2)

        // Half 2: I2 -> corners[e2+1..e1] -> I1
        val corners2 = ArrayList<Point2D>(n + 2)
        corners2.add(i2)
        cur = (e2 + 1) % n
        while (cur != (e1 + 1) % n) {
            corners2.add(corners[cur])
            cur = (cur + 1) % n
        }
        corners2.add(i1)

        if (corners1.size !in MIN_VERTICES..MAX_VERTICES ||
            corners2.size !in MIN_VERTICES..MAX_VERTICES
        ) return null

        val finalCorners1: List<Point2D>
        val finalCorners2: List<Point2D>
        if (halfGap.isFinite() && halfGap > 0f) {
            finalCorners1 = insetHalfCutChord(corners1, halfGap) ?: return null
            finalCorners2 = insetHalfCutChord(corners2, halfGap) ?: return null
        } else {
            finalCorners1 = corners1
            finalCorners2 = corners2
        }

        val q1 = PanelQuad(finalCorners1)
        val q2 = PanelQuad(finalCorners2)
        if (q1.area() < DEGENERATE_AREA_EPS || q2.area() < DEGENERATE_AREA_EPS) return null
        if (q1.signedArea() * signedAreaOf(corners1) <= 0f ||
            q2.signedArea() * signedAreaOf(corners2) <= 0f
        ) return null

        return Pair(q1, q2)
    }

    companion object {
        const val MIN_VERTICES = 3
        const val MAX_VERTICES = 12
        const val CORNER_COUNT = 4

        /** 推荐构造入口: 按 p0→p1→p2→p3 周长顺序给出 4 个角点。 */
        fun of(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D): PanelQuad =
            PanelQuad(listOf(p0, p1, p2, p3))

        /** 构造入口: 按周长顺序给出多边形角点 (3..12 角)。 */
        fun of(points: List<Point2D>): PanelQuad = PanelQuad(points.toList())

        /**
         * 凸包合并: 对输入点集执行 Andrew 单调链凸包算法。
         * 输入去重 (容差 1e-4) 后角点数 <3 或 >MAX_VERTICES、凸包面积 < DEGENERATE_AREA_EPS、
         * 或含非有限点返回 null。返回角点按周长顺序且与 [signedArea] 正方向一致。
         */
        fun hullOf(points: List<Point2D>): PanelQuad? {
            if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null

            // 输入去重 (容差 1e-4)
            val unique = ArrayList<Point2D>()
            for (p in points) {
                if (unique.none { q -> closeEnough(p, q, HULL_DEDUP_TOL2) }) {
                    unique.add(p)
                }
            }
            if (unique.size < MIN_VERTICES || unique.size > MAX_VERTICES) return null

            // Andrew 单调链: x 升序, x 相同时 y 升序
            unique.sortWith { a, b ->
                val cmpX = a.x.compareTo(b.x)
                if (cmpX != 0) cmpX else a.y.compareTo(b.y)
            }

            fun cross(o: Point2D, a: Point2D, b: Point2D): Float =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = ArrayList<Point2D>()
            for (p in unique) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 1e-6f) {
                    lower.removeAt(lower.size - 1)
                }
                lower.add(p)
            }

            val upper = ArrayList<Point2D>()
            for (i in unique.indices.reversed()) {
                val p = unique[i]
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 1e-6f) {
                    upper.removeAt(upper.size - 1)
                }
                upper.add(p)
            }

            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            val hull = ArrayList<Point2D>(lower.size + upper.size)
            hull.addAll(lower)
            hull.addAll(upper)

            if (hull.size < MIN_VERTICES || hull.size > MAX_VERTICES) return null

            val s = signedAreaOf(hull)
            val ordered = if (s < 0f) hull.reversed() else hull
            val quad = PanelQuad(ordered)
            if (quad.area() < DEGENERATE_AREA_EPS) return null
            return quad
        }
    }
}

/** 顶层快捷调用: 对输入点集求凸包多边形, 见 [PanelQuad.hullOf]。 */
fun hullOf(points: List<Point2D>): PanelQuad? = PanelQuad.hullOf(points)

/**
 * 两格联动拖共享边: 对 a 的 [aEdge] 边与 b 的 [bEdge] 边同时应用 [delta] 平移; 任一侧违反
 * minSize (面积过小) 或参数非法则整体返回 null (两格都不动, 避免半更新)。
 */
fun applyEdgeDrag(
    a: PanelQuad,
    b: PanelQuad,
    aEdge: Int,
    bEdge: Int,
    delta: Point2D,
    minSize: Float,
): Pair<PanelQuad, PanelQuad>? {
    val nextA = a.draggedEdge(aEdge, delta, minSize) ?: return null
    val nextB = b.draggedEdge(bEdge, delta, minSize) ?: return null
    return nextA to nextB
}

/**
 * 分格吸附候选。
 *
 * @param delta 吸附平移量 (x 轴候选 delta.y=0, y 轴候选 delta.x=0)
 * @param guideAxis 吸附轴向: 0 = x 轴向吸附 (垂直参考线), 1 = y 轴向吸附 (水平参考线)
 * @param guideCoord 吸附后 moving 对应边的坐标 (供画吸附参考线)
 */
data class PanelSnapCandidate(
    val delta: Point2D,
    val guideAxis: Int, // guideAxis: 0=x 轴向吸附, 1=y
    val guideCoord: Float,
)

private enum class EdgeOrientation { HORIZONTAL, VERTICAL }
private class AlignedEdge(val orientation: EdgeOrientation, val coord: Float)

private fun extractAlignedEdges(quad: PanelQuad): List<AlignedEdge> {
    val edges = ArrayList<AlignedEdge>(quad.corners.size)
    val n = quad.corners.size
    for (i in 0 until n) {
        val a = quad.corners[i]
        val b = quad.corners[(i + 1) % n]
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (hypot(dx, dy) < MIN_EDGE_EPS) continue
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx >= 4f * ady) {
            edges.add(AlignedEdge(EdgeOrientation.HORIZONTAL, (a.y + b.y) / 2f))
        } else if (ady >= 4f * adx) {
            edges.add(AlignedEdge(EdgeOrientation.VERTICAL, (a.x + b.x) / 2f))
        }
    }
    return edges
}

/**
 * gutter 吸附候选生成 (轴对齐简化版)。
 *
 * moving 整体平移某个 delta 后, 其某条近轴对齐边 (|边向量 dx| >= 4|dy| 视为水平边,
 * |dy| >= 4|dx| 视为垂直边; 其余斜边不产候选) 与某邻格对应方向的边的间隙等于 [defaultGap],
 * 或两边共线 (间隙 0), 即得到一个候选 delta。
 * 每对 (moving 边, 邻格边) 最多产 1 个候选 (gap 吸附与共线吸附取其一, 按与零位移更近者);
 * 吸附分量绝对值 > [tolerance] 的丢弃。
 */
fun snapCandidates(
    moving: PanelQuad,
    neighbors: List<PanelQuad>,
    defaultGap: Float,
    tolerance: Float,
): List<PanelSnapCandidate> {
    if (!tolerance.isFinite() || tolerance < 0f) return emptyList()
    if (!defaultGap.isFinite()) return emptyList()
    if (moving.corners.any { !it.x.isFinite() || !it.y.isFinite() }) return emptyList()
    if (moving.area() < DEGENERATE_AREA_EPS) return emptyList()

    val movingEdges = extractAlignedEdges(moving)
    if (movingEdges.isEmpty()) return emptyList()

    val candidates = ArrayList<PanelSnapCandidate>()

    for (neighbor in neighbors) {
        if (neighbor === moving) continue
        if (neighbor.corners.any { !it.x.isFinite() || !it.y.isFinite() }) continue
        if (neighbor.area() < DEGENERATE_AREA_EPS) continue

        val nbrEdges = extractAlignedEdges(neighbor)
        if (nbrEdges.isEmpty()) continue

        for (em in movingEdges) {
            for (en in nbrEdges) {
                if (em.orientation != en.orientation) continue
                val coordM = em.coord
                val coordN = en.coord

                var bestD = Float.MAX_VALUE
                var bestAbs = Float.MAX_VALUE

                fun consider(target: Float) {
                    val d = target - coordM
                    val ad = abs(d)
                    if (ad < bestAbs) {
                        bestAbs = ad
                        bestD = d
                    }
                }

                // 共线吸附 (间隙 0)
                consider(coordN)
                // gap 吸附
                if (defaultGap > 0f) {
                    consider(coordN + defaultGap)
                    consider(coordN - defaultGap)
                }

                if (bestAbs <= tolerance) {
                    if (em.orientation == EdgeOrientation.VERTICAL) {
                        candidates.add(
                            PanelSnapCandidate(
                                delta = Point2D(bestD, 0f),
                                guideAxis = 0,
                                guideCoord = coordM + bestD,
                            )
                        )
                    } else {
                        candidates.add(
                            PanelSnapCandidate(
                                delta = Point2D(0f, bestD),
                                guideAxis = 1,
                                guideCoord = coordM + bestD,
                            )
                        )
                    }
                }
            }
        }
    }

    return candidates.distinct()
}

/** 分格样式; 可空字段 = 跟随页面默认 [PanelDefaults]。 */
data class PanelStyle(
    val borderWidth: Float? = null,
    val borderColor: Int? = null, // ARGB
    val fillColor: Int? = null, // ARGB
    val fillEnabled: Boolean? = null,
)

/** 页面级分格默认值 (hGap/vGap 同时用于新建格相对页面边缘的内缩)。 */
data class PanelDefaults(
    val borderWidth: Float,
    val borderColor: Int,
    val hGap: Float,
    val vGap: Float,
    val fillColor: Int,
    val fillEnabled: Boolean,
)

/** [PanelStyle.resolve] 的结果: 样式四元组全部非空。 */
data class ResolvedPanelStyle(
    val borderWidth: Float,
    val borderColor: Int,
    val fillColor: Int,
    val fillEnabled: Boolean,
)

/** 解析实际生效样式: [PanelStyle] 的 null 字段取页面默认。 */
fun PanelStyle.resolve(d: PanelDefaults): ResolvedPanelStyle = ResolvedPanelStyle(
    borderWidth = borderWidth ?: d.borderWidth,
    borderColor = borderColor ?: d.borderColor,
    fillColor = fillColor ?: d.fillColor,
    fillEnabled = fillEnabled ?: d.fillEnabled,
)

/**
 * 分格元数据 (持久化用)。编解码为手写极简 JSON (仅数字/布尔/嵌套容器, 无字符串值),
 * 与 [parsePanelMeta] 配对使用。
 */
data class PanelMeta(
    val quad: PanelQuad,
    val style: PanelStyle,
    val cropEnabled: Boolean,
    val panelId: Long,
)

/**
 * 序列化为紧凑 JSON: `{"panelId":L,"cropEnabled":b,"quad":{"corners":[[x,y]xN]},"style":{...}}`,
 * style 只写出非空字段。角点为非有限值时产出的文本无法被 [parsePanelMeta] 读回 (返回 null)。
 */
fun PanelMeta.toJson(): String {
    val sb = StringBuilder(256)
    sb.append("{\"panelId\":").append(panelId)
    sb.append(",\"cropEnabled\":").append(cropEnabled)
    sb.append(",\"quad\":{\"corners\":[")
    quad.corners.forEachIndexed { i, c ->
        if (i > 0) sb.append(',')
        sb.append('[').append(c.x).append(',').append(c.y).append(']')
    }
    sb.append("]}")
    sb.append(",\"style\":{")
    var first = true
    fun field(name: String, value: Any?) {
        if (value == null) return
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(name).append("\":").append(value)
    }
    field("borderWidth", style.borderWidth)
    field("borderColor", style.borderColor)
    field("fillColor", style.fillColor)
    field("fillEnabled", style.fillEnabled)
    sb.append("}}")
    return sb.toString()
}

/**
 * 从 JSON 反序列化 [PanelMeta] (只容忍本文件 [PanelMeta.toJson] 的产出结构 + 任意空白与
 * 未知字段的额外容错)。
 *
 * 宽严约定: 必需字段 (panelId/cropEnabled/quad 3..12 角坐标) 缺失、类型不符或非有限 → 返回 null;
 * style 整体缺失视为全默认 ([PanelStyle] 全 null), style 内单字段缺失/类型不符视为该字段 null
 * (跟随页面默认)。不处理字符串转义 (本模型不含字符串值)。
 */
fun parsePanelMeta(json: String): PanelMeta? {
    val root = PanelJsonParser.parse(json) as? PanelJsonObject ?: return null
    val panelId = (root.fields["panelId"] as? PanelJsonNumber)?.raw?.toLongOrNull() ?: return null
    val cropEnabled = (root.fields["cropEnabled"] as? PanelJsonBoolean)?.value ?: return null
    val quadObj = root.fields["quad"] as? PanelJsonObject ?: return null
    val cornersArr = quadObj.fields["corners"] as? PanelJsonArray ?: return null
    if (cornersArr.items.size !in PanelQuad.MIN_VERTICES..PanelQuad.MAX_VERTICES) return null
    val corners = ArrayList<Point2D>(cornersArr.items.size)
    for (item in cornersArr.items) {
        val pair = item as? PanelJsonArray ?: return null
        if (pair.items.size != 2) return null
        val x = (pair.items[0] as? PanelJsonNumber)?.raw?.toFloatOrNull() ?: return null
        val y = (pair.items[1] as? PanelJsonNumber)?.raw?.toFloatOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        corners.add(Point2D(x, y))
    }
    val styleObj = root.fields["style"] as? PanelJsonObject
    fun numText(key: String): String? = (styleObj?.fields?.get(key) as? PanelJsonNumber)?.raw
    val style = PanelStyle(
        borderWidth = numText("borderWidth")?.toFloatOrNull()?.takeIf { it.isFinite() },
        borderColor = numText("borderColor")?.toIntOrNull(),
        fillColor = numText("fillColor")?.toIntOrNull(),
        fillEnabled = (styleObj?.fields?.get("fillEnabled") as? PanelJsonBoolean)?.value,
    )
    return PanelMeta(PanelQuad(corners), style, cropEnabled, panelId)
}

/** 手写极简 JSON 值模型 (仅覆盖分格元数据用到的类型)。 */
private sealed interface PanelJsonNode

private class PanelJsonObject(val fields: MutableMap<String, PanelJsonNode>) : PanelJsonNode

private class PanelJsonArray(val items: MutableList<PanelJsonNode>) : PanelJsonNode

private class PanelJsonNumber(val raw: String) : PanelJsonNode

private class PanelJsonBoolean(val value: Boolean) : PanelJsonNode

private class PanelJsonString(val value: String) : PanelJsonNode

private object PanelJsonNull : PanelJsonNode

/** 递归下降极简 JSON 解析器: 对象/数组/数字/布尔/null, 字符串不支持转义 (本模型不含字符串值)。 */
private object PanelJsonParser {
    private const val MAX_DEPTH = 32

    fun parse(text: String): PanelJsonNode? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0) ?: return null
        p.skipWs()
        return if (p.atEnd()) v else null // 根值后仍有内容视为非法
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): PanelJsonNode? {
            if (depth > MAX_DEPTH) return null
            skipWs()
            if (atEnd()) return null
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()?.let { PanelJsonString(it) }
                else -> literal()
            }
        }

        fun obj(depth: Int): PanelJsonNode? {
            i++
            val fields = LinkedHashMap<String, PanelJsonNode>()
            skipWs()
            if (!atEnd() && s[i] == '}') {
                i++
                return PanelJsonObject(fields)
            }
            while (true) {
                skipWs()
                val key = string() ?: return null
                skipWs()
                if (atEnd() || s[i] != ':') return null
                i++
                val v = value(depth + 1) ?: return null
                fields[key] = v
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return PanelJsonObject(fields)
                    }
                    else -> return null
                }
            }
        }

        fun arr(depth: Int): PanelJsonNode? {
            i++
            val items = ArrayList<PanelJsonNode>()
            skipWs()
            if (!atEnd() && s[i] == ']') {
                i++
                return PanelJsonArray(items)
            }
            while (true) {
                val v = value(depth + 1) ?: return null
                items.add(v)
                skipWs()
                if (atEnd()) return null
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return PanelJsonArray(items)
                    }
                    else -> return null
                }
            }
        }

        fun string(): String? {
            skipWs()
            if (atEnd() || s[i] != '"') return null
            i++
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    val v = s.substring(start, i)
                    i++
                    return v
                }
                if (c == '\\' || c == '\n' || c == '\r') return null
                i++
            }
            return null
        }

        fun literal(): PanelJsonNode? {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace()) i++
            if (i == start) return null
            return when (val tok = s.substring(start, i)) {
                "true" -> PanelJsonBoolean(true)
                "false" -> PanelJsonBoolean(false)
                "null" -> PanelJsonNull
                else -> if (tok.all { it.isDigit() || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' }) {
                    PanelJsonNumber(tok)
                } else {
                    null
                }
            }
        }
    }
}

/** 分格命中结果 (供手势层分发)。 */
sealed class PanelHit {
    object None : PanelHit()
    data class Body(val panelIndex: Int) : PanelHit()
    data class Handle(val panelIndex: Int, val corner: Int) : PanelHit()
}

/**
 * 分格命中测试。优先级: 手柄 (仅 selectedIndex>=0 时检测选中格角点, 命中半径 handleRadius,
 * 取最近角点) > 格体 (从列表末尾向前找第一个包含点者, 即最上层) > [PanelHit.None]。
 * selectedIndex 越界时跳过手柄检测; handleRadius<0 时同样跳过。
 */
fun hitTestPanels(quads: List<PanelQuad>, selectedIndex: Int, p: Point2D, handleRadius: Float): PanelHit {
    if (!p.x.isFinite() || !p.y.isFinite()) return PanelHit.None
    if (selectedIndex in quads.indices && handleRadius.isFinite() && handleRadius >= 0f) {
        val quad = quads[selectedIndex]
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (c in quad.corners.indices) {
            val corner = quad.corners[c]
            val dx = p.x - corner.x
            val dy = p.y - corner.y
            val d2 = dx * dx + dy * dy
            if (d2 <= handleRadius * handleRadius && d2 < bestD2) {
                best = c
                bestD2 = d2
            }
        }
        if (best >= 0) return PanelHit.Handle(selectedIndex, best)
    }
    for (i in quads.indices.reversed()) {
        if (quads[i].contains(p)) return PanelHit.Body(i)
    }
    return PanelHit.None
}

/**
 * 新建格辅助: 把拖出的矩形四边按默认间隔内缩 (水平 [hGap] / 垂直 [vGap]) 后转成四角 quad,
 * 角点顺序 TL→TR→BR→BL。间隔过大致左右/上下翻转时按公式原样输出, 由调用方用
 * [PanelQuad.isDegenerate] 或边界检查拦截。
 */
fun insetForDefaultGaps(rect: PanelBounds, hGap: Float, vGap: Float): PanelQuad = PanelQuad.of(
    Point2D(rect.left + hGap, rect.top + vGap),
    Point2D(rect.right - hGap, rect.top + vGap),
    Point2D(rect.right - hGap, rect.bottom - vGap),
    Point2D(rect.left + hGap, rect.bottom - vGap),
)
