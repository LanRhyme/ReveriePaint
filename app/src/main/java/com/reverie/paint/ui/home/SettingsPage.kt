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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
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
                vm = vm,
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
    vm: PaintViewModel,
    onNavigate: (SettingsSubPage) -> Unit
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "设置",
            color = colors.text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "个性化配置与全局偏好",
            color = colors.subText,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(28.dp))

        // Module Item: 主题设置
        SettingsModuleCard(
            icon = Icons.Rounded.Palette,
            title = "主题设置",
            subtitle = "主色调、界面透明度、沉浸模式与刘海屏适配",
            onClick = { onNavigate(SettingsSubPage.THEME) }
        )
    }
}

@Composable
private fun SettingsModuleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = Theme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.width(12.dp))

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.subText.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        // Top Back Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.panel)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.icon,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = "主题设置",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(28.dp))

        // Section 1: 主色调 (Accent Color)
        SettingsSectionHeader(
            icon = Icons.Rounded.Palette,
            title = "主色调 (Accent Color)",
            subtitle = "全局按钮、滑块及高亮强调色"
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val swatches = listOf("#5E8BA8", "#C9ADA7", "#8D9E8F", "#B4552D", "#5A6E8A", "#7C8F9E", "#9A8F7B")
            swatches.forEach { hex ->
                val swatchColor = parseColor(hex)
                val isSelected = vm.accentColorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(32.dp))

        // Section 2: 界面不透明度 (UI Opacity)
        SettingsSectionHeader(
            icon = Icons.Rounded.Opacity,
            title = "界面不透明度",
            subtitle = "调节工具栏与浮动面板的透明质感"
        )
        Spacer(Modifier.height(20.dp))

        OpacitySliderItem(
            label = "主界面面板 (工具栏 / 顶部栏)",
            value = vm.uiOpacity,
            onValueChange = { vm.updateUiOpacity(it) }
        )

        Spacer(Modifier.height(20.dp))

        OpacitySliderItem(
            label = "浮动面板 (图层 / 笔刷 / 颜色等)",
            value = vm.popupPanelOpacity,
            onValueChange = { vm.updatePopupPanelOpacity(it) }
        )

        Spacer(Modifier.height(32.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(32.dp))

        // Section 3: 沉浸模式与刘海屏适配
        SettingsSectionHeader(
            icon = Icons.Rounded.Fullscreen,
            title = "沉浸模式与显示",
            subtitle = "最大化绘画创作视野"
        )
        Spacer(Modifier.height(16.dp))

        SettingToggleRow(
            title = "全屏沉浸模式",
            description = "自动隐藏系统状态栏与底部导航栏，向内轻扫可呼出",
            checked = vm.immersiveMode,
            onCheckedChange = { vm.updateImmersiveMode(it) }
        )

        Spacer(Modifier.height(14.dp))

        SettingToggleRow(
            title = "拓展到刘海屏区域",
            description = "沉浸模式下将画布和软件界面延伸覆盖至屏幕刘海与摄像头挖孔",
            checked = vm.extendToCutout,
            onCheckedChange = { vm.updateExtendToCutout(it) }
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val colors = Theme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OpacitySliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val colors = Theme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel.copy(alpha = 0.5f))
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${(value * 100).toInt()}%", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.2f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentHi,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.panelHi
            )
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = Theme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel.copy(alpha = 0.5f))
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                color = colors.subText,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(Modifier.width(14.dp))

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
