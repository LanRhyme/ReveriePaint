/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.quickaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.QuickAction
import com.reverie.paint.model.QuickActionLayoutMode
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * 悬浮快捷操作小窗 (QuickActionWindow)
 * 支持：
 * 1. 自适应胶囊 (单行/单列) 与自适应圆角矩形 (多列网格)
 * 2. 自由拖动、贴边防出界
 * 3. 顶部微触把手一键折叠收缩为半透明悬浮球/微胶囊
 * 4. 高斯毛玻璃背景与透明度
 * 5. 右上角齿轮即时进入动作自定义/排序/模式选择对话框
 */
@Composable
fun QuickActionWindow(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val dm = context.resources.displayMetrics
    val screenWidthPx = dm.widthPixels.toFloat()
    val screenHeightPx = dm.heightPixels.toFloat()

    val currentScreenWidthPx by rememberUpdatedState(screenWidthPx)
    val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)

    val marginPx = with(density) { 8.dp.toPx() }

    var showEditDialog by remember { mutableStateOf(false) }

    var actionToastText by remember { mutableStateOf<String?>(null) }
    var actionToastIcon by remember { mutableIntStateOf(0) }
    var actionToastRevision by remember { mutableLongStateOf(0L) }

    LaunchedEffect(actionToastRevision) {
        if (actionToastRevision > 0L) {
            kotlinx.coroutines.delay(1000)
            actionToastText = null
        }
    }

    fun onTriggerAction(action: QuickAction) {
        val (toastText, toastIcon) = getActionToastInfo(action, vm, context)
        actionToastText = toastText
        actionToastIcon = toastIcon
        actionToastRevision++
        vm.executeQuickAction(action)
    }

    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    val config = vm.quickActionsConfig
    val isCollapsed = vm.quickActionCollapsed
    val layoutMode = config.layoutMode
    val activeActions = config.actions

    val defaultW = with(density) {
        if (isCollapsed) 64.dp.toPx()
        else when (layoutMode) {
            QuickActionLayoutMode.COLUMN -> 56.dp.toPx()
            QuickActionLayoutMode.ROW -> 240.dp.toPx()
            QuickActionLayoutMode.GRID_2 -> 110.dp.toPx()
            QuickActionLayoutMode.GRID_3 -> 160.dp.toPx()
        }
    }
    val defaultH = with(density) {
        if (isCollapsed) 44.dp.toPx()
        else when (layoutMode) {
            QuickActionLayoutMode.COLUMN -> 280.dp.toPx()
            QuickActionLayoutMode.ROW -> 76.dp.toPx()
            QuickActionLayoutMode.GRID_2, QuickActionLayoutMode.GRID_3 -> 160.dp.toPx()
        }
    }

    LaunchedEffect(screenWidthPx, screenHeightPx, windowSize, isCollapsed, layoutMode) {
        val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
        val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
        val minX = marginPx
        val maxX = (screenWidthPx - curEffectiveW - marginPx).coerceAtLeast(minX)
        val minY = marginPx
        val maxY = (screenHeightPx - curEffectiveH - marginPx).coerceAtLeast(minY)
        if (vm.quickActionWindowX >= 0f && vm.quickActionWindowY >= 0f) {
            val clampedX = vm.quickActionWindowX.coerceIn(minX, maxX)
            val clampedY = vm.quickActionWindowY.coerceIn(minY, maxY)
            if (clampedX != vm.quickActionWindowX || clampedY != vm.quickActionWindowY) {
                vm.quickActionWindowX = clampedX
                vm.quickActionWindowY = clampedY
                vm.persistQuickActionsState()
            }
        }
    }

    // 动态圆角：单排为极限胶囊 (CircleShape 或大圆角)，多排为 20dp 圆角矩形，折叠时为小胶囊/圆形
    val windowShape = remember(isCollapsed, layoutMode) {
        if (isCollapsed) {
            RoundedCornerShape(22.dp)
        } else when (layoutMode) {
            QuickActionLayoutMode.ROW, QuickActionLayoutMode.COLUMN -> RoundedCornerShape(24.dp)
            QuickActionLayoutMode.GRID_2, QuickActionLayoutMode.GRID_3 -> RoundedCornerShape(20.dp)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.quickActionWindowWidth = 0f
            vm.quickActionWindowHeight = 0f
        }
    }

    Box(
        modifier = modifier
            .offset {
                val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                val minX = marginPx
                val maxX = (screenWidthPx - curEffectiveW - marginPx).coerceAtLeast(minX)
                val minY = marginPx
                val maxY = (screenHeightPx - curEffectiveH - marginPx).coerceAtLeast(minY)
                val x = if (vm.quickActionWindowX >= 0f) {
                    vm.quickActionWindowX.coerceIn(minX, maxX).roundToInt()
                } else {
                    ((screenWidthPx - curEffectiveW) / 2f).coerceIn(minX, maxX).roundToInt()
                }
                val y = if (vm.quickActionWindowY >= 0f) {
                    vm.quickActionWindowY.coerceIn(minY, maxY).roundToInt()
                } else {
                    ((screenHeightPx - curEffectiveH) / 2f).coerceIn(minY, maxY).roundToInt()
                }
                IntOffset(x, y)
            }
    ) {
        // ---- 悬浮窗实体 ----
        Box(
            modifier = Modifier
                .onSizeChanged { size ->
                    windowSize = size
                    vm.quickActionWindowWidth = size.width.toFloat()
                    vm.quickActionWindowHeight = size.height.toFloat()
                    if (size.width > 0 && size.height > 0) {
                        val minX = marginPx
                        val maxX = (screenWidthPx - size.width - marginPx).coerceAtLeast(minX)
                        val minY = marginPx
                        val maxY = (screenHeightPx - size.height - marginPx).coerceAtLeast(minY)
                        if (vm.quickActionWindowX < 0f || vm.quickActionWindowY < 0f) {
                            vm.quickActionWindowX = (maxX / 2f).coerceIn(minX, maxX)
                            vm.quickActionWindowY = (maxY / 2f).coerceIn(minY, maxY)
                            vm.persistQuickActionsState()
                        } else {
                            val clampedX = vm.quickActionWindowX.coerceIn(minX, maxX)
                            val clampedY = vm.quickActionWindowY.coerceIn(minY, maxY)
                            if (clampedX != vm.quickActionWindowX || clampedY != vm.quickActionWindowY) {
                                vm.quickActionWindowX = clampedX
                                vm.quickActionWindowY = clampedY
                                vm.persistQuickActionsState()
                            }
                        }
                    }
                }
                .shadow(12.dp, windowShape)
                .systemHoverIcon(context)
                .clip(windowShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = com.reverie.paint.ui.theme.Glass.popupStyle(if (opacity >= 0.99f) 0.92f else opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .animateContentSize(Motion.enterSpring())
        ) {
        if (isCollapsed) {
            // ---- 折叠微缩态 (流线型悬浮小球/胶囊) ----
            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { vm.persistQuickActionsState() },
                            onDragCancel = { vm.persistQuickActionsState() },
                        ) { change, dragAmount ->
                            change.consume()
                            val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                            val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                            val curMinX = marginPx
                            val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                            val curMinY = marginPx
                            val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                            vm.quickActionWindowX = (vm.quickActionWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                            vm.quickActionWindowY = (vm.quickActionWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 展开图标
                val expandSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Morandi.accent.copy(alpha = 0.18f))
                        .pressScale(expandSource, pressedScale = 0.88f)
                        .clickable(
                            interactionSource = expandSource,
                            indication = null,
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.quickActionCollapsed = false
                            vm.persistQuickActionsState()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shortcut),
                        contentDescription = stringResource(R.string.quick_action_window_title),
                        tint = Morandi.accent,
                        modifier = Modifier.size(17.dp),
                    )
                }

                // 关闭按钮
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable {
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = "Close",
                        tint = Morandi.subText,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            // ---- 展开完整态 ----
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 1. 顶部控制栏 (拖拽把手 + 折叠 + 设置 + 关闭)
                Row(
                    modifier = Modifier
                        .then(
                            when (layoutMode) {
                                QuickActionLayoutMode.ROW -> Modifier.widthIn(min = 120.dp)
                                QuickActionLayoutMode.COLUMN -> Modifier.width(44.dp)
                                QuickActionLayoutMode.GRID_2 -> Modifier.width(96.dp)
                                QuickActionLayoutMode.GRID_3 -> Modifier.width(144.dp)
                            }
                        )
                        .height(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { vm.persistQuickActionsState() },
                                onDragCancel = { vm.persistQuickActionsState() },
                            ) { change, dragAmount ->
                                change.consume()
                                val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                val curMinX = marginPx
                                val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                val curMinY = marginPx
                                val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                vm.quickActionWindowX = (vm.quickActionWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                vm.quickActionWindowY = (vm.quickActionWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (layoutMode == QuickActionLayoutMode.COLUMN) {
                        // 单列窄胶囊模式下的极简拖动手柄与折叠
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .clickable {
                                    vm.quickActionCollapsed = true
                                    vm.persistQuickActionsState()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Morandi.subText.copy(alpha = 0.45f))
                            )
                        }
                    } else {
                        // 横向或网格模式下的微型工具头
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 折叠按钮
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        vm.quickActionCollapsed = true
                                        vm.persistQuickActionsState()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(10.dp)
                                        .height(2.5.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(Morandi.subText)
                                )
                            }

                            // 标题
                            Text(
                                text = stringResource(R.string.quick_action_window_title),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // 编辑齿轮
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { showEditDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = "Edit",
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }

                            // 关闭
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { onClose() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = "Close",
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }

                if (layoutMode == QuickActionLayoutMode.COLUMN) {
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(6.dp))
                }

                // 2. 动作按钮渲染布局
                when (layoutMode) {
                    QuickActionLayoutMode.ROW -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            activeActions.forEach { action ->
                                QuickActionButton(
                                    vm = vm,
                                    action = action,
                                    showLabel = config.showLabels,
                                    onClick = { onTriggerAction(action) },
                                )
                            }
                        }
                    }
                    QuickActionLayoutMode.COLUMN -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            activeActions.forEach { action ->
                                QuickActionButton(
                                    vm = vm,
                                    action = action,
                                    showLabel = config.showLabels,
                                    onClick = { onTriggerAction(action) },
                                )
                            }

                            // 单列底部配置齿轮
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showEditDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = "Edit",
                                    tint = Morandi.subText.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    QuickActionLayoutMode.GRID_2 -> {
                        val chunks = activeActions.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            chunks.forEach { rowActions ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowActions.forEach { action ->
                                        QuickActionButton(
                                            vm = vm,
                                            action = action,
                                            showLabel = config.showLabels,
                                            onClick = { onTriggerAction(action) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    QuickActionLayoutMode.GRID_3 -> {
                        val chunks = activeActions.chunked(3)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            chunks.forEach { rowActions ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowActions.forEach { action ->
                                        QuickActionButton(
                                            vm = vm,
                                            action = action,
                                            showLabel = config.showLabels,
                                            onClick = { onTriggerAction(action) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // ---- 就近微型 Toast 提示 (智能贴合悬浮窗侧边或上方，提供操作即时反馈) ----
        val toastText = actionToastText
        val toastIcon = actionToastIcon
        val densityDpi = density.density
        val spacingPx = with(density) { 10.dp.roundToPx() }

        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(0, 0) {
                    val isCol = layoutMode == QuickActionLayoutMode.COLUMN
                    val isRight = vm.quickActionWindowX > screenWidthPx / 2f
                    val isNearTop = vm.quickActionWindowY < 90f * densityDpi

                    val px = if (isCol) {
                        if (isRight) -placeable.width - spacingPx else windowSize.width + spacingPx
                    } else {
                        (windowSize.width - placeable.width) / 2
                    }

                    val py = if (isCol) {
                        (windowSize.height - placeable.height) / 2
                    } else {
                        if (isNearTop) windowSize.height + spacingPx else -placeable.height - spacingPx
                    }

                    placeable.placeRelative(px, py)
                }
            }
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = toastText != null,
                enter = androidx.compose.animation.fadeIn(Motion.enterSpring()) +
                    androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.82f),
                exit = androidx.compose.animation.fadeOut(Motion.exitTween(150)) +
                    androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.82f),
            ) {
                if (toastText != null) {
                    Box(
                        modifier = Modifier
                            .shadow(8.dp, RoundedCornerShape(10.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.96f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (toastIcon != 0) {
                                Icon(
                                    painter = painterResource(toastIcon),
                                    contentDescription = null,
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            Text(
                                text = toastText,
                                color = Morandi.text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 自定义快捷操作编辑对话框 ----
    if (showEditDialog) {
        QuickActionsEditDialog(
            vm = vm,
            onDismiss = {
                showEditDialog = false
                vm.persistQuickActionsState()
            },
        )
    }
}

/**
 * 独立的快捷功能按钮
 */
@Composable
private fun QuickActionButton(
    vm: PaintViewModel,
    action: QuickAction,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }

    // 判断该动作当前是否处于高亮/激活态
    val isActivated = when (action) {
        QuickAction.LOCK_VIEW -> vm.isViewTransformLocked
        QuickAction.FLIP_H -> vm.viewFlipX
        QuickAction.FLIP_V -> vm.viewFlipY
        QuickAction.TOGGLE_ERASER -> vm.currentToolId == "eraser"
        QuickAction.ALPHA_LOCK -> vm.isCurrentLayerAlphaLocked()
        QuickAction.DISABLE_TOUCH -> vm.isCanvasTouchDisabled
        else -> false
    }

    val bg = if (isActivated) Morandi.accent else Morandi.panelHi.copy(alpha = 0.65f)
    val tint = if (isActivated) Color.White else Morandi.text

    if (showLabel) {
        // 图标 + 文字标签
        Column(
            modifier = Modifier
                .width(52.dp)
                .pressScale(source, pressedScale = 0.90f)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .clickable(interactionSource = source, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = stringResource(action.titleRes),
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(action.titleRes),
                color = if (isActivated) Color.White else Morandi.subText,
                fontSize = 9.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        // 纯高质感图标胶囊
        Box(
            modifier = Modifier
                .size(38.dp)
                .pressScale(source, pressedScale = 0.90f)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .clickable(interactionSource = source, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = stringResource(action.titleRes),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 快捷操作自定义配置弹窗
 */
/**
 * 快捷操作自定义配置弹窗
 * 支持：
 * 1. 自由调整各快捷操作的顺序位置 (向上/向下移动，实时伴随顺滑插槽动效)
 * 2. 快捷操作增删 (可从“可添加操作”池自由扩充或移除至回收池)
 * 3. 悬浮窗外观与排布模式实时切换 (单排横向/纵向胶囊、2列/3列网格、文字标签显隐)
 * 4. 纯净统一的莫兰迪配色体系与双列/分段自适应布局
 */
@Composable
private fun QuickActionsEditDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val config = LocalConfiguration.current
    val isWideScreen = config.screenWidthDp >= 640

    var currentActions by remember { mutableStateOf(vm.quickActionsConfig.actions) }
    var layoutMode by remember { mutableStateOf(vm.quickActionsConfig.layoutMode) }
    var showLabels by remember { mutableStateOf(vm.quickActionsConfig.showLabels) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val availableActions = remember(currentActions) {
        QuickAction.entries.filter { it !in currentActions }
    }

    fun saveAndDismiss() {
        vm.quickActionsConfig = vm.quickActionsConfig.copy(
            actions = currentActions,
            layoutMode = layoutMode,
            showLabels = showLabels,
        )
        onDismiss()
    }

    fun moveUp(index: Int) {
        if (index > 0) {
            val list = currentActions.toMutableList()
            val item = list.removeAt(index)
            list.add(index - 1, item)
            currentActions = list
        }
    }

    fun moveDown(index: Int) {
        if (index < currentActions.size - 1) {
            val list = currentActions.toMutableList()
            val item = list.removeAt(index)
            list.add(index + 1, item)
            currentActions = list
        }
    }

    fun removeAction(action: QuickAction) {
        if (currentActions.size > 1) {
            currentActions = currentActions.filter { it != action }
        }
    }

    fun addAction(action: QuickAction) {
        if (!currentActions.contains(action)) {
            currentActions = currentActions + action
        }
    }

    fun resetDefaults() {
        currentActions = QuickAction.DEFAULT_ACTIONS
        layoutMode = QuickActionLayoutMode.COLUMN
        showLabels = false
    }

    fun addAllActions() {
        currentActions = QuickAction.entries.toList()
    }

    Dialog(
        onDismissRequest = { saveAndDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable { saveAndDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val dialogShape = RoundedCornerShape(20.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = if (isWideScreen) 660.dp else 460.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = if (isWideScreen) 540.dp else 600.dp)
                    .shadow(20.dp, dialogShape, spotColor = Color.Black.copy(alpha = 0.45f))
                    .clip(dialogShape)
                    .background(Morandi.panel)
                    .glassBorder(dialogShape)
                    .clickable(enabled = false) {}
                    .padding(20.dp),
            ) {
                // ---- 1. 顶部标题栏 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shortcut),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.quick_action_edit),
                                color = Morandi.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.quick_action_edit_desc),
                                color = Morandi.subText,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Morandi.panelHi)
                            .clickable { saveAndDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "Close",
                            tint = Morandi.text,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- 2. 主体内容区 ----
                if (isWideScreen) {
                    // ---- 平板双列并排布局 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // 左侧栏：样式设置与实时效果预览 (宽度 42%)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            QuickActionsStyleSection(
                                layoutMode = layoutMode,
                                onLayoutModeChange = { layoutMode = it },
                                showLabels = showLabels,
                                onShowLabelsChange = { showLabels = it },
                            )

                            Text(
                                text = stringResource(R.string.quick_action_preview),
                                color = Morandi.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )

                            QuickActionsPreviewCard(
                                actions = currentActions,
                                layoutMode = layoutMode,
                                showLabels = showLabels,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 右侧栏：已启用快捷操作列表与添加更多操作池 (宽度 58%)
                        Column(
                            modifier = Modifier
                                .weight(1.35f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${stringResource(R.string.quick_action_active_list)} (${currentActions.size})",
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.quick_action_reset_default),
                                        color = Morandi.subText,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Morandi.panelHi)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                resetDefaults()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )

                                    if (availableActions.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.quick_action_add_all),
                                            color = Morandi.accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Morandi.accent.copy(alpha = 0.12f))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    addAllActions()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(currentActions, key = { _, action -> action.id }) { index, action ->
                                    ActiveActionRow(
                                        index = index,
                                        action = action,
                                        isFirst = index == 0,
                                        isLast = index == currentActions.size - 1,
                                        canRemove = currentActions.size > 1,
                                        onMoveUp = { moveUp(index) },
                                        onMoveDown = { moveDown(index) },
                                        onRemove = { removeAction(action) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }

                                if (availableActions.isNotEmpty()) {
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${stringResource(R.string.quick_action_available_list)} (${availableActions.size})",
                                            color = Morandi.subText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }

                                    item {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            availableActions.forEach { action ->
                                                AvailableActionChip(
                                                    action = action,
                                                    onAdd = { addAction(action) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ---- 手机/窄屏分段 Tab 布局 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Morandi.panelHi)
                            .padding(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selectedTab == 0) Morandi.accent else Color.Transparent)
                                .clickable { selectedTab = 0 },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${stringResource(R.string.quick_action_tab_actions)} (${currentActions.size})",
                                color = if (selectedTab == 0) Color.White else Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selectedTab == 1) Morandi.accent else Color.Transparent)
                                .clickable { selectedTab = 1 },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.quick_action_tab_style),
                                color = if (selectedTab == 1) Color.White else Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (selectedTab == 0) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${stringResource(R.string.quick_action_active_list)} (${currentActions.size})",
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )

                                Text(
                                    text = stringResource(R.string.quick_action_reset_default),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panelHi)
                                        .clickable { resetDefaults() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(currentActions, key = { _, action -> action.id }) { index, action ->
                                    ActiveActionRow(
                                        index = index,
                                        action = action,
                                        isFirst = index == 0,
                                        isLast = index == currentActions.size - 1,
                                        canRemove = currentActions.size > 1,
                                        onMoveUp = { moveUp(index) },
                                        onMoveDown = { moveDown(index) },
                                        onRemove = { removeAction(action) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }

                                if (availableActions.isNotEmpty()) {
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${stringResource(R.string.quick_action_available_list)} (${availableActions.size})",
                                            color = Morandi.subText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }

                                    item {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            availableActions.forEach { action ->
                                                AvailableActionChip(
                                                    action = action,
                                                    onAdd = { addAction(action) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            QuickActionsStyleSection(
                                layoutMode = layoutMode,
                                onLayoutModeChange = { layoutMode = it },
                                showLabels = showLabels,
                                onShowLabelsChange = { showLabels = it },
                            )

                            Text(
                                text = stringResource(R.string.quick_action_preview),
                                color = Morandi.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )

                            QuickActionsPreviewCard(
                                actions = currentActions,
                                layoutMode = layoutMode,
                                showLabels = showLabels,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- 3. 底部完成栏 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已启用 ${currentActions.size} 项快捷操作",
                        color = Morandi.subText,
                        fontSize = 12.sp,
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.accent)
                            .clickable { saveAndDismiss() }
                            .padding(horizontal = 24.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_action_done),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 实时效果缩略预览卡片
 */
@Composable
private fun QuickActionsPreviewCard(
    actions: List<QuickAction>,
    layoutMode: QuickActionLayoutMode,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val windowShape = remember(layoutMode) {
        when (layoutMode) {
            QuickActionLayoutMode.ROW, QuickActionLayoutMode.COLUMN -> RoundedCornerShape(16.dp)
            QuickActionLayoutMode.GRID_2, QuickActionLayoutMode.GRID_3 -> RoundedCornerShape(14.dp)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .shadow(6.dp, windowShape)
                .clip(windowShape)
                .background(Morandi.panel)
                .padding(4.dp),
        ) {
            when (layoutMode) {
                QuickActionLayoutMode.ROW -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions.take(6).forEach { action ->
                            MiniActionItem(action, showLabels)
                        }
                        if (actions.size > 6) {
                            Text(
                                text = "+${actions.size - 6}",
                                color = Morandi.subText,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                QuickActionLayoutMode.COLUMN -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        actions.take(5).forEach { action ->
                            MiniActionItem(action, showLabels)
                        }
                        if (actions.size > 5) {
                            Text(
                                text = "+${actions.size - 5}",
                                color = Morandi.subText,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                QuickActionLayoutMode.GRID_2 -> {
                    val chunks = actions.take(6).chunked(2)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        chunks.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                row.forEach { action ->
                                    MiniActionItem(action, showLabels)
                                }
                            }
                        }
                    }
                }
                QuickActionLayoutMode.GRID_3 -> {
                    val chunks = actions.take(6).chunked(3)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        chunks.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                row.forEach { action ->
                                    MiniActionItem(action, showLabels)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniActionItem(action: QuickAction, showLabel: Boolean) {
    if (showLabel) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Morandi.panelHi)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                tint = Morandi.text,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = stringResource(action.titleRes),
                color = Morandi.text,
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Morandi.panelHi),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                tint = Morandi.text,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 样式设置卡片 (排布模式选择器 + 标签开关)
 */
@Composable
private fun QuickActionsStyleSection(
    layoutMode: QuickActionLayoutMode,
    onLayoutModeChange: (QuickActionLayoutMode) -> Unit,
    showLabels: Boolean,
    onShowLabelsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_action_layout_title),
            color = Morandi.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )

        // 4 档排布模式：两列并排，整齐紧凑
        val chunks = QuickActionLayoutMode.entries.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chunks.forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowModes.forEach { mode ->
                        val isSelected = layoutMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Morandi.accent else Morandi.panelHi)
                                .border(
                                    1.dp,
                                    if (isSelected) Morandi.accent else Morandi.border.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { onLayoutModeChange(mode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(mode.labelRes),
                                color = if (isSelected) Color.White else Morandi.subText,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // 显示文字标签切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { onShowLabelsChange(!showLabels) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_action_show_labels),
                color = Morandi.text,
                fontSize = 12.sp,
            )
            ReSwitch(
                checked = showLabels,
                onChecked = onShowLabelsChange,
            )
        }
    }
}

@Composable
private fun ActiveActionRow(
    index: Int,
    action: QuickAction,
    isFirst: Boolean,
    isLast: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧：序号角标 + 图标 + 操作名称
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Morandi.panel),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    color = Morandi.subText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Morandi.panel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                text = stringResource(action.titleRes),
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // 右侧：上移、下移与移除按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 上移
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isFirst) Morandi.panel else Color.Transparent)
                    .clickable(enabled = !isFirst) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onMoveUp()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = "Move Up",
                    tint = if (!isFirst) Morandi.text else Morandi.subText.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
            }

            // 下移
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isLast) Morandi.panel else Color.Transparent)
                    .clickable(enabled = !isLast) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onMoveDown()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = "Move Down",
                    tint = if (!isLast) Morandi.text else Morandi.subText.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(Modifier.width(3.dp))

            // 移除 (至少保留 1 项)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (canRemove) Morandi.panel else Color.Transparent)
                    .clickable(enabled = canRemove) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRemove()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = "Remove",
                    tint = if (canRemove) Color(0xFFE57373) else Morandi.subText.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AvailableActionChip(
    action: QuickAction,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onAdd()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(action.titleRes),
            color = Morandi.text,
            fontSize = 11.sp,
        )
        Icon(
            painter = painterResource(R.drawable.ic_plus),
            contentDescription = "Add",
            tint = Morandi.accent,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * 格式化快捷操作触发时的微型 Toast 提示文案与图标
 */
private fun getActionToastInfo(
    action: QuickAction,
    vm: PaintViewModel,
    context: android.content.Context,
): Pair<String, Int> {
    val text = when (action) {
        QuickAction.LOCK_VIEW -> {
            if (!vm.isViewTransformLocked) context.getString(R.string.toast_view_locked)
            else context.getString(R.string.toast_view_unlocked)
        }
        QuickAction.TOGGLE_ERASER -> {
            if (vm.currentToolId == "eraser") context.getString(R.string.tool_brush)
            else context.getString(R.string.tool_eraser)
        }
        QuickAction.FLIP_H -> {
            if (!vm.viewFlipX) context.getString(R.string.toast_view_flip_h_on)
            else context.getString(R.string.toast_view_flip_h_off)
        }
        QuickAction.FLIP_V -> {
            if (!vm.viewFlipY) context.getString(R.string.toast_view_flip_v_on)
            else context.getString(R.string.toast_view_flip_v_off)
        }
        QuickAction.BRUSH_SIZE_INC -> {
            val newSize = (vm.brushSize * 1.25).coerceAtMost(vm.effectiveBrushMaxSize)
            "${context.getString(R.string.quick_action_brush_size_inc)} (${newSize.toInt()}px)"
        }
        QuickAction.BRUSH_SIZE_DEC -> {
            val minL = vm.brushMinSizeLimit.coerceAtLeast(0.5)
            val newSize = (vm.brushSize / 1.25).coerceAtLeast(minL)
            "${context.getString(R.string.quick_action_brush_size_dec)} (${newSize.toInt()}px)"
        }
        QuickAction.ALPHA_LOCK -> {
            val layer = vm.layers.firstOrNull { it.index == vm.currentLayerIndex }
            if (layer?.alphaLocked == true) "已解除透明度锁定"
            else "已锁定透明度"
        }
        QuickAction.DISABLE_TOUCH -> {
            if (!vm.isCanvasTouchDisabled) context.getString(R.string.toast_canvas_touch_disabled)
            else context.getString(R.string.toast_canvas_touch_enabled)
        }
        else -> context.getString(action.titleRes)
    }
    return text to action.iconRes
}
