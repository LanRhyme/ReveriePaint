/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.reportUiBounds
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi

/**
 * Top operation bar
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    isFloating: Boolean = false,
    isVertical: Boolean = false,
    onBack: () -> Unit,
    onRotateCw: () -> Unit,
    onRotateCcw: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onLayers: () -> Unit,
    onSettings: () -> Unit,
    onGuides: () -> Unit = {},
    onUndo: () -> Unit = { vm.undo() },
    onRedo: () -> Unit = { vm.redo() },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = if (isFloating) {
        androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    } else if (vm.leftHandMode) {
        androidx.compose.foundation.shape.RoundedCornerShape(bottomEnd = 16.dp)
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 16.dp)
    }

    val sizeModifier = if (isFloating) {
        if (isVertical) Modifier.width(36.dp) else Modifier.height(36.dp)
    } else {
        Modifier
    }

    val contentModifier = modifier
        .then(sizeModifier)
        // 顶栏真实屏幕边界 (root 坐标) 回写 ViewModel, 供 CanvasTouchView 做前缓冲的 UI 避让
        .reportUiBounds { vm.measuredTopBarRect = it }
        .pointerHoverIcon(com.reverie.paint.ui.theme.systemDefaultPointerIcon(context), overrideDescendants = true)
        .shadow(10.dp, shape, spotColor = Color.Black.copy(alpha = 0.5f))
        .clip(shape)
        .then(
            if (vm.blurBackground && hazeState != null) {
                Modifier.hazeChild(
                    state = hazeState,
                    style = Glass.barStyle(if (opacity >= 0.99f) 0.90f else opacity),
                )
            } else {
                Modifier.background(Morandi.panel.copy(alpha = opacity))
            }
        )
        .padding(horizontal = if (isVertical) 2.dp else 4.dp, vertical = if (isVertical) 4.dp else 2.dp)

    if (isVertical) {
        androidx.compose.foundation.layout.Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ReIconButton(R.drawable.ic_undo, stringResource(R.string.common_undo), onUndo, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_redo, stringResource(R.string.common_redo), onRedo, modifier = Modifier.size(32.dp))
            if (isFloating) {
                vm.floatingUiConfig.topBarActions.forEach { action ->
                    val isActivated = vm.isQuickActionActivated(action)
                    ReIconButton(
                        action.iconRes,
                        stringResource(action.titleRes),
                        onTap = { vm.executeQuickAction(action) },
                        selected = isActivated,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            if (vm.anim.enabled) {
                ReIconButton(
                    R.drawable.ic_clock,
                    if (vm.anim.panelOpen) stringResource(R.string.anim_collapse_timeline) else stringResource(R.string.anim_expand_timeline),
                    { vm.anim.panelOpen = !vm.anim.panelOpen },
                    selected = vm.anim.panelOpen,
                    modifier = Modifier.size(32.dp),
                )
            }
            ReIconButton(R.drawable.ic_layers, stringResource(R.string.layer_title), onLayers, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_settings, stringResource(R.string.common_settings), onSettings, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_x, stringResource(R.string.common_close), onBack, modifier = Modifier.size(32.dp))
        }
    } else {
        Row(
            modifier = contentModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ReIconButton(R.drawable.ic_undo, stringResource(R.string.common_undo), onUndo, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_redo, stringResource(R.string.common_redo), onRedo, modifier = Modifier.size(32.dp))
            if (isFloating) {
                vm.floatingUiConfig.topBarActions.forEach { action ->
                    val isActivated = vm.isQuickActionActivated(action)
                    ReIconButton(
                        action.iconRes,
                        stringResource(action.titleRes),
                        onTap = { vm.executeQuickAction(action) },
                        selected = isActivated,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            if (vm.anim.enabled) {
                ReIconButton(
                    R.drawable.ic_clock,
                    if (vm.anim.panelOpen) stringResource(R.string.anim_collapse_timeline) else stringResource(R.string.anim_expand_timeline),
                    { vm.anim.panelOpen = !vm.anim.panelOpen },
                    selected = vm.anim.panelOpen,
                    modifier = Modifier.size(32.dp),
                )
            }
            ReIconButton(R.drawable.ic_layers, stringResource(R.string.layer_title), onLayers, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_settings, stringResource(R.string.common_settings), onSettings, modifier = Modifier.size(32.dp))
            ReIconButton(R.drawable.ic_x, stringResource(R.string.common_close), onBack, modifier = Modifier.size(32.dp))
        }
    }
}
