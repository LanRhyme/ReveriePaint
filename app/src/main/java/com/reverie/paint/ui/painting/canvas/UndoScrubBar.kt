/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
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
import kotlin.math.roundToInt

/** 空闲自动隐藏时长: 这段时间内无任何撤销/重做/拖动交互则淡出 */
private const val UNDO_BAR_IDLE_HIDE_MS = 5000L

/** canShowUndoBar 周期复查间隔: isInteracting 等触摸状态非 Compose 状态, 只能轮询 */
private const val UNDO_BAR_GUARD_POLL_MS = 250L

/** 紧凑胶囊高度 (dp) */
private const val UNDO_BAR_HEIGHT = 34

/** 紧凑胶囊最大宽度 (dp) */
private const val UNDO_BAR_MAX_WIDTH = 260

/** MD3 滑块指示线尺寸 (指示线略微加长至 18dp) */
private val UNDO_BAR_THUMB_SIZE = DpSize(4.dp, 18.dp)

/**
 * 撤回条紧凑浮动滑杆 (Undo History scrub bar):
 * 紧凑毛玻璃胶囊, 使用 Material 3 Slider, 指示线调短为精致微矩形。
 * 满刻度 = 可撤销+可重做总深度, 拇指位置 = 还可撤销步数。
 *
 * 视觉与交互:
 * - 紧凑尺寸: 高度 34dp, 宽度 260dp, 居中贴合底部;
 * - Material 3 滑块: 原生 MD3 Track 与短指示线 Thumb, 融入莫兰迪强调色;
 * - 相对步数药丸气泡: 交互时在上方弹性浮现, 实时显示 "-X 步" / "+X 步" / "当前";
 * - 显隐联动与防误触: 5 秒空闲淡出, 报备 UiPanelSlot.UNDO_SCRUB_BAR 防落笔穿透。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // —— 滑块交互与气泡状态 ——
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val isInteracting = isDragged || isPressed

    // 拖动 scrub 的目标步数; -1 = 非拖动态
    var scrubTarget by remember { mutableIntStateOf(-1) }
    // 拖动开始时的基准步数 (用于计算相对 "+X / -X" 步数); -1 = 非拖动态
    var dragStartPos by remember { mutableIntStateOf(-1) }
    // 气泡测量宽 (px)
    var bubbleWidthPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isInteracting) {
        if (isInteracting) {
            dragStartPos = undoBarPosition
            vm.onUndoBarActivity()
        } else {
            scrubTarget = -1
            dragStartPos = -1
        }
    }

    val density = LocalDensity.current
    val barWidth = UNDO_BAR_MAX_WIDTH.dp
    val capsuleShape = RoundedCornerShape((UNDO_BAR_HEIGHT / 2).dp)

    // 拖动中拇指/气泡显示目标位置 (即时跟手), 非拖动显示真实 position
    val displayPos = if (scrubTarget >= 0) scrubTarget else undoBarPosition
    val displayFrac = if (undoBarMax > 0) displayPos.toFloat() / undoBarMax else 0f
    val currentVal = displayPos.toFloat()

    val showBubble = isInteracting || scrubTarget >= 0

    AnimatedVisibility(
        visible = undoBarVisible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        BoxWithConstraints {
            val effectiveBarWidth = (maxWidth - 32.dp).coerceAtMost(barWidth)
            val a11yDesc = stringResource(R.string.undo_bar_a11y)

            // 拖动中的目标步数药丸气泡 (画在胶囊上方)
            AnimatedVisibility(
                visible = showBubble,
                enter = fadeIn(tween(150)) + scaleIn(
                    tween(150),
                    initialScale = 0.85f,
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    tween(120),
                    targetScale = 0.85f,
                ),
            ) {
                val padDp = 14.dp
                val bubbleCenterDp = padDp + (effectiveBarWidth - padDp * 2) * displayFrac
                val bubbleCenterPx = with(density) { bubbleCenterDp.toPx() }
                val effectiveBarWidthPx = with(density) { effectiveBarWidth.toPx() }
                val bubbleLeftPx = (bubbleCenterPx - bubbleWidthPx / 2f)
                    .coerceIn(0f, maxOf(0f, effectiveBarWidthPx - bubbleWidthPx))
                val bubbleTopPx = with(density) { (-32).dp.toPx() }

                val basePos = if (dragStartPos >= 0) dragStartPos else undoBarPosition
                val delta = displayPos - basePos
                val bubbleText = when {
                    delta < 0 -> stringResource(R.string.undo_bar_bubble_undo, -delta)
                    delta > 0 -> stringResource(R.string.undo_bar_bubble_redo, delta)
                    else -> stringResource(R.string.undo_bar_bubble_current)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(bubbleLeftPx.roundToInt(), bubbleTopPx.roundToInt()) }
                        .onSizeChanged { bubbleWidthPx = it.width.toFloat() }
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.accent)
                        .defaultMinSize(minWidth = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = bubbleText,
                        color = Morandi.onAccent,
                        fontSize = 11.sp,
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
                val positionA11y = stringResource(
                    R.string.undo_bar_position_a11y, undoBarPosition, undoBarMax
                )
                val sliderColors = SliderDefaults.colors(
                    thumbColor = Morandi.accent,
                    activeTrackColor = Morandi.accent,
                    inactiveTrackColor = Morandi.subText.copy(alpha = 0.22f),
                    activeTickColor = Morandi.panel.copy(alpha = 0.55f),
                    inactiveTickColor = Morandi.subText.copy(alpha = 0.35f),
                )

                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Slider(
                        value = currentVal.coerceIn(0f, undoBarMax.toFloat().coerceAtLeast(1f)),
                        onValueChange = { newVal ->
                            val t = newVal.roundToInt().coerceIn(0, undoBarMax)
                            scrubTarget = t
                            vm.scrubTo(t)
                        },
                        onValueChangeFinished = {
                            scrubTarget = -1
                            dragStartPos = -1
                            vm.onUndoBarActivity()
                        },
                        valueRange = 0f..undoBarMax.toFloat().coerceAtLeast(1f),
                        steps = 0,
                        colors = sliderColors,
                        interactionSource = interactionSource,
                        thumb = {
                            SliderDefaults.Thumb(
                                interactionSource = interactionSource,
                                thumbSize = UNDO_BAR_THUMB_SIZE,
                                colors = sliderColors,
                            )
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                colors = sliderColors,
                                drawStopIndicator = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(UNDO_BAR_HEIGHT.dp)
                            .padding(horizontal = 8.dp)
                            .semantics { contentDescription = positionA11y },
                    )
                }
            }
        }
    }
}
