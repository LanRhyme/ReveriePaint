/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun SettingsTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    var currentSubPage by remember { mutableStateOf<String?>(null) }
    var recordingShortcut by remember { mutableStateOf<ShortcutDefinition?>(null) }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            fadeIn(tween(160, easing = FastOutSlowInEasing))
                .togetherWith(fadeOut(tween(100)))
        },
        label = "SettingsSubPageTransition",
    ) { subPage ->
        when (subPage) {
            // ---- 1. 视图显示 (参考图 1) ----
            "VIEW" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header with back button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
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
                            "视图显示",
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                    Spacer(Modifier.height(8.dp))

                    // 快捷滑块: 单选 流量 vs 不透明度
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("快捷滑块", color = Morandi.text, fontSize = 13.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 流量 (Flow)
                            val isFlow = vm.quickSliderMode == 1
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { vm.updateQuickSliderMode(1) },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(
                                            width = if (isFlow) 5.dp else 1.5.dp,
                                            color = if (isFlow) Morandi.accent else Morandi.subText,
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "流量",
                                    color = if (isFlow) Morandi.text else Morandi.subText,
                                    fontSize = 12.sp,
                                )
                            }

                            // 不透明度 (Opacity)
                            val isOpacity = vm.quickSliderMode == 0
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { vm.updateQuickSliderMode(0) },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(
                                            width = if (isOpacity) 5.dp else 1.5.dp,
                                            color = if (isOpacity) Morandi.accent else Morandi.subText,
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "不透明度",
                                    color = if (isOpacity) Morandi.text else Morandi.subText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    // 画布可旋转
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("画布可旋转", color = Morandi.text, fontSize = 13.sp)
                        ReSwitch(
                            checked = vm.canvasRotationEnabled,
                            onChecked = { vm.updateCanvasRotationEnabled(it) },
                        )
                    }

                    // 放大插值
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("放大插值", color = Morandi.text, fontSize = 13.sp)
                        ReSwitch(
                            checked = vm.magnificationInterpolation,
                            onChecked = { vm.updateMagnificationInterpolation(it) },
                        )
                    }

                    // 放大显示网格线
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("放大显示网格线", color = Morandi.text, fontSize = 13.sp)
                        ReSwitch(
                            checked = vm.pixelGridEnabled,
                            onChecked = { vm.updatePixelGridEnabled(it) },
                        )
                    }

                    // 撤销操作提醒
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("撤销操作提醒", color = Morandi.text, fontSize = 13.sp)
                        ReSwitch(
                            checked = vm.undoToastEnabled,
                            onChecked = { vm.updateUndoToastEnabled(it) },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---- 2. 快捷键设置 (参考图 2) ----
            "SHORTCUTS" -> {
                var activeCategory by remember { mutableStateOf(ShortcutCategory.PAINTING) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Header: ✕ 快捷键设置 on left, 重置 on right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { currentSubPage = null }
                                    .padding(4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = "关闭",
                                    tint = Morandi.text,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "快捷键设置",
                                color = Morandi.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Text(
                            "重置",
                            color = Morandi.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { vm.resetShortcuts() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 4 Category Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        val tabs = listOf(
                            ShortcutCategory.PAINTING to R.drawable.ic_brush,
                            ShortcutCategory.TOOLS to R.drawable.ic_grid,
                            ShortcutCategory.FILTERS to R.drawable.ic_magicwand,
                            ShortcutCategory.LAYERS to R.drawable.ic_layers,
                        )
                        tabs.forEach { (cat, iconRes) ->
                            val isSel = activeCategory == cat
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { activeCategory = cat }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = cat.title,
                                    tint = if (isSel) Morandi.accent else Morandi.subText,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    cat.title,
                                    color = if (isSel) Morandi.accent else Morandi.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                    Spacer(Modifier.height(4.dp))

                    // Shortcuts List
                    val items = ALL_SHORTCUT_DEFINITIONS.filter { it.category == activeCategory }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        items.forEach { def ->
                            val currentKey = vm.getShortcutKey(def.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { recordingShortcut = def }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    def.name,
                                    color = Morandi.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panelHi)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        currentKey,
                                        color = if (currentKey == "无") Morandi.subText else Morandi.text,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 3. 手势设置 ----
            "GESTURE" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header with back button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
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

                    Row(
                        modifier = Modifier
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

                    Row(
                        modifier = Modifier
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

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("双指快速内捏复位画布", color = Morandi.text, fontSize = 13.sp)
                            Text("双指快速向内收拢捏合时自动平滑居中并满屏复位", color = Morandi.subText, fontSize = 11.sp)
                        }
                        ReSwitch(
                            checked = vm.gestureQuickPinchFit,
                            onChecked = { vm.updateGestureQuickPinchFit(it) },
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
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

            // ---- 4. 颜色设置 ----
            "COLOR" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
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

                    Row(
                        modifier = Modifier
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
                                modifier = Modifier
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

                        Row(
                            modifier = Modifier
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
                                    modifier = Modifier
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

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
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
                        modifier = Modifier
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

            // ---- 通用设置 (自动保存/撤销) ----
            "GENERAL" -> {
                com.reverie.paint.ui.home.GeneralSettingsSubPage(
                    vm = vm,
                    onBack = { currentSubPage = null },
                    compact = true,
                )
            }

            // ---- 5. 关于应用 ----
            "ABOUT" -> {
                com.reverie.paint.ui.home.AboutSettingsSubPage(
                    onBack = { currentSubPage = null },
                    compact = true,
                )
            }

            // ---- 主设置页 ----
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 1. 笔模式 (快速切换)
                    Row(
                        modifier = Modifier
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
                    SettingNavRow("通用设置") {
                        currentSubPage = "GENERAL"
                    }
                    SettingNavRow("视图显示") {
                        currentSubPage = "VIEW"
                    }
                    SettingNavRow("手势") {
                        currentSubPage = "GESTURE"
                    }
                    SettingNavRow("手写笔设置") {
                        vm.openMoreSettings("STYLUS")
                        onClose()
                    }
                    SettingNavRow("快捷键设置") {
                        currentSubPage = "SHORTCUTS"
                    }
                    SettingNavRow("颜色设置") {
                        currentSubPage = "COLOR"
                    }
                    SettingNavRow("关于") {
                        currentSubPage = "ABOUT"
                    }

                    // 更多设置 -> 绘画页内全屏覆盖层（不退出画布）
                    SettingNavRow("更多设置") {
                        vm.openMoreSettings("MAIN")
                        onClose()
                    }

                    Spacer(Modifier.height(8.dp))

                    // 抖动修正 (Stroke Stabilizer)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("抖动修正", color = Morandi.text, fontSize = 13.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Morandi.panel)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("${(vm.strokeStabilizer * 100).toInt()}%", color = Morandi.subText, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Interactive Stabilizer Slider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    vm.updateStrokeStabilizer(frac)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    vm.updateStrokeStabilizer(frac)
                                }
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Morandi.panel),
                        )
                        // Active Track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(vm.strokeStabilizer.coerceIn(0.01f, 1f))
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Morandi.accent),
                        )
                        // Thumb
                        Box(
                            modifier = Modifier
                                .padding(start = ((260 - 16) * vm.strokeStabilizer).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Morandi.text)
                                .border(2.dp, Morandi.panelHi, CircleShape),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ---- Key Recording Dialog ----
    recordingShortcut?.let { def ->
        var recordedKey by remember { mutableStateOf(vm.getShortcutKey(def.id)) }
        val dialogFocusRequester = remember { FocusRequester() }

        Dialog(onDismissRequest = { recordingShortcut = null }) {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .shadow(16.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi)
                    .glassBorder(RoundedCornerShape(14.dp))
                    .focusRequester(dialogFocusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        val k = keyEventToString(event)
                        if (k.isNotBlank()) {
                            recordedKey = k
                            true
                        } else {
                            false
                        }
                    }
                    .padding(16.dp),
            ) {
                LaunchedEffect(Unit) {
                    try {
                        dialogFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "设置快捷键",
                        color = Morandi.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        def.name,
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .border(1.5.dp, Morandi.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (recordedKey.isBlank() || recordedKey == "无") "请按下快捷键..." else recordedKey,
                            color = if (recordedKey.isBlank() || recordedKey == "无") Morandi.subText else Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "设为无",
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    vm.setShortcutKey(def.id, "无")
                                    recordingShortcut = null
                                }
                                .padding(8.dp),
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "取消",
                                color = Morandi.subText,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { recordingShortcut = null }
                                    .padding(8.dp),
                            )
                            Text(
                                "保存",
                                color = Morandi.onAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Morandi.accent)
                                    .clickable {
                                        vm.setShortcutKey(def.id, recordedKey)
                                        recordingShortcut = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
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
        modifier = Modifier
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Morandi.text, fontSize = 12.sp)
    }
}
