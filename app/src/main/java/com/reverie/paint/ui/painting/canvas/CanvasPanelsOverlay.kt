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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.panelMultiSelectedIds
import com.reverie.paint.core.duplicatePanel
import com.reverie.paint.core.deletePanel
import com.reverie.paint.core.panels
import com.reverie.paint.core.reorderPanel
import com.reverie.paint.core.selectedPanelId
import com.reverie.paint.model.PanelBounds
import com.reverie.paint.model.PanelQuad
import com.reverie.paint.ui.painting.panels.ToolFloatChip
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlin.math.roundToInt

/** 一条拖动预览: 某 格 的预览四角 (doc 坐标), 覆盖层用它替代该格镜像轮廓。 */
internal class PanelPreviewEntry(val panelId: Long, val quad: PanelQuad)

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

    /** 长按菜单锚点 (画布视图内坐标) 与目标格。 */
    var menuScreenPos: Offset? by mutableStateOf(null)
    var menuPanelId: Long? by mutableStateOf(null)

    /** 结束一次拖动: 清预览与矩形 (菜单不在此清, 抬笔后保持)。 */
    fun clearDrag() {
        dragPreviews = emptyList()
        marqueeRect = null
        createRect = null
    }

    fun dismissMenu() {
        menuScreenPos = null
        menuPanelId = null
    }
}

/** doc 坐标四边形 → 覆盖层位图空间路径 (与 CanvasOverlay 其它覆盖物同一套映射)。 */
private fun DrawScope.panelQuadPath(
    quad: PanelQuad,
    bmpWidth: Float,
    bmpHeight: Float,
    scX: Float,
    scY: Float,
): Path {
    val halfW = bmpWidth / 2f
    val halfH = bmpHeight / 2f
    val path = Path()
    quad.corners.forEachIndexed { i, c ->
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

/**
 * 分格覆盖层: 所有格的边框轮廓 (细, subText), 选中格高亮 + 4 角手柄, 多选高亮,
 * 拖动预览 (半透明填充 + 虚线), 建格/框选矩形。坐标与 CanvasOverlay 其它覆盖物
 * 同一套 (doc → 位图空间 → withTransform 视图变换)。
 *
 * [viewScale] = zoom * fitScale (与 CanvasTouchView 手势阈值同一口径), 用于让
 * 线宽/手柄/虚线在屏幕上保持恒定尺寸。
 */
internal fun DrawScope.drawPanelsOverlay(
    vm: PaintViewModel,
    interaction: PanelInteractionState,
    bmpWidth: Float,
    bmpHeight: Float,
    viewScale: Float,
) {
    val docW = vm.docWidth.coerceAtLeast(1)
    val docH = vm.docHeight.coerceAtLeast(1)
    val scX = bmpWidth / docW
    val scY = bmpHeight / docH
    val scale = viewScale.coerceAtLeast(0.001f)
    val previews = interaction.dragPreviews.associateBy { it.panelId }
    val mirrors = vm.panels
    val hasSelection = vm.selectedPanelId != null || vm.panelMultiSelectedIds.isNotEmpty()
    if (mirrors.isEmpty() && !hasSelection &&
        interaction.createRect == null && interaction.marqueeRect == null
    ) {
        return
    }

    val outlineCol = Morandi.subText.copy(alpha = 0.75f)
    val outlineStroke = Stroke(width = 1.dp.toPx() / scale)
    val selStroke = Stroke(width = 1.5.dp.toPx() / scale)
    val dashEffect = PathEffect.dashPathEffect(
        floatArrayOf(6.dp.toPx() / scale, 4.dp.toPx() / scale),
    )
    val previewFill = Morandi.accent.copy(alpha = 0.15f)
    val previewStroke = Stroke(
        width = 1.5.dp.toPx() / scale,
        pathEffect = dashEffect,
    )

    // 1. 建格 / 框选矩形 (半透明填充 + 虚线)
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

    // 2. 所有格轮廓 (拖动中的格画预览, 其余画镜像轮廓)
    for (m in mirrors) {
        val path = panelQuadPath(m.meta.quad, bmpWidth, bmpHeight, scX, scY)
        when {
            m.panelId == vm.selectedPanelId ->
                drawPath(path, Morandi.accent, style = selStroke)
            m.panelId in vm.panelMultiSelectedIds ->
                drawPath(path, Morandi.accent.copy(alpha = 0.7f), style = selStroke)
            else ->
                drawPath(path, outlineCol, style = outlineStroke)
        }
    }

    // 3. 拖动预览 (半透明填充 + 虚线, 盖在原轮廓上)
    for (entry in interaction.dragPreviews) {
        val path = panelQuadPath(entry.quad, bmpWidth, bmpHeight, scX, scY)
        drawPath(path, previewFill)
        drawPath(path, Morandi.accent, style = previewStroke)
    }

    // 4. 选中格 4 角手柄 (约 10dp 圆点, accent 底白描边; 位置取预览四角)
    val selMirror = mirrors.firstOrNull { it.panelId == vm.selectedPanelId }
    if (selMirror != null) {
        val quad = previews[selMirror.panelId]?.quad ?: selMirror.meta.quad
        val handleR = 5.dp.toPx() / scale
        for (c in quad.corners) {
            val center = Offset(c.x * scX - bmpWidth / 2f, c.y * scY - bmpHeight / 2f)
            drawCircle(Morandi.accent, radius = handleR, center = center)
            drawCircle(
                Color.White,
                radius = handleR,
                center = center,
                style = Stroke(width = 1.2.dp.toPx() / scale),
            )
        }
    }
}

/**
 * 分格长按极简菜单 (锚在按下点): 删除/复制/置顶/置底, 多选态追加"取消多选"。
 * 纯 UI: 动作全部走既有 VM 操作, 完成即收菜单。
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
            }
            if (panelId != null) {
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_delete),
                    danger = true,
                    onClick = {
                        vm.deletePanel(panelId)
                        interaction.dismissMenu()
                    },
                )
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_duplicate),
                    onClick = {
                        vm.duplicatePanel(panelId)
                        interaction.dismissMenu()
                    },
                )
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_bring_top),
                    onClick = {
                        vm.reorderPanel(panelId, toTop = true)
                        interaction.dismissMenu()
                    },
                )
                ToolFloatChip(
                    label = stringResource(R.string.panel_menu_send_bottom),
                    onClick = {
                        vm.reorderPanel(panelId, toTop = false)
                        interaction.dismissMenu()
                    },
                )
            }
        }
    }
}
