/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PanelMirror
import com.reverie.paint.core.panelDefaults
import com.reverie.paint.core.panelDivideMode
import com.reverie.paint.core.panelMultiSelectedIds
import com.reverie.paint.core.duplicatePanel
import com.reverie.paint.core.currentPaintingPanelIds
import com.reverie.paint.core.adjacentPanelGroupInfo
import com.reverie.paint.core.canSeparatePanelGroup
import com.reverie.paint.core.separatePanelToNewGroup
import com.reverie.paint.core.mergePanelGroup
import com.reverie.paint.core.deletePanel
import com.reverie.paint.core.mergePanels
import com.reverie.paint.core.movePanelZBy
import com.reverie.paint.core.panelZMoveCapability
import com.reverie.paint.core.panels
import com.reverie.paint.core.updatePanelStyle
import com.reverie.paint.model.PanelStyle
import com.reverie.paint.core.selectedPanelId
import com.reverie.paint.model.PanelBounds
import com.reverie.paint.model.PanelDefaults
import com.reverie.paint.model.PanelQuad
import com.reverie.paint.model.Point2D
import com.reverie.paint.ui.painting.panels.ToolFloatChip
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlin.math.roundToInt

/** 一条拖动预览: 某 格 的预览四角 (doc 坐标), 覆盖层用它替代该格镜像轮廓。 */
internal class PanelPreviewEntry(val panelId: Long, val quad: PanelQuad)

/**
 * 吸附参考线: 贯穿移动后边位置的虚线 (Morandi.accent 色)。
 * [axis]: 0 = x 轴向吸附 (垂直线 x = coord), 1 = y 轴向吸附 (水平线 y = coord)。
 * [coord]: doc 坐标。
 */
internal data class PanelSnapGuide(val axis: Int, val coord: Float)

/**
 * 分格工具手势 ↔ 覆盖层的共享预览状态 (CanvasTouchView 写, CanvasOverlay 读;
 * 挂载在 [CanvasView] 里 remember, 传给两侧)。全部为 Compose 快照状态,
 * 变化即触发覆盖层重绘; 引擎调用在抬笔时才发生 (拖动中不入撤销栈)。
 */
internal class PanelInteractionState {

    /** 拖动中的预览四角 (手柄拖形 / 格体拖移 / 共享边间隔拖动)。 */
    var dragPreviews: List<PanelPreviewEntry> by mutableStateOf(emptyList())

    /** 框选多选矩形 (doc 坐标)。 */
    var marqueeRect: PanelBounds? by mutableStateOf(null)

    /** 新建格拖拽矩形 (doc 坐标, 未内缩)。 */
    var createRect: PanelBounds? by mutableStateOf(null)

    /** 切分直线预览 (doc 坐标起点与终点)。 */
    var divideLine: Pair<Point2D, Point2D>? by mutableStateOf(null)

    /** 切分高亮格 panelId 集合 (与切线内部相交的格)。 */
    var divideHighlightPanelIds: Set<Long> by mutableStateOf(emptySet())

    /** 吸附参考线 (doc 坐标, 抬起/作废手势时清空)。单格居中最多横竖各一条。 */
    var snapGuides: List<PanelSnapGuide> by mutableStateOf(emptyList())

    /** 长按菜单锚点 (画布视图内坐标) 与目标格。 */
    var menuScreenPos: Offset? by mutableStateOf(null)
    var menuPanelId: Long? by mutableStateOf(null)

    /** 结束一次拖动: 清预览与矩形 (菜单不在此清, 抬笔后保持)。 */
    fun clearDrag() {
        dragPreviews = emptyList()
        marqueeRect = null
        createRect = null
        divideLine = null
        divideHighlightPanelIds = emptySet()
        snapGuides = emptyList()
    }

    fun dismissMenu() {
        menuScreenPos = null
        menuPanelId = null
    }
}

/** doc 坐标多边形 (可为 PanelQuad.roundedCorners 的密化顶点) → 覆盖层位图空间路径。 */
private fun DrawScope.panelQuadPath(
    points: List<Point2D>,
    bmpWidth: Float,
    bmpHeight: Float,
    scX: Float,
    scY: Float,
): Path {
    val halfW = bmpWidth / 2f
    val halfH = bmpHeight / 2f
    val path = Path()
    points.forEachIndexed { i, c ->
        val p = Offset(c.x * scX - halfW, c.y * scY - halfH)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

private fun DrawScope.docBoundsPath(
    bounds: PanelBounds,
    bmpWidth: Float,
    bmpHeight: Float,
    scX: Float,
    scY: Float,
): Path {
    val halfW = bmpWidth / 2f
    val halfH = bmpHeight / 2f
    val path = Path()
    val pts = listOf(
        bounds.left to bounds.top,
        bounds.right to bounds.top,
        bounds.right to bounds.bottom,
        bounds.left to bounds.bottom,
    )
    pts.forEachIndexed { i, (x, y) ->
        val p = Offset(x.toFloat() * scX - halfW, y.toFloat() * scY - halfH)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

// ---- 逐帧绘制复用对象 (参照 CanvasOverlay.kt 的复用模板; 绘制只在主线程执行,
// 顶层单实例复用安全) ----

// Morandi 色值是常量语义, copy 结果预构建一次 (Color 是 inline value class, 原实现
// 也不产生堆分配, 这里上提只为消除每次调用的重复计算)
private val PANEL_OUTLINE_COLOR = Morandi.subText.copy(alpha = 0.75f)
private val PANEL_PREVIEW_FILL = Morandi.accent.copy(alpha = 0.15f)
private val PANEL_MULTI_SELECT_COLOR = Morandi.accent.copy(alpha = 0.7f)
private val PANEL_DIVIDE_HINT_FILL = Morandi.accent.copy(alpha = 0.25f)
private val PANEL_PAINT_HINT_COLOR = Morandi.accent.copy(alpha = 0.85f)

// Stroke/PathEffect 依赖 (density, viewScale): 按 (density, scale) 缓存, 拖格/悬停/
// 平移期间 scale 恒定 → 命中缓存零分配; 仅捏合缩放的首帧重建一次 (与旧实现的逐帧
// 重建相比, 缩放过程中行为等价)。Stroke 属性全 val 不可变, 只能整体换新。
private var panelFxScale = -1f
private var panelFxDensity = -1f
private var panelDashEffect: PathEffect? = null
private var panelOutlineStroke = Stroke(width = 1f)
private var panelSelStroke = Stroke(width = 1f)
private var panelHandleStroke = Stroke(width = 1f)
private var panelPreviewStroke = Stroke(width = 1f)
private var panelHintShadowStroke = Stroke(width = 1f)

/** 缓存键任一变化才重建 (本函数即 DrawScope, 直接读 density 与 dp.toPx)。 */
private fun DrawScope.ensurePanelDrawObjects(scale: Float) {
    val density = density
    if (panelFxScale == scale && panelFxDensity == density) return
    panelFxScale = scale
    panelFxDensity = density
    panelDashEffect = PathEffect.dashPathEffect(
        floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale),
    )
    panelOutlineStroke = Stroke(width = 1.dp.toPx() / scale)
    panelSelStroke = Stroke(width = 1.5.dp.toPx() / scale)
    panelHandleStroke = Stroke(width = 1.2.dp.toPx() / scale)
    panelPreviewStroke = Stroke(
        width = 1.5.dp.toPx() / scale,
        pathEffect = panelDashEffect,
    )
    panelHintShadowStroke = Stroke(width = 3.dp.toPx() / scale)
}

/** 该格生效圆角半径 (px): 样式覆盖优先, 否则页面默认 (与引擎渲染同一取值口径)。 */
private fun panelCornerRadius(m: PanelMirror, defaults: PanelDefaults): Float =
    m.meta.style.cornerRadius ?: defaults.cornerRadius

/**
 * 分格覆盖层: 所有格的边框轮廓 (细, subText), 选中格高亮 + 4 角手柄, 多选高亮,
 * 拖动预览 (半透明填充 + 虚线), 建格/框选矩形, 切分手势可发现性提示。坐标与
 * CanvasOverlay 其它覆盖物同一套 (doc → 位图空间 → withTransform 视图变换)。
 *
 * [viewScale] = zoom * fitScale (与 CanvasTouchView 手势阈值同一口径), 用于让
 * 线宽/手柄/虚线在屏幕上保持恒定尺寸。
 * [toolActive] = 当前工具是否分格工具 (调用方传入; 决定是否画切分提示)。
 */
internal fun DrawScope.drawPanelsOverlay(
    vm: PaintViewModel,
    interaction: PanelInteractionState,
    bmpWidth: Float,
    bmpHeight: Float,
    viewScale: Float,
    toolActive: Boolean,
) {
    val docW = vm.docWidth.coerceAtLeast(1)
    val docH = vm.docHeight.coerceAtLeast(1)
    val scX = bmpWidth / docW
    val scY = bmpHeight / docH
    val scale = viewScale.coerceAtLeast(0.001f)
    val mirrors = vm.panels
    val hasSelection = vm.selectedPanelId != null || vm.panelMultiSelectedIds.isNotEmpty()
    if (mirrors.isEmpty() && !hasSelection &&
        interaction.createRect == null && interaction.marqueeRect == null &&
        interaction.divideLine == null && interaction.snapGuides.isEmpty()
    ) {
        return
    }
    val previews = if (interaction.dragPreviews.isEmpty()) {
        emptyMap()
    } else {
        interaction.dragPreviews.associateBy { it.panelId }
    }
    ensurePanelDrawObjects(scale)
    val outlineStroke = panelOutlineStroke
    val selStroke = panelSelStroke
    val previewStroke = panelPreviewStroke
    val previewFill = PANEL_PREVIEW_FILL
    val defaults = vm.panelDefaults

    // 0. 全局动态矢量边框常驻渲染:
    // 边框彻底脱离位图像素层, 始终高帧率以纯矢量方式渲染在所有画层之上, 物理上绝不受画笔或橡皮擦除影响
    for (m in mirrors) {
        val r = panelCornerRadius(m, defaults)
        val path = panelQuadPath(
            m.meta.quad.roundedCorners(r),
            bmpWidth, bmpHeight, scX, scY,
        )
        val style = m.meta.style
        val bw = (style.borderWidth ?: defaults.borderWidth).toFloat()
        val bc = Color(style.borderColor ?: defaults.borderColor)
        if (bw > 0f) {
            val strokePx = (bw * scX).coerceAtLeast(1f)
            drawPath(path, bc, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // 0.5. 非分格工具 (画笔/橡皮等): 四角拖动点绝对不画; 保留可绘制区域提醒框
    if (!toolActive) {
        val activePids = vm.currentPaintingPanelIds()
        val hintShadowStroke = panelHintShadowStroke
        val safeBoundaryStroke = Stroke(width = (1f * scX).coerceAtLeast(1f))
        for (m in mirrors) {
            val path = panelQuadPath(
                m.meta.quad.roundedCorners(panelCornerRadius(m, defaults)),
                bmpWidth, bmpHeight, scX, scY,
            )
            if (m.panelId in activePids) {
                // 当前正选中的绘制图层所在格/组: 以高对比莫兰迪强调色勾勒安全作画边界
                drawPath(path, Color(0x33000000), style = hintShadowStroke)
                drawPath(path, PANEL_PAINT_HINT_COLOR, style = selStroke)
            } else {
                // 其余分格: 绘制极为轻微柔和的虚淡安全界线提醒 (0x38808080)
                drawPath(path, Color(0x38808080), style = safeBoundaryStroke)
            }
        }
        return
    }

    // 1. 建格 / 框选矩形 (半透明填充 + 虚线) / 切分预览线 (虚线) / 吸附参考线 (贯穿虚线)
    interaction.createRect?.let { rect ->
        val path = docBoundsPath(rect, bmpWidth, bmpHeight, scX, scY)
        drawPath(path, previewFill)
        drawPath(path, Morandi.accent, style = previewStroke)
    }
    interaction.marqueeRect?.let { rect ->
        val path = docBoundsPath(rect, bmpWidth, bmpHeight, scX, scY)
        drawPath(path, previewFill)
        drawPath(path, Morandi.accent, style = previewStroke)
    }
    interaction.divideLine?.let { (p0, p1) ->
        val start = Offset(p0.x * scX - bmpWidth / 2f, p0.y * scY - bmpHeight / 2f)
        val end = Offset(p1.x * scX - bmpWidth / 2f, p1.y * scY - bmpHeight / 2f)
        // 预览线端点随手势实时变化, Path 必须逐帧重建 (Paint/Stroke 已复用)
        val linePath = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        drawPath(linePath, Morandi.accent, style = previewStroke)
    }
    for (guide in interaction.snapGuides) {
        // 吸附线随拖动位置实时变化, Path 必须逐帧重建 (Paint/Stroke 已复用)
        val linePath = Path()
        if (guide.axis == 0) {
            val x = guide.coord * scX - bmpWidth / 2f
            linePath.moveTo(x, -bmpHeight / 2f)
            linePath.lineTo(x, bmpHeight / 2f)
        } else {
            val y = guide.coord * scY - bmpHeight / 2f
            linePath.moveTo(-bmpWidth / 2f, y)
            linePath.lineTo(bmpWidth / 2f, y)
        }
        drawPath(linePath, Morandi.accent, style = previewStroke)
    }

    // 2. 所有格轮廓 (拖动中的格画预览, 其余画镜像轮廓)。轮廓与引擎渲染同源: 密化圆角
    // 顶点 (radius 与 style.cornerRadius ?: panelDefaults.cornerRadius 一致)。
    // 每格 Path 重建保留: quad 内容与 doc→位图映射 (缩放/平移) 都会变, 缓存失效
    // 管理成本高于新建; Path 是本覆盖层逐帧的主要分配, 但其余一次性对象已清零
    val curSelId = vm.selectedPanelId
    val curSelGroupId = mirrors.firstOrNull { it.panelId == curSelId }?.groupId
    for (m in mirrors) {
        val path = panelQuadPath(
            m.meta.quad.roundedCorners(panelCornerRadius(m, defaults)),
            bmpWidth, bmpHeight, scX, scY,
        )
        when {
            m.panelId in interaction.divideHighlightPanelIds ->
                drawPath(path, Morandi.accent, style = selStroke)
            m.panelId == curSelId ->
                drawPath(path, Morandi.accent, style = selStroke)
            curSelGroupId != null && m.groupId == curSelGroupId ->
                drawPath(path, Morandi.accent, style = previewStroke)
            m.panelId in vm.panelMultiSelectedIds ->
                drawPath(path, PANEL_MULTI_SELECT_COLOR, style = selStroke)
            else ->
                drawPath(path, PANEL_OUTLINE_COLOR, style = outlineStroke)
        }
    }

    // 2.5) 切分模式可发现性提示 (仅切分模式开 + 有格; 不要求未选中): 每格满格淡 accent
    //  填充, 提示"画布任意处拖线即可切开"。填充 Path 随缩放平移变化, 与轮廓同口径逐帧重建
    if (toolActive && vm.panelDivideMode && mirrors.isNotEmpty()) {
        for (m in mirrors) {
            val path = panelQuadPath(
                m.meta.quad.roundedCorners(panelCornerRadius(m, defaults)),
                bmpWidth, bmpHeight, scX, scY,
            )
            drawPath(path, PANEL_DIVIDE_HINT_FILL)
        }
    }

    // 3. 拖动预览 (半透明填充 + 虚线, 盖在原轮廓上; 预览 quad 随手势变化, Path 逐帧重建;
    // 圆角与目标格渲染同源, 避免拖动中直角/圆角跳变)
    for (entry in interaction.dragPreviews) {
        val radius = mirrors.firstOrNull { it.panelId == entry.panelId }
            ?.let { panelCornerRadius(it, defaults) } ?: 0f
        val path = panelQuadPath(entry.quad.roundedCorners(radius), bmpWidth, bmpHeight, scX, scY)
        drawPath(path, previewFill)
        drawPath(path, Morandi.accent, style = previewStroke)
    }

    // 4. 选中格 8 手柄: 4 角大圆手柄 (自由角点) + 4 边中点小圆手柄 (平行推拉整边)。
    //  切分模式不画: 手柄会抢切刀落点, 画布任意处都应视为切刀
    val selMirror = mirrors.firstOrNull { it.panelId == vm.selectedPanelId }
    if (toolActive && selMirror != null && !vm.panelDivideMode) {
        val quad = previews[selMirror.panelId]?.quad ?: selMirror.meta.quad
        val cornerR = 5.dp.toPx() / scale
        val midR = 3.5.dp.toPx() / scale

        // 4 角手柄 (Morandi.accent 实心圆 + 白色外圈)
        for (c in quad.corners) {
            val center = Offset(c.x * scX - bmpWidth / 2f, c.y * scY - bmpHeight / 2f)
            drawCircle(Morandi.accent, radius = cornerR, center = center)
            drawCircle(
                Color.White,
                radius = cornerR,
                center = center,
                style = panelHandleStroke,
            )
        }

        // 4 边中点手柄 (白色实心圆 + Morandi.accent 外圈，清晰区分)
        for (m in quad.edgeMidpoints()) {
            val center = Offset(m.x * scX - bmpWidth / 2f, m.y * scY - bmpHeight / 2f)
            drawCircle(Color.White, radius = midR, center = center)
            drawCircle(
                Morandi.accent,
                radius = midR,
                center = center,
                style = panelHandleStroke,
            )
        }
    }
}

/**
 * 分格长按极简菜单 (锚在按下点): 单选态为 上移一层/下移一层/删除/复制/恢复默认
 * (顶/底边界时对应移动 chip 隐藏), 多选态追加 "取消多选/合并"。
 * 长按提示在分格属性浮窗底部, 不放在已经弹出的菜单里。动作走既有 VM 操作, 完成即收菜单。
 */
@Composable
internal fun PanelLongPressMenu(
    vm: PaintViewModel,
    interaction: PanelInteractionState,
) {
    val anchor = interaction.menuScreenPos ?: return
    val panelId = interaction.menuPanelId
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
        onDismissRequest = { interaction.dismissMenu() },
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panel)
                .glassBorder(RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (vm.panelMultiSelectedIds.isNotEmpty()) {
                ToolFloatChip(
                    label = stringResource(R.string.panel_cancel_multi),
                    onClick = {
                        vm.panelMultiSelectedIds = emptySet()
                        interaction.dismissMenu()
                    },
                )
                if (vm.panelMultiSelectedIds.size >= 2) {
                    ToolFloatChip(
                        label = stringResource(R.string.panel_menu_merge_frame),
                        icon = painterResource(R.drawable.ic_panel_frame),
                        onClick = {
                            val ids = vm.panelMultiSelectedIds
                            vm.mergePanels(ids)
                            interaction.dismissMenu()
                        },
                    )
                }
            }
            if (panelId != null) {
                // 单选态: 分格组管理 (拆分为独立组 / 上下合并)
                if (vm.panelMultiSelectedIds.isEmpty()) {
                    val curMirror = vm.panels.firstOrNull { it.panelId == panelId }
                    if (curMirror != null) {
                        // 1. 拆分为独立组 (当前组拥有 ≥2 个格子时允许拆分)
                        if (vm.canSeparatePanelGroup(panelId)) {
                            ToolFloatChip(
                                label = stringResource(R.string.panel_menu_separate_group),
                                icon = painterResource(R.drawable.ic_unlink),
                                onClick = {
                                    vm.separatePanelToNewGroup(panelId)
                                    interaction.dismissMenu()
                                },
                            )
                        }
                        // 2. 合并到上一组 / 合并到下一组
                        val (prevGId, nextGId) = vm.adjacentPanelGroupInfo(panelId)
                        if (prevGId != null) {
                            val prevName = vm.layers.firstOrNull { it.id == prevGId }?.name
                            val label = if (prevName != null) {
                                "${stringResource(R.string.panel_menu_merge_above)} ($prevName)"
                            } else {
                                stringResource(R.string.panel_menu_merge_above)
                            }
                            ToolFloatChip(
                                label = label,
                                icon = painterResource(R.drawable.ic_link),
                                onClick = {
                                    vm.mergePanelGroup(curMirror.groupId, prevGId)
                                    interaction.dismissMenu()
                                },
                            )
                        }
                        if (nextGId != null) {
                            val nextName = vm.layers.firstOrNull { it.id == nextGId }?.name
                            val label = if (nextName != null) {
                                "${stringResource(R.string.panel_menu_merge_below)} ($nextName)"
                            } else {
                                stringResource(R.string.panel_menu_merge_below)
                            }
                            ToolFloatChip(
                                label = label,
                                icon = painterResource(R.drawable.ic_link),
                                onClick = {
                                    vm.mergePanelGroup(curMirror.groupId, nextGId)
                                    interaction.dismissMenu()
                                },
                            )
                        }
                    }
                }
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_duplicate),
                    icon = painterResource(R.drawable.ic_copy),
                    onClick = {
                        vm.duplicatePanel(panelId)
                        interaction.dismissMenu()
                    },
                )
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_delete),
                    icon = painterResource(R.drawable.ic_trash),
                    danger = true,
                    onClick = {
                        vm.deletePanel(panelId)
                        interaction.dismissMenu()
                    },
                )
                if (vm.panelMultiSelectedIds.isEmpty()) {
                    ToolFloatChip(
                        label = stringResource(R.string.panel_reset_style),
                        onClick = {
                            vm.updatePanelStyle(panelId, PanelStyle())
                            interaction.dismissMenu()
                        },
                    )
                }
            }
        }
    }
}
