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
import com.reverie.paint.model.toJson
import kotlin.math.roundToInt

/**
 * 漫画分格工具会话层 (ViewModel partial, 参考 PaintViewModelStoryboard 的 partial 模式)。
 *
 * 文档结构约定 (与 C++ 底座对齐):
 *  - 根下建一个组「分格」(首个分格创建时自动创建), 所有分格组是它的子层;
 *  - 每格 = 一个图层组, 组内自底向上: 「格内填充」颜料层、「内容」颜料层、「格边框」颜料层;
 *  - 裁剪蒙版 = 分格组的**同级上方兄弟** (addMaskToLayer(groupIdx, 0) 的现有行为,
 *    侧表 flag reverie_is_transparency_mask 自动打上), 白=透光/黑=遮挡;
 *  - 裁剪开关 = 蒙版层可见性 (setLayerVisible);
 *  - 格元数据 (PanelMeta.toJson) 存分格组的 reverie_panel 节点侧表, 随 maindoc.xml 持久化;
 *  - 跨格容器 = 「分格」根组顶部的组「跨格内容」+ 其上方蒙版 (白色 quad = 所有选中格并集),
 *    用侧表标记 {"crossContent":true} 识别 (parsePanelMeta 解析失败 → 不算分格)。
 *
 * 索引是镜像: 图层任何结构变化都会让索引漂移, 因此所有操作在 runCore 内先用
 * [scanEnginePanels] 重扫定位 (按 panelId 匹配), 镜像由 [refreshPanels] 重建。
 *
 * 录制协议: 结构增删复用既有 L_* 码; 元数据 + 像素级重绘 (蒙版/填充/边框) 统一用
 * [RecordingEvents.L_PANEL_META] (payload 见 [PanelMetaCodec]), 回放端据此重绘出与
 * 录制时一致的蒙版/填充/边框; 内容层像素平移复用 T_MOVE_CONTENT_LAYERS + T_MOVE_CONTENT。
 *
 * 状态挂载: 本文件的状态是 PaintViewModel 的顶层扩展属性 (mutableStateOf), 依赖
 * 单实例引擎假设 (AGENTS.md §4 铁律 6), 全局只有一份 PaintViewModel。
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
/** 蒙版语义: 黑=遮挡, 白=透光。 */
private const val MASK_BLOCK_ARGB = 0xFF000000.toInt()
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

internal var PaintViewModel.panelDefaults: PanelDefaults by mutableStateOf(DEFAULT_PANEL_DEFAULTS)

/** 跨格容器组镜像索引 (-1 = 不存在)。 */
internal var PaintViewModel.panelCrossContainerIdx: Int by mutableIntStateOf(-1)

/** 跨格内容层镜像索引/节点 id (-1 = 不存在)。 */
internal var PaintViewModel.panelCrossContentIdx: Int by mutableIntStateOf(-1)
private var PaintViewModel.panelCrossContentId: Long by mutableLongStateOf(-1L)

/** 已画进跨格蒙版的 quad 集 (去重绘; 空列表 + 容器存在 = 需要重画)。 */
private var PaintViewModel.panelCrossMaskStamp: List<PanelQuad> by mutableStateOf(emptyList())

/** 当前工具是否分格工具 (UI 下一步接 "panel" 工具 id)。 */
internal fun PaintViewModel.panelSessionActive(): Boolean = currentToolId == "panel"

// ---- 引擎侧扫描 (render thread / main thread 通用) ----

/** 一次引擎侧扫描的结果: 分格镜像 + 根组/跨格容器定位。 */
internal class PanelEngineScan(
    val layers: List<SnapshotLayer>,
    val rootIdx: Int,
    val crossIdx: Int,
    val crossMaskIdx: Int,
    val crossContentIdx: Int,
    val mirrors: List<PanelMirror>,
)

/**
 * 从图层快照平铺列表扫描分格结构。平铺顺序 = 树遍历 (自底向上), 组的子树占连续区段;
 * 附属透明蒙版 (PaintLayer + 侧表 flag) 紧跟在子树末尾, 其深度被 C++ 侧刻意 +1。
 */
internal fun scanPanelsFrom(ls: List<SnapshotLayer>): PanelEngineScan {
    val mirrors = ArrayList<PanelMirror>()
    var cross = -1
    var crossMask = -1
    var crossContent = -1
    for (i in ls.indices) {
        val e = ls[i]
        if (!e.isGroup) continue
        val json = ReverieCoreBridge.panelMeta(e.index) ?: continue
        val meta = parsePanelMeta(json) ?: run {
            // 非分格元数据: 只认跨格容器标记
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
    return PanelEngineScan(ls, root, cross, crossMask, crossContent, mirrors)
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
 * (回退: 最底), 边框 = 带"格边框"前缀的最后一个直接子层 (回退: 最顶), 其余为内容。
 * scanPanelsFrom 与切分/合并的内容搬移共用此分类, 防止两处口径漂移 —— 否则用户
 * 重命名填充/边框层后, 内容搬移会把这些层当内容搬走, 造成视觉错乱。
 * 返回 (fillIdx, borderIdx, contentIdxs), 索引为平铺列表下标; fill==border 时 fill = -1。
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

private fun PaintViewModel.scanEnginePanels(): PanelEngineScan {
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
)

/**
 * L_PANEL_META 的字符串编解码。
 *
 * 格式: `groupIdx|maskIdx|fillIdx|borderIdx|panelJson|borderWidth|borderColor|fillColor|fillEnabled[|maskQuads=...]`
 * - panelJson: [com.reverie.paint.model.PanelMeta.toJson] 产出, 只含数字/布尔/结构符,
 *   **不含 '|'** (parsePanelMeta 不支持字符串值), 可用 '-' 表示无元数据;
 * - maskQuads: `x,y;x,y;...` 每组 8 个数 (跨格容器的多 quad 蒙版);
 * - 全文确定性、可往返 (Float.toString ↔ toFloatOrNull 一一对应)。
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
        if (f.size >= 10 && f[9].startsWith(QUADS_KEY)) {
            maskQuads = f[9].substring(QUADS_KEY.length).split(';').mapNotNull { seg ->
                val xy = seg.split(',').mapNotNull { it.toFloatOrNull() }
                val n = xy.size / 2
                if (xy.size % 2 == 0 && n in PanelQuad.MIN_VERTICES..PanelQuad.MAX_VERTICES) {
                    PanelQuad.of(xy.chunked(2).map { Point2D(it[0], it[1]) })
                } else {
                    null
                }
            }
        }
        return PanelMetaEvent(
            groupIdx, maskIdx, fillIdx, borderIdx, metaJson,
            borderWidth, borderColor, fillColor, fillEnabled, maskQuads,
        )
    }
}

/**
 * 执行一条 [PanelMetaEvent]: 写元数据 + 重绘蒙版(黑底+白 quad)/填充/边框(外框+ERASE 内框)。
 * 渲染线程直连 Bridge (live 操作的 op 体与回放 dispatch 共用此路径); index<0 的部分跳过。
 */
internal fun applyPanelMetaEvent(e: PanelMetaEvent) {
    if (e.groupIdx >= 0 && !e.metaJson.isNullOrEmpty()) {
        runCatching { ReverieCoreBridge.setPanelMeta(e.groupIdx, e.metaJson) }
    }
    val metaQuad = e.metaJson?.let { parsePanelMeta(it)?.quad }
    val maskQuads: List<PanelQuad>? = when {
        e.maskIdx < 0 -> null
        e.maskQuads != null -> e.maskQuads
        else -> metaQuad?.let { listOf(it) }
    }
    if (!maskQuads.isNullOrEmpty()) {
        val w = ReverieCoreBridge.docWidth()
        val h = ReverieCoreBridge.docHeight()
        if (w > 0 && h > 0) {
            // 黑底整幅覆盖 (白=透光/黑=遮挡), 再按 quad 画白 → 旧形状无需清除
            ReverieCoreBridge.paintPanelQuad(e.maskIdx, canvasQuadXY(w, h), MASK_BLOCK_ARGB, false)
            for (q in maskQuads) {
                ReverieCoreBridge.paintPanelQuad(e.maskIdx, quadToXY(q), MASK_SHOW_ARGB, false)
            }
        }
    }
    if (e.fillIdx >= 0 && e.fillIdx != e.borderIdx) {
        if (e.fillEnabled) {
            val quad = metaQuad
            if (quad != null) {
                ReverieCoreBridge.clearLayer(e.fillIdx)
                ReverieCoreBridge.paintPanelQuad(e.fillIdx, quadToXY(quad), e.fillColor, false)
            }
        } else {
            // 格内填充开关关闭: 清掉已画填充, 不留旧像素; 回放走同一函数, 效果自动一致
            ReverieCoreBridge.clearLayer(e.fillIdx)
        }
    }
    if (e.borderIdx >= 0) {
        val quad = metaQuad
        if (quad != null) {
            ReverieCoreBridge.clearLayer(e.borderIdx)
            ReverieCoreBridge.paintPanelQuad(e.borderIdx, quadToXY(quad), e.borderColor, false)
            val inner = quad.insetBorder(e.borderWidth)
            if (inner !== quad) {
                // ERASE 挖洞 → 边框环
                ReverieCoreBridge.paintPanelQuad(e.borderIdx, quadToXY(inner), e.borderColor, true)
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

private fun canvasQuadXY(w: Int, h: Int): FloatArray =
    floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())

// ---- 镜像重建 ----

/**
 * 从引擎重扫分格结构, 重建 [panels] / 跨格容器镜像, 并修剪失效的选中集合。
 * 主线程调用 (notifyLayerChanged / 文档加载后的同步点); 快照不可用时保持上次镜像。
 */
internal fun PaintViewModel.refreshPanels() {
    val page = LayerSnapshotProbe.read() ?: return
    val s = scanPanelsFrom(page.layers)
    panels = s.mirrors
    panelCrossContainerIdx = s.crossIdx
    panelCrossContentIdx = s.crossContentIdx
    panelCrossContentId = if (s.crossContentIdx in s.layers.indices) s.layers[s.crossContentIdx].id else -1L
    if (selectedPanelId != null && s.mirrors.none { it.panelId == selectedPanelId }) {
        selectedPanelId = null
    }
    if (panelMultiSelectedIds.isNotEmpty()) {
        val valid = s.mirrors.asSequence().map { it.panelId }.toSet()
        panelMultiSelectedIds = panelMultiSelectedIds.intersect(valid)
    }
}

private fun PaintViewModel.nextPanelId(): Long =
    (scanEnginePanels().mirrors.maxOfOrNull { it.meta.panelId } ?: 0L) + 1L

// ---- 操作 (每个 = beginUndoMacro/endUndoMacro 一条撤销步; 录制埋点齐全) ----

/**
 * 新建一格: 建根组(若无) → 分格组 → 填充/内容/边框三层 → 裁剪蒙版 → 重绘视觉 → 写元数据。
 */
internal fun PaintViewModel.createPanelAt(quad: PanelQuad) {
    if (coreW <= 0 || coreH <= 0) return
    if (quad.isDegenerate(MIN_PANEL_AREA)) return
    val panelId = nextPanelId()
    val meta = PanelMeta(quad = quad, style = PanelStyle(), cropEnabled = true, panelId = panelId)
    Breadcrumbs.record("Panel", "createPanelAt id=$panelId")
    runCore(after = {
        refreshPanels()
        selectedPanelId = panelId
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_create))
        try {
            val build = buildPanelStructureOp(meta, meta.style.resolve(panelDefaults)) ?: return@runCore
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
 * 在撤销宏内构造一个完整分格结构: 根组(若无) → 分格组 → 填充/内容/边框三层 → 裁剪蒙版 →
 * 元数据 + 视觉重绘。渲染线程直连 Bridge, 调用方必须已处于 beginUndoMacro/endUndoMacro
 * 之间; 含录制埋点; 内部自行重扫定位。失败返回 null。
 */
private fun PaintViewModel.buildPanelStructureOp(
    meta: PanelMeta,
    resolved: ResolvedPanelStyle,
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
    }
    // 2. 分格组挂到根组底部 (current=组 → 组内最底; 各格间 z 序由 reorderPanel 调整)
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, rootIdx)
    ReverieCoreBridge.setCurrentLayer(rootIdx)
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_ADD_GROUP)
    val groupIdx = ReverieCoreBridge.addGroupLayer("格 ${scan.mirrors.size + 1}")
    if (groupIdx < 0) return null
    val groupId = ReverieCoreBridge.layerId(groupIdx)
    // 3. 组内自底向上: 填充 → 内容 → 边框 (current=组, 每层依次叠在上一层上方)
    if (recorder.recording) recorder.layerOp(RecordingEvents.L_SET_CURRENT, groupIdx)
    ReverieCoreBridge.setCurrentLayer(groupIdx)
    for (name in listOf(FILL_LAYER_NAME, CONTENT_LAYER_NAME, BORDER_LAYER_NAME)) {
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
        borderIdx = direct.getOrNull(2) ?: -1,
        metaJson = json,
        borderWidth = resolved.borderWidth,
        borderColor = resolved.borderColor,
        fillColor = resolved.fillColor,
        fillEnabled = resolved.fillEnabled,
    )
    // 6. 元数据 + 视觉重绘 (黑底白 quad 蒙版 / 填充 / 边框环)
    applyPanelMetaEvent(ev)
    if (recorder.recording) {
        recorder.layerOp(RecordingEvents.L_PANEL_META, ev.groupIdx, PanelMetaCodec.encode(ev))
    }
    return PanelBuildResult(
        groupIdx = groupNow,
        groupId = groupId,
        contentIdx = direct.getOrNull(1) ?: -1,
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
 * 直线切分: 用 start→end 的无限直线切开所有被穿过的格, 每格变两格 (切线两侧各让
 * hGap/2 间隙), 内容层留在面积较大的一半, 另一半空内容层。一个撤销宏; 录制全用
 * 既有结构码 + L_PANEL_META, 回放零新码。
 */
internal fun PaintViewModel.dividePanelsByLine(start: Point2D, end: Point2D) {
    if (coreW <= 0 || coreH <= 0) return
    Breadcrumbs.record("Panel", "divideByLine")
    runCore(after = {
        refreshPanels()
        selectedPanelId = null
        notifyLayerChanged(pixelChanged = true)
    }) {
        val halfGap = panelDefaults.hGap / 2f
        val scan = scanEnginePanels()
        // 预筛 + 收集内容层 id (结构变化前快照)
        data class CutJob(val mirror: PanelMirror, val a: PanelQuad, val b: PanelQuad, val contentIds: List<Long>)
        val jobs = ArrayList<CutJob>()
        for (m in scan.mirrors) {
            val halves = m.meta.quad.cutByLine(start, end, halfGap) ?: continue
            val page = LayerSnapshotProbe.read()
            val contentIds = if (page != null && m.groupIdx in page.layers.indices) {
                contentLayerIdsOf(page, m.groupIdx)
            } else {
                emptyList()
            }
            jobs.add(CutJob(m, halves.first, halves.second, contentIds))
        }
        if (jobs.isEmpty()) return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_divide))
        try {
            for (job in jobs) {
                // 先建两半 (build 内部自行重扫, 索引新鲜; 此时旧格仍在)。
                // 填充样式继承旧格的已解析值 (否则开过填充的格被切开后丢底色);
                // 边框随新格默认样式重绘 (旧边框像素随旧层删除)。
                val oldResolved = job.mirror.meta.style.resolve(panelDefaults)
                val inheritedStyle = PanelStyle(
                    fillColor = oldResolved.fillColor,
                    fillEnabled = oldResolved.fillEnabled,
                )
                val panelIdA = nextPanelId()
                val buildA = buildPanelStructureOp(
                    PanelMeta(quad = job.a, style = inheritedStyle, cropEnabled = true, panelId = panelIdA),
                    inheritedStyle.resolve(panelDefaults),
                ) ?: continue
                val panelIdB = nextPanelId()
                val buildB = buildPanelStructureOp(
                    PanelMeta(quad = job.b, style = inheritedStyle, cropEnabled = true, panelId = panelIdB),
                    inheritedStyle.resolve(panelDefaults),
                ) ?: continue
                // 内容层留在面积较大的一半 (索引按 id 重定位)。必须先把内容搬出旧组
                // 再删旧格 —— 删组会连同组内内容层一起删除, 顺序颠倒即丢内容。
                val targetGroupId = if (job.a.area() >= job.b.area()) buildA.groupId else buildB.groupId
                val targetGroupIdx = indexOfLayerId(targetGroupId)
                if (targetGroupIdx >= 0) {
                    for (cid in job.contentIds) {
                        val idx = indexOfLayerId(cid)
                        if (idx < 0) continue
                        if (recorder.recording) {
                            recorder.layerOp(RecordingEvents.L_MOVE_TO_GROUP, idx, targetGroupIdx.toString())
                        }
                        ReverieCoreBridge.moveLayerToGroup(idx, targetGroupIdx)
                    }
                }
                // 内容搬出后再删旧格 (蒙版先删, 索引按 id 重定位)
                removePanelNodesById(job.mirror)
            }
            // 跨格容器蒙版重同步: 格集变化后跨可见区域必须重画为剩余格并集
            val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
            if (after.crossIdx >= 0 && after.mirrors.isNotEmpty()) {
                repaintCrossMask(after.crossIdx, after.crossMaskIdx, after.mirrors.map { it.meta.quad })
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
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
    runCore(after = {
        refreshPanels()
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
            val newPanelId = nextPanelId()
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
                repaintCrossMask(after.crossIdx, after.crossMaskIdx, after.mirrors.map { it.meta.quad })
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

private fun PaintViewModel.parsePanelMetaAt(id: Long): PanelMeta? =
    panels.firstOrNull { it.panelId == id }?.meta

/**
 * 更新一格样式 (边框粗细/颜色、格内填充; null 字段 = 跟随页面默认): 写元数据并重绘
 * 受影响视觉 (蒙版无关, maskIdx=-1 跳过)。与 [updatePanelShape] 同构的独立新增操作,
 * 不改动既有函数语义 (UI 层属性面板的样式编辑落盘路径)。
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
 * 平移一格: 组内内容颜料层整体平移 (复用 T_MOVE_CONTENT_LAYERS/T_MOVE_CONTENT 的引擎路径,
 * 无选区时是零拷贝的设备偏移) + 蒙版/填充/边框按平移后的 quad 重绘 + 元数据更新。
 */
internal fun PaintViewModel.movePanelBy(
    id: Long,
    dx: Float,
    dy: Float,
) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    if (!dx.isFinite() || !dy.isFinite()) return
    val dxi = dx.roundToInt()
    val dyi = dy.roundToInt()
    if (dxi == 0 && dyi == 0) return
    val d = panelDefaults
    val oldMeta = m.meta
    val newQuad = oldMeta.quad.movedBy(Point2D(dx, dy))
    if (newQuad === oldMeta.quad) return
    val json = oldMeta.copy(quad = newQuad).toJson()
    val resolved = oldMeta.style.resolve(d)
    Breadcrumbs.record("Panel", "movePanelBy id=$id d=($dxi,$dyi)")
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        val scan = scanEnginePanels()
        val cur = scan.mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        val content = contentIndicesOf(scan.layers, cur)
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_move))
        try {
            if (content.isNotEmpty()) {
                if (recorder.recording) {
                    recorder.toolOp(RecordingEvents.T_MOVE_CONTENT_LAYERS) {
                        it.u16(content.size.coerceIn(0, 0xFFFF))
                        for (idx in content) it.u16(idx.coerceIn(0, 0xFFFF))
                    }
                    recorder.toolOp(RecordingEvents.T_MOVE_CONTENT) {
                        it.f32(dxi.toFloat())
                        it.f32(dyi.toFloat())
                    }
                }
                ReverieCoreBridge.moveLayerContentLayers(content, dxi, dyi, ignoreSelection = true)
            }
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
    val restQuads = if (!removeCross && remaining.isNotEmpty()) {
        panels.filter { it.panelId in remaining }.map { it.meta.quad }
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
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_delete))
        try {
            // 先删高索引 (蒙版在组上方), 再删组
            if (cur.maskIdx >= 0) {
                if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, cur.maskIdx)
                ReverieCoreBridge.removeLayer(cur.maskIdx)
            }
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_REMOVE, cur.groupIdx)
            ReverieCoreBridge.removeLayer(cur.groupIdx)
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
            } else if (restQuads.isNotEmpty()) {
                // 删除后索引已漂移, 跨格蒙版重绘前重扫一次
                val after = scanPanelsFrom(LayerSnapshotProbe.read()?.layers ?: emptyList())
                if (after.crossIdx >= 0) {
                    repaintCrossMask(after.crossIdx, after.crossMaskIdx, restQuads)
                }
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
    if (selectedPanelId == id) selectedPanelId = null
}

/** 调整 z 序: 组与蒙版成对移动 (保持蒙版紧贴组上方); 跨格容器始终保持在最顶。 */
internal fun PaintViewModel.reorderPanel(
    id: Long,
    toTop: Boolean,
) {
    if (!panels.any { it.panelId == id }) return
    Breadcrumbs.record("Panel", "reorderPanel id=$id toTop=$toTop")
    runCore(after = { notifyLayerChanged() }) {
        val scan = scanEnginePanels()
        val cur = scan.mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        if (scan.rootIdx < 0) return@runCore
        val siblings = siblingPanelGroupsOf(scan.layers, cur.groupIdx, scan.rootIdx, scan.crossIdx)
        if (siblings.isEmpty()) return@runCore
        val target = if (toTop) siblings.last() else siblings.first()
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_reorder))
        try {
            if (recorder.recording) {
                recorder.layerOp(
                    RecordingEvents.L_MOVE_RELATIVE, cur.groupIdx, "$target:${if (toTop) 1 else 0}",
                )
            }
            if (!ReverieCoreBridge.moveLayerRelative(cur.groupIdx, target, toTop)) return@runCore
            // 蒙版紧跟组上方; 组移动后按节点 id 重新定位
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

private fun siblingPanelGroupsOf(
    ls: List<SnapshotLayer>,
    groupIdx: Int,
    rootIdx: Int,
    crossIdx: Int,
): List<Int> {
    if (groupIdx !in ls.indices || rootIdx !in ls.indices) return emptyList()
    val d = ls[groupIdx].depth
    var end = rootIdx
    while (end + 1 < ls.size && ls[end + 1].depth > ls[rootIdx].depth) end++
    return (rootIdx + 1..end).filter {
        it != groupIdx && it != crossIdx && ls[it].isGroup && ls[it].depth == d
    }
}

/**
 * 复制一格: copyLayer 对组做**深拷贝** (子层 + 附着蒙版含像素与侧表 flag + reverie_panel
 * 侧表全部随拷, 见 ReverieCoreLayers.cpp copyLayer), 因此只需把新组的 panelId 换成新 id。
 */
internal fun PaintViewModel.duplicatePanel(id: Long) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    val newId = nextPanelId()
    val json = m.meta.copy(panelId = newId).toJson()
    Breadcrumbs.record("Panel", "duplicatePanel id=$id -> newId=$newId")
    runCore(after = {
        refreshPanels()
        selectedPanelId = newId
        notifyLayerChanged()
    }) {
        val cur = scanEnginePanels().mirrors.firstOrNull { it.panelId == id } ?: return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_duplicate))
        try {
            if (recorder.recording) recorder.layerOp(RecordingEvents.L_COPY, cur.groupIdx)
            val newIdx = ReverieCoreBridge.copyLayer(cur.groupIdx)
            if (newIdx < 0) return@runCore
            // 只改元数据 (mask/fill/border 索引全 -1 → 跳过重绘, 拷贝的像素已正确)
            val ev = PanelMetaEvent(
                groupIdx = newIdx, maskIdx = -1, fillIdx = -1, borderIdx = -1, metaJson = json,
                borderWidth = 0f, borderColor = 0, fillColor = 0, fillEnabled = false,
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
 * 跨格容器 (最小实现): 在「分格」根组顶部建/复用组「跨格内容」{内容层} + 其上方蒙版,
 * 蒙版 = 底一次黑 + 所有选中格 quad 依次白。多选确定的瞬间调用 (CanvasTouchView 框选 UP
 * 写完 multiSelectedIds 后即刻), 引擎侧建组/切层随 runCore 完成并录制 L_SET_CURRENT,
 * 回放跟随 —— 落笔路径 (panelBeginCrossStroke) 只做镜像同步, 不再建结构。
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
                repaintCrossMask(cross, crossMask, quads)
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
    quads: List<PanelQuad>,
) {
    val ev = PanelMetaEvent(
        groupIdx = crossGroupIdx, maskIdx = crossMaskIdx, fillIdx = -1, borderIdx = -1,
        metaJson = CROSS_MARKER_JSON, borderWidth = 0f, borderColor = 0, fillColor = 0,
        fillEnabled = false, maskQuads = quads,
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
 * 多选状态下落笔的同步前置: 跨格内容层已在镜像中时, 同步切 UI 的 currentLayerIndex,
 * 让笔画 CONTEXT 事件 (captureContext) 录到正确图层 —— 纯内存镜像写, 必须在
 * touchStart 的 captureContext 读取 currentLayerIndex 之前同步完成。
 *
 * 结构创建/引擎侧切换不在落笔路径: 改由多选确定的瞬间 (CanvasTouchView 框选 UP) 即刻
 * [PaintViewModel.ensureCrossContainer]。跨格内容层被隐藏/锁定时这里不切层 (返回 false),
 * 落笔按当前层走 touchStart 既有检查 —— 不切层、不建结构, 检查语义保持单一。
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
 * 应用页面默认样式: 立即更新 [panelDefaults] (随 extraJson 持久化), 并对所有"未覆盖
 * 对应字段"的格 (style 对应字段为 null 跟随默认) 重绘填充/边框 (一个宏一条撤销步)。
 */
internal fun PaintViewModel.applyPanelDefaults(d: PanelDefaults) {
    panelDefaults = d
    val affected = panels.filter { m ->
        m.meta.style.let {
            it.borderWidth == null || it.borderColor == null || it.fillColor == null || it.fillEnabled == null
        }
    }
    if (affected.isEmpty()) return
    Breadcrumbs.record("Panel", "applyPanelDefaults affected=${affected.size}")
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        // 一次扫描定位全部格 (循环内只做像素重绘, 无结构变化, 索引不漂移);
        // 逐格重扫是 O(N²) 且每格多一次图层快照读取
        val scan = scanEnginePanels()
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.panel_op_defaults))
        try {
            for (m in affected) {
                val cur = scan.mirrors.firstOrNull { it.panelId == m.meta.panelId } ?: continue
                val resolved = m.meta.style.resolve(d)
                // 蒙版与默认样式无关 (maskIdx=-1 跳过蒙版重绘)
                val ev = PanelMetaEvent(
                    groupIdx = cur.groupIdx, maskIdx = -1, fillIdx = cur.fillIdx, borderIdx = cur.borderIdx,
                    metaJson = m.meta.toJson(), borderWidth = resolved.borderWidth,
                    borderColor = resolved.borderColor, fillColor = resolved.fillColor,
                    fillEnabled = resolved.fillEnabled,
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

/** 双击进格: 把当前层切到该格的内容层。 */
internal fun PaintViewModel.enterPanelPainting(id: Long) {
    val m = panels.firstOrNull { it.panelId == id } ?: return
    if (m.contentIdx >= 0) {
        setCurrentLayer(m.contentIdx)
    }
}

// ---- 持久化 (extraJson 的 panelDefaults 片段, 供 PaintViewModelProjects 拼装) ----

/** 默认值序列化为 extraJson 里的 JSON 对象片段 (手写极简, 与 loadProject 的解析配对)。 */
internal fun PaintViewModel.panelDefaultsJson(): String {
    val d = panelDefaults
    return "{\"borderWidth\": ${d.borderWidth}, \"borderColor\": ${d.borderColor}, " +
        "\"hGap\": ${d.hGap}, \"vGap\": ${d.vGap}, \"fillColor\": ${d.fillColor}, " +
        "\"fillEnabled\": ${d.fillEnabled}}"
}
