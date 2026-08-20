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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showCustomColorDialog by remember { mutableStateOf(false) }

    val presetSwatches = listOf(
        "#5E8BA8", "#7C8F9E", "#8D9E8F", "#C9ADA7",
        "#B4552D", "#5A6E8A", "#9A8F7B", "#A27B8A"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // Native back bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "主题设置",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Section: 外观与取色
        SettingCategoryHeader("外观与主题")

        Text(
            text = "色彩模式",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
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

        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "主色调 (莫奈动态接管中)" else "主色调",
            color = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) colors.subText else colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "点击下方色块可关闭莫奈取色并应用指定颜色"
            } else {
                "应用于按钮、滑块及高亮强调色"
            },
            color = colors.subText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        // Swatch list + Custom "+" button
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            items(presetSwatches) { hex ->
                val swatchColor = parseColor(hex)
                val isSelected = !vm.monetEnabled && vm.accentColorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else colors.border,
                            shape = CircleShape
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
                val currentCustomColor = if (isCustomSelected) parseColor(vm.accentColorHex) else colors.panel
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(currentCustomColor)
                        .border(
                            width = if (isCustomSelected) 3.dp else 1.dp,
                            color = if (isCustomSelected) Color.White else colors.border,
                            shape = CircleShape
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

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // Section: 界面不透明度
        SettingCategoryHeader("界面不透明度")

        SettingSliderRow(
            title = "主界面面板",
            summary = "工具栏与顶部栏不透明度",
            value = vm.uiOpacity,
            onValueChange = { vm.updateUiOpacity(it) }
        )

        Spacer(Modifier.height(16.dp))

        SettingSliderRow(
            title = "浮动面板",
            summary = "图层、笔刷、颜色等弹窗不透明度",
            value = vm.popupPanelOpacity,
            onValueChange = { vm.updatePopupPanelOpacity(it) }
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // Section: 界面尺寸与缩放
        SettingCategoryHeader("界面尺寸")

        SettingSliderRow(
            title = "绘画界面整体大小",
            summary = "缩放画布四周的工具栏、顶栏及各浮动面板 (${(vm.paintingUiScale * 100).toInt()}%)",
            value = ((vm.paintingUiScale - 0.75f) / (1.35f - 0.75f)).coerceIn(0f, 1f),
            onValueChange = { fraction ->
                val newScale = 0.75f + fraction * (1.35f - 0.75f)
                vm.updatePaintingUiScale(newScale)
            }
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // Section: 显示与效果
        SettingCategoryHeader("显示与效果")

        SettingSwitchRow(
            title = "背景毛玻璃效果",
            summary = "为所有面板与工具栏启用半透明背景高斯模糊",
            checked = vm.blurBackground,
            onCheckedChange = { vm.updateBlurBackground(it) }
        )

        Spacer(Modifier.height(8.dp))

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
}


