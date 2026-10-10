/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.quickbrush

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.BrushPresetInfo
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.applyTool
import com.reverie.paint.core.selectBrushPreset
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.painting.brush.rememberPresetThumb
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * 悬浮快捷笔刷胶囊小窗 (QuickBrushWindow)
 * 1. 极简单行/单列胶囊模式，显示常用画笔缩略图
 * 2. 自由拖动、贴边防出界
 * 3. 一键折叠为半透明小球/展开
 * 4. 高斯毛玻璃背景与透明度
 * 5. 点击画笔即时切换 Tool.BRUSH 并选中对应预设，附带近身微型 Toast 气泡
 * 6. 齿轮管理面板支持快速星标分类画笔与上下拖拽排序
 */
@Composable
fun QuickBrushWindow(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var showManageDialog by remember { mutableStateOf(false) }

    var toastText by remember { mutableStateOf<String?>(null) }
    var toastRevision by remember { mutableLongStateOf(0L) }

    LaunchedEffect(toastRevision) {
        if (toastRevision > 0L) {
            kotlinx.coroutines.delay(1200)
            toastText = null
        }
    }

    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    val isCollapsed = vm.quickBrushCollapsed
    val isVertical = vm.quickBrushOrientation == "vertical"
    val favoritePresets = remember(vm.brushPresets, vm.favoriteBrushNames, vm.quickBrushOrder) {
        vm.getOrderedFavoriteBrushes()
    }
    val maxListLengthDp = remember(vm.quickBrushMaxLength) {
        (vm.quickBrushMaxLength * 43 - 1).coerceAtLeast(42).dp
    }

    val configuration = LocalConfiguration.current
    val dm = context.resources.displayMetrics
    val screenWidthPx = dm.widthPixels.toFloat()
    val screenHeightPx = dm.heightPixels.toFloat()

    val currentScreenWidthPx by rememberUpdatedState(screenWidthPx)
    val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)

    val marginPx = with(density) { 8.dp.toPx() }
    val defaultW = with(density) {
        if (isCollapsed) 64.dp.toPx()
        else if (isVertical) 54.dp.toPx()
        else 240.dp.toPx()
    }
    val defaultH = with(density) {
        if (isCollapsed) 44.dp.toPx()
        else if (isVertical) 260.dp.toPx()
        else 54.dp.toPx()
    }

    LaunchedEffect(screenWidthPx, screenHeightPx, windowSize, isCollapsed, isVertical) {
        val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
        val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
        val minX = marginPx
        val maxX = (screenWidthPx - curEffectiveW - marginPx).coerceAtLeast(minX)
        val minY = marginPx
        val maxY = (screenHeightPx - curEffectiveH - marginPx).coerceAtLeast(minY)
        if (vm.quickBrushWindowX >= 0f && vm.quickBrushWindowY >= 0f) {
            val clampedX = vm.quickBrushWindowX.coerceIn(minX, maxX)
            val clampedY = vm.quickBrushWindowY.coerceIn(minY, maxY)
            if (clampedX != vm.quickBrushWindowX || clampedY != vm.quickBrushWindowY) {
                vm.quickBrushWindowX = clampedX
                vm.quickBrushWindowY = clampedY
                vm.persistQuickBrushState()
            }
        }
    }

    val windowShape = remember(isCollapsed) {
        if (isCollapsed) RoundedCornerShape(22.dp) else RoundedCornerShape(22.dp)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.quickBrushWindowWidth = 0f
            vm.quickBrushWindowHeight = 0f
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
                val x = if (vm.quickBrushWindowX >= 0f) {
                    vm.quickBrushWindowX.coerceIn(minX, maxX).roundToInt()
                } else {
                    ((screenWidthPx - curEffectiveW) / 2f).coerceIn(minX, maxX).roundToInt()
                }
                val y = if (vm.quickBrushWindowY >= 0f) {
                    vm.quickBrushWindowY.coerceIn(minY, maxY).roundToInt()
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
                    vm.quickBrushWindowWidth = size.width.toFloat()
                    vm.quickBrushWindowHeight = size.height.toFloat()
                    if (size.width > 0 && size.height > 0) {
                        val minX = marginPx
                        val maxX = (screenWidthPx - size.width - marginPx).coerceAtLeast(minX)
                        val minY = marginPx
                        val maxY = (screenHeightPx - size.height - marginPx).coerceAtLeast(minY)
                        if (vm.quickBrushWindowX < 0f || vm.quickBrushWindowY < 0f) {
                            vm.quickBrushWindowX = (maxX / 2f).coerceIn(minX, maxX)
                            vm.quickBrushWindowY = (maxY / 2f).coerceIn(minY, maxY)
                            vm.persistQuickBrushState()
                        } else {
                            val clampedX = vm.quickBrushWindowX.coerceIn(minX, maxX)
                            val clampedY = vm.quickBrushWindowY.coerceIn(minY, maxY)
                            if (clampedX != vm.quickBrushWindowX || clampedY != vm.quickBrushWindowY) {
                                vm.quickBrushWindowX = clampedX
                                vm.quickBrushWindowY = clampedY
                                vm.persistQuickBrushState()
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
                // ---- 折叠微缩态 (流线型悬浮微球/微胶囊) ----
                Row(
                    modifier = Modifier
                        .wrapContentSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { vm.persistQuickBrushState() },
                                onDragCancel = { vm.persistQuickBrushState() },
                            ) { change, dragAmount ->
                                change.consume()
                                val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                val curMinX = marginPx
                                val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                val curMinY = marginPx
                                val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                vm.quickBrushWindowX = (vm.quickBrushWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                vm.quickBrushWindowY = (vm.quickBrushWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
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
                                vm.quickBrushCollapsed = false
                                vm.persistQuickBrushState()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_brush_quick),
                            contentDescription = stringResource(R.string.quick_brush_window_title),
                            tint = Morandi.accent,
                            modifier = Modifier.size(17.dp),
                        )
                    }

                    // 关闭按钮
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
            } else {
                // ---- 展开完整态 ----
                if (isVertical) {
                    // ---- 垂直胶囊模式 ----
                    Column(
                        modifier = Modifier
                            .wrapContentSize()
                            .padding(horizontal = 5.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        // 顶部拖动手柄与折叠
                        Box(
                            modifier = Modifier
                                .width(42.dp)
                                .height(20.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragEnd = { vm.persistQuickBrushState() },
                                        onDragCancel = { vm.persistQuickBrushState() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                        val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                        val curMinX = marginPx
                                        val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                        val curMinY = marginPx
                                        val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                        vm.quickBrushWindowX = (vm.quickBrushWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                        vm.quickBrushWindowY = (vm.quickBrushWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                                    }
                                }
                                .clickable {
                                    vm.quickBrushCollapsed = true
                                    vm.persistQuickBrushState()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Morandi.subText.copy(alpha = 0.5f))
                            )
                        }

                        // 笔刷列表或空状态
                        if (favoritePresets.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.accent.copy(alpha = 0.12f))
                                    .clickable { showManageDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_star),
                                    contentDescription = stringResource(R.string.quick_brush_empty),
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = maxListLengthDp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(vertical = 2.dp),
                            ) {
                                items(favoritePresets, key = { it.name }) { preset ->
                                    QuickBrushItem(
                                        preset = preset,
                                        isSelected = vm.currentToolId == Tool.BRUSH.id && preset.index == vm.brushPresetIndex,
                                        onClick = {
                                            vm.applyTool(Tool.BRUSH.id)
                                            vm.selectBrushPreset(preset.index)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            toastText = preset.name
                                            toastRevision++
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            toastText = "${preset.group.ifBlank { "常用" }} · ${preset.name}"
                                            toastRevision++
                                        },
                                    )
                                }
                            }
                        }

                        // 分割线
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(1.dp)
                                .background(Morandi.border.copy(alpha = 0.5f))
                        )

                        // 底部操作区 (齿轮管理与关闭)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 齿轮配置
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { showManageDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.quick_brush_manage_title),
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }

                            // 关闭
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
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
                } else {
                    // ---- 水平横向胶囊模式 ----
                    Row(
                        modifier = Modifier
                            .wrapContentSize()
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        // 拖动手柄与操作区
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragEnd = { vm.persistQuickBrushState() },
                                        onDragCancel = { vm.persistQuickBrushState() },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                        val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                        val curMinX = marginPx
                                        val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                        val curMinY = marginPx
                                        val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                        vm.quickBrushWindowX = (vm.quickBrushWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                        vm.quickBrushWindowY = (vm.quickBrushWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                                    }
                                }
                                .padding(end = 2.dp),
                        ) {
                            // 折叠按钮
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        vm.quickBrushCollapsed = true
                                        vm.persistQuickBrushState()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(10.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(Morandi.subText)
                                )
                            }

                            // 齿轮配置
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { showManageDialog = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.quick_brush_manage_title),
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }

                        // 纵向分割线
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(Morandi.border.copy(alpha = 0.5f))
                        )

                        // 笔刷横向排列或空状态
                        if (favoritePresets.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.accent.copy(alpha = 0.12f))
                                    .clickable { showManageDialog = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_star),
                                    contentDescription = null,
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = stringResource(R.string.quick_brush_empty),
                                    color = Morandi.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        } else {
                            LazyRow(
                                modifier = Modifier.widthIn(max = maxListLengthDp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                contentPadding = PaddingValues(horizontal = 2.dp),
                            ) {
                                items(favoritePresets, key = { it.name }) { preset ->
                                    QuickBrushItem(
                                        preset = preset,
                                        isSelected = vm.currentToolId == Tool.BRUSH.id && preset.index == vm.brushPresetIndex,
                                        onClick = {
                                            vm.applyTool(Tool.BRUSH.id)
                                            vm.selectBrushPreset(preset.index)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            toastText = preset.name
                                            toastRevision++
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            toastText = "${preset.group.ifBlank { "常用" }} · ${preset.name}"
                                            toastRevision++
                                        },
                                    )
                                }
                            }
                        }

                        // 关闭按钮
                        Box(
                            modifier = Modifier
                                .size(22.dp)
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
        }

        // ---- 就近微型 Toast 气泡 ----
        val currentToast = toastText
        val densityDpi = density.density
        val spacingPx = with(density) { 8.dp.roundToPx() }

        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(0, 0) {
                    val isRight = vm.quickBrushWindowX > screenWidthPx / 2f
                    val isNearTop = vm.quickBrushWindowY < 90f * densityDpi

                    val px = if (isVertical) {
                        if (isRight) -placeable.width - spacingPx else windowSize.width + spacingPx
                    } else {
                        (windowSize.width - placeable.width) / 2
                    }

                    val py = if (isVertical) {
                        (windowSize.height - placeable.height) / 2
                    } else {
                        if (isNearTop) windowSize.height + spacingPx else -placeable.height - spacingPx
                    }

                    placeable.placeRelative(px, py)
                }
            }
        ) {
            AnimatedVisibility(
                visible = currentToast != null,
                enter = fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.84f),
                exit = fadeOut(Motion.exitTween(150)) + scaleOut(Motion.exitTween(150), targetScale = 0.84f),
            ) {
                if (currentToast != null) {
                    Box(
                        modifier = Modifier
                            .shadow(8.dp, RoundedCornerShape(10.dp), spotColor = Color.Black.copy(alpha = 0.22f))
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.96f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_brush_quick),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = currentToast,
                                color = Morandi.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    // 画笔管理与排序对话框
    if (showManageDialog) {
        QuickBrushManageDialog(
            vm = vm,
            onDismiss = { showManageDialog = false },
        )
    }
}

/**
 * 快捷笔刷单个项目
 */
@Composable
private fun QuickBrushItem(
    preset: BrushPresetInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)

    Box(
        modifier = modifier
            .size(38.dp)
            .pressScale(interactionSource, pressedScale = 0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Morandi.accent.copy(alpha = 0.24f)
                else Morandi.panelHi.copy(alpha = 0.45f)
            )
            .then(
                if (isSelected) Modifier.border(1.2.dp, Morandi.accent.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = preset.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp)),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_brush),
                contentDescription = preset.name,
                tint = if (isSelected) Morandi.accent else Morandi.icon,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
