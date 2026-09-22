/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.painting.panels.CompactColorPickerPopup
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

@Composable
internal fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    val colors = Theme.current
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showCustomCanvasBgDialog by remember { mutableStateOf(false) }

    val presetSwatches = listOf(
        "#5A6E8A", "#5A8A86", "#5A8A6A", "#768A5A", "#8A7A5A",
        "#8A665A", "#8A5A66", "#825A8A", "#625A8A"
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
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 12.dp else 20.dp),
        ) {
            SettingSubPageHeader(
                title = stringResource(R.string.theme_settings_title),
                subtitle = stringResource(R.string.theme_settings_subtitle),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // Section 1: 色彩与主题模式
            SettingCategoryTitle(stringResource(R.string.theme_category_color_mode))
            SettingGroup {
                SettingSegmentGroupItem(
                    icon = Icons.Rounded.Brightness4,
                    title = stringResource(R.string.theme_color_mode_title),
                    summary = stringResource(R.string.theme_color_mode_summary),
                    options = listOf(
                        "DARK" to stringResource(R.string.theme_mode_dark),
                        "LIGHT" to stringResource(R.string.theme_mode_light),
                        "SYSTEM" to stringResource(R.string.theme_mode_system),
                    ),
                    selected = vm.themeMode,
                    shape = settingGroupShape(0, 2),
                    onSelect = { vm.updateThemeMode(it) },
                )

                SettingSwitchGroupItem(
                    icon = Icons.Rounded.ColorLens,
                    title = stringResource(R.string.theme_monet_title),
                    summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stringResource(R.string.theme_monet_summary)
                    } else {
                        stringResource(R.string.theme_monet_unsupported)
                    },
                    checked = vm.monetEnabled,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    shape = settingGroupShape(1, 2),
                    onCheckedChange = { vm.updateMonetEnabled(it) },
                )
            }

            // Section 2: 主色调
            SettingCategoryTitle(stringResource(R.string.theme_category_accent))
            SettingGroup {
                SettingCardBox(shape = settingGroupShape(0, 1)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SettingIcon(icon = Icons.Rounded.Palette, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stringResource(R.string.theme_accent_palette_monet) else stringResource(R.string.theme_accent_palette),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        stringResource(R.string.theme_monet_override_hint)
                                    } else {
                                        stringResource(R.string.theme_accent_desc)
                                    },
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(presetSwatches) { hex ->
                                val swatchColor = parseColor(hex)
                                val isSelected = !vm.monetEnabled && vm.accentColorHex.equals(hex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
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
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
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
                                        .size(38.dp)
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
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                                        contentDescription = stringResource(R.string.color_custom),
                                        tint = if (isCustomSelected) Color.White else colors.icon,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: 画布工作区背景
            SettingCategoryTitle(stringResource(R.string.theme_category_canvas_bg))
            SettingGroup {
                SettingCardBox(shape = settingGroupShape(0, 1)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SettingIcon(icon = Icons.Rounded.Wallpaper, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.theme_canvas_bg_title),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.theme_canvas_bg_desc),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(canvasPresetSwatches) { hex ->
                                if (hex == "DEFAULT") {
                                    val isDefaultSelected = vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()
                                    Box(
                                        modifier = Modifier
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(19.dp))
                                            .background(colors.panelHi)
                                            .then(
                                                if (isDefaultSelected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(19.dp)) else Modifier
                                            )
                                            .clickable { vm.updateCanvasBgColor("DEFAULT") }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isDefaultSelected) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = colors.accent,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = stringResource(R.string.theme_canvas_bg_follow),
                                                color = if (isDefaultSelected) colors.accent else colors.text,
                                                fontSize = 12.sp,
                                                fontWeight = if (isDefaultSelected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                } else {
                                    val swatchColor = parseColor(hex)
                                    val isSelected = vm.canvasBgColorHex.equals(hex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                        .background(swatchColor)
                                        .then(
                                            if (isSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                        )
                                        .clickable { vm.updateCanvasBgColor(hex) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            val checkTint = if (swatchColor.red * 0.299 + swatchColor.green * 0.587 + swatchColor.blue * 0.114 > 0.6) Color.Black else Color.White
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = checkTint,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // Custom canvas background button
                            item {
                                val isCustomSelected = vm.canvasBgColorHex != "DEFAULT" && vm.canvasBgColorHex.isNotBlank() && canvasPresetSwatches.none { it.equals(vm.canvasBgColorHex, ignoreCase = true) }
                                val currentCustomColor = if (isCustomSelected) parseColor(vm.canvasBgColorHex) else colors.panelHi
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(currentCustomColor)
                                        .then(
                                            if (isCustomSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                        )
                                        .clickable { showCustomCanvasBgDialog = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val iconTint = if (isCustomSelected) {
                                        if (currentCustomColor.red * 0.299 + currentCustomColor.green * 0.587 + currentCustomColor.blue * 0.114 > 0.6) Color.Black else Color.White
                                    } else colors.icon
                                    Icon(
                                        painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                                        contentDescription = stringResource(R.string.theme_custom_canvas_bg),
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 4: 界面不透明度
            SettingCategoryTitle(stringResource(R.string.theme_category_opacity))
            SettingGroup {
                SettingSliderGroupItem(
                    icon = Icons.Rounded.Opacity,
                    title = stringResource(R.string.theme_opacity_main_panel),
                    summary = stringResource(R.string.theme_opacity_main_panel_desc),
                    valueText = "${(vm.uiOpacity * 100).toInt()}%",
                    sliderFraction = ((vm.uiOpacity - 0.2f) / 0.8f).coerceIn(0f, 1f),
                    shape = settingGroupShape(0, 2),
                    onValueChange = { vm.updateUiOpacity((0.2f + it * 0.8f).coerceIn(0.2f, 1f)) },
                )

                SettingSliderGroupItem(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.theme_opacity_floating_panel),
                    summary = stringResource(R.string.theme_opacity_floating_panel_desc),
                    valueText = "${(vm.popupPanelOpacity * 100).toInt()}%",
                    sliderFraction = ((vm.popupPanelOpacity - 0.2f) / 0.8f).coerceIn(0f, 1f),
                    shape = settingGroupShape(1, 2),
                    onValueChange = { vm.updatePopupPanelOpacity((0.2f + it * 0.8f).coerceIn(0.2f, 1f)) },
                )
            }

            // Section 5: 界面尺寸
            SettingCategoryTitle(stringResource(R.string.theme_category_ui_scale))
            SettingGroup {
                SettingSliderGroupItem(
                    icon = Icons.Rounded.AspectRatio,
                    title = stringResource(R.string.theme_ui_scale_title),
                    summary = stringResource(R.string.theme_ui_scale_desc),
                    valueText = "${(vm.paintingUiScale * 100).toInt()}%",
                    sliderFraction = ((vm.paintingUiScale - 0.75f) / (1.35f - 0.75f)).coerceIn(0f, 1f),
                    shape = settingGroupShape(0, 1),
                    onValueChange = { fraction ->
                        val newScale = 0.75f + fraction * (1.35f - 0.75f)
                        vm.updatePaintingUiScale(newScale)
                    },
                )
            }

            // Section 6: 显示与效果
            SettingCategoryTitle(stringResource(R.string.theme_category_effects))
            SettingGroup {
                val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.BlurOn,
                    title = stringResource(R.string.theme_blur_title),
                    summary = if (blurSupported) stringResource(R.string.theme_blur_desc) else stringResource(R.string.theme_blur_unsupported),
                    checked = vm.blurBackground,
                    enabled = blurSupported,
                    shape = settingGroupShape(0, 2),
                    onCheckedChange = { vm.updateBlurBackground(it) },
                )

                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Fullscreen,
                    title = stringResource(R.string.theme_immersive_title),
                    summary = stringResource(R.string.theme_immersive_desc),
                    checked = vm.immersiveMode,
                    shape = settingGroupShape(1, 2),
                    onCheckedChange = {
                        vm.updateExtendToCutout(true)
                        vm.updateImmersiveMode(it)
                    },
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showCustomColorDialog) {
        CompactColorPickerPopup(
            title = stringResource(R.string.color_custom),
            initialHex = vm.accentColorHex,
            onColorConfirmed = { hex ->
                vm.updateAccentColor(hex)
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false },
        )
    }

    if (showCustomCanvasBgDialog) {
        CompactColorPickerPopup(
            title = stringResource(R.string.theme_custom_canvas_bg),
            initialHex = if (vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()) "#2F3136" else vm.canvasBgColorHex,
            onColorConfirmed = { hex ->
                vm.updateCanvasBgColor(hex)
                showCustomCanvasBgDialog = false
            },
            onDismiss = { showCustomCanvasBgDialog = false },
        )
    }
}


