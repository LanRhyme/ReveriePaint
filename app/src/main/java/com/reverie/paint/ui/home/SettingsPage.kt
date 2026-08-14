package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

enum class SettingsSubPage {
    MAIN,
    THEME
}

@Composable
fun SettingsPageContent(vm: PaintViewModel) {
    var subPage by remember { mutableStateOf(SettingsSubPage.MAIN) }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            if (targetState == SettingsSubPage.THEME) {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)))
            }
        },
        label = "SettingsSubPageTransition"
    ) { page ->
        when (page) {
            SettingsSubPage.MAIN -> SettingsMainPage(
                onNavigate = { subPage = it }
            )
            SettingsSubPage.THEME -> ThemeSettingsSubPage(
                vm = vm,
                onBack = { subPage = SettingsSubPage.MAIN }
            )
        }
    }
}

@Composable
private fun SettingsMainPage(
    onNavigate: (SettingsSubPage) -> Unit
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "设置",
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // Native Android settings row: 主题设置
        SettingNavRow(
            icon = Icons.Rounded.Palette,
            title = "主题设置",
            summary = "主色调、面板透明度、沉浸模式与刘海屏适配",
            onClick = { onNavigate(SettingsSubPage.THEME) }
        )
    }
}

@Composable
private fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showCustomColorDialog by remember { mutableStateOf(false) }

    val presetSwatches = listOf(
        "#5E8BA8", "#C9ADA7", "#8D9E8F", "#B4552D",
        "#5A6E8A", "#7C8F9E", "#9A8F7B", "#3E6B89"
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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

        // Section: 主色调
        SettingCategoryHeader("外观")
        Text(
            text = "主色调",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = "应用于按钮、滑块及高亮强调色",
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
                val isSelected = vm.accentColorHex.equals(hex, ignoreCase = true)
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
                            vm.updateAccentColor(hex)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Custom color button
            item {
                val isCustomSelected = presetSwatches.none { it.equals(vm.accentColorHex, ignoreCase = true) }
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
                            showCustomColorDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCustomSelected) Icons.Default.Check else Icons.Default.Add,
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

        // Section: 显示与沉浸
        SettingCategoryHeader("显示")

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

@Composable
private fun SettingCategoryHeader(title: String) {
    val colors = Theme.current
    Text(
        text = title,
        color = colors.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun SettingNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.icon,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = colors.subText,
                fontSize = 12.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.subText.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.panelHi,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.subText,
                uncheckedTrackColor = colors.panel
            )
        )
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val colors = Theme.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = summary,
                    color = colors.subText,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "${(value * 100).toInt()}%",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.2f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentHi,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.panel
            )
        )
    }
}

@Composable
private fun CustomColorDialog(
    initialHex: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Theme.current
    var hexInput by remember { mutableStateOf(initialHex.removePrefix("#")) }
    val parsedPreview = remember(hexInput) {
        try {
            parseColor("#$hexInput")
        } catch (_: Exception) {
            colors.accent
        }
    }

    val extraColors = listOf(
        "#E06C75", "#E5C07B", "#98C379", "#56B6C2",
        "#61AFEF", "#C678DD", "#FF6B6B", "#4ECDC4",
        "#45B7D1", "#F7B731", "#5F27CD", "#00D2D3"
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelHi)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "自定义主色调",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                // Color preview & HEX input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(parsedPreview)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    )
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexInput = filtered
                        },
                        prefix = { Text("#", color = colors.subText) },
                        singleLine = true,
                        placeholder = { Text("5E8BA8", color = colors.subText.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor = colors.accent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (hexInput.length == 6) {
                                onConfirm("#$hexInput")
                            }
                        }),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("快速选取色盘", color = colors.subText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))

                // Quick extra colors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    extraColors.take(6).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseColor(hex))
                                .clickable {
                                    hexInput = hex.removePrefix("#")
                                }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    extraColors.takeLast(6).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseColor(hex))
                                .clickable {
                                    hexInput = hex.removePrefix("#")
                                }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.subText)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val hex = if (hexInput.length == 6) "#$hexInput" else initialHex
                            onConfirm(hex)
                        }
                    ) {
                        Text("确定", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
