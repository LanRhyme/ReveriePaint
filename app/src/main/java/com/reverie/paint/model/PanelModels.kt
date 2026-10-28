/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 漫画分格工具的纯 Kotlin 几何/数据模型 (无 Android 依赖, 可单测)。
 *
 * 坐标一律使用 [Point2D] (文档像素坐标); 分格四边形的角点按周长顺序 p0→p1→p2→p3→p0 存储,
 * 支持任意凸/凹四边形。本文件只做几何与元数据, 不含 UI/引擎逻辑。
 */

private const val DEGENERATE_AREA_EPS = 1e-6f // 面积低于此值视为退化 (contains/内缩结果判定)
private const val SIGNED_AREA_EPS = 1e-6f // 整体有向面积近零视为无内外定向
private const val MIN_EDGE_EPS = 1e-6f // 边长近零无法定义法向
private const val PARALLEL_EPS = 1e-9f // 相邻两条内移线近平行, 交点无意义

/** 极简轴对齐包围盒 (纯 JVM, 不引入 Android RectF)。 */
data class PanelBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: Point2D get() = Point2D((left + right) / 2f, (top + bottom) / 2f)

    fun contains(p: Point2D): Boolean = p.x >= left && p.x <= right && p.y >= top && p.y <= bottom
}

/**
 * 分格四边形: 恰好 4 个角点 (init 校验, 推荐通过工厂 [PanelQuad.of] 构造)。
 */
data class PanelQuad(val corners: List<Point2D>) {
    init {
        require(corners.size == CORNER_COUNT) { "PanelQuad 需要恰好 4 个角点, 实际 ${corners.size}" }
    }

    /** 鞋带公式绝对值 / 2。 */
    fun area(): Float = abs(signedArea())

    /** 面积非有限或低于 [minArea] 视为退化。 */
    fun isDegenerate(minArea: Float): Boolean = !area().isFinite() || area() < minArea

    /** 顶点均值中心 (凹四边形时可能在形外, 仅作手柄/标注锚点用)。 */
    fun centroid(): Point2D = Point2D(
        (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4f,
        (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4f,
    )

    fun bounds(): PanelBounds {
        var l = corners[0].x
        var t = corners[0].y
        var r = l
        var b = t
        for (i in 1 until CORNER_COUNT) {
            val c = corners[i]
            if (c.x < l) l = c.x
            if (c.y < t) t = c.y
            if (c.x > r) r = c.x
            if (c.y > b) b = c.y
        }
        return PanelBounds(l, t, r, b)
    }

    /**
     * 射线法点包含测试 (偶奇规则), 支持凹四边形。
     * 面积 < 1e-6 的退化 quad 或含非有限坐标一律 false; 边界点归属不保证。
     */
    fun contains(p: Point2D): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        if (area() < DEGENERATE_AREA_EPS) return false
        var inside = false
        var j = CORNER_COUNT - 1
        for (i in 0 until CORNER_COUNT) {
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
     * 决定, 对凸/凹四边形一致)。width<=0、非有限、原 quad 无定向或结果退化/非有限时返回原样
     * (调用方据此判断无法内缩)。深凹四边形大幅内缩可能自交, 本方法不做拓扑修复。
     */
    fun insetBorder(width: Float): PanelQuad {
        if (!width.isFinite() || width <= 0f) return this
        val signed = signedArea()
        if (!signed.isFinite() || abs(signed) < SIGNED_AREA_EPS) return this
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return this
        // 每条边的内移直线: 原点 = 边起点 + width*内法向, 方向 = 原边方向
        val lines = ArrayList<InsetLine>(CORNER_COUNT)
        for (e in 0 until CORNER_COUNT) {
            val a = corners[e]
            val b = corners[(e + 1) % CORNER_COUNT]
            val d = b - a
            val len = hypot(d.x, d.y)
            if (len < MIN_EDGE_EPS) return this // 零长边无法定义法向
            // 有向面积为正时内部在行进方向左侧 (左法向), 否则在右侧
            val n = if (signed > 0f) Point2D(-d.y / len, d.x / len) else Point2D(d.y / len, -d.x / len)
            lines.add(InsetLine(a + n * width, d))
        }
        val out = ArrayList<Point2D>(CORNER_COUNT)
        for (i in 0 until CORNER_COUNT) {
            // 角点 i = 边 (i-1) 与边 i 的两条内移直线的交点; 平行时取边 i 起点的法向偏移兜底
            out.add(intersectInset(lines[(i + CORNER_COUNT - 1) % CORNER_COUNT], lines[i]))
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
        for (i in 0 until CORNER_COUNT) {
            val a0 = corners[i]
            val a1 = corners[(i + 1) % CORNER_COUNT]
            for (j in 0 until CORNER_COUNT) {
                val b0 = other.corners[j]
                val b1 = other.corners[(j + 1) % CORNER_COUNT]
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
        if (edgeIndex !in 0 until CORNER_COUNT) return null
        if (!delta.x.isFinite() || !delta.y.isFinite()) return null
        if (!minSize.isFinite() || minSize < 0f) return null
        val moved = corners.toMutableList()
        moved[edgeIndex] = moved[edgeIndex] + delta
        moved[(edgeIndex + 1) % CORNER_COUNT] = moved[(edgeIndex + 1) % CORNER_COUNT] + delta
        val result = PanelQuad(moved)
        return if (result.area() < minSize * minSize) null else result
    }

    private fun signedArea(): Float {
        var sum = 0f
        for (i in 0 until CORNER_COUNT) {
            val a = corners[i]
            val b = corners[(i + 1) % CORNER_COUNT]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    /** 两条内移直线的交点; 近平行时退回 l2 起点 (已是边起点沿其法向偏移 width 的点)。 */
    private fun intersectInset(l1: InsetLine, l2: InsetLine): Point2D {
        val denom = l1.dir.x * l2.dir.y - l1.dir.y * l2.dir.x
        if (abs(denom) < PARALLEL_EPS) return l2.origin
        val t = ((l2.origin.x - l1.origin.x) * l2.dir.y - (l2.origin.y - l1.origin.y) * l2.dir.x) / denom
        return l1.origin + l1.dir * t
    }

    /** 内移直线 (点向式), [origin] 已沿边法向偏移 width。 */
    private class InsetLine(val origin: Point2D, val dir: Point2D)

    companion object {
        const val CORNER_COUNT = 4

        /** 推荐构造入口: 按 p0→p1→p2→p3 周长顺序给出 4 个角点。 */
        fun of(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D): PanelQuad =
            PanelQuad(listOf(p0, p1, p2, p3))

        private fun closeEnough(p: Point2D, q: Point2D, tol2: Float): Boolean {
            val dx = p.x - q.x
            val dy = p.y - q.y
            return dx * dx + dy * dy <= tol2
        }
    }
}

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
 * 序列化为紧凑 JSON: `{"panelId":L,"cropEnabled":b,"quad":{"corners":[[x,y]x4]},"style":{...}}`,
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
 * 宽严约定: 必需字段 (panelId/cropEnabled/quad 四角坐标) 缺失、类型不符或非有限 → 返回 null;
 * style 整体缺失视为全默认 ([PanelStyle] 全 null), style 内单字段缺失/类型不符视为该字段 null
 * (跟随页面默认)。不处理字符串转义 (本模型不含字符串值)。
 */
fun parsePanelMeta(json: String): PanelMeta? {
    val root = PanelJsonParser.parse(json) as? PanelJsonObject ?: return null
    val panelId = (root.fields["panelId"] as? PanelJsonNumber)?.raw?.toLongOrNull() ?: return null
    val cropEnabled = (root.fields["cropEnabled"] as? PanelJsonBoolean)?.value ?: return null
    val quadObj = root.fields["quad"] as? PanelJsonObject ?: return null
    val cornersArr = quadObj.fields["corners"] as? PanelJsonArray ?: return null
    if (cornersArr.items.size != PanelQuad.CORNER_COUNT) return null
    val corners = ArrayList<Point2D>(PanelQuad.CORNER_COUNT)
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
 * 分格命中测试。优先级: 手柄 (仅 selectedIndex>=0 时检测选中格 4 角, 命中半径 handleRadius,
 * 取最近角点) > 格体 (从列表末尾向前找第一个包含点者, 即最上层) > [PanelHit.None]。
 * selectedIndex 越界时跳过手柄检测; handleRadius<0 时同样跳过。
 */
fun hitTestPanels(quads: List<PanelQuad>, selectedIndex: Int, p: Point2D, handleRadius: Float): PanelHit {
    if (!p.x.isFinite() || !p.y.isFinite()) return PanelHit.None
    if (selectedIndex in quads.indices && handleRadius.isFinite() && handleRadius >= 0f) {
        val quad = quads[selectedIndex]
        var best = -1
        var bestD2 = Float.MAX_VALUE
        for (c in 0 until PanelQuad.CORNER_COUNT) {
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
