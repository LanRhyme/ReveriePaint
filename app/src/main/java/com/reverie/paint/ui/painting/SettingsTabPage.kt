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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

@Composable
internal fun SettingsTabPage(
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
internal fun SettingNavRow(
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
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun SettingInfoRow(
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

