package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

@Composable
fun SettingsPageContent(vm: PaintViewModel) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(48.dp)
    ) {
        Text("设置", color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(48.dp))

        // Theme Section
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Palette, contentDescription = null, tint = colors.icon, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text("外观主题", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text("主色调 (Accent Color)", color = colors.subText, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val swatches = listOf("#5E8BA8", "#C9ADA7", "#8D9E8F", "#B4552D", "#5A6E8A", "#7C8F9E")
            swatches.forEach { hex ->
                val color = parseColor(hex)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable {
                            Theme.current = colors.copy(accent = color, accentHi = color)
                        }
                )
            }
        }

        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(48.dp))

        // Opacity Section
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Opacity, contentDescription = null, tint = colors.icon, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text("界面不透明度", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("控制各个面板的全局透明度，打造统一的玻璃质感", color = colors.subText, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        OpacitySlider(
            label = "主界面面板 (工具栏/导航栏)",
            value = vm.uiOpacity,
            onValueChange = { vm.uiOpacity = it },
            colors = colors
        )
        Spacer(Modifier.height(24.dp))
        OpacitySlider(
            label = "浮动面板 (图层/笔刷/颜色等)",
            value = vm.popupPanelOpacity,
            onValueChange = { vm.popupPanelOpacity = it },
            colors = colors
        )
    }
}

@Composable
private fun OpacitySlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    colors: com.reverie.paint.ui.theme.AppColors
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = colors.text, fontSize = 16.sp)
            Text("${(value * 100).toInt()}%", color = colors.accent, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
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
