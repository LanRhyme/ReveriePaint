/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Motion
import dev.chrisbanes.haze.HazeState

/**
 * Overlay layer hosting persistent floating tool windows:
 * Reference Window, Quick Action Window, Quick Brush Window,
 * Quick Color Window, and Quick Layer Window.
 */
@Composable
internal fun BoxScope.PaintingQuickWindows(
    vm: PaintViewModel,
    hazeState: HazeState,
    onOpenFullLayerPanel: () -> Unit,
    onOpenLayerDetails: (Long) -> Unit,
) {
    // ---- Reference Window & Quick Windows 布局状态管理 ----
    // 仅初次打开或未定位过时居中显示; 后续打开或旋转时保持用户拖拽位置 (横竖屏独立记忆各方向坐标)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
        (configuration.orientation == android.content.res.Configuration.ORIENTATION_UNDEFINED && screenW > screenH)

    Box(
        Modifier
            .matchParentSize()
            .onSizeChanged { size ->
                vm.floatingHostWidthPx = size.width.toFloat()
                vm.floatingHostHeightPx = size.height.toFloat()
                vm.clampQuickWindowsToSafeArea(
                    size.width.toFloat(),
                    size.height.toFloat(),
                    density.density,
                    persist = true,
                )
            },
    )

    var isInitialLaunch by remember { mutableStateOf(true) }
    var lastW by remember { mutableFloatStateOf(screenW) }
    var lastH by remember { mutableFloatStateOf(screenH) }

    // 仅在横竖屏切换或屏幕尺寸变动的一瞬间，处理横竖屏布局切换并将超出新视口的小窗拉回安全区内
    LaunchedEffect(screenW, screenH, isLandscape) {
        if (screenW <= 0f || screenH <= 0f) return@LaunchedEffect
        if (isInitialLaunch) {
            isInitialLaunch = false
            lastW = screenW
            lastH = screenH
            if (isLandscape != vm.isLandscapeMode) {
                vm.handleOrientationChange(
                    newIsLandscape = isLandscape,
                    screenWidthPx = screenW,
                    screenHeightPx = screenH,
                    density = density.density,
                )
            }
            return@LaunchedEffect
        }
        if (screenW != lastW || screenH != lastH || isLandscape != vm.isLandscapeMode) {
            lastW = screenW
            lastH = screenH
            vm.handleOrientationChange(
                newIsLandscape = isLandscape,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                density = density.density,
            )
        }
    }

    LaunchedEffect(vm.referenceWindowOpen) {
        if (vm.referenceWindowOpen && !vm.referenceWindowWasOpen) {
            if (vm.referenceWindowX < 0f || vm.referenceWindowY < 0f) {
                val panelW = with(density) { vm.referenceWindowWidth.dp.toPx() }
                val panelH = with(density) { vm.referenceWindowHeight.dp.toPx() }
                vm.referenceWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.referenceWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
            }
        }
        vm.referenceWindowWasOpen = vm.referenceWindowOpen
    }

    LaunchedEffect(vm.quickActionWindowOpen) {
        if (vm.quickActionWindowOpen && !vm.quickActionWindowWasOpen) {
            if (vm.quickActionWindowX < 0f || vm.quickActionWindowY < 0f) {
                val panelW = if (vm.quickActionWindowWidth > 0f) vm.quickActionWindowWidth else with(density) { 200.dp.toPx() }
                val panelH = if (vm.quickActionWindowHeight > 0f) vm.quickActionWindowHeight else with(density) { 160.dp.toPx() }
                vm.quickActionWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.quickActionWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
                vm.persistQuickActionsState()
            }
        }
        vm.quickActionWindowWasOpen = vm.quickActionWindowOpen
    }

    LaunchedEffect(vm.quickBrushWindowOpen) {
        if (vm.quickBrushWindowOpen && !vm.quickBrushWindowWasOpen) {
            if (vm.quickBrushWindowX < 0f || vm.quickBrushWindowY < 0f) {
                val panelW = if (vm.quickBrushWindowWidth > 0f) vm.quickBrushWindowWidth else with(density) { 240.dp.toPx() }
                val panelH = if (vm.quickBrushWindowHeight > 0f) vm.quickBrushWindowHeight else with(density) { 54.dp.toPx() }
                vm.quickBrushWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.quickBrushWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
                vm.persistQuickBrushState()
            }
        }
        vm.quickBrushWindowWasOpen = vm.quickBrushWindowOpen
    }

    LaunchedEffect(vm.quickColorWindowOpen) {
        if (vm.quickColorWindowOpen && !vm.quickColorWindowWasOpen) {
            if (vm.quickColorWindowX < 0f || vm.quickColorWindowY < 0f) {
                val panelW = if (vm.quickColorWindowWidth > 0f) vm.quickColorWindowWidth else with(density) { 212.dp.toPx() }
                val panelH = if (vm.quickColorWindowHeight > 0f) vm.quickColorWindowHeight else with(density) { 272.dp.toPx() }
                vm.quickColorWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.quickColorWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
                vm.persistQuickColorState()
            }
        }
        vm.quickColorWindowWasOpen = vm.quickColorWindowOpen
    }

    LaunchedEffect(vm.quickOklchWindowOpen) {
        if (vm.quickOklchWindowOpen && !vm.quickOklchWindowWasOpen) {
            if (vm.quickOklchWindowX < 0f || vm.quickOklchWindowY < 0f) {
                val panelW = if (vm.quickOklchWindowWidth > 0f) vm.quickOklchWindowWidth else with(density) { 244.dp.toPx() }
                val panelH = if (vm.quickOklchWindowHeight > 0f) vm.quickOklchWindowHeight else with(density) { 320.dp.toPx() }
                vm.quickOklchWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.quickOklchWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
                vm.persistQuickOklchState()
            }
        }
        vm.quickOklchWindowWasOpen = vm.quickOklchWindowOpen
    }

    LaunchedEffect(vm.quickLayerWindowOpen) {
        if (vm.quickLayerWindowOpen && !vm.quickLayerWindowWasOpen) {
            if (vm.quickLayerWindowX < 0f || vm.quickLayerWindowY < 0f) {
                val panelW = if (vm.quickLayerWindowWidth > 0f) vm.quickLayerWindowWidth else with(density) { 156.dp.toPx() }
                val panelH = if (vm.quickLayerWindowHeight > 0f) vm.quickLayerWindowHeight else with(density) { 290.dp.toPx() }
                vm.quickLayerWindowX = ((screenW - panelW) / 2f).coerceAtLeast(0f)
                vm.quickLayerWindowY = ((screenH - panelH) / 2f).coerceAtLeast(0f)
                vm.persistQuickLayerState()
            }
        }
        vm.quickLayerWindowWasOpen = vm.quickLayerWindowOpen
    }

    // ---- Persistent Floating Reference Window (常态固定显示参考窗口) ----
    AnimatedVisibility(
        visible = vm.referenceWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(70f),
    ) {
        ReferenceWindow(
            vm = vm,
            onClose = { vm.referenceWindowOpen = false; vm.persistReferenceState() },
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }

    // ---- Persistent Floating Quick Action Window (常驻悬浮快捷操作小窗) ----
    AnimatedVisibility(
        visible = vm.quickActionWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(75f),
    ) {
        com.reverie.paint.ui.painting.quickaction.QuickActionWindow(
            vm = vm,
            onClose = {
                vm.quickActionWindowOpen = false
                vm.persistQuickActionsState()
            },
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }

    // ---- Persistent Floating Quick Brush Window (常驻悬浮快捷笔刷小窗) ----
    AnimatedVisibility(
        visible = vm.quickBrushWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(76f),
    ) {
        com.reverie.paint.ui.painting.quickbrush.QuickBrushWindow(
            vm = vm,
            onClose = {
                vm.quickBrushWindowOpen = false
                vm.persistQuickBrushState()
            },
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }

    // ---- Persistent Floating Quick Color Window (常驻悬浮快捷颜色小窗) ----
    AnimatedVisibility(
        visible = vm.quickColorWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(77f),
    ) {
        com.reverie.paint.ui.painting.quickcolor.QuickColorWindow(
            vm = vm,
            onClose = {
                vm.quickColorWindowOpen = false
                vm.persistQuickColorState()
            },
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }

    // ---- Persistent Floating Quick OKLCH Window (常驻悬浮 OKLCH 调色小窗) ----
    AnimatedVisibility(
        visible = vm.quickOklchWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(77.5f),
    ) {
        com.reverie.paint.ui.painting.quickoklch.QuickOklchWindow(
            vm = vm,
            onClose = {
                vm.quickOklchWindowOpen = false
                vm.persistQuickOklchState()
            },
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }

    // ---- Persistent Floating Quick Layer Window (常驻悬浮快捷图层小窗) ----
    AnimatedVisibility(
        visible = vm.quickLayerWindowOpen,
        enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
        exit = fadeOut(Motion.exitTween(150)) + androidx.compose.animation.scaleOut(Motion.exitTween(150), targetScale = 0.92f),
        modifier = Modifier.zIndex(78f),
    ) {
        com.reverie.paint.ui.painting.quicklayer.QuickLayerWindow(
            vm = vm,
            onClose = {
                vm.quickLayerWindowOpen = false
                vm.persistQuickLayerState()
            },
            onOpenFullLayerPanel = onOpenFullLayerPanel,
            onOpenLayerDetails = onOpenLayerDetails,
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }
}
