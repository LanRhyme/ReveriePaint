/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.quickcolor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.DragPillHandle
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.painting.panels.CompactHsvSlider
import com.reverie.paint.ui.painting.panels.RainbowHueColors
import com.reverie.paint.ui.painting.panels.SquarePaletteSwatchesGrid
import com.reverie.paint.ui.painting.panels.WheelPickerCanvas
import com.reverie.paint.ui.painting.panels.hsvModelToRgb
import com.reverie.paint.ui.painting.panels.hueToPureColor
import com.reverie.paint.ui.painting.panels.rgbToHsvModel
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮快捷颜色面板 (QuickColorWindow - 紧凑高质感版)
 * 1. 紧凑型面板容器:
 *    - 宽度收紧为 ~212dp, 精确贴合 180dp 核心色环而毫不压缩色轮视觉大小
 *    - 莫兰迪面板容器 (16dp 圆角, 半透明背景, 玻璃描边与柔和投影)
 * 2. 灵动丝滑微动效:
 *    - 切页动效: 弹簧缩放与淡入淡出 (AnimatedContent + scaleIn/Out)
 *    - 前背景色交换: 180度卡片翻转弹簧微动效 (graphicsLayer rotationZ)
 *    - 底部 Tab 切换: 平滑色彩与高亮填充过渡 (animateColorAsState)
 *    - 全局触感微缩 (pressScale) 与容器弹性自适应 (animateContentSize)
 * 3. 颜色模型动态全局跟随:
 *    - 无论配置为 HSV, v-HSV, HSL 还是 HSY', 所有色环、色方与滑块均严格跟随并动态实时换算
 * 4. 4 大经典模式:
 *    - Tab 0 [色环]: 跟随 vm.colorWheelInnerShape 与 vm.colorModel 的外环色相 + 内置形取色区 (完整 180dp)
 *    - Tab 1 [色方]: 二维色域矩形 (支持全颜色模型双重渐变/像素缓冲池) + 彩虹色相滑块
 *    - Tab 2 [色板]: 当前调色板专属 SquarePaletteSwatchesGrid 矩阵 + 一键插槽增删
 *    - Tab 3 [滑块]: 依据当前颜色模型自适应标签 (H/S/V 或 H/S/L 或 H/S/Y') 与 RGB 滑块
 */
@Composable
fun QuickColorWindow(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val isCollapsed = vm.quickColorCollapsed
    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    val dm = context.resources.displayMetrics
    val screenWidthPx = dm.widthPixels.toFloat()
    val screenHeightPx = dm.heightPixels.toFloat()

    val currentScreenWidthPx by rememberUpdatedState(screenWidthPx)
    val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)

    val marginPx = with(density) { 8.dp.toPx() }
    val defaultW = with(density) { if (isCollapsed) 48.dp.toPx() else 212.dp.toPx() }
    val defaultH = with(density) { if (isCollapsed) 48.dp.toPx() else 272.dp.toPx() }

    // HSV 颜色状态 (严格跟随 vm.colorModel 进行非线性映射)
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }
    var isInteracting by remember { mutableStateOf(false) }
    var lastSelfUpdatedHex by remember { mutableStateOf("") }

    // 监听 vm.brushColor 与 vm.colorModel 同步 (非色盘交互期间始终同步最新颜色)
    LaunchedEffect(vm.brushColor, vm.colorModel) {
        if (!isInteracting && !vm.brushColor.equals(lastSelfUpdatedHex, ignoreCase = true)) {
            try {
                val c = android.graphics.Color.parseColor(vm.brushColor)
                val modelHsv = rgbToHsvModel(c, vm.colorModel)
                hue = modelHsv[0]
                sat = modelHsv[1]
                valB = modelHsv[2]
            } catch (_: Exception) {}
            lastSelfUpdatedHex = ""
        }
    }

    val updateColorHsv = { h: Float, s: Float, v: Float ->
        val rgb = hsvModelToRgb(h, s, v, vm.colorModel)
        val hex = "#%06X".format(rgb and 0xFFFFFF)
        lastSelfUpdatedHex = hex
        vm.updateBrushColor(hex)
    }

    // 屏幕尺寸变化时的出界夹紧
    LaunchedEffect(screenWidthPx, screenHeightPx, windowSize, isCollapsed) {
        val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
        val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
        val minX = marginPx
        val maxX = (screenWidthPx - curEffectiveW - marginPx).coerceAtLeast(minX)
        val minY = marginPx
        val maxY = (screenHeightPx - curEffectiveH - marginPx).coerceAtLeast(minY)
        if (vm.quickColorWindowX >= 0f && vm.quickColorWindowY >= 0f) {
            val clampedX = vm.quickColorWindowX.coerceIn(minX, maxX)
            val clampedY = vm.quickColorWindowY.coerceIn(minY, maxY)
            if (clampedX != vm.quickColorWindowX || clampedY != vm.quickColorWindowY) {
                vm.quickColorWindowX = clampedX
                vm.quickColorWindowY = clampedY
                vm.persistQuickColorState()
            }
        }
    }

    val windowShape = remember(isCollapsed) {
        if (isCollapsed) CircleShape else RoundedCornerShape(16.dp)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.quickColorWindowWidth = 0f
            vm.quickColorWindowHeight = 0f
        }
    }

    val parsedCurrentColor = remember(vm.brushColor) {
        try {
            Color(android.graphics.Color.parseColor(vm.brushColor))
        } catch (_: Exception) {
            Color.Black
        }
    }

    val parsedSecondaryColor = remember(vm.brushSecondaryColor) {
        try {
            Color(android.graphics.Color.parseColor(vm.brushSecondaryColor))
        } catch (_: Exception) {
            Color.White
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
                val x = if (vm.quickColorWindowX >= 0f) {
                    vm.quickColorWindowX.coerceIn(minX, maxX).roundToInt()
                } else {
                    ((screenWidthPx - curEffectiveW) / 2f).coerceIn(minX, maxX).roundToInt()
                }
                val y = if (vm.quickColorWindowY >= 0f) {
                    vm.quickColorWindowY.coerceIn(minY, maxY).roundToInt()
                } else {
                    ((screenHeightPx - curEffectiveH) / 2f).coerceIn(minY, maxY).roundToInt()
                }
                IntOffset(x, y)
            }
    ) {
        Box(
            modifier = Modifier
                .onSizeChanged { size ->
                    windowSize = size
                    vm.quickColorWindowWidth = size.width.toFloat()
                    vm.quickColorWindowHeight = size.height.toFloat()
                    if (size.width > 0 && size.height > 0) {
                        val minX = marginPx
                        val maxX = (screenWidthPx - size.width - marginPx).coerceAtLeast(minX)
                        val minY = marginPx
                        val maxY = (screenHeightPx - size.height - marginPx).coerceAtLeast(minY)
                        if (vm.quickColorWindowX < 0f || vm.quickColorWindowY < 0f) {
                            vm.quickColorWindowX = (maxX / 2f).coerceIn(minX, maxX)
                            vm.quickColorWindowY = (maxY / 2f).coerceIn(minY, maxY)
                            vm.persistQuickColorState()
                        } else {
                            val clampedX = vm.quickColorWindowX.coerceIn(minX, maxX)
                            val clampedY = vm.quickColorWindowY.coerceIn(minY, maxY)
                            if (clampedX != vm.quickColorWindowX || clampedY != vm.quickColorWindowY) {
                                vm.quickColorWindowX = clampedX
                                vm.quickColorWindowY = clampedY
                                vm.persistQuickColorState()
                            }
                        }
                    }
                }
                .shadow(16.dp, windowShape, spotColor = Color.Black.copy(alpha = 0.5f))
                .systemHoverIcon(context)
                .clip(windowShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.popupStyle(if (opacity >= 0.99f) 0.90f else opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .glassBorder(windowShape)
                .animateContentSize(Motion.enterSpring())
        ) {
            if (isCollapsed) {
                // ---- 折叠微缩态：高质感悬浮色球 ----
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { vm.persistQuickColorState() },
                                onDragCancel = { vm.persistQuickColorState() },
                            ) { change, dragAmount ->
                                change.consume()
                                val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                val curMinX = marginPx
                                val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                val curMinY = marginPx
                                val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                vm.quickColorWindowX = (vm.quickColorWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                vm.quickColorWindowY = (vm.quickColorWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                            }
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.quickColorCollapsed = false
                            vm.persistQuickColorState()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(parsedCurrentColor)
                            .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    )
                }
            } else {
                // ---- 展开态：紧凑致密 ReveriePaint 颜色面板 ----
                Column(
                    modifier = Modifier
                        .width(212.dp)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 1. 顶部操作栏: 拖动手柄 + 标题与模型徽章 + 前背景双色重叠预览 + 关闭按钮
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DragPillHandle(
                            onDrag = { dragAmount ->
                                val curEffectiveW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                val curEffectiveH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                val curMinX = marginPx
                                val curMaxX = (currentScreenWidthPx - curEffectiveW - marginPx).coerceAtLeast(curMinX)
                                val curMinY = marginPx
                                val curMaxY = (currentScreenHeightPx - curEffectiveH - marginPx).coerceAtLeast(curMinY)
                                vm.quickColorWindowX = (vm.quickColorWindowX + dragAmount.x).coerceIn(curMinX, curMaxX)
                                vm.quickColorWindowY = (vm.quickColorWindowY + dragAmount.y).coerceIn(curMinY, curMaxY)
                                vm.persistQuickColorState()
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 标题与跟随的主颜色模型徽标
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.color_window_title),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Morandi.text,
                                )

                                // 颜色模型徽标 (跟随 vm.colorModel)
                                val modelLabel = when (vm.colorModel) {
                                    "v-hsv" -> "v-HSV"
                                    "hsl" -> "HSL"
                                    "hsy" -> "HSY'"
                                    else -> "HSV"
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Morandi.panelHi)
                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = modelLabel,
                                        color = Morandi.subText,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            // 右侧：前背景双色重叠预览 (点击翻转动效交换) 与关闭按钮
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val swapSource = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp, 22.dp)
                                        .pressScale(swapSource)
                                        .clickable(
                                            interactionSource = swapSource,
                                            indication = null,
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val targetHex = vm.brushSecondaryColor
                                            try {
                                                val c = android.graphics.Color.parseColor(targetHex)
                                                val modelHsv = rgbToHsvModel(c, vm.colorModel)
                                                hue = modelHsv[0]
                                                sat = modelHsv[1]
                                                valB = modelHsv[2]
                                            } catch (_: Exception) {}
                                            lastSelfUpdatedHex = ""
                                            vm.swapColors()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 背景色块 (右下)
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .align(Alignment.BottomEnd)
                                            .clip(RoundedCornerShape(3.5.dp))
                                            .background(parsedSecondaryColor)
                                            .border(0.5.dp, Morandi.border, RoundedCornerShape(3.5.dp))
                                    )
                                    // 前景色块 (左上)
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .align(Alignment.TopStart)
                                            .clip(RoundedCornerShape(3.5.dp))
                                            .background(parsedCurrentColor)
                                            .border(0.5.dp, Morandi.border, RoundedCornerShape(3.5.dp))
                                    )
                                }

                                PanelCloseButton(onClose = onClose)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // 2. 调色核心内容区 (4 Tabs 切换，高度紧凑固定 182dp，色轮保持 180dp 绝不缩小)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(182.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = vm.quickColorTab,
                            transitionSpec = {
                                fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.94f) togetherWith
                                        fadeOut(Motion.exitTween(90)) + scaleOut(Motion.exitTween(90), targetScale = 0.96f)
                            },
                            label = "QuickColorTabAnimation",
                        ) { tab ->
                            when (tab) {
                                // Tab 0: 经典色环 (完整 180dp 色轮，色相外环 + 内部取色区，跟随 vm.colorModel)
                                0 -> {
                                    val isHsvFixed = vm.colorWheelInnerShape != "SQUARE"
                                    val effectiveColorModel = if (isHsvFixed) "hsv" else vm.colorModel
                                    Box(
                                        modifier = Modifier
                                            .size(180.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        WheelPickerCanvas(
                                            shape = vm.colorWheelInnerShape,
                                            colorModel = effectiveColorModel,
                                            hue = hue,
                                            sat = sat,
                                            valB = valB,
                                            onHue = {
                                                hue = it
                                                updateColorHsv(it, sat, valB)
                                            },
                                            onSatVal = { s, v ->
                                                sat = s
                                                valB = v
                                                updateColorHsv(hue, s, v)
                                            },
                                            onInteractionStart = { isInteracting = true },
                                            onInteractionEnd = { isInteracting = false },
                                        )
                                    }
                                }

                                // Tab 1: 经典色方 (支持全部非线性颜色模型的二维色域与彩虹色相滑块)
                                1 -> {
                                    QuickColorSquareTab(
                                        vm = vm,
                                        hue = hue,
                                        sat = sat,
                                        valB = valB,
                                        onHue = {
                                            hue = it
                                            updateColorHsv(it, sat, valB)
                                        },
                                        onSatVal = { s, v ->
                                            sat = s
                                            valB = v
                                            updateColorHsv(hue, s, v)
                                        },
                                        onInteractionStart = { isInteracting = true },
                                        onInteractionEnd = { isInteracting = false },
                                    )
                                }

                                // Tab 2: 色板 (调色板矩阵 + 一键插槽保存)
                                2 -> {
                                    QuickColorPalettesTab(
                                        vm = vm,
                                        onColorSelected = { hex ->
                                            vm.updateBrushColor(hex)
                                        },
                                    )
                                }

                                // Tab 3: 精密滑块 (自适应当前模型标签与 RGB 滑块)
                                else -> {
                                    QuickColorSlidersTab(
                                        vm = vm,
                                        hue = hue,
                                        sat = sat,
                                        valB = valB,
                                        onHsvChange = { h, s, v ->
                                            hue = h
                                            sat = s
                                            valB = v
                                            updateColorHsv(h, s, v)
                                        },
                                        onInteractionStart = { isInteracting = true },
                                        onInteractionEnd = { isInteracting = false },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(5.dp))

                    // 3. 底部导航栏 (ColorPanel 标准风格，Morandi.panelHi 紧凑底槽 + 平滑切换动效)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Tab 0: 色环
                        QuickColorBottomTabButton(
                            selected = vm.quickColorTab == 0,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.quickColorTab = 0
                                vm.persistQuickColorState()
                            },
                        ) { tint ->
                            Icon(
                                painter = painterResource(R.drawable.ic_tabler_color_wheel),
                                contentDescription = stringResource(R.string.color_tab_wheel),
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // Tab 1: 色方
                        QuickColorBottomTabButton(
                            selected = vm.quickColorTab == 1,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.quickColorTab = 1
                                vm.persistQuickColorState()
                            },
                        ) { tint ->
                            Icon(
                                painter = painterResource(R.drawable.ic_tabler_color_square),
                                contentDescription = stringResource(R.string.color_tab_square),
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // Tab 2: 色板
                        QuickColorBottomTabButton(
                            selected = vm.quickColorTab == 2,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.quickColorTab = 2
                                vm.persistQuickColorState()
                            },
                        ) { tint ->
                            Icon(
                                painter = painterResource(R.drawable.ic_grid),
                                contentDescription = stringResource(R.string.color_tab_palette),
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // Tab 3: 滑块
                        QuickColorBottomTabButton(
                            selected = vm.quickColorTab == 3,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.quickColorTab = 3
                                vm.persistQuickColorState()
                            },
                        ) { tint ->
                            Icon(
                                painter = painterResource(R.drawable.ic_sliders),
                                contentDescription = stringResource(R.string.color_tab_slider),
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部导航 Tab 按钮 (对齐 ColorPanel 底栏规范: 莫兰迪平滑高亮切换)
 */
@Composable
private fun QuickColorBottomTabButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bgAnim by animateColorAsState(
        targetValue = if (selected) Morandi.accent.copy(alpha = 0.25f) else Color.Transparent,
        animationSpec = Motion.enterSpring(),
        label = "tabBgAnim"
    )
    val tintAnim by animateColorAsState(
        targetValue = if (selected) Morandi.accent else Morandi.icon,
        animationSpec = Motion.enterSpring(),
        label = "tabTintAnim"
    )

    Box(
        modifier = Modifier
            .size(44.dp, 26.dp)
            .pressScale(interactionSource)
            .clip(RoundedCornerShape(6.dp))
            .background(bgAnim)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(tintAnim)
    }
}

/**
 * Tab 1: 色方页面 (支持全颜色模型 GPU 渐变与像素缓冲池)
 */
@Composable
private fun QuickColorSquareTab(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val res = 80
    var cachedBmp by remember { mutableStateOf<Bitmap?>(null) }
    val pixelBuffer = remember { IntArray(res * res) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastModelForBmp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 二维 SV 矩形取色区 (支持非线性颜色模型)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(hue, vm.colorModel) {
                        awaitEachGesture {
                            val down = awaitFirstDown().also { it.consume() }
                            onInteractionStart()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val s = (down.position.x / w).coerceIn(0f, 1f)
                            val v = (1f - down.position.y / h).coerceIn(0f, 1f)
                            onSatVal(s, v)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val curS = (change.position.x / w).coerceIn(0f, 1f)
                                val curV = (1f - change.position.y / h).coerceIn(0f, 1f)
                                onSatVal(curS, curV)
                                change.consume()
                            }
                            onInteractionEnd()
                        }
                    },
            ) {
                val w = size.width
                val h = size.height

                if (vm.colorModel == "hsv") {
                    // 标准 HSV: 硬件加速双重渐变
                    val pureHueColor = hueToPureColor(hue)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, pureHueColor),
                            startX = 0f,
                            endX = w,
                        ),
                        size = size,
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = 0f,
                            endY = h,
                        ),
                        size = size,
                    )
                } else {
                    // 非线性模型 (v-HSV, HSL, HSY'): 像素缓冲池绘制
                    val bmp = cachedBmp ?: Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888).also {
                        cachedBmp = it
                    }
                    if (abs(lastHueForBmp - hue) > 0.8f || lastModelForBmp != vm.colorModel) {
                        for (y in 0 until res) {
                            val vy = 1.0f - (y / (res - 1.0f))
                            for (x in 0 until res) {
                                val sx = x / (res - 1.0f)
                                pixelBuffer[y * res + x] = hsvModelToRgb(hue, sx, vy, vm.colorModel)
                            }
                        }
                        bmp.setPixels(pixelBuffer, 0, res, 0, 0, res, res)
                        lastHueForBmp = hue
                        lastModelForBmp = vm.colorModel
                    }
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                    )
                }

                // 取色准星
                val selX = sat * w
                val selY = (1f - valB) * h
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(selX, selY),
                    style = Stroke(2.2.dp.toPx()),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.65f),
                    radius = 4.dp.toPx(),
                    center = Offset(selX, selY),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }

        // 彩虹色相滑块
        CompactHsvSlider(
            label = "H",
            value = hue,
            max = 360f,
            colors = RainbowHueColors,
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = onHue,
            unitSuffix = "°",
        )
    }
}

/**
 * Tab 2: 色板页面 (基于 SquarePaletteSwatchesGrid 统一构建)
 */
@Composable
private fun QuickColorPalettesTab(
    vm: PaintViewModel,
    onColorSelected: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val activePalette = remember(vm.defaultPaletteId, vm.allPalettes) {
        vm.allPalettes.firstOrNull { it.id == vm.defaultPaletteId } ?: vm.allPalettes.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(182.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 顶部调色板标题与一键添加按键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = activePalette?.name ?: stringResource(R.string.color_tab_palette),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.text,
                maxLines = 1,
            )

            // [+] 存入色板按键
            val addSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Morandi.panelHi)
                    .pressScale(addSource)
                    .clickable(
                        interactionSource = addSource,
                        indication = null,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (activePalette != null) {
                            vm.addColorToPalette(activePalette.id, vm.brushColor)
                            vm.showActionToast(R.string.color_pal_saved_cur_color, R.drawable.ic_palette)
                        }
                    }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.quick_color_add_palette),
                    tint = Morandi.text,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = stringResource(R.string.quick_color_add_palette),
                    fontSize = 9.sp,
                    color = Morandi.text,
                )
            }
        }

        // 标准调色板矩阵 (支持点击选中与空位快捷添加)
        if (activePalette != null) {
            SquarePaletteSwatchesGrid(
                colors = activePalette.colors,
                selectedColor = vm.brushColor,
                onColorSelect = onColorSelected,
                onColorLongPress = { colorIdx ->
                    vm.removeColorFromPalette(activePalette.id, colorIdx)
                    vm.showActionToast(R.string.color_pal_removed_color, R.drawable.ic_palette)
                },
                onEmptySlotClick = {
                    vm.addColorToPalette(activePalette.id, vm.brushColor)
                    vm.showActionToast(R.string.color_pal_saved_cur_color, R.drawable.ic_palette)
                },
            )
        }
    }
}

/**
 * Tab 3: 精密滑块页面 (严格根据 vm.colorModel 适配标签与端点色彩)
 */
@Composable
private fun QuickColorSlidersTab(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHsvChange: (Float, Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val vLabel = when (vm.colorModel) {
        "hsl" -> "L"
        "hsy" -> "Y'"
        else -> "V"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(182.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 色相滑块
        CompactHsvSlider(
            label = "H",
            value = hue,
            max = 360f,
            colors = RainbowHueColors,
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { onHsvChange(it, sat, valB) },
            unitSuffix = "°",
        )

        // 饱和度滑块 (端点由当前模型换算)
        CompactHsvSlider(
            label = "S",
            value = sat * 100f,
            max = 100f,
            colors = listOf(
                Color(hsvModelToRgb(hue, 0f, valB, vm.colorModel)),
                Color(hsvModelToRgb(hue, 1f, valB, vm.colorModel)),
            ),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { onHsvChange(hue, it / 100f, valB) },
            unitSuffix = "%",
        )

        // 明度/亮度/纯度滑块 (端点由当前模型换算)
        CompactHsvSlider(
            label = vLabel,
            value = valB * 100f,
            max = 100f,
            colors = listOf(
                Color(hsvModelToRgb(hue, sat, 0f, vm.colorModel)),
                Color(hsvModelToRgb(hue, sat, 1f, vm.colorModel)),
            ),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { onHsvChange(hue, sat, it / 100f) },
            unitSuffix = "%",
        )

        Spacer(Modifier.weight(1f))

        // HEX 色值展示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(Morandi.panelHi)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HEX",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Morandi.subText,
            )
            Text(
                text = vm.brushColor.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.text,
            )
        }
    }
}
