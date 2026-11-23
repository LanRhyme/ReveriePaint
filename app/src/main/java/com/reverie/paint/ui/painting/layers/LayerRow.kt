/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.optimalTextColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 图层树每级缩进 (dp)。拖拽时的"保持原层级"判定与这里画的引导线必须用同一个值,
 * 否则手势阈值和视觉缩进对不上 (见 LayerListView.isLayerDragIndented)。
 */
internal const val LAYER_INDENT_STEP_DP = 14


private val drawerWidth = 132.dp

/** 没有滑动守卫时的共享哨兵 (恒为 0): 行可跳过重组, 不必为每行各建一个 State。 */
private val NoScrollGuard: State<Long> = object : State<Long> {
    override val value: Long = 0L
}

@Composable
internal fun LayerRow(
    vm: PaintViewModel,
    layer: PaintViewModel.LayerUiState,
    selected: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    revealed: Boolean,
    onReveal: () -> Unit,
    onRevealClose: () -> Unit,
    onBounds: (Float, Float) -> Unit,
    onDragStart: (Float, Float) -> Unit,
    onDragPosition: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    dragOnGroup: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit = {},
    multiSelected: Boolean = false,
    onRename: ((Int, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasNextSibling: Boolean = false,
    openDepths: Set<Int> = emptySet(),
    panelCrop: Pair<Long, Boolean>? = null,
    scrollGuardUntil: State<Long> = NoScrollGuard,
    /** 自身可见但祖先组隐藏时为 false: 眼睛画暗, 提示"亮了也看不见" (见 LayerListView) */
    effectiveVisible: Boolean = true,
    /** 当前拖拽落点是否以本组为父图层 (用于组高亮与引导线变色, 参考 Mangar) */
    isDropTargetParent: Boolean = false,
    /** 点行内混合模式标签直达混合模式选择页; null 时该行只显示纯文本 */
    onOpenBlendModes: ((Int) -> Unit)? = null,
) {
    val rowHeight = vm.layerRowHeightDp.dp
    val index = layer.index
    val isBg = layer.isBackground
    val visible = layer.visible
    val isPanelMask = panelCrop != null
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // Right-swipe (multi-select) threshold to trigger selection (deliberate swipe, prevents accidental tap jitter)
    val selectThresholdPx = with(density) { 36.dp.roundToPx() }
    val drawerPx = with(density) { drawerWidth.roundToPx() }
    // Right-swipe (multi-select) follow distance cap before the row springs back
    val selectMaxPx = with(density) { 64.dp.roundToPx() }
    var rowTop by remember { mutableFloatStateOf(0f) }
    var rowBottom by remember { mutableFloatStateOf(0f) }
    var rowLeft by remember { mutableFloatStateOf(0f) }
    val rowInteraction = remember { MutableInteractionSource() }

    // 行内控件 (不透明度药丸 / 拖拽手柄) 的按下标记: 置位期间本行的"长按拖动"
    // 不启动, 否则在药丸上按住再拖动会被当成拖动排序 (见 LayerRowControls 头注释)
    val controlPressGate = remember { LayerRowPressGate() }
    // 行内不透明度药丸: 拖动中每 ~40ms 推一次预览 (引擎侧另有 16ms 渲染节流),
    // 松手用非 preview 版本提交, 一次拖动 = 一步撤销。
    // 滤镜/调整层的 setLayerOpacity 在引擎里走整棵投影重合成
    // (ReverieCore::recompositeProjection, 见 issue #139), 逐帧预览会顶住引擎线程:
    // 这类层拖动中只更新本地数值, 松手提交一次 (详情页滑杆行为不变)。
    val opacityScrub = remember(index) {
        var lastPreviewMs = 0L
        LayerOpacityScrub(
            onChange = { percent ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPreviewMs >= 40L) {
                    lastPreviewMs = now
                    if (vm.layers.getOrNull(index)?.nodeType != 3) {
                        vm.setLayerOpacity(index, percent / 100.0, preview = true)
                    }
                }
            },
            onEnd = { startPercent, percent ->
                lastPreviewMs = 0L
                if (startPercent == percent) {
                    // 拖回起手值: 只把引擎上的预览值退回, 不产生空的撤销步
                    vm.setLayerOpacity(index, percent / 100.0, preview = true)
                } else {
                    // 起手值一并交回: 提交前先把引擎退回起手值, 撤销一次才回到拖动前
                    // (直接提交只会退到最后一个预览值)
                    vm.commitLayerOpacityScrub(
                        i = index,
                        from = startPercent / 100.0,
                        to = percent / 100.0,
                    )
                }
            },
        )
    }

    // Coroutine scope used to drive revealAnim directly from gesture callbacks —
    // no LaunchedEffect follow-loop, zero scheduling delay, 1:1 finger tracking
    val scope = rememberCoroutineScope()

    // Row slide offset in px. snapTo() is called directly in the gesture handler
    // (via scope.launch) for instant follow; on release animateTo() springs back
    // or fully opens. One Animatable covers both phases, so there is no value
    // jump at finger lift.
    val revealAnim = remember { Animatable(0f) }
    var liveSwipeOffset by remember { mutableFloatStateOf(Float.NaN) }

    // When the external revealed flag flips (e.g. another row opens and this one
    // should close, or a tap closes it), snap-animate to the correct position.
    LaunchedEffect(revealed) {
        val animSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
        revealAnim.animateTo(if (revealed) -drawerPx.toFloat() else 0f, animSpec)
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(rowHeight)
                // Clip overflow so buttons are hidden at rest
                .clipToBounds()
                .pressScale(rowInteraction, pressedScale = 0.97f)
                .onGloballyPositioned { c ->
                    val bounds = c.boundsInRoot()
                    rowTop = bounds.top
                    rowBottom = bounds.bottom
                    rowLeft = bounds.left
                    onBounds(rowTop, rowBottom)
                }
                .pointerInput(index, isBg) {
                    if (isBg) {
                        detectTapGestures(
                            onPress = { offset ->
                                val press = androidx.compose.foundation.interaction.PressInteraction.Press(offset)
                                scope.launch { rowInteraction.emit(press) }
                                val released = tryAwaitRelease()
                                if (released) {
                                    scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press)) }
                                } else {
                                    scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                                }
                            },
                            onTap = {
                                onClick()
                            }
                        )
                        return@pointerInput
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        val startOffset = revealAnim.value
                        val touchSlop = viewConfiguration.touchSlop
                        val isDrawerOpen = revealed || startOffset < -touchSlop
                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
                        if (!isDrawerOpen) {
                            scope.launch { rowInteraction.emit(press) }
                        }

                        var lastChange: androidx.compose.ui.input.pointer.PointerInputChange = down
                        var isTap = false
                        var isSwipe = false
                        var isScroll = false

                        // Phase 1: Wait for 300ms long-press with real coroutine timer
                        val earlyExit: Int? = withTimeoutOrNull(300L) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastChange = change
                                if (change.changedToUpIgnoreConsumed()) {
                                    isTap = true
                                    return@withTimeoutOrNull 0
                                }
                                // 行内控件 (不透明度药丸 / 拖拽手柄) 只消费位移、把松手
                                // 留给本行判定: 一旦看到已被消费的位移, 说明这次手势
                                // 归子控件, 本行不再做点击/长按/滑动判定
                                if (change.isConsumed) return@withTimeoutOrNull 3
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.25f) {
                                    isSwipe = true
                                    return@withTimeoutOrNull 1
                                }
                                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                                    isScroll = true
                                    return@withTimeoutOrNull 2
                                }
                            }
                            null
                        }

                        val isMask = com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType)
                        val scrollGuarded = android.os.SystemClock.uptimeMillis() < scrollGuardUntil.value
                        // 行内控件上的按住 (药丸 / 手柄) 不长按拖动: 药丸要留给横向调值,
                        // 手柄自己会在位移后立即开始拖动
                        val controlHold = controlPressGate.pressedInside
                        if (earlyExit == null && !isDrawerOpen && !isMask && !scrollGuarded && !controlHold) {
                            // Phase 2: EXACTLY 300ms elapsed without movement exceeding slop -> TRIGGER DRAG!
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            onDragStart(rowLeft + startX, rowTop + startY)

                            // Consume ALL subsequent movement events so LazyColumn NEVER receives drag deltas
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastChange = change
                                if (change.changedToUpIgnoreConsumed()) break
                                change.consume()
                                onDragPosition(rowLeft + change.position.x, rowTop + change.position.y)
                            }
                            if (lastChange.changedToUpIgnoreConsumed()) {
                                onDragEnd()
                            }
                        } else if (isSwipe) {
                            if (!isDrawerOpen) {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            }
                            var velocityX = 0f
                            var prevX = lastChange.position.x
                            var prevTimeNs = lastChange.uptimeMillis * 1_000_000L
                            var selectTriggered = false

                            lastChange.consume()
                            val initDx = lastChange.position.x - startX
                            val isRightSwipe = initDx > 0 && startOffset >= -selectThresholdPx && !isMask
                            if (isRightSwipe) {
                                liveSwipeOffset = (initDx * 0.6f).coerceIn(0f, selectMaxPx.toFloat())
                                if (initDx > selectThresholdPx && !selectTriggered) {
                                    selectTriggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onSelect()
                                }
                            } else if (!isPanelMask && !isRightSwipe && initDx < 0) {
                                liveSwipeOffset = (startOffset + initDx).coerceIn(-drawerPx.toFloat(), 0f)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastChange = change
                                if (change.changedToUpIgnoreConsumed()) break
                                change.consume()
                                val currentX = change.position.x
                                val dx = currentX - startX
                                val nowNs = change.uptimeMillis * 1_000_000L
                                val dt = ((nowNs - prevTimeNs) / 1_000_000f).coerceAtLeast(1f)
                                velocityX = ((currentX - prevX) / dt * 1000f).coerceIn(-5000f, 5000f)
                                prevX = currentX
                                prevTimeNs = nowNs
                                if (dx > 0 && startOffset >= -selectThresholdPx && !isMask) {
                                    liveSwipeOffset = (dx * 0.6f).coerceIn(0f, selectMaxPx.toFloat())
                                    if (dx > selectThresholdPx && !selectTriggered) {
                                        selectTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onSelect()
                                    }
                                } else if (!isPanelMask && (dx < 0 || !isMask)) {
                                    liveSwipeOffset = (startOffset + dx).coerceIn(-drawerPx.toFloat(), 0f)
                                }
                            }

                            val currentOffset = if (liveSwipeOffset.isNaN()) revealAnim.value else liveSwipeOffset
                            val shouldReveal = !isPanelMask && (currentOffset < -drawerPx * 0.4f || velocityX < -500f)
                            val targetOffset = if (shouldReveal) -drawerPx.toFloat() else 0f
                            val animSpec = spring<Float>(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            )
                            scope.launch {
                                if (!liveSwipeOffset.isNaN()) {
                                    revealAnim.snapTo(currentOffset)
                                    liveSwipeOffset = Float.NaN
                                }
                                revealAnim.animateTo(targetOffset, animSpec)
                            }
                            if (shouldReveal) onReveal() else onRevealClose()
                        } else if (isTap) {
                            if (isDrawerOpen) {
                                if (startX < size.width - drawerPx) {
                                    scope.launch {
                                        revealAnim.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                                    }
                                    onRevealClose()
                                }
                            } else if (lastChange.isConsumed) {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            } else {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press)) }
                                onClick()
                            }
                        } else if (isScroll) {
                            if (!isDrawerOpen) {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                            }
                        } else {
                            if (!isDrawerOpen) {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            }
                        }
                        // 本次手势结束: 行内控件的按下标记一并清掉, 避免子控件手势
                        // 异常收尾时留下"永远置位"的标记 (那会让本行长按拖动再也起不来)
                        controlPressGate.pressedInside = false
                    }
                },
    ) {
        // Inner unit: row content + buttons slide as one piece.
        val groupScale by animateFloatAsState(
            targetValue = if (dragOnGroup) 1.025f else 1.0f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
            label = "groupScale",
        )
        val labelColor = if (layer.colorLabel > 0) layerLabelColor(layer.colorLabel) else Color.Transparent
        val selectionBg by animateColorAsState(
            targetValue =
                when {
                    isDragging -> Color.Transparent
                    layer.colorLabel > 0 ->
                        when {
                            dragOnGroup || selected -> labelColor.copy(alpha = 0.26f)
                            multiSelected -> labelColor.copy(alpha = 0.18f)
                            else -> labelColor.copy(alpha = 0.12f)
                        }
                    else ->
                        when {
                            isDropTargetParent -> Morandi.accent.copy(alpha = 0.16f)
                            dragOnGroup || selected -> Morandi.accent.copy(alpha = 0.22f)
                            multiSelected -> Morandi.accent.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        }
                },
            animationSpec = spring(dampingRatio = 0.90f, stiffness = 500f),
            label = "selectionBg",
        )
        val groupBorderColor by animateColorAsState(
            targetValue = if (dragOnGroup) (if (layer.colorLabel > 0) labelColor else Morandi.accent) else Color.Transparent,
            animationSpec = tween(180),
            label = "groupBorderColor",
        )
        val groupBorderWidth by animateDpAsState(
            targetValue = if (dragOnGroup) 2.dp else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
            label = "groupBorderWidth",
        )
        val groupBorder = if (groupBorderWidth > 0.dp) BorderStroke(groupBorderWidth, groupBorderColor) else null
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .then(if (groupBorder != null) Modifier.border(groupBorder) else Modifier)
                    .offset {
                        val x = if (liveSwipeOffset.isNaN()) revealAnim.value else liveSwipeOffset
                        IntOffset(x.roundToInt(), 0)
                    }
                    .graphicsLayer {
                        alpha = when {
                            isDragging && vm.isLayerDragSettling -> 0f
                            isDragging -> 0.35f
                            else -> 1f
                        }
                        scaleX = groupScale
                        scaleY = groupScale
                    },
        ) {
            // Selection / color-label background starts after the 32dp eye visibility column
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp)
                    .background(selectionBg),
            )

            // Selected indicator pill on the left edge of the content area (after eye column)
            if (selected && !isDragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 32.dp)
                        .width(3.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp))
                        .background(Morandi.accent),
                )
            }

                LayerRowContent(
                    vm = vm,
                    layer = layer,
                    selected = selected,
                    collapsed = collapsed,
                    index = index,
                    onToggleCollapse = onToggleCollapse,
                    onClick = onClick,
                    onRename = onRename,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .padding(end = 6.dp),
                    hasNextSibling = hasNextSibling,
                    openDepths = openDepths,
                    panelCrop = panelCrop,
                    effectiveVisible = effectiveVisible,
                    // 背景层不透明度由引擎固定为 100% (ReverieCore::setLayerOpacity 直接
                    // 忽略 index 0), 分格裁剪蒙版也没有独立不透明度: 这两种行只显示数值
                    opacityScrub = if (panelCrop != null || isBg) null else opacityScrub,
                    // 混合模式直达: 背景层恒为"正常", 蒙版没有混合模式, 分格组行走
                    // 自己的面板工具 —— 这三种行不挂入口, 标签退化成纯文本
                    onOpenBlendModes =
                        if (isBg || isPanelMask ||
                            com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType) ||
                            vm.panelIdForGroupRow(index) != null
                        ) {
                            null
                        } else {
                            onOpenBlendModes
                        },
                    isDropTargetParent = isDropTargetParent,
                    pressGate = controlPressGate,
                )
            Row(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .offset { IntOffset(drawerPx, 0) }
                        .width(drawerWidth)
                        .fillMaxHeight(),
            ) {
                val isMask = com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType)
                if (isMask && isPanelMask) {
                    // 分格裁剪蒙版行: 抽屉动作整体隐藏 (反相/应用/删除蒙版均不适用)
                } else if (!isMask) {
                    DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_copy, stringResource(R.string.common_copy)) {
                        vm.copyLayer(index)
                        onRevealClose()
                    }
                    DrawerAction(Modifier.weight(1f), Morandi.accent, R.drawable.ic_eye, stringResource(R.string.layer_drawer_solo)) {
                        vm.soloLayer(index)
                        onRevealClose()
                    }
                } else {
                    DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_image_adjust, stringResource(R.string.layer_op_invert_mask)) {
                        vm.invertMask(index)
                        onRevealClose()
                    }
                    DrawerAction(Modifier.weight(1f), Morandi.accent, R.drawable.ic_fill, stringResource(R.string.layer_op_apply_mask)) {
                        vm.applyMask(index)
                        onRevealClose()
                    }
                }
                if (!(isMask && isPanelMask)) {
                    val isClearAction = shouldSwipeClearInsteadOfDelete(vm.layers, layer)
                    DrawerAction(
                        Modifier.weight(1f),
                        Color(0xFFB05552),
                        if (isClearAction) R.drawable.ic_erase else R.drawable.ic_trash,
                        stringResource(if (isClearAction) R.string.layer_drawer_clear else R.string.common_delete),
                    ) {
                        if (!isBg) {
                            if (isMask) {
                                vm.removeMask(index)
                            } else if (isClearAction) {
                                vm.clearLayer(index)
                            } else {
                                if (index in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1) {
                                    vm.removeSelectedLayers()
                                } else {
                                    val pid = vm.panelIdForGroupRow(index)
                                    if (pid != null) {
                                        vm.deletePanel(pid)
                                    } else {
                                        vm.removeLayer(index)
                                    }
                                }
                            }
                        }
                        onRevealClose()
                    }
                }
            }
        }
    }
}

/**
 * Visual content of a layer row (indent guides, collapse arrow, eye, thumbnail,
 * name with sub-info, status icons). No gestures - shared by the in-list row
 * and the floating drag overlay.
 */
@Composable
internal fun LayerRowContent(
    vm: PaintViewModel,
    layer: PaintViewModel.LayerUiState,
    selected: Boolean,
    collapsed: Boolean,
    index: Int,
    onToggleCollapse: () -> Unit,
    onClick: () -> Unit = {},
    onRename: ((Int, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasNextSibling: Boolean = false,
    openDepths: Set<Int> = emptySet(),
    // 分格裁剪蒙版行 (panelCropStateFor): 非 null 时眼睛走裁剪开关、副标题改裁剪口径
    panelCrop: Pair<Long, Boolean>? = null,
    // 自身可见但祖先组隐藏: 眼睛画暗 (画布上看不见这一层)
    effectiveVisible: Boolean = true,
    // 行内不透明度药丸的手势宿主; null = 只显示数值 (拖拽跟随卡片用它)
    opacityScrub: LayerOpacityScrub? = null,
    pressGate: LayerRowPressGate? = null,
    // 当前拖拽落点是否以本组为父图层 (用于组高亮与引导线变色, 参考 Mangar)
    isDropTargetParent: Boolean = false,
    // 行内混合模式标签的直达入口; null = 纯文本 (拖拽卡片与背景/蒙版行)
    onOpenBlendModes: ((Int) -> Unit)? = null,
) {
    val isBg = layer.isBackground
    val visible = layer.visible
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Visibility eye switch (ALWAYS at the absolute leftmost edge across ALL rows, filling full row height)
        Box(
            modifier = Modifier
                .width(32.dp)
                .fillMaxHeight()
                .background(if (visible) Color.Transparent else Morandi.panel.copy(alpha = 0.35f))
                .noRippleClickable {
                    when {
                        vm.panelIdForGroupRow(index) != null ->
                            vm.setPanelRowVisible(vm.panelIdForGroupRow(index)!!, !visible)
                        // 分格蒙版: 眼睛 = setPanelCrop (可见性 + cropEnabled 元数据同写);
                        // 跨格容器蒙版 (panelId=-1 哨兵) 无元数据, 可见性即裁剪, 走既有 toggle
                        panelCrop == null || panelCrop.first == -1L -> vm.toggleLayerVisible(index)
                        else -> vm.setPanelCrop(panelCrop.first, !visible)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                contentDescription = if (visible && !effectiveVisible) {
                    stringResource(R.string.layer_hidden_by_group)
                } else {
                    stringResource(R.string.layer_visibility)
                },
                // 自身可见但祖先组隐藏: 眼睛画暗 (Mangar 的图层面板同样处理) ——
                // 否则用户会盯着一个"亮着的眼睛"找不到画面
                tint = when {
                    !effectiveVisible -> Morandi.subText.copy(alpha = 0.30f)
                    visible -> Morandi.icon
                    else -> Morandi.subText.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(17.dp),
            )
        }

        // 2. Group tree guide lines (depth > 0)
        if (layer.depth > 0) {
            val stepDp = LAYER_INDENT_STEP_DP.dp
            val treeWidth = (layer.depth * LAYER_INDENT_STEP_DP).dp
            Canvas(
                modifier = Modifier
                    .width(treeWidth)
                    .fillMaxHeight(),
            ) {
                val step = stepDp.toPx()
                val lw = if (isDropTargetParent) 1.6f.dp.toPx() else 1.2f.dp.toPx()
                val midY = size.height / 2f
                val r = 4.dp.toPx()
                val guideColor = if (isDropTargetParent) Morandi.accent.copy(alpha = 0.75f) else Morandi.subText.copy(alpha = 0.35f)

                for (d in 1..layer.depth) {
                    val x = step * (d - 0.5f)
                    if (d < layer.depth) {
                        // Ancestor vertical line
                        if (openDepths.isEmpty() || d in openDepths) {
                            drawLine(
                                color = guideColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = lw,
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        // Direct branch connector
                        val branchEndX = size.width
                        val path = Path().apply {
                            moveTo(x, 0f)
                            if (hasNextSibling) {
                                lineTo(x, size.height)
                                moveTo(x, midY)
                                lineTo(branchEndX, midY)
                            } else {
                                lineTo(x, midY - r)
                                quadraticTo(x, midY, x + r, midY)
                                lineTo(branchEndX, midY)
                            }
                        }
                        drawPath(
                            path = path,
                            color = guideColor,
                            style = Stroke(
                                width = lw,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }

        // 3. Thumbnail (always aligned across all rows at the same group depth)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(
                    width = 1.dp,
                    color = Morandi.border.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(7.dp),
                ),
        ) {
            LightCheckerboard(Modifier.fillMaxSize())
            val isFilter = layer.nodeType == 3
            val isMask = com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType)
            if (isFilter) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Morandi.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_image_adjust),
                        contentDescription = stringResource(R.string.layer_filter_layer),
                        tint = Morandi.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (isMask) {
                val thumb = vm.thumbFor(layer.index, layer.name)
                if (thumb != null && !thumb.isRecycled) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = stringResource(R.string.layer_thumbnail),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Morandi.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_crop),
                            contentDescription = stringResource(R.string.layer_op_add_mask),
                            tint = Morandi.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                val panelId = vm.panelIdForGroupRow(layer.index)
                val contentIdx = if (panelId != null) vm.panelContentLayerIndex(panelId) else -1
                val contentLayer = if (contentIdx >= 0) vm.layers.getOrNull(contentIdx) else null
                val thumb = if (contentLayer != null) {
                    vm.thumbFor(contentIdx, contentLayer.name) ?: vm.thumbFor(layer.index, layer.name)
                } else {
                    vm.thumbFor(layer.index, layer.name)
                }
                if (thumb != null && !thumb.isRecycled) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = stringResource(R.string.layer_thumbnail),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (layer.isGroup) {
                    val isPanel = vm.panelIdForGroupRow(layer.index) != null
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Morandi.accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (isPanel) R.drawable.ic_panel_frame else R.drawable.ic_folder),
                            contentDescription = stringResource(if (isPanel) R.string.tool_panel else R.string.layer_add_group),
                            tint = Morandi.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // 5. Group collapse chevron
        if (layer.isGroup) {
            val chevronRot by animateFloatAsState(
                targetValue = if (collapsed) 0f else 90f,
                animationSpec = tween(180),
                label = "chevronRot",
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .noRippleClickable(onToggleCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = if (collapsed) stringResource(R.string.layer_expand) else stringResource(R.string.layer_collapse),
                    tint = if (isDropTargetParent) Morandi.accent else Morandi.subText,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(chevronRot),
                )
            }
        }

        // 6. Name, type icon, opacity/blend-mode subtitle
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            var lastNameTapTime by remember(layer.index) { mutableLongStateOf(0L) }
            var pendingDetailJob by remember(layer.index) { mutableStateOf<Job?>(null) }
            val coroutineScope = rememberCoroutineScope()

            DisposableEffect(layer.index) {
                onDispose {
                    pendingDetailJob?.cancel()
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (layer.clipped) {
                    Icon(
                        painterResource(R.drawable.ic_clip),
                        contentDescription = stringResource(R.string.layer_op_clip),
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                } else if (layer.alphaInherited) {
                    Icon(
                        painterResource(R.drawable.ic_alpha_inherit),
                        contentDescription = stringResource(R.string.layer_op_alpha_inherit),
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (layer.nodeType == 2) {
                    Icon(
                        painterResource(R.drawable.ic_fill),
                        contentDescription = stringResource(R.string.layer_fill_layer),
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                } else if (layer.nodeType == 3) {
                    Icon(
                        painterResource(R.drawable.ic_image_adjust),
                        contentDescription = stringResource(R.string.layer_filter_layer),
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                } else if (layer.isStrokeLayer || layer.nodeType == 6) {
                    Icon(
                        painterResource(R.drawable.ic_shape_stroke),
                        contentDescription = stringResource(R.string.layer_stroke_layer),
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (vm.active3DLayers.containsKey(layer.id)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Morandi.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "3D",
                            color = Morandi.accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (vm.activeTextLayers.containsKey(layer.id)) {
                    val count = vm.activeTextLayers[layer.id]?.size ?: 0
                    val badgeText = if (count > 1) "T $count" else "T"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Morandi.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = Morandi.accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                val panelId = vm.panelIdForGroupRow(layer.index)
                if (panelId != null && vm.panelSharesDraw(panelId)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Morandi.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_link),
                                contentDescription = stringResource(R.string.panel_badge_shared),
                                tint = Morandi.accent,
                                modifier = Modifier.size(9.dp),
                            )
                            Text(
                                text = stringResource(R.string.panel_badge_shared),
                                color = Morandi.accent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                val panelTitle = vm.drawUnitTitleForPanelGroup(layer.index)
                Text(
                    text = panelTitle ?: layerDisplayName(layer.name),
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onRename != null && !isBg) {
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastNameTapTime < 260L) {
                                    lastNameTapTime = 0L
                                    pendingDetailJob?.cancel()
                                    pendingDetailJob = null
                                    onRename(layer.index, layer.name)
                                } else {
                                    lastNameTapTime = now
                                    if (!selected) {
                                        pendingDetailJob?.cancel()
                                        pendingDetailJob = null
                                        onClick()
                                    } else {
                                        pendingDetailJob?.cancel()
                                        pendingDetailJob = coroutineScope.launch {
                                            delay(200L)
                                            onClick()
                                        }
                                    }
                                }
                            }
                    } else Modifier,
                )
            }
            val blendName = stringResource(blendModeResId(layer.blendMode))
            val isStroke = layer.isStrokeLayer || layer.nodeType == 6
            val isMask = com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType)
            // 第二行: [类型标签] [不透明度药丸] [混合模式]。药丸始终在, 横向拖动
            // 直接调本层不透明度 (见 LayerRowControls), 详情页滑杆仍是精确入口。
            // 分格裁剪蒙版行没有独立不透明度, 副标题改「裁剪 · 开/关」口径。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (panelCrop != null) {
                    Text(
                        text = stringResource(
                            if (panelCrop.second) R.string.panel_crop_state_on else R.string.panel_crop_state_off,
                        ),
                        color = Morandi.subText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val tag = when {
                        layer.nodeType == 2 -> stringResource(R.string.layer_tag_fill_prefix)
                        layer.nodeType == 3 -> stringResource(R.string.layer_tag_filter_prefix)
                        isStroke -> stringResource(R.string.layer_tag_stroke_prefix)
                        layer.nodeType == com.reverie.paint.model.LayerNodeType.SELECTION_MASK -> stringResource(R.string.layer_tag_selection_mask_prefix)
                        layer.nodeType == com.reverie.paint.model.LayerNodeType.FILTER_MASK -> stringResource(R.string.layer_tag_filter_mask_prefix)
                        layer.nodeType == com.reverie.paint.model.LayerNodeType.TRANSFORM_MASK -> stringResource(R.string.layer_tag_transform_mask_prefix)
                        isMask -> stringResource(R.string.layer_tag_mask_prefix)
                        else -> ""
                    }
                    if (tag.isNotEmpty()) {
                        Text(
                            text = tag,
                            color = Morandi.subText,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                    LayerOpacityChip(
                        percent = (layer.opacity * 100).roundToInt().coerceIn(0, 100),
                        contentDescription = stringResource(R.string.layer_opacity),
                        scrub = opacityScrub,
                        pressGate = pressGate,
                    )
                    // 混合模式: 仅当非"正常"模式时显示
                    if (layer.blendMode != "normal") {
                        if (onOpenBlendModes != null) {
                            LayerBlendModeChip(
                                text = blendName,
                                contentDescription = stringResource(R.string.layer_blend_mode),
                                modified = true,
                                onClick = { onOpenBlendModes(index) },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        } else if (!isMask) {
                            Text(
                                text = blendName,
                                color = Morandi.subText,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }

        // 7. Right-side status icons badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (vm.layerSoloed(index)) {
                Icon(
                    painterResource(R.drawable.ic_eye),
                    contentDescription = stringResource(R.string.layer_solo),
                    tint = Morandi.accent,
                    modifier = Modifier.size(13.dp),
                )
            }
            if (layer.clipped) {
                Icon(
                    painterResource(R.drawable.ic_clip),
                    contentDescription = stringResource(R.string.layer_op_clip),
                    tint = if (selected) Morandi.text.copy(alpha = 0.85f) else Morandi.subText,
                    modifier = Modifier.size(13.dp),
                )
            } else if (layer.alphaInherited) {
                Icon(
                    painterResource(R.drawable.ic_alpha_inherit),
                    contentDescription = stringResource(R.string.layer_op_alpha_inherit),
                    tint = if (selected) Morandi.text.copy(alpha = 0.85f) else Morandi.subText,
                    modifier = Modifier.size(13.dp),
                )
            }
            if (layer.alphaLocked && !isBg) {
                Icon(
                    painterResource(R.drawable.ic_grid),
                    contentDescription = stringResource(R.string.layer_alpha_lock),
                    tint = if (selected) Morandi.text.copy(alpha = 0.85f) else Morandi.subText,
                    modifier = Modifier.size(13.dp),
                )
            }
            if (layer.locked || isBg) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = stringResource(R.string.layer_locked),
                    tint = if (selected) Morandi.text.copy(alpha = 0.85f) else Morandi.subText,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
internal fun DrawerAction(
    modifier: Modifier = Modifier,
    color: Color,
    resId: Int,
    desc: String,
    contentColor: Color = color.optimalTextColor(),
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(color)
                .noRippleClickable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(resId), contentDescription = desc, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(desc, color = contentColor, fontSize = 9.sp, maxLines = 1)
    }
}
