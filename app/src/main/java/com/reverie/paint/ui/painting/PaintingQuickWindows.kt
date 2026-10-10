/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
) {
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
            hazeState = hazeState,
            opacity = vm.popupPanelOpacity,
        )
    }
}
