package com.reverie.paint.ui.painting

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
) {
    var currentTab by remember { mutableStateOf(SettingsTab.CANVAS) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose),
    ) {
        Column(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Default)
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 8.dp)
                .width(280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panel.copy(alpha = opacity))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(14.dp))
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
        ) {
            // Top tab icons (3 tabs: 画布, 导出, 设置)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TabHeaderItem(
                    icon = R.drawable.ic_rect,
                    label = "画布",
                    selected = currentTab == SettingsTab.CANVAS,
                    onClick = { currentTab = SettingsTab.CANVAS }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_fill,
                    label = "导出",
                    selected = currentTab == SettingsTab.EXPORT,
                    onClick = { currentTab = SettingsTab.EXPORT }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_settings,
                    label = "设置",
                    selected = currentTab == SettingsTab.SETTINGS,
                    onClick = { currentTab = SettingsTab.SETTINGS }
                )
            }
            
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(tween(180, easing = FastOutSlowInEasing))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "SettingsTabTransition"
            ) { tab ->
                when (tab) {
                    SettingsTab.CANVAS -> CanvasTabPage(vm = vm, onClose = onClose)
                    SettingsTab.EXPORT -> ExportTabPage(vm = vm, onClose = onClose)
                    SettingsTab.SETTINGS -> SettingsTabPage(vm = vm, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun TabHeaderItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = Morandi.accent
    val inactiveColor = Morandi.icon
    val textColor = if (selected) activeColor else Morandi.text

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) activeColor else inactiveColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CanvasTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // Document Info
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        SettingInfoRow("创建时间", sdf.format(Date()))
        SettingInfoRow("画布尺寸", "${vm.docWidth}×${vm.docHeight} - 300ppi")
        SettingInfoRow("一共画了", "${vm.layerCount} 图层")
        SettingInfoRow("作画耗时", "00小时00分钟")
        SettingInfoRow("颜色模式", "RGB颜色")
        SettingInfoRow("文件大小", "3.36KB")
        
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        Spacer(Modifier.height(10.dp))

        // Action Grid (4 columns, compact)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ReMenuItem(R.drawable.ic_rect, "保存", { vm.saveProject(vm.docName); onClose() })
            ReMenuItem(R.drawable.ic_rect, "另存为", {})
            ReMenuItem(R.drawable.ic_settings, "修复草稿", {})
            ReMenuItem(R.drawable.ic_rect, "新建画布", {})
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ReMenuItem(R.drawable.ic_rect, "画布调整", {})
            ReMenuItem(R.drawable.ic_rect, "图像调整", {})
            ReMenuItem(R.drawable.ic_rotate_cw, "水平翻转", {})
            ReMenuItem(R.drawable.ic_rotate_ccw, "垂直翻转", {})
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            ReMenuItem(R.drawable.ic_rect, "画布盖印", {}, modifier = Modifier.padding(end = 16.dp))
            ReMenuItem(R.drawable.ic_settings, "颜色配置", {})
        }
    }
}

@Composable
private fun ExportTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("PNG") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("导出格式", color = Morandi.subText, fontSize = 12.sp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("PNG", "JPEG", "PSD").forEach { fmt ->
                val isSel = selectedFormat == fmt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                        .border(1.dp, if (isSel) Morandi.accent else Morandi.border, RoundedCornerShape(8.dp))
                        .clickable { selectedFormat = fmt },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        fmt,
                        color = if (isSel) Morandi.accent else Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.accent)
                .clickable {
                    vm.saveProject(vm.docName)
                    onClose()
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("导出到相册", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                .clickable { onClose() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = Morandi.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("分享文件", color = Morandi.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var isRightHanded by remember { mutableStateOf(false) }
    var stabilizer by remember { mutableFloatStateOf(0.15f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 1. 笔模式 (快速切换)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("笔模式", color = Morandi.text, fontSize = 13.sp)
            ReSwitch(
                checked = vm.penOnlyMode,
                onChecked = { vm.updatePenOnlyMode(it) }
            )
        }

        Spacer(Modifier.height(6.dp))

        // List item links with chevron
        SettingNavRow("视图显示") {}
        SettingNavRow("手势") {}
        SettingNavRow("手写笔设置") {
            vm.homeSelectedTab = 1
            vm.settingsInitialSubPage = "STYLUS"
            vm.goHome()
            onClose()
        }
        SettingNavRow("快捷键设置") {}
        SettingNavRow("颜色设置") {}

        // 更多设置 -> 跳转到设置页面
        SettingNavRow("更多设置") {
            vm.homeSelectedTab = 1
            vm.settingsInitialSubPage = "MAIN"
            vm.goHome()
            onClose()
        }

        Spacer(Modifier.height(4.dp))

        // 8. 抖动修正
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("抖动修正", color = Morandi.text, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.panel)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("${(stabilizer * 100).toInt()}%", color = Morandi.subText, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Compact Slider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Morandi.panel)
            )
            // Active Track
            Box(
                modifier = Modifier
                    .fillMaxWidth(stabilizer.coerceIn(0.01f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Morandi.accent)
            )
            // Thumb
            Box(
                modifier = Modifier
                    .padding(start = ((280 - 24 - 16) * stabilizer).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Morandi.text)
                    .border(2.dp, Morandi.panelHi, CircleShape)
            )
        }
        
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingNavRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Morandi.text, fontSize = 13.sp)
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Morandi.text, fontSize = 12.sp)
    }
}
