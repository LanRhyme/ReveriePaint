/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.ui.components.reportUiSlot
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** 空闲自动隐藏时长: 这段时间内无任何撤销/重做/拖动交互则淡出 */
private const val UNDO_BAR_IDLE_HIDE_MS = 5000L

/** 显示所需的最低历史总深度 (总步数 > 5 才显示) */
private const val UNDO_BAR_MIN_DEPTH = 5

/** canShowUndoBar 周期复查间隔: isInteracting 等触摸状态非 Compose 状态, 只能轮询 */
private const val UNDO_BAR_GUARD_POLL_MS = 250L

/** 拇指命中判定半径 (dp), 宽松以便快速拖动 */
private const val UNDO_BAR_THUMB_TOUCH_RADIUS = 28f

/** 胶囊高度 (dp) */
private const val UNDO_BAR_HEIGHT = 40

/** 胶囊最大宽度 (dp) */
private const val UNDO_BAR_MAX_WIDTH = 420

/** 滑轨左右留白 (dp): 轨道与拇指不顶到胶囊圆角 */
private const val UNDO_BAR_RAIL_PAD = 12

/** 拖动数字气泡直径 (dp) */
private const val UNDO_BAR_BUBBLE = 36

/**
 * 撤回条浮动滑杆 (Undo History scrub bar, 交互参考 Infinite Painter):
 * 水平胶囊, 满刻度 = 可撤销+可重做总深度, 拇指位置 = 还可撤销步数;
 * 左端 = 全部撤销完, 右端 = 全部重做完。
 *
 * 交互:
 * - 拖拇指: 手指 x 线性映射到 0..max 调 [PaintViewModel.scrubTo], 拇指上方
 *   浮出圆形数字气泡显示目标步数 (纯 Compose 绘制, 无新窗口);
 * - 点轨道: 拇指右侧 = 前进一步, 左侧 = 后退一步;
 * - 拖滑轨空白处 (拇指命中半径之外): 重新定位整条 (会话内记忆, 不持久化)。
 *
 * 显隐 (与 [PaintViewModelUndoBar] 的泵契约联动):
 * - 撤销/重做落定刷新镜像后, 总深度 > 5 且 [PaintViewModel.canShowUndoBar]
 *   为真则显示并重置空闲计时; 变 false 时立即隐藏;
 * - 空闲 5 秒自动淡出 (fade 200ms)。
 * 可见性镜像写入顶层 undoBarVisible —— 追赶泵只在可见时发步, UI 隐藏即停泵。
 *
 * 防误触: 经 [reportUiSlot] 向画布手势层报备 bounds, 落笔不穿透。
 */
@Composable
fun UndoScrubBar(
    vm: PaintViewModel,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    // —— 显隐状态 (UI 会话态; undoBarVisible 是泵侧镜像, 两者必须同步写) ——
    var visible by remember { mutableStateOf(false) }

    // 首次进入绘画页: 刷新一次计数镜像 (显示判断由下面的镜像监听 effect 完成)
    LaunchedEffect(Unit) {
        vm.refreshUndoCounts()
    }

    // 隐藏 -> 出现: 任何撤销/重做落定都会刷新 position/max 镜像并重启本 effect;
    // 总深度 > 5 且当前可显示 (无笔画/变换/QuickShape/3D/lasso 收点) 才出现
    LaunchedEffect(undoBarPosition, undoBarMax) {
        if (undoBarMax > UNDO_BAR_MIN_DEPTH && vm.canShowUndoBar()) {
            visible = true
            undoBarVisible = true
            vm.onUndoBarActivity()
            // 追赶若曾被中断 (笔画/变换期间泵停车), 条恢复后立刻续跑剩余步骤
            vm.resumeUndoBarPumpIfPending()
        }
    }

    // 展开 -> 隐藏: 空闲 5 秒自动淡出 (拖动中 scrubTo 会持续刷新活跃时间戳,
    // 计时不会在拖动中途触发)
    LaunchedEffect(visible, undoBarLastActivityAt) {
        if (!visible) return@LaunchedEffect
        delay(UNDO_BAR_IDLE_HIDE_MS)
        visible = false
        undoBarVisible = false
    }

    // canShowUndoBar() 变 false 时立即隐藏。isInteracting/isTransformActive 是
    // 普通字段 (非 Compose 状态), 无重组信号, 周期轮询兜底 (笔画/变换期间隐藏)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            delay(UNDO_BAR_GUARD_POLL_MS)
            if (!vm.canShowUndoBar()) {
                visible = false
                undoBarVisible = false
                break
            }
        }
    }

    // —— 手势/布局状态 (组件自管, 不进 ViewModel) ——
    // 整条拖动重定位偏移 (会话内记忆)
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 拖动 scrub 的目标步数; -1 = 非拖动态
    var scrubTarget by remember { mutableIntStateOf(-1) }
    // 滑轨 Box 实测宽 (px)
    var railWidthPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val barWidth = UNDO_BAR_MAX_WIDTH.dp
    val railPadDp = UNDO_BAR_RAIL_PAD.dp
    val capsuleShape = RoundedCornerShape(20.dp)

    // 拇指中心 x (滑轨本地 px): 组合期计算后经 holder 供手势读取,
    // 手势保持 Unit key (铁律: 手势不 key 在高频状态上, 否则拖动中被取消)
    val positionFrac = if (undoBarMax > 0) undoBarPosition.toFloat() / undoBarMax else 0f
    val thumbXHolder = remember { FloatArray(1) }
    SideEffect {
        val pad = with(density) { railPadDp.toPx() }
        thumbXHolder[0] = pad + positionFrac * maxOf(0f, railWidthPx - 2 * pad)
    }

    // 拖动中拇指/气泡显示目标位置 (即时跟手), 非拖动显示真实 position
    val displayPos = if (scrubTarget >= 0) scrubTarget else undoBarPosition
    val displayFrac = if (undoBarMax > 0) displayPos.toFloat() / undoBarMax else 0f

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier.offset {
                IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
            }
        ) {
            val effectiveBarWidth = (maxWidth - 32.dp).coerceAtMost(barWidth)
            val a11yDesc = stringResource(R.string.undo_bar_a11y)

            // 拖动中的目标步数气泡 (画在胶囊上方, 外层不 clip 所以不会被裁)
            if (scrubTarget >= 0) {
                val bubbleDp = UNDO_BAR_BUBBLE.dp
                val gapDp = 10.dp
                // 滑轨占满整条胶囊, 刻度范围 = 全宽 (左右各留 railPad)
                val bubbleCenterDp = railPadDp + (effectiveBarWidth - railPadDp * 2) * displayFrac
                val bubbleLeftPx = with(density) {
                    (bubbleCenterDp - bubbleDp / 2).coerceIn(0.dp, effectiveBarWidth - bubbleDp).toPx()
                }
                val bubbleTopPx = with(density) { -(bubbleDp + gapDp).toPx() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(bubbleLeftPx.roundToInt(), bubbleTopPx.roundToInt()) }
                        .size(bubbleDp)
                        .shadow(6.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                        .clip(CircleShape)
                        .background(Morandi.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayPos.toString(),
                        color = Morandi.onAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .width(effectiveBarWidth)
                    .height(UNDO_BAR_HEIGHT.dp)
                    .reportUiSlot(vm, UiPanelSlot.UNDO_SCRUB_BAR)
                    .shadow(12.dp, capsuleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                    .clip(capsuleShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.popupStyle(vm.popupPanelOpacity),
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
                        }
                    )
                    .glassBorder(capsuleShape)
                    .semantics(mergeDescendants = true) { contentDescription = a11yDesc },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 滑轨: 点按步进 + 拇指拖动 scrub + 空白处拖动重定位整条
                val positionA11y = stringResource(
                    R.string.undo_bar_position_a11y, undoBarPosition, undoBarMax
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .semantics { contentDescription = positionA11y }
                        .onSizeChanged { railWidthPx = it.width.toFloat() }
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                // 点轨道不拖: 拇指右侧 = 前进一步, 左侧 = 后退一步
                                val pad = railPadDp.toPx()
                                val span = maxOf(1f, railWidthPx - 2 * pad)
                                val t = ((pos.x - pad) / span * undoBarMax).roundToInt()
                                    .coerceIn(0, undoBarMax)
                                if (t > undoBarPosition) {
                                    vm.scrubTo(undoBarPosition + 1)
                                } else if (t < undoBarPosition) {
                                    vm.scrubTo(undoBarPosition - 1)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    // 模式以 onDragStart 判定为准, 拖动中途不切换:
                                    // 命中拇指 (宽松半径) = scrub 拖动
                                    if (abs(start.x - thumbXHolder[0]) <=
                                        UNDO_BAR_THUMB_TOUCH_RADIUS.dp.toPx()
                                    ) {
                                        val pad = railPadDp.toPx()
                                        val span = maxOf(1f, railWidthPx - 2 * pad)
                                        val t = ((start.x - pad) / span * undoBarMax).roundToInt()
                                            .coerceIn(0, undoBarMax)
                                        scrubTarget = t
                                        vm.scrubTo(t)
                                    } else {
                                        // 未命中拇指 = 整条重定位; 持续报活跃防拖动中被空闲计时藏掉
                                        vm.onUndoBarActivity()
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (scrubTarget >= 0) {
                                        val pad = railPadDp.toPx()
                                        val span = maxOf(1f, railWidthPx - 2 * pad)
                                        val t = ((change.position.x - pad) / span * undoBarMax)
                                            .roundToInt().coerceIn(0, undoBarMax)
                                        scrubTarget = t
                                        vm.scrubTo(t)
                                    } else {
                                        dragOffset += dragAmount
                                        vm.onUndoBarActivity()
                                    }
                                },
                                onDragEnd = {
                                    scrubTarget = -1
                                },
                                onDragCancel = {
                                    scrubTarget = -1
                                },
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pad = railPadDp.toPx()
                        val span = maxOf(0f, size.width - 2 * pad)
                        val cy = size.height / 2f
                        val trackH = 4.dp.toPx()
                        val trackRadius = CornerRadius(trackH / 2f)
                        // 未走过部分 (右段): subText 低透明度
                        drawRoundRect(
                            color = Morandi.subText.copy(alpha = 0.35f),
                            topLeft = Offset(pad, cy - trackH / 2f),
                            size = Size(span, trackH),
                            cornerRadius = trackRadius,
                        )
                        // 已走过部分 (左段): accent 高亮
                        val walked = span * displayFrac.coerceIn(0f, 1f)
                        if (walked > 0f) {
                            drawRoundRect(
                                color = Morandi.accent,
                                topLeft = Offset(pad, cy - trackH / 2f),
                                size = Size(walked, trackH),
                                cornerRadius = trackRadius,
                            )
                        }
                        // 拇指 16dp 圆点
                        drawCircle(
                            color = Morandi.accentHi,
                            radius = 8.dp.toPx(),
                            center = Offset(pad + walked, cy),
                        )
                        drawCircle(
                            color = Morandi.text,
                            radius = 3.dp.toPx(),
                            center = Offset(pad + walked, cy),
                        )
                    }
                }
            }
        }
    }
}
