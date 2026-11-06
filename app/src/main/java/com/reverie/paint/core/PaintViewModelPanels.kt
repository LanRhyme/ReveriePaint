/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reverie.paint.R
import com.reverie.paint.model.LayerNodeType
import com.reverie.paint.model.PanelDefaults
import com.reverie.paint.model.PanelMeta
import com.reverie.paint.model.PanelQuad
import com.reverie.paint.model.PanelStyle
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.RecordingEvents
import com.reverie.paint.model.ResolvedPanelStyle
import com.reverie.paint.model.parsePanelMeta
import com.reverie.paint.model.resolve
import com.reverie.paint.model.segmentCutsQuad
import com.reverie.paint.model.toJson
import kotlin.math.roundToInt

/**
 * 漫画分格工具会话层 (ViewModel partial)。
 *
 * 文档结构 (与 C++ 底座对齐): 根下「分格」组 → 每格一个组, 组内自底向上为「格内填充」/
 * 「内容」/「格边框」; 裁剪蒙版是分格组的同级上方兄弟 (白 quad=透光, 其余透明=遮挡),
 * 裁剪开关 = 蒙版层可见性; 格元数据 (PanelMeta) 存分格组的 reverie_panel 侧表。
 * 跨格容器 = 根组顶部「跨格内容」组 + 其上方蒙版 (白色 quad = 选中格并集), 侧表标记
 * {"crossContent":true} 识别。
 *
 * 索引是镜像: 结构变化会让索引漂移, 引擎侧操作在 runCore 内用 [scanEnginePanels] 按
 * panelId 重定位, 主线程判定只用 UI 镜像 ([refreshPanels] 同步重建)。
 *
 * 录制协议: 结构增删复用既有 L_* 码; 元数据 + 像素重绘统一用 [RecordingEvents.L_PANEL_META]。
 */

// ---- 约定常量 ----

/** 根组名 (首个分格时创建; 回放端 L_ADD_GROUP 不携带名字, 仅影响回放树里的显示名)。 */
private const val ROOT_GROUP_NAME = "分格"
private const val FILL_LAYER_NAME = "格内填充"
private const val CONTENT_LAYER_NAME = "内容"
private const val BORDER_LAYER_NAME = "格边框"
private const val CROSS_GROUP_NAME = "跨格内容"
private const val CROSS_LAYER_NAME = "跨格内容"
/** 跨格容器侧表标记 (写在 reverie_panel 键下; parsePanelMeta 解析失败 → 不算分格)。 */
private const val CROSS_MARKER_JSON = "{\"crossContent\":true}"
private const val CROSS_MARKER_KEY = "\"crossContent\""
private const val MASK_TYPE_TRANSPARENCY = 0
/** 蒙版语义: 透明=遮挡, 白=透光 (稀疏蒙版, 黑底与透明在调制语义下等价) */
private const val MASK_SHOW_ARGB = 0xFFFFFFFF.toInt()
/** 新建格的最小面积 (px²), 拖得太小直接忽略。 */
private const val MIN_PANEL_AREA = 64f

/** 页面级分格默认值 (工程未存 panelDefaults 时的兜底)。 */
internal val DEFAULT_PANEL_DEFAULTS = PanelDefaults(
    borderWidth = 4f,
    borderColor = 0xFF000000.toInt(),
    hGap = 12f,
    vGap = 12f,
    fillColor = 0xFFFFFFFF.toInt(),
    fillEnabled = false,
    cornerRadius = 0f,
    cropEnabled = true,
)

// ---- 状态 (顶层 internal 扩展属性) ----

/** 一个分格的镜像; 索引字段是快照时刻的视图, 结构变化后由 [refreshPanels] 重建。 */
internal data class PanelMirror(
    val panelId: Long,
    val groupId: Long,
    val groupIdx: Int,
    val maskIdx: Int,
    val maskId: Long,
    val fillIdx: Int,
    val contentIdx: Int,
    val borderIdx: Int,
    val meta: PanelMeta,
)

internal var PaintViewModel.panels: List<PanelMirror> by mutableStateOf(emptyList())

internal var PaintViewModel.selectedPanelId: Long? by mutableStateOf(null)

internal var PaintViewModel.panelMultiSelectedIds: Set<Long> by mutableStateOf(emptySet())

/**
 * 切分模式: true = 画布任意位置起笔拖线即切分 (端点吸附边框); false = 既有手势。切离
 * Tool.PANEL 时由 CanvasTouchView 复位。
 */
internal var PaintViewModel.panelDivideMode: Boolean by mutableStateOf(false)

/** 进入/退出切分模式 (UI 唯一入口); 不清空选中: 切分中隐藏四角手柄, 轻点不改变选中。 */
// @JvmName: 与 panelDivideMode 属性 setter 的 JVM 签名 (setPanelDivideMode) 冲突, 仅改 JVM 名
@JvmName("enterPanelDivideMode")
internal fun PaintViewModel.setPanelDivideMode(on: Boolean) {
    panelDivideMode = on
}

internal var PaintViewModel.panelDefaults: PanelDefaults by mutableStateOf(DEFAULT_PANEL_DEFAULTS)

/** 跨格容器组镜像索引 (-1 = 不存在)。 */
internal var PaintViewModel.panelCrossContainerIdx: Int by mutableIntStateOf(-1)

/** 「分格」根组镜像索引 (-1 = 不存在); 主线程判定分格树归属专用, 免 JNI 重扫。 */
internal var PaintViewModel.panelRootGroupIdx: Int by mutableIntStateOf(-1)

/** 跨格内容层镜像索引/节点 id (-1 = 不存在)。 */
internal var PaintViewModel.panelCrossContentIdx: Int by mutableIntStateOf(-1)
private var PaintViewModel.panelCrossContentId: Long by mutableLongStateOf(-1L)

/** 跨格容器附属蒙版节点 id (-1 = 不存在); 分格蒙版行识别用 (见 panelCropStateFor)。 */
internal var PaintViewModel.panelCrossMaskId: Long by mutableLongStateOf(-1L)

/** 共用画容器。key 不重要，按 drawId 查。索引会漂，这里只存节点 id。 */
internal var PaintViewModel.panelShareGroups: List<DrawShareGroup> by mutableStateOf(emptyList())

/** 本会话已应用过默认折叠的组名; 每会话每名只折叠一次, 用户手动 toggle 后不再改写。 */
private val panelDefaultCollapseNames = mutableSetOf<String>()

/** 文档切换/新建时清空分格组默认折叠会话标记 (PaintViewModelProjects 调用点)。 */
internal fun PaintViewModel.resetPanelDefaultCollapse() {
    panelDefaultCollapseNames.clear()
}

/** 已画进跨格蒙版的 quad 集 (去重绘; 空列表 + 容器存在 = 需要重画)。 */
private var PaintViewModel.panelCrossMaskStamp: List<PanelQuad> by mutableStateOf(emptyList())

/** 当前工具是否分格工具 (UI 下一步接 "panel" 工具 id)。 */
internal fun PaintViewModel.panelSessionActive(): Boolean = currentToolId == "panel"

// ---- 引擎侧扫描 (render thread / main thread 通用) ----

/** 一次引擎侧扫描的结果: 分格镜像 + 根组/跨格容器定位。 */
/** 同一张画的隐藏容器。内容层不在任何一个格子组里，蒙版是这些格子的并集。 */
internal class DrawShareGroup(
    val drawId: Long,
    val groupId: Long,
    val maskId: Long,
    val contentId: Long,
)

internal class PanelEngineScan(
    val layers: List<SnapshotLayer>,
    val rootIdx: Int,
    val crossIdx: Int,
    val crossMaskIdx: Int,
    val crossContentIdx: Int,
    val mirrors: List<PanelMirror>,
    val shareGroups: List<DrawShareGroup> = emptyList(),
    val defaults: PanelDefaults? = null,
)

/** 页面默认值解析: 从根组 reverie_panel 属性读取并反序列化。 */
internal fun parsePanelDefaults(json: String?): PanelDefaults? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = org.json.JSONObject(json)
        PanelDefaults(
            borderWidth = obj.optDouble("borderWidth", DEFAULT_PANEL_DEFAULTS.borderWidth.toDouble()).toFloat(),
            borderColor = obj.optInt("borderColor", DEFAULT_PANEL_DEFAULTS.borderColor),
            hGap = obj.optDouble("hGap", DEFAULT_PANEL_DEFAULTS.hGap.toDouble()).toFloat(),
            vGap = obj.optDouble("vGap", DEFAULT_PANEL_DEFAULTS.vGap.toDouble()).toFloat(),
            fillColor = obj.optInt("fillColor", DEFAULT_PANEL_DEFAULTS.fillColor),
            fillEnabled = obj.optBoolean("fillEnabled", DEFAULT_PANEL_DEFAULTS.fillEnabled),
            cornerRadius = obj.optDouble("cornerRadius", DEFAULT_PANEL_DEFAULTS.cornerRadius.toDouble()).toFloat(),
            cropEnabled = obj.optBoolean("cropEnabled", DEFAULT_PANEL_DEFAULTS.cropEnabled),
        )
    }.getOrNull()
}

/**
 * 从图层快照平铺列表扫描分格结构。平铺顺序 = 树遍历 (自底向上), 组的子树占连续区段;
 * 附属透明蒙版 (PaintLayer + 侧表 flag) 紧跟在子树末尾, 其深度被 C++ 侧刻意 +1。
 */
internal fun scanPanelsFrom(ls: List<SnapshotLayer>): PanelEngineScan {
    val mirrors = ArrayList<PanelMirror>()
    val shares = ArrayList<DrawShareGroup>()
    var cross = -1
    var crossMask = -1
    var crossContent = -1
    for (i in ls.indices) {
        val e = ls[i]
        if (!e.isGroup) continue
        val json = ReverieCoreBridge.panelMeta(e.index) ?: continue
        val meta = parsePanelMeta(json) ?: run {
            // 非分格元数据: 跨格容器
            if (json.contains(CROSS_MARKER_KEY)) {
                cross = i
                crossMask = trailingMaskIdxOf(ls, i)
                crossContent = firstDirectChildIdxOf(ls, i)
            }
            null
        } ?: continue
        var end = i
        while (end + 1 < ls.size && ls[end + 1].depth > e.depth) end++
        var childEnd = end
        // 末尾连续的透明蒙版是分格组自己附属的裁剪蒙版
        while (childEnd > i && ls[childEnd].nodeType == LayerNodeType.TRANSPARENCY_MASK) childEnd--
        val maskIdx = if (childEnd < end) childEnd + 1 else -1
        val direct = (i + 1..childEnd).filter { ls[it].depth == e.depth + 1 }
        val (fillIdx, borderIdx, contentIdxs) = classifyPanelChildren(ls, direct)
        val contentIdx = contentIdxs.firstOrNull() ?: -1
        mirrors.add(
            PanelMirror(
                panelId = meta.panelId,
                groupId = e.id,
                groupIdx = i,
                maskIdx = maskIdx,
                maskId = if (maskIdx >= 0) ls[maskIdx].id else -1L,
                fillIdx = fillIdx,
                contentIdx = contentIdx,
                borderIdx = borderIdx,
                meta = meta,
            ),
        )
    }
    // 根组定位: 优先"某个分格的父组" (结构信号), 兜底按名字找 depth-0 组
    var root = if (mirrors.isNotEmpty()) parentGroupIndexOf(ls, mirrors.first().groupIdx) else -1
    if (root < 0) {
        root = ls.firstOrNull { it.isGroup && it.depth == 0 && it.name == ROOT_GROUP_NAME }?.index ?: -1
    }
    val defaults = if (root in ls.indices) parsePanelDefaults(ReverieCoreBridge.panelMeta(ls[root].index)) else null
    return PanelEngineScan(ls, root, cross, crossMask, crossContent, mirrors, shares, defaults)
}

/** [g] 子树末尾连续附属透明蒙版的第一个索引; 无则 -1。 */
private fun trailingMaskIdxOf(ls: List<SnapshotLayer>, g: Int): Int {
    if (g !in ls.indices) return -1
    val d = ls[g].depth
    var end = g
    while (end + 1 < ls.size && ls[end + 1].depth > d) end++
    if (end <= g || ls[end].nodeType != LayerNodeType.TRANSPARENCY_MASK) return -1
    var first = end
    while (first - 1 > g && ls[first - 1].nodeType == LayerNodeType.TRANSPARENCY_MASK) first--
    return first
}

/** [g] 的第一个直接子层索引 (跳过附属蒙版); 无则 -1。 */
private fun firstDirectChildIdxOf(ls: List<SnapshotLayer>, g: Int): Int {
    if (g !in ls.indices) return -1
    val d = ls[g].depth
    var end = g
    while (end + 1 < ls.size && ls[end + 1].depth > d) end++
    var childEnd = end
    while (childEnd > g && ls[childEnd].nodeType == LayerNodeType.TRANSPARENCY_MASK) childEnd--
    return (g + 1..childEnd).firstOrNull { ls[it].depth == d + 1 && !LayerNodeType.isMask(ls[it].nodeType) } ?: -1
}

/**
 * 分格组直接子层的角色分类 (单一真相): 填充 = 带"格内填充"前缀的第一个直接子层
 * (回退最底), 边框 = 带"格边框"前缀的最后一个直接子层 (回退最顶), 其余为内容。
 * 扫描与切分/合并搬移共用此口径, 防止用户改名后内容层被误搬。
 * 返回 (fillIdx, borderIdx, contentIdxs); fill==border 时 fill = -1。
 */
private fun classifyPanelChildren(
    ls: List<SnapshotLayer>,
    direct: List<Int>,
): Triple<Int, Int, List<Int>> {
    var fillIdx = direct.firstOrNull { ls[it].name.startsWith(FILL_LAYER_NAME) } ?: direct.firstOrNull() ?: -1
    val borderIdx = direct.lastOrNull { ls[it].name.startsWith(BORDER_LAYER_NAME) } ?: direct.lastOrNull() ?: -1
    if (fillIdx == borderIdx) fillIdx = -1
    val contents = direct.filter { it != fillIdx && it != borderIdx }
    return Triple(fillIdx, borderIdx, contents)
}

/** 平铺列表里 [g] 的父组索引 (前方最近的更浅组); 无则 -1。 */
private fun parentGroupIndexOf(ls: List<SnapshotLayer>, g: Int): Int {
    if (g !in ls.indices) return -1
    val d = ls[g].depth
    for (j in g - 1 downTo 0) {
        if (ls[j].depth < d) return if (ls[j].isGroup) j else -1
    }
    return -1
}

internal fun PaintViewModel.scanEnginePanels(): PanelEngineScan {
    val page = LayerSnapshotProbe.read()
        ?: return PanelEngineScan(emptyList(), -1, -1, -1, -1, emptyList())
    return scanPanelsFrom(page.layers)
}

/** 按节点 id 找平铺索引 (渲染线程, 操作内部定位用)。 */
private fun indexOfLayerId(id: Long): Int {
    // 节点 id 是 C++ 侧的 KisNode 指针 (layerId()), arm64 上堆地址可能高于 2^63,
    // 转 Java Long 后为负数 —— 只能按位比较, 哨兵用 -1L 精确匹配, 禁止按符号过滤
    if (id == -1L) return -1
    val n = ReverieCoreBridge.layerCount()
    for (i in 0 until n) {
        if (ReverieCoreBridge.layerId(i) == id) return i
    }
    return -1
}

// ---- 录制 payload (L_PANEL_META) ----

/** [RecordingEvents.L_PANEL_META] 的一条事件 (编码见 [PanelMetaCodec])。 */
internal class PanelMetaEvent(
    val groupIdx: Int,
    val maskIdx: Int,
    val fillIdx: Int,
    val borderIdx: Int,
    /** PanelMeta JSON; null/- 表示不写元数据 (纯蒙版重绘的跨格容器用标记 JSON)。 */
    val metaJson: String?,
    // 重绘所需的**已解析**样式值 (回放端不依赖 panelDefaults 状态, 保证确定性)
    val borderWidth: Float,
    val borderColor: Int,
    val fillColor: Int,
    val fillEnabled: Boolean,
    /** 蒙版 quad 集合; null = 用 metaJson 里的单个 quad (普通分格)。 */
    val maskQuads: List<PanelQuad>? = null,
    /** 圆角半径 (px, 蒙版/填充/边框渲染用); 0 = 直角 (与旧渲染逐像素一致)。 */
    val cornerRadius: Float = 0f,
    /**
     * 蒙版 quad 对应的**已解析**圆角半径 (px, 与 [maskQuads] 一一对应); null/缺项回落到
     * [cornerRadius]。跨格容器各格 resolved 半径可不同, 统一 radius 表达不了, 随事件
     * 录制保证回放确定性 (回放端不读 panelDefaults)。
     */
    val maskQuadRadii: List<Float>? = null,
)

/**
 * L_PANEL_META 的字符串编解码。
 *
 * 格式: `groupIdx|maskIdx|fillIdx|borderIdx|panelJson|borderWidth|borderColor|fillColor|fillEnabled[|maskQuads=...][|cornerRadius]`
 * panelJson 不含 '|', 用 '-' 表示无元数据; 尾段圆角/逐格半径对旧 payload 缺失时回落 0,
 * 旧版读新 payload 只丢圆角不丢蒙版; 全文确定性、可往返。
 */
internal object PanelMetaCodec {
    private const val NO_META = "-"
    private const val QUADS_KEY = "maskQuads="

    fun encode(e: PanelMetaEvent): String {
        val sb = StringBuilder(192)
        sb.append(e.groupIdx).append('|')
        sb.append(e.maskIdx).append('|')
        sb.append(e.fillIdx).append('|')
        sb.append(e.borderIdx).append('|')
        sb.append(e.metaJson?.takeIf { it.isNotEmpty() } ?: NO_META).append('|')
        sb.append(e.borderWidth).append('|')
        sb.append(e.borderColor).append('|')
        sb.append(e.fillColor).append('|')
        sb.append(if (e.fillEnabled) '1' else '0')
        val quads = e.maskQuads
        if (quads != null) {
            sb.append('|').append(QUADS_KEY)
            quads.forEachIndexed { qi, q ->
                if (qi > 0) sb.append(';')
                q.corners.forEachIndexed { ci, c ->
                    if (ci > 0) sb.append(',')
                    sb.append(c.x).append(',').append(c.y)
                }
            }
        }
        // 尾段始终写出 (cornerRadius); 跨格蒙版带逐格半径时写逗号列表 (旧版 decode
        // toFloatOrNull 失败 → 0f, 只丢圆角不丢蒙版不崩)
        sb.append('|')
        val radii = e.maskQuadRadii
        if (quads != null && !radii.isNullOrEmpty()) {
            radii.joinTo(sb, ",")
        } else {
            sb.append(e.cornerRadius)
        }
        return sb.toString()
    }

    fun decode(arg: String): PanelMetaEvent? {
        val f = arg.split('|')
        if (f.size < 9) return null
        val groupIdx = f[0].toIntOrNull() ?: return null
        val maskIdx = f[1].toIntOrNull() ?: return null
        val fillIdx = f[2].toIntOrNull() ?: return null
        val borderIdx = f[3].toIntOrNull() ?: return null
        val metaJson = f[4].takeIf { it != NO_META && it.isNotEmpty() }
        val borderWidth = f[5].toFloatOrNull() ?: return null
        val borderColor = f[6].toIntOrNull() ?: return null
        val fillColor = f[7].toIntOrNull() ?: return null
        val fillEnabled = f[8] == "1"
        var maskQuads: List<PanelQuad>? = null
        var maskQuadRadii: List<Float>? = null
        var cornerRadius = 0f
        if (f.size >= 10) {
            if (f[9].startsWith(QUADS_KEY)) {
                // 旧格式: 第 10 段 = maskQuads (无圆角段 → 0)
                maskQuads = parseMaskQuads(f[9].substring(QUADS_KEY.length))
                if (f.size >= 11) {
                    val tail = f[10]
                    if (tail.indexOf(',') >= 0) {
                        // 逐格圆角半径 (与 maskQuads 一一对应, 缺项回落 0 = 直角)
                        val radii = tail.split(',').mapNotNull { it.toFloatOrNull() }
                        if (radii.isNotEmpty()) maskQuadRadii = radii
                        cornerRadius = radii.firstOrNull() ?: 0f
                    } else {
                        cornerRadius = tail.toFloatOrNull() ?: 0f
                    }
                }
            } else {
                // 新格式: 第 10 段 = cornerRadius
                cornerRadius = f[9].toFloatOrNull() ?: 0f
            }
        }
        return PanelMetaEvent(
            groupIdx, maskIdx, fillIdx, borderIdx, metaJson,
            borderWidth, borderColor, fillColor, fillEnabled, maskQuads, cornerRadius, maskQuadRadii,
        )
    }

    private fun parseMaskQuads(raw: String): List<PanelQuad> = raw.split(';').mapNotNull { seg ->
        val xy = seg.split(',').mapNotNull { it.toFloatOrNull() }
        val n = xy.size / 2
        if (xy.size % 2 == 0 && n in PanelQuad.MIN_VERTICES..PanelQuad.MAX_VERTICES) {
            PanelQuad.of(xy.chunked(2).map { Point2D(it[0], it[1]) })
        } else {
            null
        }
    }
}

/**
 * 执行一条 [PanelMetaEvent]: 写元数据 + 重绘蒙版(稀疏白 quad)/填充/边框(外框+ERASE 内框)。
 * index<0 的部分跳过。蒙版是稀疏的: 透明即遮挡, 只需白色 quad 像素。
 *
 * [oldQuad] 传入蒙版上已有的旧白 quad (改形状/平移) 时走增量重画: 老 quad 硬边 ERASE +
 * 新 quad 涂白, 脏区收敛为新老 quad bbox 并集; null (创建/切分/回放) 时先
 * clearLayerToTransparent 真清掉建蒙版时的全画布白底再逐 quad 涂白。
 */
internal fun applyPanelMetaEvent(
    e: PanelMetaEvent,
    oldQuad: PanelQuad? = null,
) {
    if (e.groupIdx >= 0 && !e.metaJson.isNullOrEmpty()) {
        runCatching { ReverieCoreBridge.setPanelMeta(e.groupIdx, e.metaJson) }
    }
    val metaQuad = e.metaJson?.let { parsePanelMeta(it)?.quad }
    val maskQuads: List<PanelQuad>? = when {
        e.maskIdx < 0 -> null
        e.maskQuads != null -> e.maskQuads
        else -> metaQuad?.let { listOf(it) }
    }
    // 旧像素清除: oldQuad 非空时按老 quad 外扩 1px 硬边 ERASE (脏区只到老 quad bbox);
    // null (创建/回放兜底) 时仍走 clearLayer
    val clearLayerPixels: (Int) -> Unit = oldQuad?.let { q ->
        { idx: Int ->
            ReverieCoreBridge.paintPanelQuad(idx, quadToXY(q.expandBorder(1f)), 0, true, false)
        }
    } ?: { idx: Int -> ReverieCoreBridge.clearLayer(idx) }
    if (!maskQuads.isNullOrEmpty()) {
        if (oldQuad != null && maskQuads.size == 1) {
            // 增量: 老 quad 外扩 1px 硬边 ERASE (擦净 AA 边缘) + 新 quad 涂白 (AA);
            // 坐标不做圆角密化, 圆角只由新 quad 的 cornerRadius 施加
            ReverieCoreBridge.paintPanelQuad(e.maskIdx, quadToXY(oldQuad.expandBorder(1f)), 0, true, false)
            ReverieCoreBridge.paintPanelQuad(e.maskIdx, pointsToXY(maskQuads[0].roundedCorners(e.cornerRadius)), MASK_SHOW_ARGB, false)
        } else {
            // 兜底: 先真清掉建蒙版时的全画布白底 (clearLayer 对蒙版会铺全白, 不能用),
            // 再逐 quad 画白; 其余透明区域天然=遮挡
            ReverieCoreBridge.clearLayerToTransparent(e.maskIdx)
            for ((qi, q) in maskQuads.withIndex()) {
                // 逐格圆角 (跨格容器各格 resolved 半径可不同); 缺项回落统一 cornerRadius
                val r = e.maskQuadRadii?.getOrNull(qi) ?: e.cornerRadius
                ReverieCoreBridge.paintPanelQuad(e.maskIdx, pointsToXY(q.roundedCorners(r)), MASK_SHOW_ARGB, false)
            }
        }
    }
    if (e.fillIdx >= 0 && e.fillIdx != e.borderIdx) {
        if (e.fillEnabled) {
            val quad = metaQuad
            if (quad != null) {
                clearLayerPixels(e.fillIdx)
                ReverieCoreBridge.paintPanelQuad(e.fillIdx, pointsToXY(quad.roundedCorners(e.cornerRadius)), e.fillColor, false)
            }
        } else {
            // 填充关闭: 清掉已画填充
            clearLayerPixels(e.fillIdx)
        }
    }
    if (e.borderIdx >= 0) {
        val quad = metaQuad
        if (quad != null) {
            clearLayerPixels(e.borderIdx)
            ReverieCoreBridge.paintPanelQuad(e.borderIdx, pointsToXY(quad.roundedCorners(e.cornerRadius)), e.borderColor, false)
            val inner = quad.insetBorder(e.borderWidth)
            if (inner !== quad) {
                // ERASE 挖洞 → 边框环 (硬边挖洞避免内圈残留亮线)
                ReverieCoreBridge.paintPanelQuad(e.borderIdx, pointsToXY(inner.roundedCorners(e.cornerRadius)), e.borderColor, true, false)
            }
        }
    }
}

private fun quadToXY(q: PanelQuad): FloatArray = FloatArray(q.corners.size * 2).also { a ->
    for ((i, c) in q.corners.withIndex()) {
        a[i * 2] = c.x
        a[i * 2 + 1] = c.y
    }
}

/** 密化顶点列表 (PanelQuad.roundedCorners 产出) → paintPanelQuad 的 xy 交错数组。 */
private fun pointsToXY(points: List<Point2D>): FloatArray = FloatArray(points.size * 2).also { a ->
    for ((i, c) in points.withIndex()) {
        a[i * 2] = c.x
        a[i * 2 + 1] = c.y
    }
}

// ---- 镜像重建 ----

/**
 * 从引擎重扫分格结构, 重建 [panels] / 根组 / 跨格容器镜像, 并修剪失效的选中集合。
 * 主线程调用; 快照不可用时保持上次镜像。
 */
internal fun PaintViewModel.refreshPanels() {
    val page = LayerSnapshotProbe.read() ?: return
    val s = scanPanelsFrom(page.layers)
    panels = s.mirrors
    panelRootGroupIdx = s.rootIdx
    panelCrossContainerIdx = s.crossIdx
    panelCrossContentIdx = s.crossContentIdx
    panelCrossContentId = if (s.crossContentIdx in s.layers.indices) s.layers[s.crossContentIdx].id else -1L
    panelCrossMaskId = if (s.crossMaskIdx in s.layers.indices) s.layers[s.crossMaskIdx].id else -1L
    panelShareGroups = s.shareGroups
    if (s.defaults != null) {
        panelDefaults = s.defaults
    }
    if (s.mirrors.isNotEmpty() || panelSessionActive()) {
        ensurePanelGroupsDefaultCollapsed(s)
    }
    if (selectedPanelId != null && s.mirrors.none { it.panelId == selectedPanelId }) {
        selectedPanelId = null
    }
    if (panelMultiSelectedIds.isNotEmpty()) {
        val valid = s.mirrors.asSequence().map { it.panelId }.toSet()
        panelMultiSelectedIds = panelMultiSelectedIds.intersect(valid)
    }
}

/** 下一个 panelId; 默认取 UI 镜像, 引擎侧可传入 runCore 内的扫描结果免重复 JNI。 */
private fun PaintViewModel.nextPanelId(mirrors: List<PanelMirror> = panels): Long =
    (mirrors.maxOfOrNull { it.meta.panelId } ?: 0L) + 1L

/**
 * 分格组默认折叠 (B2 降噪): 「分格」根组与「跨格内容」组在分格会话激活时默认收起;
 * 每会话每名只应用一次, 用户手动 toggle 后不再自动改写。
 */
private fun PaintViewModel.ensurePanelGroupsDefaultCollapsed(s: PanelEngineScan) {
    for (idx in listOf(s.rootIdx, s.crossIdx)) {
        if (idx !in s.layers.indices) continue
        val layer = s.layers[idx]
        val key = groupCollapseKey(layer.id)
        if (key in panelDefaultCollapseNames) continue
        panelDefaultCollapseNames.add(key)
        setGroupCollapsed(layer.id, collapsed = true)
    }
}

/**
 * 分格组行识别: [layerIndex] 命中某分格组或共用画容器时返回其代表 panelId, 否则 null (节点 id 只用 ==
 * 比较, 不做符号过滤); 镜像由 refreshPanels 重建后自动重新匹配。
 */
internal fun PaintViewModel.panelIdForGroupRow(layerIndex: Int): Long? {
    val id = layers.getOrNull(layerIndex)?.id ?: return null
    return panels.firstOrNull { it.groupId == id }?.panelId
}

/** 这一格是否还和别的格子共用一张画。 */
internal fun PaintViewModel.panelSharesDraw(panelId: Long): Boolean {
    val drawId = panels.firstOrNull { it.panelId == panelId }?.meta?.drawId ?: return false
    return panels.count { it.meta.drawId == drawId } > 1
}

/** 获取与指定格共用画区的其他伙伴格名称 (以顿号拼接，如「格 2」或「格 2、格 3」)。 */
internal fun PaintViewModel.panelSharedPeersDescription(panelId: Long): String? {
    val curMirror = panels.firstOrNull { it.panelId == panelId } ?: return null
    val peers = panels.filter { it.meta.drawId == curMirror.meta.drawId && it.panelId != panelId }
    if (peers.isEmpty()) return null
    val names = peers.map { p ->
        val groupIdx = indexOfLayerId(p.groupId)
        if (groupIdx >= 0) layers.getOrNull(groupIdx)?.name ?: "格 ${p.panelId}" else "格 ${p.panelId}"
    }
    return names.joinToString("、")
}

/** 某格是否为其所属 drawId 的主控代表格。 */
internal fun PaintViewModel.isRepresentativePanel(panelId: Long): Boolean {
    val mirror = panels.firstOrNull { it.panelId == panelId } ?: return false
    val firstWithDrawId = panels.firstOrNull { it.meta.drawId == mirror.meta.drawId } ?: return false
    return firstWithDrawId.panelId == panelId
}

/** 为分格组行生成图层显示标题 (保持原名，关联状态由 LayerRow 的矢量徽标呈现)。 */
internal fun PaintViewModel.drawUnitTitleForPanelGroup(layerIndex: Int): String? = null

/** [targetIdx] 是否落在 [groupIdx] 子树内 (含自身); 读 UI 镜像, 零 JNI 零分配。 */
internal fun PaintViewModel.isInGroupSubtree(groupIdx: Int, targetIdx: Int): Boolean {
    if (groupIdx !in layers.indices || targetIdx < groupIdx) return false
    if (targetIdx == groupIdx) return true
    val gDepth = layers[groupIdx].depth
    for (i in groupIdx + 1..targetIdx) {
        if (layers[i].depth <= gDepth) return false
    }
    return true
}

/**
 * 维持分格组内边框层物理压顶 (黑框压顶):
 * 遍历所有分格, 若其组内存在索引高于边框层的非蒙版画层, 将边框层提升到最高子层上方。
 */
internal fun PaintViewModel.ensurePanelBordersOnTop(s: PanelEngineScan) {
    for (m in s.mirrors) {
        if (m.borderIdx !in s.layers.indices || m.groupIdx !in s.layers.indices) continue
        val gDepth = s.layers[m.groupIdx].depth
        var maxChildIdx = m.groupIdx
        var i = m.groupIdx + 1
        while (i < s.layers.size && s.layers[i].depth > gDepth) {
            if (s.layers[i].nodeType != LayerNodeType.TRANSPARENCY_MASK) {
                maxChildIdx = i
            }
            i++
        }
        if (maxChildIdx > m.borderIdx) {
            ReverieCoreBridge.moveLayerRelative(m.borderIdx, maxChildIdx, true)
        }
    }
}

/**
 * 当前正在画画的图层对应的分格 ID 集合: [currentLayerIndex] 处于某个分格画层
 * (独立或共用) 时返回其全部绑定 panelId, 否则 emptySet()。纯 UI 镜像查询。
 */
internal fun PaintViewModel.currentPaintingPanelIds(): Set<Long> {
    val curIdx = currentLayerIndex
    val rootIdx = panelRootGroupIdx
    if (curIdx !in layers.indices || rootIdx < 0 || curIdx == rootIdx) return emptySet()
    for (m in panels) {
        if (isInGroupSubtree(m.groupIdx, curIdx)) {
            val drawId = m.meta.drawId
            val linked = panels.filter { it.meta.drawId == drawId }.map { it.panelId }.toSet()
            return if (linked.size > 1) linked else setOf(m.panelId)
        }
    }
    if (panelCrossContainerIdx >= 0 && isInGroupSubtree(panelCrossContainerIdx, curIdx)) {
        return panels.map { it.panelId }.toSet()
    }
    return emptySet()
}

/** 检查某图层是否位于受控分格树内部 (分格根组、分格组或其子层)。 */
internal fun PaintViewModel.isInsidePanelTree(layerIndex: Int): Boolean =
    layerIndex in layers.indices && isInGroupSubtree(panelRootGroupIdx, layerIndex)

/** 落笔目标。定位到该分格组内的活动画层或最顶层可绘制画层。 */
internal fun PaintViewModel.panelContentLayerIndex(panelId: Long): Int {
    val mirror = panels.firstOrNull { it.panelId == panelId } ?: return -1
    if (isInGroupSubtree(mirror.groupIdx, currentLayerIndex) && currentLayerIndex != mirror.groupIdx) {
        val cur = layers.getOrNull(currentLayerIndex)
        if (cur != null && !isPanelTreeHidden(currentLayerIndex) && !LayerNodeType.isMask(cur.nodeType) && !cur.isGroup) {
            return currentLayerIndex
        }
    }
    val gDepth = layers.getOrNull(mirror.groupIdx)?.depth ?: return mirror.contentIdx
    var topPaintIdx = -1
    var i = mirror.groupIdx + 1
    while (i < layers.size && layers[i].depth > gDepth) {
        val l = layers[i]
        if (!isPanelTreeHidden(i) && !LayerNodeType.isMask(l.nodeType) && !l.isGroup) {
            topPaintIdx = i
        }
        i++
    }
    if (topPaintIdx >= 0) return topPaintIdx
    return mirror.contentIdx
}

/** 获取指定分格在视觉/列表自顶向下顺序上的前一格 (上方) 与后一格 (下方) ID */
internal fun PaintViewModel.adjacentPanelIds(panelId: Long): Pair<Long?, Long?> {
    // 图层平铺快照为底层到顶层的自底向上顺序 (高索引 = 视觉顶层 = 图层列表第0行)
    // 因此图层列表中自顶向下显示的顺序对应 groupIdx 降序
    val visualPanels = panels.sortedByDescending { it.groupIdx }
    val vIdx = visualPanels.indexOfFirst { it.panelId == panelId }
    if (vIdx < 0) return null to null
    val prevId = if (vIdx > 0) visualPanels[vIdx - 1].panelId else null // 视觉上方一格
    val nextId = if (vIdx < visualPanels.size - 1) visualPanels[vIdx + 1].panelId else null // 视觉下方一格
    return prevId to nextId
}

/**
 * 图层树里不该出现的行：跨格容器、分格内部辅助层(填充/边框)及其裁剪蒙版。
 * 分格组与普通画层全部保留展示。
 */
internal fun PaintViewModel.isPanelTreeHidden(layerIndex: Int): Boolean {
    val layer = layers.getOrNull(layerIndex) ?: return false
    // 隐藏分格内部的系统辅助层（填充层与边框层，由属性浮窗与矢量覆盖层全权负责）
    if (isInsidePanelTree(layerIndex) && (layer.name.startsWith(FILL_LAYER_NAME) || layer.name.startsWith(BORDER_LAYER_NAME))) {
        return true
    }
    val hiddenIds = HashSet<Long>()
    layers.getOrNull(panelCrossContainerIdx)?.id?.let { if (panelCrossContainerIdx >= 0) hiddenIds.add(it) }
    if (panelCrossMaskId != -1L) hiddenIds.add(panelCrossMaskId)
    if (panelCrossContentId != -1L) hiddenIds.add(panelCrossContentId)
    if (layer.id in hiddenIds) return true
    var depth = layer.depth
    for (i in layerIndex - 1 downTo 0) {
        val prev = layers[i]
        if (prev.depth < depth && prev.isGroup) {
            if (prev.id in hiddenIds) return true
            depth = prev.depth
        }
    }
    return false
}

/**
 * 分格蒙版行查询: [layerIndex] 命中某分格的裁剪蒙版时返回 (panelId, cropEnabled);
 * 跨格容器蒙版返回 (-1L, 当前可见性) —— 跨格容器无 PanelMeta, 裁剪口径 = 可见性;
 * 非蒙版/通用蒙版返回 null (UI 层完全走既有行为)。
 */
internal fun PaintViewModel.panelCropStateFor(layerIndex: Int): Pair<Long, Boolean>? {
    val l = layers.getOrNull(layerIndex) ?: return null
    if (!LayerNodeType.isMask(l.nodeType)) return null
    for (m in panels) {
        if (m.maskId == l.id) return m.panelId to m.meta.cropEnabled
    }
    if (panelCrossMaskId != -1L && panelCrossMaskId == l.id) return -1L to l.visible
    return null
}

// ---- 操作 (每个 = beginUndoMacro/endUndoMacro 一条撤销步; 录制埋点齐全) ----

/** 新建一格: 根组(若无) → 分格组 → 填充/内容/边框 → 裁剪蒙版 → 重绘视觉 → 写元数据。 */
internal fun PaintViewModel.createPanelAt(quad: PanelQuad) {
    if (coreW <= 0 || coreH <= 0) return
    if (quad.isDegenerate(MIN_PANEL_AREA)) return
    val panelId = nextPanelId()
    val meta = PanelMeta(
        quad = quad, style = PanelStyle(), cropEnabled = true, panelId = panelId,
    )
    Breadcrumbs.record("Panel", "createPanelAt id=$panelId")
    runCore(after = {
        refreshPanels()
        // 新格默认展开，以便在图层树中清晰展示内容层并就地作画
        collapsedPanelIds = collapsedPanelIds - panelId
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_create))
        try {
            val build = buildPanelStructureOp(meta, meta.style.resolve(panelDefaults)) ?: return@runCore
            // 切分继承父格 crop=false 时隐藏新蒙版
            if (!meta.cropEnabled && build.maskIdx >= 0) {
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_VISIBLE, build.maskIdx, "0")
                }
                ReverieCoreBridge.setLayerVisible(build.maskIdx, false)
            }
            // 停在内容层, 双击进格/直接作画的目标一致
            if (build.contentIdx >= 0) {
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, build.contentIdx)
                ReverieCoreBridge.setCurrentLayer(build.contentIdx)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 一次分格结构构造的结果 (索引为构造完成时刻的快照, 后续结构变化会漂移; 长期定位用 id)。 */
private class PanelBuildResult(
    val groupIdx: Int,
    val groupId: Long,
    val contentIdx: Int,
    val maskIdx: Int,
)

/**
 * 在撤销宏内构造完整分格结构: 根组(若无) → 分格组 → 填充/内容/边框 → 裁剪蒙版 →
 * 元数据 + 视觉重绘。调用方必须已处于 beginUndoMacro/endUndoMacro 之间, 失败返回 null。
 */
private fun PaintViewModel.buildPanelStructureOp(
    meta: PanelMeta,
    resolved: ResolvedPanelStyle,
    includeContent: Boolean = true,
): PanelBuildResult? {
    val json = meta.toJson()
    val scan = scanEnginePanels()
    // 1. 根组 (首个分格时自动创建; 挂在最顶的顶层图层上方)
    var rootIdx = scan.rootIdx
    if (rootIdx < 0) {
        val topIdx = topmostRootLayerIdx()
        if (topIdx >= 0) {
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, topIdx)
            ReverieCoreBridge.setCurrentLayer(topIdx)
        }
        if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_GROUP)
        rootIdx = ReverieCoreBridge.addGroupLayer(ROOT_GROUP_NAME)
        if (rootIdx < 0) return null
        ReverieCoreBridge.setPanelMeta(rootIdx, panelDefaultsJson())
    }
    // 2. 分格组挂到根组底部 (current=组 → 组内最底; 各格间 z 序由 movePanelZBy 调整)
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, rootIdx)
    ReverieCoreBridge.setCurrentLayer(rootIdx)
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_GROUP)
    val groupIdx = ReverieCoreBridge.addGroupLayer("格 ${scan.mirrors.size + 1}")
    if (groupIdx < 0) return null
    val groupId = ReverieCoreBridge.layerId(groupIdx)
    // 3. 组内自底向上: 填充 → 内容 → 边框。切分出的小半不建内容层，画留在共用层。
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, groupIdx)
    ReverieCoreBridge.setCurrentLayer(groupIdx)
    val childNames = if (includeContent) {
        listOf(FILL_LAYER_NAME, CONTENT_LAYER_NAME, BORDER_LAYER_NAME)
    } else {
        listOf(FILL_LAYER_NAME, BORDER_LAYER_NAME)
    }
    for (name in childNames) {
        if (recorder.recording) {
            recorder.layerOp(
                RecordingEvents.L_ADD_LAYER_TYPE, 0, "$name|0|${0xFFFFFFFF.toInt()}",
            )
        }
        ReverieCoreBridge.addLayerWithType(name, 0, 0xFFFFFFFF.toInt())
    }
    // 4. 裁剪蒙版 = 分格组的同级上方兄弟 (addMaskToLayer 现有行为), 完成后 current=蒙版
    if (!ReverieCoreBridge.addMaskToLayer(groupIdx, MASK_TYPE_TRANSPARENCY)) return null
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_MASK, groupIdx, "0")
    // 5. 重扫定位真实索引 (组/子层/蒙版都已稳定)
    val groupNow = indexOfLayerId(groupId)
    val page = LayerSnapshotProbe.read()
    val direct = if (page != null && groupNow >= 0) directChildrenOf(page.layers, groupNow) else emptyList()
    val maskNow = ReverieCoreBridge.currentLayerIndex()
    val ev = PanelMetaEvent(
        groupIdx = groupNow,
        maskIdx = maskNow,
        fillIdx = direct.getOrNull(0) ?: -1,
        borderIdx = direct.getOrNull(if (includeContent) 2 else 1) ?: -1,
        metaJson = json,
        borderWidth = resolved.borderWidth,
        borderColor = resolved.borderColor,
        fillColor = resolved.fillColor,
        fillEnabled = resolved.fillEnabled,
        cornerRadius = resolved.cornerRadius,
    )
    // 6. 元数据 + 视觉重绘 (黑底白 quad 蒙版 / 填充 / 边框环)
    applyPanelMetaEvent(ev)
    if (recorder.recording) {
        recorder.layerOp(RecordingEvents.L_PANEL_META, ev.groupIdx, PanelMetaCodec.encode(ev))
    }
    return PanelBuildResult(
        groupIdx = groupNow,
        groupId = groupId,
        contentIdx = if (includeContent) direct.getOrNull(1) ?: -1 else -1,
        maskIdx = maskNow,
    )
}

/**
 * [groupIdx] 格的内容层节点 id (排除格内填充/格边框; 名字口径与 scanPanelsFrom 一致)。
 * 渲染线程调用, 供切分/合并搬移内容。
 */
private fun contentLayerIdsOf(page: SnapshotPage, groupIdx: Int): List<Long> {
    val direct = directChildrenOf(page.layers, groupIdx)
    val (_, _, contents) = classifyPanelChildren(page.layers, direct)
    return contents.map { page.layers[it].id }
}

/** 按节点 id 删一格 (先蒙版后组; 索引在删前按 id 重定位, 录制埋点照 deletePanel 惯例)。 */
private fun PaintViewModel.removePanelNodesById(mirror: PanelMirror) {
    val maskNow = indexOfLayerId(mirror.maskId)
    if (maskNow >= 0) {
        if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, maskNow)
        ReverieCoreBridge.removeLayer(maskNow)
    }
    val groupNow = indexOfLayerId(mirror.groupId)
    if (groupNow >= 0) {
        if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, groupNow)
        ReverieCoreBridge.removeLayer(groupNow)
    }
}

/**
 * 直线切分: 只切框。旧组留下当较大的一半，较小的一半新建框、不建内容层。
 * 两半的 drawId 不变，像素留在原来那张画上。第一次变成共用时，内容层挪进隐藏容器。
 */
internal fun PaintViewModel.dividePanelsByLine(start: Point2D, end: Point2D, extendDocPx: Float) {
    if (coreW <= 0 || coreH <= 0) return
    Breadcrumbs.record("Panel", "divideByLine")
    val builtIds = mutableListOf<Long>()
    runCore(after = {
        refreshPanels()
        if (builtIds.isNotEmpty()) collapsedPanelIds = collapsedPanelIds + builtIds
        selectedPanelId = null
        notifyLayerChanged(pixelChanged = true)
    }) {
        val halfGap = panelDefaults.hGap / 2f
        val scan = scanEnginePanels()
        data class CutJob(val mirror: PanelMirror, val large: PanelQuad, val small: PanelQuad)
        val jobs = ArrayList<CutJob>()
        for (m in scan.mirrors) {
            if (!segmentCutsQuad(m.meta.quad, start, end, extendDocPx)) continue
            val halves = m.meta.quad.cutByLine(start, end, halfGap) ?: continue
            val large = if (halves.first.area() >= halves.second.area()) halves.first else halves.second
            val small = if (large === halves.first) halves.second else halves.first
            jobs.add(CutJob(m, large, small))
        }
        if (jobs.isEmpty()) return@runCore
        var nextId = nextPanelId(scan.mirrors)
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_divide))
        try {
            for (job in jobs) {
                val old = scanEnginePanels().mirrors.firstOrNull { it.panelId == job.mirror.panelId } ?: continue
                val resolved = old.meta.style.resolve(panelDefaults)
                val inherited = PanelStyle(
                    fillColor = resolved.fillColor,
                    fillEnabled = resolved.fillEnabled,
                    cornerRadius = resolved.cornerRadius,
                )
                val largeJson = old.meta.copy(quad = job.large).toJson()
                val largeEv = PanelMetaEvent(
                    groupIdx = indexOfLayerId(old.groupId),
                    maskIdx = indexOfLayerId(old.maskId),
                    fillIdx = old.fillIdx,
                    borderIdx = old.borderIdx,
                    metaJson = largeJson,
                    borderWidth = resolved.borderWidth,
                    borderColor = resolved.borderColor,
                    fillColor = resolved.fillColor,
                    fillEnabled = resolved.fillEnabled,
                    cornerRadius = resolved.cornerRadius,
                )
                applyPanelMetaEvent(largeEv, old.meta.quad)
                if (recorder.recording && largeEv.groupIdx >= 0) {
                    recorder.layerOp(RecordingEvents.L_PANEL_META, largeEv.groupIdx, PanelMetaCodec.encode(largeEv))
                }
                val newId = nextId++
                val build = buildPanelStructureOp(
                    PanelMeta(
                        quad = job.small,
                        style = inherited,
                        cropEnabled = old.meta.cropEnabled,
                        panelId = newId,
                        drawId = old.meta.drawId,
                    ),
                    inherited.resolve(panelDefaults),
                    includeContent = true,
                ) ?: continue
                builtIds.add(newId)
                if (!old.meta.cropEnabled && build.maskIdx >= 0) {
                    if (recorder.recording) {
                        recorder.layerOp(RecordingEvents.L_VISIBLE, build.maskIdx, "0")
                    }
                    ReverieCoreBridge.setLayerVisible(build.maskIdx, false)
                }
                syncPanelMasks(old.meta.drawId)
            }
            val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
            if (after.crossIdx >= 0 && after.mirrors.isNotEmpty()) {
                repaintCrossMask(after.crossIdx, after.crossMaskIdx, after.mirrors)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 同步指定 drawId 下所有分格的裁剪蒙版：将各格蒙版统一重绘为所属全部格子的 quad 并集 */
internal fun PaintViewModel.syncPanelMasks(drawId: Long) {
    val scan = scanEnginePanels()
    val members = scan.mirrors.filter { it.meta.drawId == drawId }
    if (members.isEmpty()) return
    val allQuads = members.map { it.meta.quad }
    val defaults = panelDefaults
    for (m in members) {
        val maskIdx = indexOfLayerId(m.maskId).takeIf { it >= 0 } ?: m.maskIdx
        if (maskIdx < 0) continue
        val resolved = m.meta.style.resolve(defaults)
        val radii = members.map { it.meta.style.resolve(defaults).cornerRadius }
        val ev = PanelMetaEvent(
            groupIdx = -1,
            maskIdx = maskIdx,
            fillIdx = -1,
            borderIdx = -1,
            metaJson = null,
            borderWidth = 0f,
            borderColor = 0,
            fillColor = 0,
            fillEnabled = false,
            maskQuads = allQuads,
            cornerRadius = resolved.cornerRadius,
            maskQuadRadii = radii,
        )
        applyPanelMetaEvent(ev)
        if (recorder.recording) {
            recorder.layerOp(RecordingEvents.L_PANEL_META, -1, PanelMetaCodec.encode(ev))
        }
    }
}

/** 把分格从画区关联中解散。核心：双向完全对称解散，分离则各自复制画层离开，画面像素 100% 毫发无损！ */
internal fun PaintViewModel.separatePanelDraw(panelId: Long) {
    if (!panelSharesDraw(panelId)) return
    val cur = panels.firstOrNull { it.panelId == panelId } ?: return
    val oldDrawId = cur.meta.drawId
    val members = panels.filter { it.meta.drawId == oldDrawId }
    if (members.size < 2) return

    Breadcrumbs.record("Panel", "separatePanelDraw id=$panelId members=${members.map { it.panelId }}")
    runCore(after = {
        refreshPanels()
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_menu_separate))
        try {
            val scan = scanEnginePanels()
            val primary = scan.mirrors.firstOrNull { it.meta.drawId == oldDrawId } ?: return@runCore

            // 1. 若当前关联组仅有两格，则解散该组时两格双向同时恢复独立；若三格以上，则仅将当前格剔除出关联组
            val separatingPanels = if (members.size == 2) {
                scan.mirrors.filter { it.meta.drawId == oldDrawId }
            } else {
                listOfNotNull(scan.mirrors.firstOrNull { it.panelId == panelId })
            }

            for (sep in separatingPanels) {
                var groupNow = indexOfLayerId(sep.groupId)
                if (groupNow < 0) continue

                // 1.1 若分离格不是主代表格，将主代表格的内容画层复制到分离格中（分离则各自复制画层！）
                if (sep.panelId != primary.panelId) {
                    val pageBefore = LayerSnapshotProbe.read()
                    val priGroupNow = indexOfLayerId(primary.groupId)
                    if (pageBefore != null && priGroupNow >= 0) {
                        val priContents = contentLayerIdsOf(pageBefore, priGroupNow)
                        val sepContents = contentLayerIdsOf(pageBefore, groupNow)
                        for (cId in priContents) {
                            val cIdx = indexOfLayerId(cId)
                            if (cIdx >= 0) {
                                val clonedIdx = ReverieCoreBridge.copyLayer(cIdx)
                                val targetGrpNow = indexOfLayerId(sep.groupId)
                                if (clonedIdx >= 0 && targetGrpNow >= 0) {
                                    ReverieCoreBridge.moveLayerToGroup(clonedIdx, targetGrpNow)
                                }
                            }
                        }
                        // 清理分离格原先残留的空内容层
                        for (oldCId in sepContents) {
                            val oldIdx = indexOfLayerId(oldCId)
                            if (oldIdx >= 0) {
                                ReverieCoreBridge.removeLayer(oldIdx)
                            }
                        }
                    }
                }

                // 1.2 重置 drawId 为自身 panelId (通过重新定位确保索引精准)
                groupNow = indexOfLayerId(sep.groupId)
                if (groupNow >= 0) {
                    val resolved = sep.meta.style.resolve(panelDefaults)
                    val newMeta = sep.meta.copy(drawId = sep.panelId)
                    val newMetaJson = newMeta.toJson()
                    ReverieCoreBridge.setPanelMeta(groupNow, newMetaJson)
                    val ev = PanelMetaEvent(
                        groupIdx = groupNow,
                        maskIdx = -1,
                        fillIdx = -1,
                        borderIdx = -1,
                        metaJson = newMetaJson,
                        borderWidth = resolved.borderWidth,
                        borderColor = resolved.borderColor,
                        fillColor = resolved.fillColor,
                        fillEnabled = resolved.fillEnabled,
                        cornerRadius = resolved.cornerRadius,
                    )
                    applyPanelMetaEvent(ev)
                    if (recorder.recording) {
                        recorder.layerOp(RecordingEvents.L_PANEL_META, groupNow, PanelMetaCodec.encode(ev))
                    }
                }

                // 1.3 将该格蒙版刷回仅包含自己的 Quad
                val maskNow = indexOfLayerId(sep.maskId)
                if (maskNow >= 0) {
                    val resolved = sep.meta.style.resolve(panelDefaults)
                    val maskEv = PanelMetaEvent(
                        groupIdx = -1,
                        maskIdx = maskNow,
                        fillIdx = -1,
                        borderIdx = -1,
                        metaJson = null,
                        borderWidth = 0f,
                        borderColor = 0,
                        fillColor = 0,
                        fillEnabled = false,
                        maskQuads = listOf(sep.meta.quad),
                        cornerRadius = resolved.cornerRadius,
                    )
                    applyPanelMetaEvent(maskEv)
                    if (recorder.recording) {
                        recorder.layerOp(RecordingEvents.L_PANEL_META, -1, PanelMetaCodec.encode(maskEv))
                    }
                }
            }

            // 2. 如果还有剩余未分离的成员格，更新剩余成员格的蒙版并集
            val remainingMembers = members.filter { m -> separatingPanels.none { it.panelId == m.panelId } }
            if (remainingMembers.isNotEmpty()) {
                syncPanelMasks(oldDrawId)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 寻找与 [panelId] 相邻且属于不同 drawId 的分格 (长按单格「与相邻格合并画层」用)。 */
internal fun PaintViewModel.findAdjacentPanel(panelId: Long): Long? {
    val seed = panels.firstOrNull { it.panelId == panelId } ?: return null
    val others = panels.filter { it.panelId != panelId && it.meta.drawId != seed.meta.drawId }
    if (others.isEmpty()) return null
    val withSharedEdge = others.firstOrNull {
        seed.meta.quad.sharedEdge(it.meta.quad, 8f) != null
    }
    if (withSharedEdge != null) return withSharedEdge.panelId
    val seedCenter = seed.meta.quad.centroid()
    return others.minByOrNull {
        val c = it.meta.quad.centroid()
        val dx = c.x - seedCenter.x
        val dy = c.y - seedCenter.y
        dx * dx + dy * dy
    }?.panelId
}

/**
 * 把多选格的画层合并为同一个联动绘制单元 (以第一个格的 drawId 为基准): 纯共享遮罩并集，
 * 绝不压缩合并图层或搬运图层！
 */
internal fun PaintViewModel.mergeAllPanelDraws(panelIds: Collection<Long>) {
    val selected = panelIds.distinct().mapNotNull { id -> panels.firstOrNull { it.panelId == id } }
    if (selected.size < 2) return
    val targetDrawId = selected.first().meta.drawId
    val sourceDrawIds = selected.map { it.meta.drawId }.filter { it != targetDrawId }.toSet()
    if (sourceDrawIds.isEmpty()) return

    Breadcrumbs.record("Panel", "mergeAllPanelDraws target=$targetDrawId sources=$sourceDrawIds")
    runCore(after = {
        refreshPanels()
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_menu_merge_layer))
        try {
            val scan = scanEnginePanels()

            // 1. 将涉及的所有源成员分格的 drawId 改为 targetDrawId
            for (m in scan.mirrors.filter { it.panelId in panelIds || it.meta.drawId in sourceDrawIds }) {
                val groupIdx = indexOfLayerId(m.groupId).takeIf { it >= 0 } ?: m.groupIdx
                if (groupIdx < 0) continue
                val resolved = m.meta.style.resolve(panelDefaults)
                val newMeta = m.meta.copy(drawId = targetDrawId)
                val newMetaJson = newMeta.toJson()
                ReverieCoreBridge.setPanelMeta(groupIdx, newMetaJson)
                val ev = PanelMetaEvent(
                    groupIdx = groupIdx, maskIdx = -1, fillIdx = -1, borderIdx = -1,
                    metaJson = newMetaJson,
                    borderWidth = resolved.borderWidth, borderColor = resolved.borderColor,
                    fillColor = resolved.fillColor, fillEnabled = resolved.fillEnabled,
                    cornerRadius = resolved.cornerRadius,
                )
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_PANEL_META, groupIdx, PanelMetaCodec.encode(ev))
                }
            }

            // 2. 将非主代表格的内容画层并入主代表格（合流共享，零像素丢失）
            val primaryMirror = selected.first()
            val primaryGroupIdx = indexOfLayerId(primaryMirror.groupId)
            for (m in scan.mirrors.filter { it.panelId in panelIds && it.panelId != primaryMirror.panelId }) {
                val secGroupIdx = indexOfLayerId(m.groupId)
                val page = LayerSnapshotProbe.read()
                if (page != null && primaryGroupIdx >= 0 && secGroupIdx >= 0) {
                    val secContents = contentLayerIdsOf(page, secGroupIdx)
                    for (cId in secContents) {
                        val cIdx = indexOfLayerId(cId)
                        val pNow = indexOfLayerId(primaryMirror.groupId)
                        if (cIdx >= 0 && pNow >= 0) {
                            ReverieCoreBridge.moveLayerToGroup(cIdx, pNow)
                        }
                    }
                }
            }

            // 3. 统一刷新 targetDrawId 的共享蒙版并集
            syncPanelMasks(targetDrawId)
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 把格 A 与格 B 的画层合并为同一个联动绘制单元 (以 A 的 drawId 为基准)。 */
internal fun PaintViewModel.mergePanelDraw(panelIdA: Long, panelIdB: Long) {
    if (panelIdA != panelIdB) mergeAllPanelDraws(listOf(panelIdA, panelIdB))
}

/** 图层树眼睛：整格一起显隐。 */
internal fun PaintViewModel.setPanelRowVisible(panelId: Long, visible: Boolean) {
    val mirror = panels.firstOrNull { it.panelId == panelId } ?: return
    runCore(after = { notifyLayerChanged() }) {
        val scan = scanEnginePanels()
        val cur = scan.mirrors.firstOrNull { it.panelId == panelId } ?: return@runCore
        val maskOn = visible && cur.meta.cropEnabled
        if (cur.groupIdx >= 0) {
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_VISIBLE, cur.groupIdx, if (visible) "1" else "0")
            }
            ReverieCoreBridge.setLayerVisible(cur.groupIdx, visible)
        }
        if (cur.maskIdx >= 0) {
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_VISIBLE, cur.maskIdx, if (maskOn) "1" else "0")
            }
            ReverieCoreBridge.setLayerVisible(cur.maskIdx, maskOn)
        }
    }
}

/**
 * 合并多选格: 凸包为新格边界 (相邻性校验: 凸包面积明显大于各格面积和即拒绝),
 * 所有源格内容层搬进新组, 删源格。一个撤销宏; 结构码 + L_PANEL_META 可回放。
 */
internal fun PaintViewModel.mergePanels(ids: Set<Long>) {
    if (ids.size < 2) return
    Breadcrumbs.record("Panel", "mergePanels n=${ids.size}")
    var rejected = false
    var mergedId: Long? = null
    runCore(after = {
        refreshPanels()
        if (mergedId != null && !rejected) {
            collapsedPanelIds = collapsedPanelIds + mergedId!!
        }
        selectedPanelId = null
        panelMultiSelectedIds = emptySet()
        notifyLayerChanged(pixelChanged = true)
        if (rejected) {
            showActionToast(R.string.panel_merge_not_adjacent, R.drawable.ic_panel_frame)
        }
    }) {
        val scan = scanEnginePanels()
        val targets = ids.mapNotNull { id -> scan.mirrors.firstOrNull { it.panelId == id } }
        if (targets.size < 2) return@runCore
        val hull = PanelQuad.hullOf(targets.flatMap { it.meta.quad.corners })
        val sumArea = targets.sumOf { it.meta.quad.area().toDouble() }.toFloat()
        if (hull == null || hull.area() > sumArea * 1.15f + 1f) {
            // 不相邻 (合并会圈进大空洞) → 拒绝, 主线程 toast
            rejected = true
            return@runCore
        }
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_merge))
        try {
            // 内容层 id (结构变化前快照)
            val contentIds = targets.flatMap { m ->
                val page = LayerSnapshotProbe.read()
                if (page != null && m.groupIdx in page.layers.indices) {
                    contentLayerIdsOf(page, m.groupIdx)
                } else {
                    emptyList()
                }
            }
            // 建合并后的新格 (样式回默认覆盖态, 用户可在面板再调)
            val newPanelId = nextPanelId(scan.mirrors)
            mergedId = newPanelId
            val build = buildPanelStructureOp(
                PanelMeta(quad = hull, style = PanelStyle(), cropEnabled = true, panelId = newPanelId),
                PanelStyle().resolve(panelDefaults),
            ) ?: return@runCore
            // 内容搬移 (索引按 id 重定位)
            for (cid in contentIds) {
                val idx = indexOfLayerId(cid)
                if (idx < 0) continue
                val groupNow = indexOfLayerId(build.groupId)
                if (groupNow < 0) continue
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_MOVE_TO_GROUP, idx, groupNow.toString())
                }
                ReverieCoreBridge.moveLayerToGroup(idx, groupNow)
            }
            // 删源格
            for (t in targets) removePanelNodesById(t)
            // 跨格容器蒙版重同步: 格集变化后跨可见区域必须重画为剩余格并集
            val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
            if (after.crossIdx >= 0 && after.mirrors.isNotEmpty()) {
                repaintCrossMask(after.crossIdx, after.crossMaskIdx, after.mirrors)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

private fun topmostRootLayerIdx(): Int {
    val n = ReverieCoreBridge.layerCount()
    for (i in n - 1 downTo 0) {
        if (ReverieCoreBridge.layerDepth(i) == 0) return i
    }
    return -1
}

/** [g] 的直接子层 (非蒙版), 自底向上。 */
private fun directChildrenOf(ls: List<SnapshotLayer>, g: Int): List<Int> {
    if (g !in ls.indices) return emptyList()
    val d = ls[g].depth
    var end = g
    while (end + 1 < ls.size && ls[end + 1].depth > d) end++
    var childEnd = end
    while (childEnd > g && ls[childEnd].nodeType == LayerNodeType.TRANSPARENCY_MASK) childEnd--
    return (g + 1..childEnd).filter { ls[it].depth == d + 1 && !LayerNodeType.isMask(ls[it].nodeType) }
}

/** 改形状: 元数据 + 蒙版/填充/边框按新 quad 重绘 (内容层像素不动)。 */
internal fun PaintViewModel.updatePanelShape(id: Long, newQuad: PanelQuad) {
    if (newQuad.isDegenerate(MIN_PANEL_AREA)) return
    val d = panelDefaults
    val oldMeta = parsePanelMetaAt(id) ?: return
    val json = oldMeta.copy(quad = newQuad).toJson()
    val resolved = oldMeta.style.resolve(d)
    Breadcrumbs.record("Panel", "updatePanelShape id=$id")
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        val cur = scanEnginePanels().mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_shape))
        try {
            val ev = PanelMetaEvent(
                groupIdx = cur.groupIdx,
                maskIdx = cur.maskIdx,
                fillIdx = cur.fillIdx,
                borderIdx = cur.borderIdx,
                metaJson = json,
                borderWidth = resolved.borderWidth,
                borderColor = resolved.borderColor,
                fillColor = resolved.fillColor,
                fillEnabled = resolved.fillEnabled,
                cornerRadius = resolved.cornerRadius,
            )
            // 改形状: 蒙版上已有旧白 quad (扫描在元数据写入前, cur.meta.quad 即旧形状)
            applyPanelMetaEvent(ev, cur.meta.quad)
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_PANEL_META, cur.groupIdx, PanelMetaCodec.encode(ev))
            }
            if (panelSharesDraw(id)) {
                syncPanelMasks(cur.meta.drawId)
            }
            if (panelCrossContainerIdx >= 0 && id in panelMultiSelectedIds) {
                val after = scanEnginePanels()
                val selMirrors = after.mirrors.filter { it.panelId in panelMultiSelectedIds }
                if (selMirrors.isNotEmpty() && after.crossIdx >= 0) {
                    repaintCrossMask(after.crossIdx, after.crossMaskIdx, selMirrors)
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

private fun PaintViewModel.parsePanelMetaAt(id: Long): PanelMeta? =
    panels.firstOrNull { it.panelId == id }?.meta

/**
 * 更新一格样式 (边框粗细/颜色、格内填充; null 字段 = 跟随页面默认): 写元数据并重绘
 * 填充/边框 (蒙版无关, maskIdx=-1 跳过)。
 */
internal fun PaintViewModel.updatePanelStyle(id: Long, style: PanelStyle) {
    val d = panelDefaults
    val oldMeta = parsePanelMetaAt(id) ?: return
    val json = oldMeta.copy(style = style).toJson()
    val resolved = style.resolve(d)
    Breadcrumbs.record("Panel", "updatePanelStyle id=$id")
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        val cur = scanEnginePanels().mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_style))
        try {
            val ev = PanelMetaEvent(
                groupIdx = cur.groupIdx,
                maskIdx = -1,
                fillIdx = cur.fillIdx,
                borderIdx = cur.borderIdx,
                metaJson = json,
                borderWidth = resolved.borderWidth,
                borderColor = resolved.borderColor,
                fillColor = resolved.fillColor,
                fillEnabled = resolved.fillEnabled,
                cornerRadius = resolved.cornerRadius,
            )
            applyPanelMetaEvent(ev)
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_PANEL_META, cur.groupIdx, PanelMetaCodec.encode(ev))
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/**
 * 批量平移多格 (多选拖移): 单 runCore + 单撤销宏; 逐格做内容层平移 + 元数据更新 +
 * 蒙版/填充/边框重绘 (oldQuad 增量); 跨格蒙版重同步整批只做一次。
 *
 * @return ids 为空 / 增量为 0 返回 true; 任一 id 缺镜像或 delta 非有限返回 false。
 */
internal fun PaintViewModel.movePanelsBy(
    ids: Collection<Long>,
    dx: Float,
    dy: Float,
): Boolean {
    if (ids.isEmpty()) return true
    if (!dx.isFinite() || !dy.isFinite()) return false
    val dxi = dx.roundToInt()
    val dyi = dy.roundToInt()
    if (dxi == 0 && dyi == 0) return true
    val d = panelDefaults
    val failed = ArrayList<Long>()
    val jobs = ArrayList<PanelMirror>(ids.size)
    for (id in ids) {
        val m = panels.firstOrNull { it.panelId == id }
        if (m == null) failed.add(id) else jobs.add(m)
    }
    if (failed.isNotEmpty()) {
        android.util.Log.w("Panel", "movePanelsBy: mirrors missing for $failed")
        return false
    }
    val delta = Point2D(dx, dy)
    Breadcrumbs.record("Panel", "movePanelsBy n=${jobs.size} d=($dxi,$dyi)")
    // 跨格容器是否存在以平移前镜像为准 (平移不改结构); 重同步用的多选 quad 集在 op 内
    // 按平移后的引擎重扫取, 口径与 ensureCrossContainer 一致 (当前多选格的并集)
    val crossResync = panelCrossContainerIdx >= 0
    var crossStamp: List<PanelQuad>? = null
    runCore(after = {
        notifyLayerChanged(pixelChanged = true)
        // 与实际画进跨格蒙版的内容对齐, 避免下次 ensureCrossContainer 误判重画
        crossStamp?.let { panelCrossMaskStamp = it }
    }) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_move))
        try {
            // 全程无结构变化 (仅分格边框与蒙版取景框平移, 画布内容原位锚定), 索引不漂移: 一次扫描供整批定位
            val scan = scanEnginePanels()
            val movedDrawIds = jobs.map { it.meta.drawId }.toSet()
            for (m in jobs) {
                val cur = scan.mirrors.firstOrNull { it.panelId == m.panelId }
                if (cur == null) {
                    // 引擎侧定位失败: 停止后续格, 已成功的保留 (引擎与镜像经 after 重扫一致)
                    failed.add(m.panelId)
                    Breadcrumbs.record("Panel", "movePanelsBy failed: engine mirror missing for ${m.panelId}")
                    android.util.Log.w("Panel", "movePanelsBy: engine mirror missing for ${m.panelId}")
                    break
                }
                val oldQuad = cur.meta.quad
                val newQuad = oldQuad.movedBy(delta)
                val json = cur.meta.copy(quad = newQuad).toJson()
                val resolved = cur.meta.style.resolve(d)
                val ev = PanelMetaEvent(
                    groupIdx = cur.groupIdx,
                    maskIdx = cur.maskIdx,
                    fillIdx = cur.fillIdx,
                    borderIdx = cur.borderIdx,
                    metaJson = json,
                    borderWidth = resolved.borderWidth,
                    borderColor = resolved.borderColor,
                    fillColor = resolved.fillColor,
                    fillEnabled = resolved.fillEnabled,
                    cornerRadius = resolved.cornerRadius,
                )
                applyPanelMetaEvent(ev, oldQuad)
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_PANEL_META, cur.groupIdx, PanelMetaCodec.encode(ev))
                }
            }
            for (drawId in movedDrawIds) {
                syncPanelMasks(drawId)
            }
            if (crossResync) {
                val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
                if (after.crossIdx >= 0) {
                    val selIds = panelMultiSelectedIds
                    val selMirrors = if (selIds.isNotEmpty()) {
                        after.mirrors.filter { it.panelId in selIds }
                    } else {
                        emptyList()
                    }
                    val quads = selMirrors.map { it.meta.quad }
                    if (quads.isNotEmpty()) {
                        repaintCrossMask(after.crossIdx, after.crossMaskIdx, selMirrors)
                        crossStamp = quads
                    }
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
    return true
}

/** [m] 分格组子树里所有内容颜料层索引 (排除填充/边框/蒙版/嵌套组; 蒙版是组外兄弟)。 */
private fun contentIndicesOf(
    ls: List<SnapshotLayer>,
    m: PanelMirror,
): IntArray {
    val g = m.groupIdx
    if (g !in ls.indices || !ls[g].isGroup) return IntArray(0)
    var end = g
    while (end + 1 < ls.size && ls[end + 1].depth > ls[g].depth) end++
    var childEnd = end
    while (childEnd > g && ls[childEnd].nodeType == LayerNodeType.TRANSPARENCY_MASK) childEnd--
    return (g + 1..childEnd)
        .filter { idx ->
            idx != m.fillIdx && idx != m.borderIdx &&
                ls[idx].nodeType != LayerNodeType.GROUP && !LayerNodeType.isMask(ls[idx].nodeType)
        }
        .toIntArray()
}

/**
 * 删除一格: 删蒙版 + 删组 (+ 清理选中); 若删空了多选且跨格容器存在, 一并删除。
 * 剩余多选时把跨格蒙版重画成剩余格的并集。
 */
internal fun PaintViewModel.deletePanel(id: Long) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    val remaining = panelMultiSelectedIds - id
    // 只有 id 确实在多选里且删掉后多选为空, 才连带删跨格容器
    // (多选本来为空时 remaining.isEmpty() 恒真, 会误删跨格容器)
    val removeCross = id in panelMultiSelectedIds && remaining.isEmpty() && panelCrossContainerIdx >= 0
    val restMirrors = if (!removeCross && remaining.isNotEmpty()) {
        panels.filter { it.panelId in remaining }
    } else {
        emptyList()
    }
    Breadcrumbs.record("Panel", "deletePanel id=$id removeCross=$removeCross")
    runCore(after = {
        panelMultiSelectedIds = remaining
        if (removeCross) panelCrossMaskStamp = emptyList()
        notifyLayerChanged()
    }) {
        val scan = scanEnginePanels()
        val cur = scan.mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        val drawId = cur.meta.drawId
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_delete))
        try {
            // 先删高索引 (蒙版在组上方), 再删组
            if (cur.maskIdx >= 0) {
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, cur.maskIdx)
                ReverieCoreBridge.removeLayer(cur.maskIdx)
            }
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, cur.groupIdx)
            ReverieCoreBridge.removeLayer(cur.groupIdx)
            syncPanelMasks(drawId)
            if (removeCross) {
                val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
                val cross = after.crossIdx
                if (cross >= 0) {
                    if (after.crossMaskIdx >= 0) {
                        if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, after.crossMaskIdx)
                        ReverieCoreBridge.removeLayer(after.crossMaskIdx)
                    }
                    if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, cross)
                    ReverieCoreBridge.removeLayer(cross)
                }
            } else if (restMirrors.isNotEmpty()) {
                // 删除后索引已漂移, 跨格蒙版重绘前重扫一次
                val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
                if (after.crossIdx >= 0) {
                    repaintCrossMask(after.crossIdx, after.crossMaskIdx, restMirrors)
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
    if (selectedPanelId == id) selectedPanelId = null
}

/**
 * [groupIdx] 所在父组 span 内的顶层组条目 (格组 + 跨格内容组, 底→顶) 的平铺索引序;
 * depth/isGroup 由调用方按索引供给, 供 UI 镜像与引擎快照复用同一口径。
 */
private fun panelZSiblingEntries(
    size: Int,
    groupIdx: Int,
    depthAt: (Int) -> Int,
    isGroupAt: (Int) -> Boolean,
): List<Int> {
    if (groupIdx !in 0 until size) return emptyList()
    val d = depthAt(groupIdx)
    if (d <= 0) return emptyList()
    var parent = -1
    for (i in groupIdx - 1 downTo 0) {
        if (depthAt(i) < d - 1) break
        if (depthAt(i) == d - 1 && isGroupAt(i)) {
            parent = i
            break
        }
    }
    if (parent < 0) return emptyList()
    var end = parent
    while (end + 1 < size && depthAt(end + 1) > d - 1) end++
    return (parent + 1..end).filter { isGroupAt(it) && depthAt(it) == d }
}

/** 分格 z 序单步移动能力 (可上移, 可下移); 读 UI 图层镜像, 供长按菜单判断 chips 显隐。 */
internal fun PaintViewModel.panelZMoveCapability(panelId: Long): Pair<Boolean, Boolean> {
    val m = panels.firstOrNull { it.panelId == panelId } ?: return false to false
    val ls = layers
    val entries = panelZSiblingEntries(ls.size, m.groupIdx, { ls[it].depth }, { ls[it].isGroup })
    val pos = entries.indexOf(m.groupIdx)
    if (pos < 0) return false to false
    return (pos < entries.lastIndex) to (pos > 0)
}

/**
 * 分格 z 序上移/下移一层: 与根组 span 内相邻顶层条目交换位置 (L_MOVE_RELATIVE 埋点),
 * 移动后蒙版按 id 兜底跟随; 相邻条目缺失时静默返回。
 */
internal fun PaintViewModel.movePanelZBy(
    panelId: Long,
    up: Boolean,
) {
    if (!panels.any { it.panelId == panelId }) return
    Breadcrumbs.record("Panel", "movePanelZBy id=$panelId up=$up")
    runCore(after = { notifyLayerChanged() }) {
        val scan = scanEnginePanels()
        val cur = scan.mirrors.firstOrNull { it.panelId == panelId } ?: return@runCore
        if (scan.rootIdx < 0) return@runCore
        val entries = panelZSiblingEntries(
            scan.layers.size,
            cur.groupIdx,
            { scan.layers[it].depth },
            { scan.layers[it].isGroup },
        )
        val pos = entries.indexOf(cur.groupIdx)
        val target = if (up) entries.getOrNull(pos + 1) else entries.getOrNull(pos - 1)
        if (target == null || target !in scan.layers.indices) return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_reorder))
        try {
            if (recorder.recording) {
                recorder.layerOp(
                    RecordingEvents.L_MOVE_RELATIVE, cur.groupIdx, "$target:${if (up) 1 else 0}",
                )
            }
            if (!ReverieCoreBridge.moveLayerRelative(cur.groupIdx, target, up)) return@runCore
            // 蒙版紧跟组上方 (按节点 id 兜底, 引擎成对移动外的保险)
            val newGroupIdx = indexOfLayerId(cur.groupId)
            if (newGroupIdx >= 0 && cur.maskId != -1L) {
                val maskNow = indexOfLayerId(cur.maskId)
                if (maskNow >= 0 && maskNow != newGroupIdx + 1) {
                    if (recorder.recording) {
                        recorder.layerOp(RecordingEvents.L_MOVE_RELATIVE, maskNow, "$newGroupIdx:1")
                    }
                    ReverieCoreBridge.moveLayerRelative(maskNow, newGroupIdx, true)
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/**
 * 复制一格: copyLayer 对组做深拷贝 (子层 + 附着蒙版 + reverie_panel 侧表随拷),
 * 因此只需把新组的 panelId/drawId 换成新 id。
 */
internal fun PaintViewModel.duplicatePanel(id: Long) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    val newId = nextPanelId()
    val json = m.meta.copy(panelId = newId, drawId = newId).toJson()
    Breadcrumbs.record("Panel", "duplicatePanel id=$id -> newId=$newId")
    runCore(after = {
        refreshPanels()
        // 复制出的新格默认折叠 (B2 降噪); 不自动选中, 浮窗保持默认页
        collapsedPanelIds = collapsedPanelIds + newId
        notifyLayerChanged()
    }) {
        val cur = scanEnginePanels().mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_duplicate))
        try {
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_COPY, cur.groupIdx)
            val newIdx = ReverieCoreBridge.copyLayer(cur.groupIdx)
            if (newIdx < 0) return@runCore
            // 只改元数据 (mask/fill/border 索引全 -1 → 跳过重绘, 拷贝的像素已正确;
            // cornerRadius 恒 0 = 不参与渲染, 与既有 borderWidth=0f 口径一致)
            val ev = PanelMetaEvent(
                groupIdx = newIdx, maskIdx = -1, fillIdx = -1, borderIdx = -1, metaJson = json,
                borderWidth = 0f, borderColor = 0, fillColor = 0, fillEnabled = false,
                cornerRadius = 0f,
            )
            applyPanelMetaEvent(ev)
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_PANEL_META, newIdx, PanelMetaCodec.encode(ev))
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/**
 * 跨格容器: 在「分格」根组顶部建/复用组「跨格内容」{内容层} + 其上方蒙版, 蒙版 =
 * 所有选中格 quad 的并集。多选确定时调用; 落笔路径只做镜像同步, 不建结构。
 */
internal fun PaintViewModel.ensureCrossContainer(ids: Set<Long>) {
    val selected = panels.filter { it.panelId in ids }
    if (selected.isEmpty()) return
    val quads = selected.map { it.meta.quad }
    Breadcrumbs.record("Panel", "ensureCrossContainer ids=$ids")
    runCore(after = {
        refreshPanels()
        panelCrossMaskStamp = quads
        notifyLayerChanged()
    }) {
        val scan = scanEnginePanels()
        var cross = scan.crossIdx
        var crossMask = scan.crossMaskIdx
        var contentId = if (scan.crossContentIdx in scan.layers.indices) scan.layers[scan.crossContentIdx].id else -1L
        val created: Boolean
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_cross))
        try {
            if (cross < 0) {
                created = true
                val rootIdx = scan.rootIdx
                if (rootIdx < 0) return@runCore
                // 顶部插入: current = 根组最顶子层 (空根则根组本身 → 组内最底, 单子层无碍)
                val topChild = topmostChildOf(scan.layers, rootIdx)
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, topChild)
                ReverieCoreBridge.setCurrentLayer(topChild)
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_GROUP)
                cross = ReverieCoreBridge.addGroupLayer(CROSS_GROUP_NAME)
                if (cross < 0) return@runCore
                val crossGroupId = ReverieCoreBridge.layerId(cross)
                // 内容层 (current=组 → 组内)
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, cross)
                ReverieCoreBridge.setCurrentLayer(cross)
                if (recorder.recording) {
                    recorder.layerOp(
                        RecordingEvents.L_ADD_LAYER_TYPE, 0, "$CROSS_LAYER_NAME|0|${0xFFFFFFFF.toInt()}",
                    )
                }
                ReverieCoreBridge.addLayerWithType(CROSS_LAYER_NAME, 0, 0xFFFFFFFF.toInt())
                contentId = ReverieCoreBridge.layerId(ReverieCoreBridge.currentLayerIndex())
                // 蒙版 (current=蒙版); 成功才落录制, 失败时黑底不会误画进内容层
                if (!ReverieCoreBridge.addMaskToLayer(cross, MASK_TYPE_TRANSPARENCY)) return@runCore
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_MASK, cross, "0")
                crossMask = ReverieCoreBridge.currentLayerIndex()
                cross = indexOfLayerId(crossGroupId)
            } else {
                created = false
            }
            // 蒙版重绘 (新建必画; 已存在的只在选中集变化时重画)
            if (cross >= 0 && (created || panelCrossMaskStamp != quads)) {
                repaintCrossMask(cross, crossMask, selected)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
        // 落笔目标 = 跨格内容层
        val contentIdx = indexOfLayerId(contentId)
        if (contentIdx >= 0) {
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, contentIdx)
            ReverieCoreBridge.setCurrentLayer(contentIdx)
        }
    }
}

/** 跨格蒙版重绘 = 元数据标记 + 黑底 + 白 quad 并集 (含 L_PANEL_META 埋点)。 */
private fun PaintViewModel.repaintCrossMask(
    crossGroupIdx: Int,
    crossMaskIdx: Int,
    mirrors: List<PanelMirror>,
    metaJson: String = CROSS_MARKER_JSON,
) {
    // 逐格带 resolved 圆角半径, 跨格蒙版轮廓与各格一致; 已解析值随事件录制
    val d = panelDefaults
    val ev = PanelMetaEvent(
        groupIdx = crossGroupIdx, maskIdx = crossMaskIdx, fillIdx = -1, borderIdx = -1,
        metaJson = metaJson, borderWidth = 0f, borderColor = 0, fillColor = 0,
        fillEnabled = false,
        maskQuads = mirrors.map { it.meta.quad },
        maskQuadRadii = mirrors.map { it.meta.style.cornerRadius ?: d.cornerRadius },
    )
    applyPanelMetaEvent(ev)
    if (recorder.recording) {
        recorder.layerOp(RecordingEvents.L_PANEL_META, crossGroupIdx, PanelMetaCodec.encode(ev))
    }
}

/** [PaintViewModel.ensureCrossContainer] 用: 根组最顶直接子层 (跳过附属蒙版); 空根返回根组本身。 */
private fun topmostChildOf(
    ls: List<SnapshotLayer>,
    rootIdx: Int,
): Int {
    if (rootIdx !in ls.indices) return rootIdx
    val d = ls[rootIdx].depth
    var end = rootIdx
    while (end + 1 < ls.size && ls[end + 1].depth > d) end++
    var i = end
    while (i > rootIdx && ls[i].nodeType == LayerNodeType.TRANSPARENCY_MASK) i--
    return if (i > rootIdx && ls[i].depth == d + 1) i else rootIdx
}

/**
 * 多选落笔前的同步前置: 跨格内容层已在镜像中时切 UI 的 currentLayerIndex, 让笔画
 * CONTEXT 事件录到正确图层 (必须在 touchStart 的 captureContext 之前完成)。结构创建
 * 由 [PaintViewModel.ensureCrossContainer] 负责; 内容层隐藏/锁定时返回 false 不切层。
 */
internal fun PaintViewModel.panelBeginCrossStroke(): Boolean {
    val ids = panelMultiSelectedIds
    if (ids.isEmpty()) return false
    val idx = panelCrossContentIdx
    if (idx < 0) return false
    if (isLayerEffectivelyHidden(idx)) return false
    if (layers.firstOrNull { it.index == idx }?.locked == true) return false
    currentLayerIndex = idx
    selectionDirty = true
    return true
}

/** 裁剪开关 = 蒙版层可见性 + 元数据同步。 */
internal fun PaintViewModel.setPanelCrop(
    id: Long,
    crop: Boolean,
) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    val d = panelDefaults
    val resolved = m.meta.style.resolve(d)
    val json = m.meta.copy(cropEnabled = crop).toJson()
    Breadcrumbs.record("Panel", "setPanelCrop id=$id crop=$crop")
    runCore(after = { notifyLayerChanged() }) {
        val cur = scanEnginePanels().mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_crop))
        try {
            if (cur.maskIdx >= 0) {
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_VISIBLE, cur.maskIdx, if (crop) "1" else "0")
                }
                ReverieCoreBridge.setLayerVisible(cur.maskIdx, crop)
            }
            // 只写元数据 (蒙版像素不变, maskIdx=-1 跳过重绘)
            val ev = PanelMetaEvent(
                groupIdx = cur.groupIdx, maskIdx = -1, fillIdx = -1, borderIdx = -1, metaJson = json,
                borderWidth = resolved.borderWidth, borderColor = resolved.borderColor,
                fillColor = resolved.fillColor, fillEnabled = resolved.fillEnabled,
                cornerRadius = resolved.cornerRadius,
            )
            applyPanelMetaEvent(ev)
            if (recorder.recording) {
                recorder.layerOp(RecordingEvents.L_PANEL_META, cur.groupIdx, PanelMetaCodec.encode(ev))
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/**
 * 应用页面默认样式: 更新 [panelDefaults] (随 extraJson 持久化), 并对跟随默认字段的格
 * 重绘填充/边框 (一个宏一条撤销步)。
 */
internal fun PaintViewModel.applyPanelDefaults(d: PanelDefaults) {
    panelDefaults = d
    val affected = panels.filter { m ->
        m.meta.style.let {
            it.borderWidth == null || it.borderColor == null || it.fillColor == null ||
                it.fillEnabled == null || it.cornerRadius == null
        }
    }
    if (affected.isEmpty() && panelRootGroupIdx < 0) return
    Breadcrumbs.record("Panel", "applyPanelDefaults affected=${affected.size} root=$panelRootGroupIdx")
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        // 一次扫描定位全部格 (循环内只做像素重绘, 无结构变化, 索引不漂移);
        // 逐格重扫是 O(N²) 且每格多一次图层快照读取
        val curScan = scanEnginePanels()
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_defaults))
        try {
            if (curScan.rootIdx >= 0) {
                ReverieCoreBridge.setPanelMeta(curScan.rootIdx, panelDefaultsJson())
            }
            for (m in affected) {
                val cur = curScan.mirrors.firstOrNull { it.panelId == m.meta.panelId } ?: continue
                val resolved = m.meta.style.resolve(d)
                // 蒙版与默认样式无关 (maskIdx=-1 跳过蒙版重绘)
                val ev = PanelMetaEvent(
                    groupIdx = cur.groupIdx, maskIdx = -1, fillIdx = cur.fillIdx, borderIdx = cur.borderIdx,
                    metaJson = m.meta.toJson(), borderWidth = resolved.borderWidth,
                    borderColor = resolved.borderColor, fillColor = resolved.fillColor,
                    fillEnabled = resolved.fillEnabled, cornerRadius = resolved.cornerRadius,
                )
                applyPanelMetaEvent(ev)
                if (recorder.recording) {
                    recorder.layerOp(RecordingEvents.L_PANEL_META, cur.groupIdx, PanelMetaCodec.encode(ev))
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 双击进格: 把当前层切到该格的内容层 (共用画时解析到共享容器内容层)。 */
internal fun PaintViewModel.enterPanelPainting(id: Long) {
    val idx = panelContentLayerIndex(id)
    if (idx >= 0) {
        setCurrentLayer(idx)
    }
}

// ---- 持久化 (extraJson 的 panelDefaults 片段, 供 PaintViewModelProjects 拼装) ----

/** 默认值序列化为 extraJson 里的 JSON 对象片段 (手写极简, 与 loadProject 的解析配对)。 */
internal fun PaintViewModel.panelDefaultsJson(): String {
    val d = panelDefaults
    return "{\"borderWidth\": ${d.borderWidth}, \"borderColor\": ${d.borderColor}, " +
        "\"hGap\": ${d.hGap}, \"vGap\": ${d.vGap}, \"fillColor\": ${d.fillColor}, " +
        "\"fillEnabled\": ${d.fillEnabled}, \"cornerRadius\": ${d.cornerRadius}, " +
        "\"cropEnabled\": ${d.cropEnabled}}"
}
