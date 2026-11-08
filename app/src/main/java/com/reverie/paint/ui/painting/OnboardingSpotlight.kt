/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlin.math.roundToInt

private const val TOTAL_STEPS = 7

/**
 * 沉浸式聚光灯新手引导 (Spotlight / Coach Marks)
 *
 * 采用统一的半透明暗色遮罩柔和高亮目标区域，自适应平板与手机屏幕尺寸：
 * - 平板宽屏：卡片横向并排贴近高亮目标；
 * - 手机窄屏：卡片纵向避让（置于高亮目标下方或上方），卡片尺寸与排版自动紧凑化，绝不遮挡高亮区。
 *
 * 包含 7 大核心步骤：
 * 1. 侧边工具栏与笔刷工坊 (ToolRail)
 * 2. 调节滑块与调色板 (SliderRail，直接支持在卡片内拖动微调滑块长度)
 * 3. 快捷面板与辅助视图 (AllToolsPanel 中的视图类型独立小窗：参考图、快捷笔刷、快捷颜色、快捷图层、快捷操作、OKLCH 调色)
 * 4. 顶栏导航与基础控制 (TopBar)
 * 5. 图层面板与进阶功能 (自动展开图层面板并根据实测 bounds 精准聚焦，详解特殊图层与常用操作)
 * 6. 画布高频手势速查 (中心双指/三指交互)
 * 7. 如何自定义专属工作区 (面板固定与自由平移、外观与布局自由悬浮模式、自定义工具栏)
 */
@Composable
fun OnboardingSpotlight(
    vm: PaintViewModel,
    onSetLayerPanelOpen: (Boolean) -> Unit,
    onSetMoreToolsOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!vm.onboardingActive) return

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val isCompact = config.screenWidthDp < 600
    val currentStep = vm.onboardingStep.coerceIn(0, TOTAL_STEPS - 1)

    // 根据步骤自动调出对应面板，切换离开时若未固定则自动收起
    LaunchedEffect(currentStep) {
        when (currentStep) {
            2 -> {
                // 打开扩展工具面板 (快捷面板与视图工具)
                onSetMoreToolsOpen(true)
                if (!vm.isLayerPanelPinned) onSetLayerPanelOpen(false)
            }
            4 -> {
                // 打开图层面板
                onSetMoreToolsOpen(false)
                onSetLayerPanelOpen(true)
            }
            else -> {
                onSetMoreToolsOpen(false)
                if (!vm.isLayerPanelPinned) onSetLayerPanelOpen(false)
            }
        }
    }

    val closeGuide = {
        onSetMoreToolsOpen(false)
        if (!vm.isLayerPanelPinned) {
            onSetLayerPanelOpen(false)
        }
        vm.dismissOnboarding(markCompleted = true)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(750f)
            .pointerInput(Unit) {
                detectTapGestures { /* 阻断点击穿透到画布与工具层 */ }
            },
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val padPx = with(density) { (if (isCompact) 6.dp else 8.dp).toPx() }

        // 计算当前步骤的高亮目标（基于实测布局精确圈选）
        val targetRect = remember(
            currentStep,
            screenWidthPx,
            screenHeightPx,
            vm.panelOcclusion.version,
            vm.leftHandMode,
            vm.layerPanelMeasuredBounds,
            vm.allToolsPanelMeasuredBounds,
            vm.viewToolsMeasuredBounds,
        ) {
            computeStepTargetRect(
                step = currentStep,
                vm = vm,
                screenWidth = screenWidthPx,
                screenHeight = screenHeightPx,
                padPx = padPx,
                density = density.density,
            )
        }

        // 高亮区域平滑过渡动效
        val animSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        )
        val animLeft by animateFloatAsState(targetRect.left, animSpec, label = "spotlight_l")
        val animTop by animateFloatAsState(targetRect.top, animSpec, label = "spotlight_t")
        val animRight by animateFloatAsState(targetRect.right, animSpec, label = "spotlight_r")
        val animBottom by animateFloatAsState(targetRect.bottom, animSpec, label = "spotlight_b")

        val cornerRadiusPx = with(density) { (if (isCompact) 14.dp else 18.dp).toPx() }

        // 聚光灯遮罩：离屏渲染层 + Clear 挖孔 + 强调光圈
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawBehind {
                    // 1. 半透明暗色遮罩
                    drawRect(Color(0xB31A1817))

                    // 2. 聚光灯挖孔 (圆角矩形)
                    val w = (animRight - animLeft).coerceAtLeast(0f)
                    val h = (animBottom - animTop).coerceAtLeast(0f)
                    if (w > 0f && h > 0f) {
                        drawRoundRect(
                            color = Color.Black,
                            topLeft = Offset(animLeft, animTop),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                            blendMode = BlendMode.Clear,
                        )

                        // 3. 柔和聚焦光圈
                        drawRoundRect(
                            color = Morandi.accent.copy(alpha = 0.85f),
                            topLeft = Offset(animLeft, animTop),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                            style = Stroke(width = if (isCompact) 2.dp.toPx() else 2.5.dp.toPx()),
                        )
                    }
                },
        )

        // 引导气泡卡片自适应定位与尺寸（手机紧凑，平板舒展）
        val cardMarginDp = if (isCompact) 10.dp else 16.dp
        val cardMarginPx = with(density) { cardMarginDp.toPx() }
        val cardWidthDp = if (isCompact) minOf(330.dp, (config.screenWidthDp - 20).dp) else 390.dp
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val cardHeightPx = with(density) { (if (isCompact) 215.dp else 320.dp).toPx() }

        val (cardX, cardY) = computeCardPosition(
            step = currentStep,
            targetTop = animTop,
            targetBottom = animBottom,
            targetLeft = animLeft,
            targetRight = animRight,
            screenWidth = screenWidthPx,
            screenHeight = screenHeightPx,
            cardWidthPx = cardWidthPx,
            cardHeightPx = cardHeightPx,
            marginPx = cardMarginPx,
            isCompact = isCompact,
        )

        val syncPanelsForStep: (Int) -> Unit = { step ->
            when (step) {
                2 -> {
                    onSetMoreToolsOpen(true)
                    if (!vm.isLayerPanelPinned) onSetLayerPanelOpen(false)
                }
                4 -> {
                    onSetMoreToolsOpen(false)
                    onSetLayerPanelOpen(true)
                }
                else -> {
                    onSetMoreToolsOpen(false)
                    if (!vm.isLayerPanelPinned) onSetLayerPanelOpen(false)
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }
                .widthIn(max = cardWidthDp)
                .padding(horizontal = cardMarginDp),
        ) {
            OnboardingCard(
                vm = vm,
                step = currentStep,
                totalSteps = TOTAL_STEPS,
                isCompact = isCompact,
                onPrev = {
                    if (vm.onboardingStep > 0) {
                        val prev = vm.onboardingStep - 1
                        syncPanelsForStep(prev)
                        vm.onboardingStep = prev
                    }
                },
                onNext = {
                    if (vm.onboardingStep < TOTAL_STEPS - 1) {
                        val next = vm.onboardingStep + 1
                        syncPanelsForStep(next)
                        vm.onboardingStep = next
                    } else {
                        closeGuide()
                    }
                },
                onSkip = closeGuide,
                onOpenThemeSettings = {
                    closeGuide()
                    vm.openMoreSettings("THEME")
                },
            )
        }
    }
}

/**
 * 计算步骤目标聚光灯矩形（保证每一步的目标均稳定存在且精确圈选）
 */
private fun computeStepTargetRect(
    step: Int,
    vm: PaintViewModel,
    screenWidth: Float,
    screenHeight: Float,
    padPx: Float,
    density: Float,
): Rect {
    val buf = FloatArray(4)
    return when (step) {
        0 -> {
            // 第 1 步：侧边工具栏 (ToolRail) —— 绘图工具与笔刷库
            if (vm.panelOcclusion.bounds(UiPanelSlot.TOOL_RAIL, buf) && (buf[2] - buf[0]) > 10f) {
                Rect(
                    (buf[0] - padPx).coerceAtLeast(0f),
                    (buf[1] - padPx).coerceAtLeast(0f),
                    (buf[2] + padPx).coerceAtMost(screenWidth),
                    (buf[3] + padPx).coerceAtMost(screenHeight),
                )
            } else {
                if (vm.leftHandMode) {
                    Rect(screenWidth - 56f * density, 48f * density, screenWidth, screenHeight * 0.55f)
                } else {
                    Rect(0f, 48f * density, 56f * density, screenHeight * 0.55f)
                }
            }
        }
        1 -> {
            // 第 2 步：调节滑块与调色板 (SliderRail)
            if (vm.panelOcclusion.bounds(UiPanelSlot.SLIDER_RAIL, buf) && (buf[2] - buf[0]) > 10f) {
                Rect(
                    (buf[0] - padPx).coerceAtLeast(0f),
                    (buf[1] - padPx).coerceAtLeast(0f),
                    (buf[2] + padPx).coerceAtMost(screenWidth),
                    (buf[3] + padPx).coerceAtMost(screenHeight),
                )
            } else {
                if (vm.leftHandMode) {
                    Rect(screenWidth - 56f * density, screenHeight * 0.45f, screenWidth, screenHeight - 16f * density)
                } else {
                    Rect(0f, screenHeight * 0.45f, 56f * density, screenHeight - 16f * density)
                }
            }
        }
        2 -> {
            // 第 3 步：快捷面板与辅助视图 (AllToolsPanel 中的视图类型工具)
            val viewBounds = vm.viewToolsMeasuredBounds
            if (viewBounds != null && viewBounds.width > 20f && viewBounds.height > 20f) {
                Rect(
                    (viewBounds.left - padPx).coerceAtLeast(0f),
                    (viewBounds.top - padPx).coerceAtLeast(0f),
                    (viewBounds.right + padPx).coerceAtMost(screenWidth),
                    (viewBounds.bottom + padPx).coerceAtMost(screenHeight),
                )
            } else {
                val panelW = 236f * density
                val panelLeft = if (vm.leftHandMode) screenWidth - 52f * density - panelW else 52f * density
                val viewTop = 48f * density + 34f * density
                val viewHeight = 150f * density
                Rect(
                    (panelLeft + 8f * density - padPx).coerceAtLeast(0f),
                    (viewTop - padPx).coerceAtLeast(0f),
                    (panelLeft + panelW - 8f * density + padPx).coerceAtMost(screenWidth),
                    (viewTop + viewHeight + padPx).coerceAtMost(screenHeight),
                )
            }
        }
        3 -> {
            // 第 4 步：顶栏导航与基础控制 (TopBar)
            if (vm.panelOcclusion.bounds(UiPanelSlot.TOP_BAR, buf) && (buf[2] - buf[0]) > 10f) {
                Rect(
                    (buf[0] - padPx).coerceAtLeast(0f),
                    (buf[1] - padPx).coerceAtLeast(0f),
                    (buf[2] + padPx).coerceAtMost(screenWidth),
                    (buf[3] + padPx).coerceAtMost(screenHeight),
                )
            } else {
                if (vm.leftHandMode) {
                    Rect(0f, 0f, screenWidth * 0.45f, 56f * density)
                } else {
                    Rect(screenWidth * 0.55f, 0f, screenWidth, 56f * density)
                }
            }
        }
        4 -> {
            // 第 5 步：图层面板与进阶功能 (自动展开图层面板并使用实测 bounds 精准聚焦)
            val measured = vm.layerPanelMeasuredBounds
            if (measured != null && measured.width > 20f && measured.height > 20f) {
                Rect(
                    (measured.left - padPx).coerceAtLeast(0f),
                    (measured.top - padPx).coerceAtLeast(0f),
                    (measured.right + padPx).coerceAtMost(screenWidth),
                    (measured.bottom + padPx).coerceAtMost(screenHeight),
                )
            } else {
                val panelW = minOf(300f * density, screenWidth * 0.85f)
                val panelTop = 44f * density
                val estimatedLayersH = (44f + 36f + vm.layers.size.coerceIn(1, 8) * vm.layerRowHeightDp + 16f) * density
                val panelHeight = estimatedLayersH.coerceIn(200f * density, screenHeight * 0.70f)
                val panelLeft = if (vm.leftHandMode) 8f * density else screenWidth - panelW - 8f * density
                Rect(
                    (panelLeft - padPx).coerceAtLeast(0f),
                    (panelTop - padPx).coerceAtLeast(0f),
                    (panelLeft + panelW + padPx).coerceAtMost(screenWidth),
                    (panelTop + panelHeight + padPx).coerceAtMost(screenHeight),
                )
            }
        }
        5 -> {
            // 第 6 步：画布核心手势速查 (中心画布区域)
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            val radius = minOf(screenWidth, screenHeight) * 0.18f
            Rect(cx - radius, cy - radius, cx + radius, cy + radius)
        }
        6 -> {
            // 第 7 步：如何自定义专属工作区 (高亮聚焦顶栏设置与工作区配置入口)
            if (vm.panelOcclusion.bounds(UiPanelSlot.TOP_BAR, buf) && (buf[2] - buf[0]) > 10f) {
                Rect(
                    (buf[0] - padPx).coerceAtLeast(0f),
                    (buf[1] - padPx).coerceAtLeast(0f),
                    (buf[2] + padPx).coerceAtMost(screenWidth),
                    (buf[3] + padPx).coerceAtMost(screenHeight),
                )
            } else {
                if (vm.leftHandMode) {
                    Rect(0f, 0f, screenWidth * 0.45f, 56f * density)
                } else {
                    Rect(screenWidth * 0.55f, 0f, screenWidth, 56f * density)
                }
            }
        }
        else -> Rect.Zero
    }
}

/**
 * 计算气泡卡片自适应呈现坐标
 *
 * 智能防遮挡策略：
 * 1. 宽屏平板：当目标左/右侧有充足空间时，卡片横向并排贴合；
 * 2. 窄屏手机：水平并排必然遮挡高亮处，因此一律沿垂直方向（下方或上方）避让，绝不盖住高亮区域。
 */
private fun computeCardPosition(
    step: Int,
    targetTop: Float,
    targetBottom: Float,
    targetLeft: Float,
    targetRight: Float,
    screenWidth: Float,
    screenHeight: Float,
    cardWidthPx: Float,
    cardHeightPx: Float,
    marginPx: Float,
    isCompact: Boolean,
): Pair<Float, Float> {
    var x: Float
    var y: Float

    val spaceLeft = targetLeft - marginPx
    val spaceRight = screenWidth - targetRight - marginPx
    val spaceTop = targetTop - marginPx
    val spaceBottom = screenHeight - targetBottom - marginPx

    // 检查是否有足够的横向空间并列卡片（仅平板宽屏且单侧空间宽裕时允许）
    val fitsHorizontally = !isCompact && (spaceRight >= cardWidthPx + marginPx || spaceLeft >= cardWidthPx + marginPx)

    if (fitsHorizontally) {
        when (step) {
            0, 1 -> {
                // 侧边栏
                if (targetRight < screenWidth * 0.5f) {
                    x = (targetRight + marginPx).coerceAtMost(screenWidth - cardWidthPx - marginPx)
                } else {
                    x = (targetLeft - cardWidthPx - marginPx).coerceAtLeast(marginPx)
                }
                y = (targetTop + targetBottom - cardHeightPx) / 2f
            }
            2 -> {
                // 快捷面板与视图工具
                if (targetRight < screenWidth * 0.5f) {
                    x = (targetRight + marginPx).coerceAtMost(screenWidth - cardWidthPx - marginPx)
                } else {
                    x = (targetLeft - cardWidthPx - marginPx).coerceAtLeast(marginPx)
                }
                y = targetTop.coerceAtLeast(marginPx)
            }
            4 -> {
                // 图层面板
                if (targetRight < screenWidth * 0.5f) {
                    x = (targetRight + marginPx).coerceAtMost(screenWidth - cardWidthPx - marginPx)
                } else {
                    x = (targetLeft - cardWidthPx - marginPx).coerceAtLeast(marginPx)
                }
                y = targetTop + 16f
            }
            else -> {
                x = (screenWidth - cardWidthPx) / 2f
                y = if (step == 3 || step == 6) targetBottom + marginPx else (targetBottom + marginPx).coerceAtMost(screenHeight - cardHeightPx - marginPx)
            }
        }
    } else {
        // 手机/窄屏模式：水平居中卡片，垂直方向根据上下剩余空间智能避让
        x = ((screenWidth - cardWidthPx) / 2f).coerceIn(marginPx, screenWidth - cardWidthPx - marginPx)

        when (step) {
            3, 6 -> {
                // 顶栏位于顶部，卡片放置在顶栏下方
                y = targetBottom + marginPx
            }
            5 -> {
                // 画布手势 (屏幕中央)
                if (spaceBottom >= cardHeightPx + marginPx) {
                    y = targetBottom + marginPx
                } else {
                    y = targetTop - cardHeightPx - marginPx
                }
            }
            else -> {
                // 步骤 0 (工具栏)、1 (滑块)、2 (扩展面板/视图工具)、4 (图层面板)
                // 智能上下避让：优先置于高亮目标下方，若下方不够且上方充足则置于上方
                if (spaceBottom >= cardHeightPx + marginPx) {
                    y = targetBottom + marginPx
                } else if (spaceTop >= cardHeightPx + marginPx) {
                    y = targetTop - cardHeightPx - marginPx
                } else {
                    // 若上下空间均紧张，放置在空间更大的一侧
                    y = if (spaceBottom >= spaceTop) targetBottom + marginPx else targetTop - cardHeightPx - marginPx
                }
            }
        }
    }

    // 屏幕安全边界约束
    x = x.coerceIn(marginPx, (screenWidth - cardWidthPx - marginPx).coerceAtLeast(marginPx))
    y = y.coerceIn(marginPx, (screenHeight - cardHeightPx - marginPx).coerceAtLeast(marginPx))

    return Pair(x, y)
}

/**
 * 莫兰迪风格引导气泡卡片
 */
@Composable
private fun OnboardingCard(
    vm: PaintViewModel,
    step: Int,
    totalSteps: Int,
    isCompact: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenThemeSettings: () -> Unit = {},
) {
    val titleRes = when (step) {
        0 -> R.string.onboarding_step1_title
        1 -> R.string.onboarding_step2_title
        2 -> R.string.onboarding_step3_title
        3 -> R.string.onboarding_step4_title
        4 -> R.string.onboarding_step5_title
        5 -> R.string.onboarding_step6_title
        else -> R.string.onboarding_step7_title
    }

    val descRes = when (step) {
        0 -> R.string.onboarding_step1_desc
        1 -> R.string.onboarding_step2_desc
        2 -> R.string.onboarding_step3_desc
        3 -> R.string.onboarding_step4_desc
        4 -> R.string.onboarding_step5_desc
        5 -> R.string.onboarding_step6_desc
        else -> R.string.onboarding_step7_desc
    }

    val icons = when (step) {
        0 -> listOf(R.drawable.ic_brush, R.drawable.ic_eraser, R.drawable.ic_smudge)
        1 -> listOf(R.drawable.ic_sliders, R.drawable.ic_picker, R.drawable.ic_palette)
        2 -> listOf(R.drawable.ic_focus_view, R.drawable.ic_palette, R.drawable.ic_sliders)
        3 -> listOf(R.drawable.ic_undo, R.drawable.ic_redo, R.drawable.ic_settings)
        4 -> listOf(R.drawable.ic_layers, R.drawable.ic_clip, R.drawable.ic_grid)
        5 -> listOf(R.drawable.ic_hand, R.drawable.ic_undo, R.drawable.ic_redo)
        else -> listOf(R.drawable.ic_panel_frame, R.drawable.ic_sliders, R.drawable.ic_settings)
    }

    val cardCornerRadius = if (isCompact) 16.dp else 20.dp
    val contentHorizontalPadding = if (isCompact) 14.dp else 20.dp
    val contentVerticalPadding = if (isCompact) 12.dp else 18.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cardCornerRadius))
            .background(Morandi.panelHi.copy(alpha = 0.98f))
            .glassBorder(RoundedCornerShape(cardCornerRadius))
            .padding(horizontal = contentHorizontalPadding, vertical = contentVerticalPadding),
    ) {
        // 头部：步骤序号指示与特性小图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 步骤进度徽标
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.accent.copy(alpha = 0.16f))
                    .padding(
                        horizontal = if (isCompact) 7.dp else 9.dp,
                        vertical = if (isCompact) 3.dp else 4.dp,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_step_format, step + 1, totalSteps),
                    color = Morandi.accent,
                    fontSize = if (isCompact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 特性提示图标组
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.forEach { iconRes ->
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 22.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Morandi.panel.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = Morandi.icon,
                            modifier = Modifier.size(if (isCompact) 12.dp else 15.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(if (isCompact) 8.dp else 14.dp))

        // 标题与说明文字
        AnimatedContent(
            targetState = Pair(titleRes, descRes),
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
            },
            label = "onboarding_text_transition",
        ) { (tRes, dRes) ->
            Column {
                Text(
                    text = stringResource(tRes),
                    color = Morandi.text,
                    fontSize = if (isCompact) 15.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))
                Text(
                    text = stringResource(dRes),
                    color = Morandi.subText,
                    fontSize = if (isCompact) 11.5.sp else 13.sp,
                    lineHeight = if (isCompact) 16.sp else 19.sp,
                )
            }
        }

        // 步骤专属特性小板块与交互式微调控件
        when (step) {
            1 -> {
                // 第 2 步：支持直接调整滑块长度，无需跳转设置中
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = if (isCompact) 6.dp else 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_step2_slider_height_title),
                            color = Morandi.text,
                            fontSize = if (isCompact) 12.sp else 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${vm.quickSliderHeightDp} dp",
                            color = Morandi.accent,
                            fontSize = if (isCompact) 11.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(if (isCompact) 4.dp else 6.dp))
                    ReSlider(
                        value = ((vm.quickSliderHeightDp - 100f) / 160f).coerceIn(0f, 1f),
                        onValue = { fraction ->
                            val newHeight = (100f + fraction * 160f).roundToInt()
                            vm.updateQuickSliderHeight(newHeight)
                        },
                        height = if (isCompact) 18 else 24,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.onboarding_step2_slider_height_hint),
                        color = Morandi.subText,
                        fontSize = if (isCompact) 10.sp else 11.sp,
                        lineHeight = if (isCompact) 13.5.sp else 15.sp,
                    )
                }
            }
            2 -> {
                // 第 3 步：快捷面板与视图工具专属提示小板块
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = if (isCompact) 6.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp),
                ) {
                    FeatureTipRow(R.drawable.ic_focus_view, stringResource(R.string.onboarding_view_tip_ref), isCompact)
                    FeatureTipRow(R.drawable.ic_sliders, stringResource(R.string.onboarding_view_tip_floats), isCompact)
                    FeatureTipRow(R.drawable.ic_palette, stringResource(R.string.onboarding_view_tip_oklch), isCompact)
                }
            }
            4 -> {
                // 第 5 步：图层面板与进阶功能专属提示小板块
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = if (isCompact) 6.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp),
                ) {
                    FeatureTipRow(R.drawable.ic_layers, stringResource(R.string.onboarding_layer_tip_more), isCompact)
                    FeatureTipRow(R.drawable.ic_clip, stringResource(R.string.onboarding_layer_tip_ops), isCompact)
                    FeatureTipRow(R.drawable.ic_image_adjust, stringResource(R.string.onboarding_layer_tip_details), isCompact)
                    FeatureTipRow(R.drawable.ic_hand, stringResource(R.string.onboarding_layer_tip_gestures), isCompact)
                }
            }
            5 -> {
                // 第 6 步：手势步骤专属小板块
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = if (isCompact) 6.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp),
                ) {
                    FeatureTipRow(R.drawable.ic_undo, stringResource(R.string.onboarding_gesture_undo), isCompact)
                    FeatureTipRow(R.drawable.ic_redo, stringResource(R.string.onboarding_gesture_redo), isCompact)
                    FeatureTipRow(R.drawable.ic_hand, stringResource(R.string.onboarding_gesture_pinch), isCompact)
                    FeatureTipRow(R.drawable.ic_focus_view, stringResource(R.string.onboarding_gesture_fit), isCompact)
                }
            }
            6 -> {
                // 第 7 步：自定义工作区专属小板块
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = if (isCompact) 6.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp),
                ) {
                    FeatureTipRow(R.drawable.ic_panel_frame, stringResource(R.string.onboarding_ws_tip_drag), isCompact)
                    FeatureTipRow(R.drawable.ic_sliders, stringResource(R.string.onboarding_ws_tip_layout), isCompact)
                    FeatureTipRow(R.drawable.ic_settings, stringResource(R.string.onboarding_ws_tip_topbar), isCompact)
                }
                Spacer(Modifier.height(if (isCompact) 8.dp else 12.dp))
                ReButton(
                    text = stringResource(R.string.onboarding_open_theme_settings),
                    onClick = onOpenThemeSettings,
                    primary = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(if (isCompact) 10.dp else 18.dp))

        // 底部操作栏：跳过 + 上一步 + 下一步/完成
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 跳过按钮
            Text(
                text = stringResource(R.string.onboarding_skip),
                color = Morandi.subText.copy(alpha = 0.85f),
                fontSize = if (isCompact) 12.sp else 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip,
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    ReButton(
                        text = stringResource(R.string.onboarding_prev),
                        onClick = onPrev,
                        primary = false,
                    )
                }

                ReButton(
                    text = stringResource(if (step < totalSteps - 1) R.string.onboarding_next else R.string.onboarding_finish),
                    onClick = onNext,
                )
            }
        }
    }
}

@Composable
private fun FeatureTipRow(iconRes: Int, text: String, isCompact: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Morandi.accent,
            modifier = Modifier
                .padding(top = if (isCompact) 1.dp else 2.dp)
                .size(if (isCompact) 13.dp else 15.dp),
        )
        Text(
            text = text,
            color = Morandi.text,
            fontSize = if (isCompact) 11.sp else 12.sp,
            lineHeight = if (isCompact) 15.sp else 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
