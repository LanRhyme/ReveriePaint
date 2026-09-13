/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

@Composable
internal fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showCustomCanvasBgDialog by remember { mutableStateOf(false) }

    val presetSwatches = listOf(
        "#5E8BA8", "#0A84FF", "#7C8F9E", "#8D9E8F", "#C9ADA7",
        "#B4552D", "#5A6E8A", "#9A8F7B", "#A27B8A"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
        // Native back bar
        if (showBackButton) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = "返回",
                        tint = colors.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "主题设置",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Section 1: 外观与色彩模式
        SettingCategoryHeader("色彩与主题模式")
        GroupedSettingsCard {
            Text(
                text = "色彩模式",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.panelHi)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val modes = listOf(
                    "DARK" to "深色",
                    "LIGHT" to "浅色",
                    "SYSTEM" to "跟随系统"
                )
                modes.forEach { (modeKey, modeTitle) ->
                    val isSelected = vm.themeMode == modeKey
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.accent else Color.Transparent)
                            .clickable { vm.updateThemeMode(modeKey) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = modeTitle,
                            color = if (isSelected) colors.onAccent else colors.text,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            SettingsCardDivider()

            SettingSwitchRow(
                title = "莫奈取色 (Monet 动态色彩)",
                summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "跟随系统壁纸与 Material You 动态提取界面主题色"
                } else {
                    "需要 Android 12 及以上系统支持"
                },
                checked = vm.monetEnabled,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { vm.updateMonetEnabled(it) }
            )
        }

        Spacer(Modifier.height(18.dp))

        // Section 2: 主色调
        SettingCategoryHeader("主色调")
        GroupedSettingsCard {
            Text(
                text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "预设色调 (莫奈动态接管中)" else "强调色色板",
                color = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) colors.subText else colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            Text(
                text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "点击下方色块可关闭莫奈取色并应用指定莫兰迪色"
                } else {
                    "应用于按钮、滑块及高亮强调色，默认采用低饱和莫兰迪灰蓝"
                },
                color = colors.subText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Swatch list + Custom "+" button
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                items(presetSwatches) { hex ->
                    val swatchColor = parseColor(hex)
                    val isSelected = !vm.monetEnabled && vm.accentColorHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .then(
                                if (isSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                            )
                            .clickable {
                                if (vm.monetEnabled) {
                                    vm.updateMonetEnabled(false)
                                }
                                vm.updateAccentColor(hex)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Custom color button
                item {
                    val isCustomSelected = !vm.monetEnabled && presetSwatches.none { it.equals(vm.accentColorHex, ignoreCase = true) }
                    val currentCustomColor = if (isCustomSelected) parseColor(vm.accentColorHex) else colors.panelHi
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(currentCustomColor)
                            .then(
                                if (isCustomSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                            )
                            .clickable {
                                if (vm.monetEnabled) {
                                    vm.updateMonetEnabled(false)
                                }
                                showCustomColorDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                            contentDescription = "自定义颜色",
                            tint = if (isCustomSelected) Color.White else colors.icon,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Section 3: 画布工作区背景
        SettingCategoryHeader("画布工作区背景")
        GroupedSettingsCard {
            Text(
                text = "工作区底色",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            Text(
                text = "自定义绘画与回放界面中画布周围工作区的底色",
                color = colors.subText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val canvasPresetSwatches = listOf(
                "DEFAULT",
                "#121316",
                "#1E2024",
                "#2F3136",
                "#35383F",
                "#4E5159",
                "#7A7E85",
                "#B0B5BD",
                "#D8DCE2",
                "#F0F2F5",
                "#000000",
                "#FFFFFF",
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                items(canvasPresetSwatches) { hex ->
                    if (hex == "DEFAULT") {
                        val isDefaultSelected = vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()
                        Box(
                            modifier = Modifier
                                .height(42.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .background(colors.panelHi)
                                .then(
                                    if (isDefaultSelected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(21.dp)) else Modifier
                                )
                                .clickable { vm.updateCanvasBgColor("DEFAULT") }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isDefaultSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = "跟随主题",
                                    color = if (isDefaultSelected) colors.accent else colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = if (isDefaultSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    } else {
                        val swatchColor = parseColor(hex)
                        val isSelected = vm.canvasBgColorHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .then(
                                    if (isSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                )
                                .clickable { vm.updateCanvasBgColor(hex) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                val checkTint = if (swatchColor.red * 0.299 + swatchColor.green * 0.587 + swatchColor.blue * 0.114 > 0.6) Color.Black else Color.White
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = checkTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Custom canvas background color button
                item {
                    val isCustomSelected = vm.canvasBgColorHex != "DEFAULT" && vm.canvasBgColorHex.isNotBlank() && canvasPresetSwatches.none { it.equals(vm.canvasBgColorHex, ignoreCase = true) }
                    val currentCustomColor = if (isCustomSelected) parseColor(vm.canvasBgColorHex) else colors.panelHi
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(currentCustomColor)
                            .then(
                                if (isCustomSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                            )
                            .clickable { showCustomCanvasBgDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        val iconTint = if (isCustomSelected) {
                            if (currentCustomColor.red * 0.299 + currentCustomColor.green * 0.587 + currentCustomColor.blue * 0.114 > 0.6) Color.Black else Color.White
                        } else colors.icon
                        Icon(
                            painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                            contentDescription = "自定义画布背景",
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Section 4: 界面不透明度
        SettingCategoryHeader("界面不透明度")
        GroupedSettingsCard {
            SettingSliderRow(
                title = "主界面面板",
                summary = "工具栏与顶部栏不透明度",
                value = vm.uiOpacity,
                onValueChange = { vm.updateUiOpacity(it) }
            )

            SettingsCardDivider()

            SettingSliderRow(
                title = "浮动面板",
                summary = "图层、笔刷、颜色等弹窗不透明度",
                value = vm.popupPanelOpacity,
                onValueChange = { vm.updatePopupPanelOpacity(it) }
            )
        }

        Spacer(Modifier.height(18.dp))

        // Section 5: 界面尺寸与缩放
        SettingCategoryHeader("界面尺寸")
        GroupedSettingsCard {
            SettingSliderRow(
                title = "绘画界面整体大小",
                summary = "缩放画布四周的工具栏、顶栏及各浮动面板 (${(vm.paintingUiScale * 100).toInt()}%)",
                value = ((vm.paintingUiScale - 0.75f) / (1.35f - 0.75f)).coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    val newScale = 0.75f + fraction * (1.35f - 0.75f)
                    vm.updatePaintingUiScale(newScale)
                }
            )
        }

        Spacer(Modifier.height(18.dp))

        // Section 6: 显示与效果
        SettingCategoryHeader("显示与效果")
        GroupedSettingsCard {
            val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SettingSwitchRow(
                title = "背景毛玻璃效果",
                summary = if (blurSupported) "为所有面板与工具栏启用半透明背景高斯模糊" else "此设备系统版本不支持模糊效果",
                checked = vm.blurBackground,
                enabled = blurSupported,
                onCheckedChange = { vm.updateBlurBackground(it) }
            )

            SettingsCardDivider()

            SettingSwitchRow(
                title = "沉浸模式",
                summary = "隐藏系统状态栏与导航栏，并将画布延展至刘海挖孔区域",
                checked = vm.immersiveMode,
                onCheckedChange = {
                    vm.updateExtendToCutout(true)
                    vm.updateImmersiveMode(it)
                }
            )
        }

        Spacer(Modifier.height(80.dp))
        }
    }

    if (showCustomColorDialog) {
        CustomColorDialog(
            initialHex = vm.accentColorHex,
            onConfirm = { hex ->
                vm.updateAccentColor(hex)
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false }
        )
    }

    if (showCustomCanvasBgDialog) {
        CustomColorDialog(
            initialHex = if (vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()) "#2F3136" else vm.canvasBgColorHex,
            onConfirm = { hex ->
                vm.updateCanvasBgColor(hex)
                showCustomCanvasBgDialog = false
            },
            onDismiss = { showCustomCanvasBgDialog = false }
        )
    }
}


