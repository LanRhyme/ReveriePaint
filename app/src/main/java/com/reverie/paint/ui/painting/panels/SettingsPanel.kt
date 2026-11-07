/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import com.reverie.paint.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.painting.canvas.CanvasTabPage
import com.reverie.paint.ui.painting.ExportTabPage

enum class SettingsTab { CANVAS, EXPORT, SETTINGS }

/**
 * Settings panel (top-right dropdown menu, multi-page 画世界 Pro style)
 */
@Composable
fun SettingsPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onResetView: () -> Unit = {},
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
    hazeState: HazeState? = null,
    onOpenFilters: ((List<Int>) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialTab = when (vm.targetSettingsTab) {
        "EXPORT" -> SettingsTab.EXPORT
        "SETTINGS" -> SettingsTab.SETTINGS
        else -> SettingsTab.CANVAS
    }
    var currentTab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(vm.targetSettingsTab) {
        when (vm.targetSettingsTab) {
            "EXPORT" -> currentTab = SettingsTab.EXPORT
            "SETTINGS" -> currentTab = SettingsTab.SETTINGS
            "CANVAS" -> currentTab = SettingsTab.CANVAS
        }
        vm.targetSettingsTab = null
    }
    val panelShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .systemHoverIcon(context)
            .noRippleClickable(onClose),
    ) {
        val (baseAlign, basePaddingModifier) = if (vm.floatingUiConfig.enabled) {
            val hostPos = vm.floatingUiConfig.topBarPosition
            val mDp = vm.floatingUiConfig.popupInwardMarginDp(hostPos).dp
            when (hostPos.edge) {
                com.reverie.paint.model.PanelEdge.TOP -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = 16.dp, top = mDp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.TopCenter to Modifier.padding(top = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.TopEnd to Modifier.padding(end = 16.dp, top = mDp)
                    }
                }
                com.reverie.paint.model.PanelEdge.BOTTOM -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.BottomStart to Modifier.padding(start = 16.dp, bottom = mDp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.BottomCenter to Modifier.padding(bottom = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = 16.dp, bottom = mDp)
                    }
                }
                com.reverie.paint.model.PanelEdge.LEFT -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = mDp, top = 16.dp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterStart to Modifier.padding(start = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomStart to Modifier.padding(start = mDp, bottom = 16.dp)
                    }
                }
                com.reverie.paint.model.PanelEdge.RIGHT -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopEnd to Modifier.padding(end = mDp, top = 16.dp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterEnd to Modifier.padding(end = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = mDp, bottom = 16.dp)
                    }
                }
            }
        } else {
            (if (vm.leftHandMode) Alignment.TopStart else Alignment.TopEnd) to Modifier.padding(
                top = 44.dp,
                start = if (vm.leftHandMode) 8.dp else 0.dp,
                end = if (vm.leftHandMode) 0.dp else 8.dp,
                bottom = 16.dp,
            )
        }

        Column(
            modifier = Modifier
                .systemHoverIcon(context)
                .align(baseAlign)
                .then(basePaddingModifier)
                .width(350.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 60).coerceAtLeast(240).dp)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.popupStyle(if (opacity >= 0.99f) 0.92f else opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
        ) {
            // Segmented Capsule Tab Bar (3 tabs: 画布, 导出, 设置)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panelHi.copy(alpha = 0.5f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabCapsuleItem(
                    icon = R.drawable.ic_canvas_tab,
                    label = stringResource(R.string.panel_tab_canvas),
                    selected = currentTab == SettingsTab.CANVAS,
                    onClick = { currentTab = SettingsTab.CANVAS },
                    modifier = Modifier.weight(1f),
                )
                TabCapsuleItem(
                    icon = R.drawable.ic_export_tab,
                    label = stringResource(R.string.panel_tab_export),
                    selected = currentTab == SettingsTab.EXPORT,
                    onClick = { currentTab = SettingsTab.EXPORT },
                    modifier = Modifier.weight(1f),
                )
                TabCapsuleItem(
                    icon = R.drawable.ic_settings,
                    label = stringResource(R.string.panel_tab_settings),
                    selected = currentTab == SettingsTab.SETTINGS,
                    onClick = { currentTab = SettingsTab.SETTINGS },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(tween(180, easing = FastOutSlowInEasing))
                        .togetherWith(fadeOut(tween(120)))
                },
                modifier = Modifier.weight(1f, fill = false),
                label = "SettingsTabTransition"
            ) { tab ->
                when (tab) {
                    SettingsTab.CANVAS -> CanvasTabPage(vm = vm, onClose = onClose, onOpenFilters = onOpenFilters)
                    SettingsTab.EXPORT -> ExportTabPage(vm = vm, onClose = onClose)
                    SettingsTab.SETTINGS -> SettingsTabPage(vm = vm, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun TabCapsuleItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(9.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Morandi.panel else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) Morandi.accent else Morandi.subText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (selected) Morandi.text else Morandi.subText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}


