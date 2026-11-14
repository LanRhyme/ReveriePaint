/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.model.layerOpacityScrubPercent
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs

/**
 * 图层行内的小控件 (参考 Mangar 图层面板的行内交互):
 *
 * - [LayerOpacityChip]: 行内"不透明度"药丸, 横向拖动直接调值, 不必进详情页;
 * - [LayerBlendModeChip]: 行内"混合模式"标签, 点一下直达混合模式选择页;
 * - [LayerDragHandle]: 行尾拖拽手柄, 按下即可拖动排序 (免去长按 300ms 的等待)。
 *
 * 两个药丸都要和行本身的手势 (轻点选中 / 长按拖动 / 左右滑) 共存, 共存约定:
 * 1. 子控件在触摸链里先于行收到事件 (Compose Main pass 由内向外), 认领手势时
 *    消费位移事件, 行看到 `isConsumed` 就放弃本次判定 (见 LayerRow.Phase 1);
 * 2. 纵向位移一律让渡给列表滚动 —— 行内只认领"横向占优"的拖动;
 * 3. 手指落在子控件上时置位 [LayerRowPressGate.pressedInside], 行长按拖动检查
 *    这个标记: 否则按住药丸再拖动会被当成"长按拖动图层"。
 */

/**
 * 行内控件按下标记。
 *
 * 只由手势协程在主线程读写, 不需要 Compose 快照状态 (置位不应触发重组)。
 */
internal class LayerRowPressGate {
    var pressedInside: Boolean = false
}

/** 行内不透明度药丸的手势钩子; null = 只显示数值 (拖拽跟随卡片等不接手势的宿主) */
internal class LayerOpacityScrub(
    val onChange: (Int) -> Unit,
    /** 松手: (起手百分比, 终值百分比) —— 起手值用于把撤销旧值修回拖动前, 见层内实现 */
    val onEnd: (startPercent: Int, endPercent: Int) -> Unit,
)

/** 走满 0%~100% 需要的横向距离: 200dp 约等于每 2dp 一个百分点, 与详情页滑杆手感接近 */
private const val OPACITY_SCRUB_RANGE_DP = 200

/** 行尾拖拽手柄宽度 (dp); 和面板宽度一起决定名称列的可用宽度 */
internal const val LAYER_DRAG_HANDLE_WIDTH_DP = 22

/**
 * 行内不透明度药丸。
 *
 * 拖动中数值只在本地显示, 松手才提交一步撤销 (与详情页滑杆同一套语义);
 * [onChange] 由行按引擎负载节流后转发, 这里不做节流。
 */
@Composable
internal fun LayerOpacityChip(
    percent: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    scrub: LayerOpacityScrub? = null,
    pressGate: LayerRowPressGate? = null,
) {
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current
    // 本地显示值: 拖动预览期间引擎镜像还没回读, 不能直接显示 percent
    var display by remember { mutableIntStateOf(percent) }
    var scrubbing by remember { mutableStateOf(false) }
    // 外部改动 (撤销/详情页滑杆) 时跟随; 拖动中与刚落手未回读时以本地值为准,
    // 避免"松手闪回旧值"的抖动
    LaunchedEffect(percent) {
        if (!scrubbing && percent != display) display = percent
    }

    val shape = RoundedCornerShape(4.dp)
    val background = when {
        scrubbing -> Morandi.accent.copy(alpha = 0.28f)
        display < 100 -> Morandi.accent.copy(alpha = 0.12f)
        else -> Morandi.panelHi.copy(alpha = 0.55f)
    }
    val textColor = when {
        scrubbing -> Morandi.accent
        display < 100 -> Morandi.accent
        else -> Morandi.subText
    }
    // 水位填充 (Mangar 的 ControlValueLevel): 药丸底部按数值高度填充 + 一条水线,
    // 不用读数字就能看出"这层被调淡了"。两端不画水线, 免得看起来像描边。
    val levelColor = when {
        scrubbing -> Morandi.accent.copy(alpha = 0.26f)
        display < 100 -> Morandi.accent.copy(alpha = 0.14f)
        else -> Morandi.subText.copy(alpha = 0.12f)
    }
    val waterlineColor = if (scrubbing) Morandi.accent.copy(alpha = 0.75f) else Morandi.subText.copy(alpha = 0.35f)

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(background)
                .drawBehind {
                    val fraction = (display / 100f).coerceIn(0f, 1f)
                    if (fraction <= 0f) return@drawBehind
                    val levelHeight = size.height * fraction
                    drawRect(
                        color = levelColor,
                        topLeft = Offset(0f, size.height - levelHeight),
                        size = Size(size.width, levelHeight),
                    )
                    if (fraction < 1f) {
                        val y = (size.height - levelHeight).coerceIn(0f, size.height)
                        drawLine(
                            color = waterlineColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
                .semantics { this.contentDescription = "$contentDescription $display%" }
                .then(
                    if (scrub == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(scrub) {
                            val rangePx = with(density) { OPACITY_SCRUB_RANGE_DP.dp.toPx() }
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                pressGate?.pressedInside = true
                                var claimed = false
                                var startPercent = display
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUpIgnoreConsumed()) break
                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y
                                        if (!claimed) {
                                            // 纵向占优 = 用户想滚列表, 主动放手
                                            if (abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                                                claimed = true
                                                startPercent = display
                                                scrubbing = true
                                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            } else {
                                                continue
                                            }
                                        }
                                        change.consume()
                                        // 认领的这一帧就开始跟手, 不用等下一次移动
                                        val next = layerOpacityScrubPercent(startPercent, dx, rangePx)
                                        if (next != display) {
                                            display = next
                                            scrub.onChange(next)
                                        }
                                    }
                                } finally {
                                    pressGate?.pressedInside = false
                                    if (claimed) {
                                        scrubbing = false
                                        scrub.onEnd(startPercent, display)
                                    }
                                }
                            }
                        }
                    },
                )
                .defaultMinSize(minWidth = 34.dp, minHeight = 18.dp)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$display%",
            color = textColor,
            fontSize = 10.sp,
            fontWeight = if (display < 100) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 行内"混合模式"标签: 点一下直接进混合模式选择页 (原来要点两次再进子页)。
 *
 * 不做拖动切换: 换混合模式没有预览接口, 逐帧 setLayerBlendMode 会走节点级重合成;
 * 想快速试模式请进选择页点选。只有带直达入口的行 (非背景/蒙版/分格组) 会用它。
 */
@Composable
internal fun LayerBlendModeChip(
    text: String,
    contentDescription: String,
    modified: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Morandi.panelHi.copy(alpha = 0.45f))
                .semantics { this.contentDescription = "$contentDescription $text" }
                .noRippleClickable(onClick)
                .defaultMinSize(minHeight = 18.dp)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (modified) Morandi.accent else Morandi.subText,
            fontSize = 10.sp,
            fontWeight = if (modified) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 行尾拖拽手柄: 按下后位移超过 touch slop 立即开始拖动 (行内长按拖动要等 300ms),
 * 拖动过程中消费全部位移, 行与列表都不再参与判定。
 */
@Composable
internal fun LayerDragHandle(
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressGate: LayerRowPressGate? = null,
) {
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    var active by remember { mutableStateOf(false) }
    var handleRootLeft by remember { mutableFloatStateOf(0f) }
    var handleRootTop by remember { mutableFloatStateOf(0f) }
    val gripColor = when {
        active -> Morandi.accent
        enabled -> Morandi.subText.copy(alpha = 0.55f)
        else -> Morandi.subText.copy(alpha = 0.28f)
    }
    Box(
        modifier =
            modifier
                .width(LAYER_DRAG_HANDLE_WIDTH_DP.dp)
                .fillMaxHeight()
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    handleRootLeft = b.left
                    handleRootTop = b.top
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressGate?.pressedInside = true
                        var dragging = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                if (!dragging) {
                                    if ((change.position - down.position).getDistance() <= touchSlop) continue
                                    dragging = true
                                    active = true
                                    change.consume()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnDragStart(Offset(handleRootLeft + down.position.x, handleRootTop + down.position.y))
                                }
                                change.consume()
                                currentOnDragMove(Offset(handleRootLeft + change.position.x, handleRootTop + change.position.y))
                            }
                        } finally {
                            pressGate?.pressedInside = false
                            if (dragging) {
                                active = false
                                currentOnDragEnd()
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = 10.dp, height = 12.dp)) {
            val lineWidth = 1.4.dp.toPx()
            val step = size.height / 3f
            for (row in 0..2) {
                val y = step * row + step / 2f
                drawLine(
                    color = gripColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 拖拽跟随卡片上的手柄: 只画形状, 不接手势 (卡片就在手指下方) */
@Composable
internal fun LayerDragHandleVisual(modifier: Modifier = Modifier) {
    val color: Color = Morandi.subText.copy(alpha = 0.55f)
    Box(
        modifier = modifier.width(LAYER_DRAG_HANDLE_WIDTH_DP.dp).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = 10.dp, height = 12.dp)) {
            val lineWidth = 1.4.dp.toPx()
            val step = size.height / 3f
            for (row in 0..2) {
                val y = step * row + step / 2f
                drawLine(
                    color = color,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
