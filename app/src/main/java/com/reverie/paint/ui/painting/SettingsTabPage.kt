package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    var isRightHanded by remember { mutableStateOf(false) }
    var stabilizer by remember { mutableFloatStateOf(0.15f) }
    var currentSubPage by remember { mutableStateOf<String?>(null) }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            fadeIn(tween(160, easing = FastOutSlowInEasing))
                .togetherWith(fadeOut(tween(100)))
        },
        label = "SettingsSubPageTransition",
    ) { subPage ->
        when (subPage) {
            "GESTURE" -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    // Header with back button
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable { currentSubPage = null }
                                    .padding(4.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_left),
                                contentDescription = "返回",
                                tint = Morandi.text,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "手势设置",
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                    Spacer(Modifier.height(8.dp))

                    // 1. 双指点击屏幕撤销 (默认开启)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("双指点击屏幕撤销", color = Morandi.text, fontSize = 13.sp)
                            Text("双指轻点画布撤销上一步操作", color = Morandi.subText, fontSize = 11.sp)
                        }
                        ReSwitch(
                            checked = vm.gestureTwoFingerUndo,
                            onChecked = { vm.updateGestureTwoFingerUndo(it) },
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 2. 三指点击屏幕恢复（重做） (默认开启)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("三指点击屏幕恢复（重做）", color = Morandi.text, fontSize = 13.sp)
                            Text("三指轻点画布恢复已撤销的操作", color = Morandi.subText, fontSize = 11.sp)
                        }
                        ReSwitch(
                            checked = vm.gestureThreeFingerRedo,
                            onChecked = { vm.updateGestureThreeFingerRedo(it) },
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi)
                                .padding(10.dp),
                    ) {
                        Text(
                            "提示：多指触控手势不受笔模式影响，手写笔模式下依然可以直接使用双指撤销与三指重做",
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            "COLOR" -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    // Header with back button
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable { currentSubPage = null }
                                    .padding(4.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_left),
                                contentDescription = "返回",
                                tint = Morandi.text,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "颜色设置",
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                    Spacer(Modifier.height(8.dp))

                    // 1. 长按画布吸色开关
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("长按画布吸色", color = Morandi.text, fontSize = 13.sp)
                            Text(
                                if (vm.penOnlyMode) "笔模式已开启：手写笔长按画布取色" else "长按画布取色，开启笔模式后由手写笔长按取色",
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                        ReSwitch(
                            checked = vm.longPressEyedropperEnabled,
                            onChecked = { vm.updateLongPressEyedropperEnabled(it) },
                        )
                    }

                    if (vm.longPressEyedropperEnabled) {
                        Spacer(Modifier.height(10.dp))

                        val sensitivityLabels = listOf("极低", "较慢", "标准", "较快", "极速")
                        val sensitivityTimes = listOf("600ms", "520ms", "450ms", "380ms", "320ms")
                        val curIdx = (vm.eyedropperSensitivity - 1).coerceIn(0, 4)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("拾色灵敏度", color = Morandi.text, fontSize = 13.sp)
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Morandi.panel)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "${vm.eyedropperSensitivity} 段 · ${sensitivityLabels[curIdx]} (${sensitivityTimes[curIdx]})",
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 5-segment level selector
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel)
                                    .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (i in 1..5) {
                                val isSelected = vm.eyedropperSensitivity == i
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Morandi.accent else Color.Transparent)
                                            .clickable { vm.updateEyedropperSensitivity(i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${i}段",
                                        color = if (isSelected) Color.White else Morandi.subText,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }

                        // 取色点偏移开关（防止手指/笔遮挡中心点）
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("取色点偏移", color = Morandi.text, fontSize = 13.sp)
                                Text(
                                    "取色位置偏移到手指上方，避免手指遮挡中心点",
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            ReSwitch(
                                checked = vm.eyedropperOffsetEnabled,
                                onChecked = { vm.updateEyedropperOffsetEnabled(it) },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi)
                                .padding(10.dp),
                    ) {
                        Text(
                            "说明：灵敏度越高，长按触发时间越短；移动容差固定为 1dp（移动超过即不触发）。笔模式开启时，长按取色由手指触控无缝转换为手写笔长按。",
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            else -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    // 1. 笔模式 (快速切换)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("笔模式", color = Morandi.text, fontSize = 13.sp)
                        ReSwitch(
                            checked = vm.penOnlyMode,
                            onChecked = { vm.updatePenOnlyMode(it) },
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // List item links with chevron
                    SettingNavRow("视图显示") {}
                    SettingNavRow("手势") {
                        currentSubPage = "GESTURE"
                    }
                    SettingNavRow("手写笔设置") {
                        vm.openMoreSettings("STYLUS")
                        onClose()
                    }
                    SettingNavRow("快捷键设置") {}
                    SettingNavRow("颜色设置") {
                        currentSubPage = "COLOR"
                    }

                    // 更多设置 -> 绘画页内全屏覆盖层（不退出画布）
                    SettingNavRow("更多设置") {
                        vm.openMoreSettings("MAIN")
                        onClose()
                    }

                    Spacer(Modifier.height(4.dp))

                    // 8. 抖动修正
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("抖动修正", color = Morandi.text, fontSize = 13.sp)
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Morandi.panel)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("${(stabilizer * 100).toInt()}%", color = Morandi.subText, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Compact Slider
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Track
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Morandi.panel),
                        )
                        // Active Track
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(stabilizer.coerceIn(0.01f, 1f))
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Morandi.accent),
                        )
                        // Thumb
                        Box(
                            modifier =
                                Modifier
                                    .padding(start = ((280 - 24 - 16) * stabilizer).dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Morandi.text)
                                    .border(2.dp, Morandi.panelHi, CircleShape),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
internal fun SettingNavRow(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = Morandi.text, fontSize = 13.sp)
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SettingInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Morandi.text, fontSize = 12.sp)
    }
}
