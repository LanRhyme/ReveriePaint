/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import com.reverie.paint.model.GestureAction
import com.reverie.paint.model.GestureTrigger
import com.reverie.paint.model.labelRes
import com.reverie.paint.model.descRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import kotlin.math.roundToInt
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.BackKeyAction
import com.reverie.paint.model.RotationSnap
import com.reverie.paint.ui.painting.CanvasEditMenuCustomizeDialog
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun SettingsTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    var currentSubPage by remember { mutableStateOf<String?>(null) }
    var smoothingAdvancedExpanded by remember { mutableStateOf(false) }
    var canvasEditMenuCustomizeOpen by remember { mutableStateOf(false) }
    var selectingActionForTrigger by remember { mutableStateOf<GestureAction?>(null) }

    if (canvasEditMenuCustomizeOpen) {
        CanvasEditMenuCustomizeDialog(vm, onDismiss = { canvasEditMenuCustomizeOpen = false })
    }

    selectingActionForTrigger?.let { action ->
        GestureTriggerPickerDialog(
            action = action,
            currentTrigger = vm.gestureConfig.getTrigger(action),
            vm = vm,
            onDismiss = { selectingActionForTrigger = null },
        )
    }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            fadeIn(tween(160, easing = FastOutSlowInEasing))
                .togetherWith(fadeOut(tween(100)))
        },
        label = "SettingsSubPageTransition",
    ) { subPage ->
        when (subPage) {
            // ---- 1. 视图显示 ----
            "VIEW" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubPageHeader(
                        title = stringResource(R.string.settings_view_display),
                        onBack = { currentSubPage = null },
                    )

                    // 控件与交互设置卡片
                    SettingsCard {
                        // 快捷滑块: 单选 流量 vs 不透明度
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_quick_slider), color = Morandi.text, fontSize = 13.sp)
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
                                        stringResource(R.string.settings_quick_slider_flow),
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
                                        stringResource(R.string.settings_quick_slider_opacity),
                                        color = if (isOpacity) Morandi.text else Morandi.subText,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }

                        SettingsInnerDivider()

                        // 面板固定与自由平移 (画笔与图层面板)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_panel_pinning_title),
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = stringResource(R.string.settings_panel_pinning_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            ReSwitch(
                                checked = vm.panelPinningEnabled,
                                onChecked = { vm.updatePanelPinningEnabled(it) },
                            )
                        }

                        SettingsInnerDivider()

                        // 运笔靠近面板时半透明避让（50% 内容/背景 + 100% 边框/阴影）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.auto_hide_panels_on_stroke_title),
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = stringResource(R.string.auto_hide_panels_on_stroke_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            ReSwitch(
                                checked = vm.autoHidePanelsOnStroke,
                                onChecked = { vm.updateAutoHidePanelsOnStroke(it) },
                            )
                        }

                        SettingsInnerDivider()

                        // ABR 导入混合模式策略 (柔和容错 vs 严格精准)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_abr_blend_policy_title),
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = stringResource(R.string.settings_abr_blend_policy_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val isCompat = vm.abrBlendPolicy == com.reverie.paint.core.AbrBlendModes.Policy.COMPAT_SAFE
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { vm.abrBlendPolicy = com.reverie.paint.core.AbrBlendModes.Policy.COMPAT_SAFE },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .border(
                                                width = if (isCompat) 5.dp else 1.5.dp,
                                                color = if (isCompat) Morandi.accent else Morandi.subText,
                                                shape = CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.settings_abr_blend_policy_compat),
                                        color = if (isCompat) Morandi.text else Morandi.subText,
                                        fontSize = 12.sp,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { vm.abrBlendPolicy = com.reverie.paint.core.AbrBlendModes.Policy.PRECISION },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .border(
                                                width = if (!isCompat) 5.dp else 1.5.dp,
                                                color = if (!isCompat) Morandi.accent else Morandi.subText,
                                                shape = CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.settings_abr_blend_policy_precision),
                                        color = if (!isCompat) Morandi.text else Morandi.subText,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }

                        SettingsInnerDivider()

                        // 图层面板顶部按钮行为 (默认关闭为剪切蒙版，开启为继承透明度)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_layer_header_inherit_alpha_title),
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = stringResource(R.string.settings_layer_header_inherit_alpha_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            ReSwitch(
                                checked = vm.layerHeaderInheritAlpha,
                                onChecked = { vm.updateLayerHeaderInheritAlpha(it) },
                            )
                        }
                    }

                    // 画布视口与辅助设置卡片
                    SettingsCard {
                        // 画布可旋转
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_canvas_rotation), color = Morandi.text, fontSize = 13.sp)
                            ReSwitch(
                                checked = vm.canvasRotationEnabled,
                                onChecked = { vm.updateCanvasRotationEnabled(it) },
                            )
                        }

                        // 旋转吸附阈值 (仅在允许旋转时可配置; 0° 表示关闭吸附)
                        if (vm.canvasRotationEnabled) {
                            SettingsInnerDivider()

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        stringResource(R.string.settings_canvas_rotation_snap),
                                        color = Morandi.text,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        if (vm.canvasRotationSnapDegrees <= 0f) {
                                            stringResource(R.string.settings_canvas_rotation_snap_off)
                                        } else {
                                            stringResource(
                                                R.string.settings_canvas_rotation_snap_format,
                                                vm.canvasRotationSnapDegrees.roundToInt(),
                                            )
                                        },
                                        color = Morandi.accent,
                                        fontSize = 12.sp,
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // 0 ~ MAX 映射到 0~1; 拖到最左即关闭吸附
                                ReSlider(
                                    value = (vm.canvasRotationSnapDegrees / RotationSnap.MAX_THRESHOLD_DEGREES).coerceIn(0f, 1f),
                                    onValue = { frac ->
                                        vm.updateCanvasRotationSnapDegrees(frac * RotationSnap.MAX_THRESHOLD_DEGREES)
                                    },
                                )
                            }
                        }

                        SettingsInnerDivider()

                        // 放大插值
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_magnification_interpolation), color = Morandi.text, fontSize = 13.sp)
                            ReSwitch(
                                checked = vm.magnificationInterpolation,
                                onChecked = { vm.updateMagnificationInterpolation(it) },
                            )
                        }

                        SettingsInnerDivider()

                        // 放大显示网格线
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_pixel_grid), color = Morandi.text, fontSize = 13.sp)
                            ReSwitch(
                                checked = vm.pixelGridEnabled,
                                onChecked = { vm.updatePixelGridEnabled(it) },
                            )
                        }

                        SettingsInnerDivider()

                        // 撤销操作提醒
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_undo_toast), color = Morandi.text, fontSize = 13.sp)
                            ReSwitch(
                                checked = vm.undoToastEnabled,
                                onChecked = { vm.updateUndoToastEnabled(it) },
                            )
                        }

                        SettingsInnerDivider()

                        // 笔刷上限跟随画布
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_brush_size_scales_with_canvas_title),
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = stringResource(R.string.settings_brush_size_scales_with_canvas_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            ReSwitch(
                                checked = vm.brushSizeScalesWithCanvas,
                                onChecked = { vm.updateBrushSizeScalesWithCanvas(it) },
                            )
                        }
                    }
                }
            }

            // ---- 2. 快捷键设置 (独立页: 搜索/分类/多按键/方案/冲突) ----
            "SHORTCUTS" -> {
                ShortcutSettingsPage(vm = vm, onBack = { currentSubPage = null })
            }

            // ---- 3. 手势设置 ----
            "GESTURE" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubPageHeader(
                        title = stringResource(R.string.settings_gestures),
                        onBack = { currentSubPage = null },
                    )

                    // 卡片 1: 动作手势映射
                    SettingsCard {
                        // 快捷菜单
                        GestureActionRow(
                            action = GestureAction.QUICK_MENU,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.QUICK_MENU),
                            onClick = { selectingActionForTrigger = GestureAction.QUICK_MENU },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { canvasEditMenuCustomizeOpen = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = Morandi.accent),
                            ) {
                                Text(stringResource(R.string.canvas_edit_customize), fontSize = 12.sp)
                            }
                        }

                        SettingsInnerDivider()

                        // 撤销
                        GestureActionRow(
                            action = GestureAction.UNDO,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.UNDO),
                            onClick = { selectingActionForTrigger = GestureAction.UNDO },
                        )

                        SettingsInnerDivider()

                        // 重做
                        GestureActionRow(
                            action = GestureAction.REDO,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.REDO),
                            onClick = { selectingActionForTrigger = GestureAction.REDO },
                        )

                        SettingsInnerDivider()

                        // 全屏纯净模式
                        GestureActionRow(
                            action = GestureAction.FULLSCREEN,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.FULLSCREEN),
                            onClick = { selectingActionForTrigger = GestureAction.FULLSCREEN },
                        )

                        SettingsInnerDivider()

                        // 适应画布
                        GestureActionRow(
                            action = GestureAction.FIT_CANVAS,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.FIT_CANVAS),
                            onClick = { selectingActionForTrigger = GestureAction.FIT_CANVAS },
                        )


                        SettingsInnerDivider()

                        // 清空当前图层
                        GestureActionRow(
                            action = GestureAction.CLEAR_LAYER,
                            trigger = vm.gestureConfig.getTrigger(GestureAction.CLEAR_LAYER),
                            onClick = { selectingActionForTrigger = GestureAction.CLEAR_LAYER },
                        )

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(
                                onClick = { vm.resetGestureConfigToDefaults() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Morandi.accent),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_refresh),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gesture_reset_defaults), fontSize = 12.sp)
                            }
                        }
                    }

                    // 系统截屏提示
                    if (vm.gestureConfig.getTrigger(GestureAction.QUICK_MENU) == GestureTrigger.THREE_FINGER_SWIPE_DOWN) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.panelHi.copy(alpha = 0.5f))
                                .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.gesture_screenshot_tip),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // 卡片 2: 辅助与画布控制
                    SettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_pen_mode_single_finger_pan_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_pen_mode_single_finger_pan_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.penModeSingleFingerPanEnabled,
                                onChecked = { vm.updatePenModeSingleFingerPan(it) },
                            )
                        }

                        SettingsInnerDivider()

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                stringResource(R.string.settings_back_key_title),
                                color = Morandi.text,
                                fontSize = 13.sp,
                            )
                            Text(
                                stringResource(R.string.settings_back_key_desc),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )

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
                                for (action in BackKeyAction.entries) {
                                    val isSelected = vm.backKeyAction == action
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Morandi.accent else Color.Transparent)
                                            .clickable { vm.updateBackKeyAction(action) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(action.labelRes()),
                                            color = if (isSelected) Morandi.onAccent else Morandi.subText,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_allow_edge_back_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_allow_edge_back_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.allowEdgeBackGesture,
                                onChecked = { vm.updateAllowEdgeBackGesture(it) },
                            )
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_gesture_continuous_undo_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_gesture_continuous_undo_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.gestureContinuousUndo,
                                onChecked = { vm.updateGestureContinuousUndo(it) },
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                            .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_gesture_pen_hint),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            // ---- 4. 颜色设置 ----
            "COLOR" -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubPageHeader(
                        title = stringResource(R.string.settings_color_title),
                        onBack = { currentSubPage = null },
                    )

                    SettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_eyedropper_long_press_title), color = Morandi.text, fontSize = 13.sp)
                                Text(
                                    if (vm.penOnlyMode) stringResource(R.string.settings_eyedropper_long_press_stylus_desc)
                                    else stringResource(R.string.settings_eyedropper_long_press_finger_desc),
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
                            SettingsInnerDivider()

                            val sensitivityLabels = listOf(
                                stringResource(R.string.settings_eyedropper_speed_lowest),
                                stringResource(R.string.settings_eyedropper_speed_slow),
                                stringResource(R.string.settings_eyedropper_speed_normal),
                                stringResource(R.string.settings_eyedropper_speed_fast),
                                stringResource(R.string.settings_eyedropper_speed_highest),
                            )
                            val sensitivityTimes = listOf("600ms", "520ms", "450ms", "380ms", "320ms")
                            val curIdx = (vm.eyedropperSensitivity - 1).coerceIn(0, 4)

                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.settings_eyedropper_sensitivity), color = Morandi.text, fontSize = 13.sp)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Morandi.panel)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.settings_eyedropper_level_summary,
                                                vm.eyedropperSensitivity,
                                                sensitivityLabels[curIdx],
                                                sensitivityTimes[curIdx]
                                            ),
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
                                                stringResource(R.string.settings_eyedropper_level_suffix, i),
                                                color = if (isSelected) Morandi.onAccent else Morandi.subText,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }

                            SettingsInnerDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(stringResource(R.string.settings_eyedropper_offset_title), color = Morandi.text, fontSize = 13.sp)
                                    Text(
                                        stringResource(R.string.settings_eyedropper_offset_desc),
                                        color = Morandi.subText,
                                        fontSize = 11.sp,
                                    )
                                }
                                ReSwitch(
                                    checked = vm.eyedropperOffsetEnabled,
                                    onChecked = { vm.updateEyedropperOffsetEnabled(it) },
                                )
                            }

                            SettingsInnerDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(stringResource(R.string.settings_eyedropper_stylus_in_pen_mode_title), color = Morandi.text, fontSize = 13.sp)
                                    Text(
                                        stringResource(R.string.settings_eyedropper_stylus_in_pen_mode_desc),
                                        color = Morandi.subText,
                                        fontSize = 11.sp,
                                    )
                                }
                                ReSwitch(
                                    checked = vm.penModeStylusEyedropperEnabled,
                                    onChecked = { vm.updatePenModeStylusEyedropperEnabled(it) },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                            .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_eyedropper_footer_desc),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            // ---- 主设置页 ----
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 1. 触控与手写笔
                    SettingsGroupCard(title = stringResource(R.string.settings_group_touch_stylus)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pencil),
                                    contentDescription = null,
                                    tint = Morandi.icon,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.settings_pen_mode), color = Morandi.text, fontSize = 13.sp)
                            }
                            ReSwitch(
                                checked = vm.penOnlyMode,
                                onChecked = { vm.updatePenOnlyMode(it) },
                            )
                        }

                        SettingsInnerDivider()

                        SettingNavRow(
                            title = stringResource(R.string.settings_gestures),
                            icon = R.drawable.ic_hand,
                        ) {
                            currentSubPage = "GESTURE"
                        }

                        SettingsInnerDivider()

                        SettingNavRow(
                            title = stringResource(R.string.settings_stylus),
                            icon = R.drawable.ic_pencil,
                        ) {
                            vm.openMoreSettings("STYLUS")
                            onClose()
                        }
                    }

                    // 2. 显示与色彩
                    SettingsGroupCard(title = stringResource(R.string.settings_group_canvas_display)) {
                        SettingNavRow(
                            title = stringResource(R.string.settings_view_display),
                            icon = R.drawable.ic_canvas_tab,
                        ) {
                            currentSubPage = "VIEW"
                        }

                        SettingsInnerDivider()

                        SettingNavRow(
                            title = stringResource(R.string.settings_color_title),
                            icon = R.drawable.ic_picker,
                        ) {
                            currentSubPage = "COLOR"
                        }
                    }

                    // 3. 效率与辅助
                    SettingsGroupCard(title = stringResource(R.string.settings_group_efficiency)) {
                        SettingNavRow(
                            title = stringResource(R.string.settings_shortcuts_title),
                            icon = R.drawable.ic_grid,
                        ) {
                            currentSubPage = "SHORTCUTS"
                        }

                        SettingsInnerDivider()

                        // 抖动修正与平滑 (Stroke Stabilizer & Smoothing)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_brush),
                                        contentDescription = null,
                                        tint = Morandi.icon,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.settings_stroke_stabilizer), color = Morandi.text, fontSize = 13.sp)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 高级设置展开/折叠按钮
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (smoothingAdvancedExpanded) Morandi.accent.copy(alpha = 0.15f) else Morandi.panel)
                                            .clickable { smoothingAdvancedExpanded = !smoothingAdvancedExpanded }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(R.string.settings_stroke_smoothing_advanced),
                                            color = if (smoothingAdvancedExpanded) Morandi.accent else Morandi.subText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            painter = painterResource(if (smoothingAdvancedExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                                            contentDescription = null,
                                            tint = if (smoothingAdvancedExpanded) Morandi.accent else Morandi.subText,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }

                                    // 当前数值/模式徽标
                                    val badgeText = when (vm.strokeSmoothingType) {
                                        PaintViewModel.SMOOTHING_OFF -> stringResource(R.string.settings_smoothing_mode_off)
                                        PaintViewModel.SMOOTHING_WEIGHTED -> "${vm.strokeSmoothnessDistanceMin.roundToInt()} px"
                                        else -> "${(vm.strokeStabilizer * 100).roundToInt()}%"
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Morandi.panel)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            badgeText,
                                            color = Morandi.accent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Interactive Stabilizer Slider (根据平滑模式自适应映射)
                            val sliderFraction = when (vm.strokeSmoothingType) {
                                PaintViewModel.SMOOTHING_OFF -> 0f
                                PaintViewModel.SMOOTHING_WEIGHTED -> ((vm.strokeSmoothnessDistanceMin - PaintViewModel.SMOOTHING_DISTANCE_MIN) / (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)).toFloat().coerceIn(0f, 1f)
                                else -> vm.strokeStabilizer.coerceIn(0f, 1f)
                            }

                            ReSlider(
                                value = sliderFraction,
                                onValue = { frac ->
                                    when (vm.strokeSmoothingType) {
                                        PaintViewModel.SMOOTHING_WEIGHTED -> {
                                            val dist = PaintViewModel.SMOOTHING_DISTANCE_MIN + frac * (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)
                                            vm.updateStrokeSmoothnessDistanceMin(dist)
                                        }
                                        else -> {
                                            if (vm.strokeSmoothingType == PaintViewModel.SMOOTHING_OFF) {
                                                vm.updateStrokeSmoothingType(PaintViewModel.SMOOTHING_BASIC)
                                            }
                                            vm.updateStrokeStabilizer(frac)
                                        }
                                    }
                                },
                            )

                            // ---- Advanced Smoothing Expandable Section ----
                            AnimatedVisibility(visible = smoothingAdvancedExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panelHi.copy(alpha = 0.5f))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    // 1. 算法模式单选
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val modes = listOf(
                                            PaintViewModel.SMOOTHING_OFF to stringResource(R.string.settings_smoothing_mode_off),
                                            PaintViewModel.SMOOTHING_BASIC to stringResource(R.string.settings_smoothing_mode_basic),
                                            PaintViewModel.SMOOTHING_WEIGHTED to stringResource(R.string.settings_smoothing_mode_weighted),
                                        )
                                        modes.forEach { (modeVal, modeLabel) ->
                                            val selected = vm.strokeSmoothingType == modeVal
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (selected) Morandi.accent else Morandi.panel)
                                                    .clickable { vm.updateStrokeSmoothingType(modeVal) }
                                                    .padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    modeLabel,
                                                    color = if (selected) Morandi.panelHi else Morandi.text,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }

                                    if (vm.strokeSmoothingType == PaintViewModel.SMOOTHING_WEIGHTED) {
                                        // 保持最小与最大距离锁定一致开关
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                stringResource(R.string.settings_smoothing_lock_distance),
                                                color = Morandi.text,
                                                fontSize = 12.sp,
                                            )
                                            ReSwitch(
                                                checked = vm.strokeSmoothDistanceLocked,
                                                onChecked = { vm.updateStrokeSmoothDistanceLocked(it) },
                                            )
                                        }

                                        if (vm.strokeSmoothDistanceLocked) {
                                            // 统一平滑距离滑块
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(stringResource(R.string.settings_smoothing_distance), color = Morandi.subText, fontSize = 11.sp)
                                                    Text("${vm.strokeSmoothnessDistanceMin.roundToInt()} px", color = Morandi.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                ReSlider(
                                                    value = ((vm.strokeSmoothnessDistanceMin - PaintViewModel.SMOOTHING_DISTANCE_MIN) / (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)).toFloat().coerceIn(0f, 1f),
                                                    onValue = { frac ->
                                                        val dist = PaintViewModel.SMOOTHING_DISTANCE_MIN + frac * (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)
                                                        vm.updateStrokeSmoothnessDistanceMin(dist)
                                                    },
                                                )
                                            }
                                        } else {
                                            // 最小距离
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(stringResource(R.string.settings_smoothing_distance_min), color = Morandi.subText, fontSize = 11.sp)
                                                    Text("${vm.strokeSmoothnessDistanceMin.roundToInt()} px", color = Morandi.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                ReSlider(
                                                    value = ((vm.strokeSmoothnessDistanceMin - PaintViewModel.SMOOTHING_DISTANCE_MIN) / (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)).toFloat().coerceIn(0f, 1f),
                                                    onValue = { frac ->
                                                        val dist = PaintViewModel.SMOOTHING_DISTANCE_MIN + frac * (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)
                                                        vm.updateStrokeSmoothnessDistanceMin(dist)
                                                    },
                                                )
                                            }
                                            // 最大距离
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(stringResource(R.string.settings_smoothing_distance_max), color = Morandi.subText, fontSize = 11.sp)
                                                    Text("${vm.strokeSmoothnessDistanceMax.roundToInt()} px", color = Morandi.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                ReSlider(
                                                    value = ((vm.strokeSmoothnessDistanceMax - PaintViewModel.SMOOTHING_DISTANCE_MIN) / (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)).toFloat().coerceIn(0f, 1f),
                                                    onValue = { frac ->
                                                        val dist = PaintViewModel.SMOOTHING_DISTANCE_MIN + frac * (PaintViewModel.SMOOTHING_DISTANCE_MAX - PaintViewModel.SMOOTHING_DISTANCE_MIN)
                                                        vm.updateStrokeSmoothnessDistanceMax(dist)
                                                    },
                                                )
                                            }
                                        }

                                        // 画布缩放自适应
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    stringResource(R.string.settings_smoothing_scalable),
                                                    color = Morandi.text,
                                                    fontSize = 12.sp,
                                                )
                                                Text(
                                                    stringResource(R.string.settings_smoothing_scalable_desc),
                                                    color = Morandi.subText,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                            ReSwitch(
                                                checked = vm.strokeScalableDistance,
                                                onChecked = { vm.updateStrokeScalableDistance(it) },
                                            )
                                        }

                                        // 平滑压感
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    stringResource(R.string.settings_smoothing_pressure),
                                                    color = Morandi.text,
                                                    fontSize = 12.sp,
                                                )
                                                Text(
                                                    stringResource(R.string.settings_smoothing_pressure_desc),
                                                    color = Morandi.subText,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                            ReSwitch(
                                                checked = vm.strokeSmoothPressure,
                                                onChecked = { vm.updateStrokeSmoothPressure(it) },
                                            )
                                        }

                                        // 笔尾收束敏锐度
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(stringResource(R.string.settings_smoothing_tail_aggressiveness), color = Morandi.text, fontSize = 12.sp)
                                                Text("${(vm.strokeTailAggressiveness * 100).roundToInt()}%", color = Morandi.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            }
                                            Text(stringResource(R.string.settings_smoothing_tail_aggressiveness_desc), color = Morandi.subText, fontSize = 10.sp)
                                            Spacer(Modifier.height(4.dp))
                                            ReSlider(
                                                value = vm.strokeTailAggressiveness.toFloat().coerceIn(0f, 1f),
                                                onValue = { frac ->
                                                    vm.updateStrokeTailAggressiveness(frac.toDouble())
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 高级设置
                    SettingsGroupCard(title = stringResource(R.string.settings_group_advanced)) {
                        SettingNavRow(
                            title = stringResource(R.string.settings_more_settings),
                            icon = R.drawable.ic_settings,
                        ) {
                            vm.openMoreSettings("MAIN")
                            onClose()
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
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(title, color = Morandi.text, fontSize = 13.sp)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Morandi.subText.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
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

@Composable
private fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Morandi.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun SettingsInnerDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SubPageHeader(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onBack() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.common_back),
                tint = Morandi.text,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun GestureTriggerPickerDialog(
    action: GestureAction,
    currentTrigger: GestureTrigger,
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val supported = com.reverie.paint.model.GestureConfig.SUPPORTED_TRIGGERS[action] ?: emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(action.labelRes()),
                color = Morandi.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(action.descRes()),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                supported.forEach { trigger ->
                    val isSelected = trigger == currentTrigger
                    val boundAction = if (trigger != GestureTrigger.NONE) {
                        vm.gestureConfig.getActionForTrigger(trigger)?.takeIf { it != action }
                    } else null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Morandi.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                vm.updateGestureTrigger(action, trigger)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                vm.updateGestureTrigger(action, trigger)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Morandi.accent,
                                unselectedColor = Morandi.subText,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(trigger.labelRes()),
                                    color = if (isSelected) Morandi.accent else Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                )
                                if (boundAction != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "(${stringResource(boundAction.labelRes())})",
                                        color = Morandi.subText,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            if (trigger == GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE) {
                                Text(
                                    stringResource(R.string.gesture_trigger_single_finger_touch_slide_desc),
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = Morandi.accent)
            }
        },
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun GestureActionRow(
    action: GestureAction,
    trigger: GestureTrigger,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(action.labelRes()), color = Morandi.text, fontSize = 13.sp)
            Text(stringResource(action.descRes()), color = Morandi.subText, fontSize = 11.sp)
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (trigger == GestureTrigger.NONE) Morandi.panelHi.copy(alpha = 0.5f) else Morandi.accent.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, if (trigger == GestureTrigger.NONE) Morandi.border.copy(alpha = 0.4f) else Morandi.accent.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(trigger.labelRes()),
                    color = if (trigger == GestureTrigger.NONE) Morandi.subText else Morandi.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = if (trigger == GestureTrigger.NONE) Morandi.subText else Morandi.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
