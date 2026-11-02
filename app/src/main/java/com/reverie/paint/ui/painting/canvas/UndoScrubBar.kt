/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** canShowUndoBar 周期复查间隔: isInteracting 等触摸状态非 Compose 状态, 只能轮询 */
private const val UNDO_BAR_GUARD_POLL_MS = 250L

/** 拇指命中判定半径 (dp), 宽松以便快速抓取 */
private const val UNDO_BAR_THUMB_TOUCH_RADIUS = 36f

/** 胶囊高度 (dp) */
private const val UNDO_BAR_HEIGHT = 42

/** 胶囊最大宽度 (dp) */
private const val UNDO_BAR_MAX_WIDTH = 380

/** 滑轨左右留白 (dp): 轨道与拇指不顶到胶囊圆角 */
private const val UNDO_BAR_RAIL_PAD = 16

/**
 * 撤回条浮动滑杆 (Undo History scrub bar):
 * 水平毛玻璃极简胶囊, 满刻度 = 可撤销+可重做总深度, 拇指位置 = 还可撤销步数;
 * 左端 = 全部撤销完, 右端 = 全部重做完。
 *
 * 视觉与交互:
 * - 纯粹单体胶囊: 始终贴合底部居中, 专注文档历史滑动, 避免误拖整条;
 * - 轨道与刻度: 轨道依步数绘制微点刻度, 刻度具有凹陷/高亮层次感;
 * - 滑块动效: 拖拽时呼吸放大并具有外层柔光与白色高光边框;
 * - 相对步数药丸气泡: 拖动时在滑块上方弹性浮出药丸气泡, 动态显示 "-3 步" / "+2 步" / "当前";
 * - 点轨道: 拇指右侧 = 重做一步, 左侧 = 撤销一步;
 * - 拖滑块: 连续洗刷 (scrubTo)。
 *
 * 显隐联动:
 * - 仅在「面板按钮撤销」或「连续撤销/重做」时显现;
 * - 空闲 5 秒自动淡出 (fade 200ms);
 * - 笔画/变换开始时立即隐藏停泵。
 *
 * 防误触: 经 [reportUiSlot] 向画布手势层报备 bounds, 落笔不穿透。
 */
@Composable
fun UndoScrubBar(
    vm: PaintViewModel,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    // 首次进入绘画页: 刷新一次计数镜像 (显示判断由 ViewModel 状态驱动)
    LaunchedEffect(Unit) {
        vm.refreshUndoCounts()
    }

    // 展开 -> 隐藏: 空闲 5 秒自动淡出 (拖动中 scrubTo 会持续刷新活跃时间戳, 计时不会在拖动中途触发)
    LaunchedEffect(undoBarVisible, undoBarLastActivityAt) {
        if (!undoBarVisible) return@LaunchedEffect
        delay(UNDO_BAR_IDLE_HIDE_MS)
        vm.hideUndoBar()
    }

    // canShowUndoBar() 变 false 时立即隐藏。isInteracting/isTransformActive 是
    // 普通字段 (非 Compose 状态), 无重组信号, 周期轮询兜底 (笔画/变换期间隐藏)
    LaunchedEffect(undoBarVisible) {
        if (!undoBarVisible) return@LaunchedEffect
        while (true) {
            delay(UNDO_BAR_GUARD_POLL_MS)
            if (!vm.canShowUndoBar()) {
                vm.hideUndoBar()
                break
            }
        }
    }

    // —— 手势/布局状态 (组件自管, 不进 ViewModel) ——
    // 拖动 scrub 的目标步数; -1 = 非拖动态
    var scrubTarget by remember { mutableIntStateOf(-1) }
    // 拖动开始时的基准步数 (用于计算相对 "+X / -X" 步数); -1 = 非拖动态
    var dragStartPos by remember { mutableIntStateOf(-1) }
    // 是否正在拖动拇指
    var isDraggingThumb by remember { mutableStateOf(false) }
    // 滑轨 Box 实测宽 (px)
    var railWidthPx by remember { mutableFloatStateOf(0f) }
    // 气泡测量宽 (px)
    var bubbleWidthPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val barWidth = UNDO_BAR_MAX_WIDTH.dp
    val railPadDp = UNDO_BAR_RAIL_PAD.dp
    val capsuleShape = RoundedCornerShape((UNDO_BAR_HEIGHT / 2).dp)

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

    // 滑块呼吸放大微动效
    val isDragging = scrubTarget >= 0
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.28f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "thumbScale",
    )

    AnimatedVisibility(
        visible = undoBarVisible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        BoxWithConstraints {
            val effectiveBarWidth = (maxWidth - 32.dp).coerceAtMost(barWidth)
            val a11yDesc = stringResource(R.string.undo_bar_a11y)

            // 拖动中的目标步数药丸气泡 (画在胶囊上方, 外层不 clip 避免被裁切)
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(tween(150)) + scaleIn(
                    tween(150),
                    initialScale = 0.85f,
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    tween(120),
                    targetScale = 0.85f,
                ),
            ) {
                val bubbleCenterDp = railPadDp + (effectiveBarWidth - railPadDp * 2) * displayFrac
                val bubbleCenterPx = with(density) { bubbleCenterDp.toPx() }
                val effectiveBarWidthPx = with(density) { effectiveBarWidth.toPx() }
                val bubbleLeftPx = (bubbleCenterPx - bubbleWidthPx / 2f)
                    .coerceIn(0f, maxOf(0f, effectiveBarWidthPx - bubbleWidthPx))
                val bubbleTopPx = with(density) { (-36).dp.toPx() }

                val delta = displayPos - (if (dragStartPos >= 0) dragStartPos else displayPos)
                val bubbleText = when {
                    delta < 0 -> stringResource(R.string.undo_bar_bubble_undo, -delta)
                    delta > 0 -> stringResource(R.string.undo_bar_bubble_redo, delta)
                    else -> stringResource(R.string.undo_bar_bubble_current)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(bubbleLeftPx.roundToInt(), bubbleTopPx.roundToInt()) }
                        .onSizeChanged { bubbleWidthPx = it.width.toFloat() }
                        .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = Morandi.accent.copy(alpha = 0.45f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Morandi.accent)
                        .defaultMinSize(minWidth = 52.dp)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = bubbleText,
                        color = Morandi.onAccent,
                        fontSize = 12.sp,
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
                    .shadow(12.dp, capsuleShape, spotColor = Color.Black.copy(alpha = 0.28f))
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
                // 滑轨: 点按单步步进 + 拇指拖动连续洗刷 (始终居中固定, 无整条自由拖拽冲突)
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
                                // 点轨道单步步进: 拇指右侧 = 重做一步, 左侧 = 撤销一步
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
                                    val pad = railPadDp.toPx()
                                    val span = maxOf(1f, railWidthPx - 2 * pad)
                                    val thumbX = thumbXHolder[0]
                                    val touchRadius = UNDO_BAR_THUMB_TOUCH_RADIUS.dp.toPx()
                                    if (abs(start.x - thumbX) <= touchRadius) {
                                        isDraggingThumb = true
                                        dragStartPos = undoBarPosition
                                        val t = ((start.x - pad) / span * undoBarMax).roundToInt()
                                            .coerceIn(0, undoBarMax)
                                        scrubTarget = t
                                        vm.scrubTo(t)
                                    } else {
                                        isDraggingThumb = false
                                        vm.onUndoBarActivity()
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (isDraggingThumb) {
                                        change.consume()
                                        val pad = railPadDp.toPx()
                                        val span = maxOf(1f, railWidthPx - 2 * pad)
                                        val t = ((change.position.x - pad) / span * undoBarMax)
                                            .roundToInt().coerceIn(0, undoBarMax)
                                        scrubTarget = t
                                        vm.scrubTo(t)
                                    } else {
                                        vm.onUndoBarActivity()
                                    }
                                },
                                onDragEnd = {
                                    isDraggingThumb = false
                                    scrubTarget = -1
                                    dragStartPos = -1
                                },
                                onDragCancel = {
                                    isDraggingThumb = false
                                    scrubTarget = -1
                                    dragStartPos = -1
                                },
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pad = railPadDp.toPx()
                        val span = maxOf(0f, size.width - 2 * pad)
                        val cy = size.height / 2f
                        val trackH = 6.dp.toPx()
                        val trackRadius = CornerRadius(trackH / 2f)

                        // 1. 轨道背景 (未走过/右段): subText 低透明度
                        drawRoundRect(
                            color = Morandi.subText.copy(alpha = 0.22f),
                            topLeft = Offset(pad, cy - trackH / 2f),
                            size = Size(span, trackH),
                            cornerRadius = trackRadius,
                        )

                        // 2. 已走过轨道 (左段): accent 高亮
                        val walked = span * displayFrac.coerceIn(0f, 1f)
                        if (walked > 0f) {
                            drawRoundRect(
                                color = Morandi.accent,
                                topLeft = Offset(pad, cy - trackH / 2f),
                                size = Size(walked, trackH),
                                cornerRadius = trackRadius,
                            )
                        }

                        // 3. 细腻刻度微点
                        if (undoBarMax > 1 && span > 0f) {
                            val stepSpan = span / undoBarMax
                            val tickInterval = when {
                                stepSpan >= 12.dp.toPx() -> 1
                                stepSpan >= 6.dp.toPx() -> 2
                                stepSpan >= 3.dp.toPx() -> 5
                                else -> 10
                            }
                            val thumbCenter = pad + walked
                            val thumbAvoidRadius = 11.dp.toPx() * thumbScale

                            for (i in 0..undoBarMax step tickInterval) {
                                val tx = pad + i * stepSpan
                                if (abs(tx - thumbCenter) > thumbAvoidRadius) {
                                    val isWalked = tx <= thumbCenter
                                    val dotRadius = if (i % (tickInterval * 2) == 0) 1.4.dp.toPx() else 1.0.dp.toPx()
                                    val dotColor = if (isWalked) {
                                        Morandi.panel.copy(alpha = 0.55f)
                                    } else {
                                        Morandi.subText.copy(alpha = 0.35f)
                                    }
                                    drawCircle(
                                        color = dotColor,
                                        radius = dotRadius,
                                        center = Offset(tx, cy),
                                    )
                                }
                            }
                        }

                        // 4. 拇指滑块与呼吸高光动效
                        val baseRadius = 8.5.dp.toPx()
                        val currentRadius = baseRadius * thumbScale
                        val thumbCenter = Offset(pad + walked, cy)

                        // 呼吸外层光晕
                        if (thumbScale > 1.0f) {
                            val glowAlpha = ((thumbScale - 1.0f) / 0.28f).coerceIn(0f, 1f) * 0.32f
                            drawCircle(
                                color = Morandi.accent.copy(alpha = glowAlpha),
                                radius = currentRadius + 5.dp.toPx(),
                                center = thumbCenter,
                            )
                        }

                        // 拇指主体
                        drawCircle(
                            color = Morandi.accentHi,
                            radius = currentRadius,
                            center = thumbCenter,
                        )

                        // 拇指高光外边框 (微质感玻璃边缘)
                        drawCircle(
                            color = Color.White.copy(alpha = 0.45f),
                            radius = currentRadius,
                            center = thumbCenter,
                            style = Stroke(width = 1.4.dp.toPx()),
                        )

                        // 拇指中心微点
                        drawCircle(
                            color = Morandi.text,
                            radius = 2.8.dp.toPx() * thumbScale,
                            center = thumbCenter,
                        )
                    }
                }
            }
        }
    }
}
