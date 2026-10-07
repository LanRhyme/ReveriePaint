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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    var recordingShortcut by remember { mutableStateOf<ShortcutDefinition?>(null) }
    var smoothingAdvancedExpanded by remember { mutableStateOf(false) }
    var canvasEditMenuCustomizeOpen by remember { mutableStateOf(false) }

    if (canvasEditMenuCustomizeOpen) {
        CanvasEditMenuCustomizeDialog(vm, onDismiss = { canvasEditMenuCustomizeOpen = false })
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

            // ---- 2. 快捷键设置 ----
            "SHORTCUTS" -> {
                var activeCategory by remember { mutableStateOf(ShortcutCategory.PAINTING) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubPageHeader(
                        title = stringResource(R.string.settings_shortcuts_title),
                        onBack = { currentSubPage = null },
                        action = {
                            Text(
                                stringResource(R.string.settings_shortcut_reset),
                                color = Morandi.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Morandi.panelHi)
                                    .clickable { vm.resetShortcuts() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    )

                    // 4 Category Tabs in capsule bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.6f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val tabs = listOf(
                            ShortcutCategory.PAINTING to R.drawable.ic_brush,
                            ShortcutCategory.TOOLS to R.drawable.ic_grid,
                            ShortcutCategory.FILTERS to R.drawable.ic_magicwand,
                            ShortcutCategory.LAYERS to R.drawable.ic_layers,
                        )
                        tabs.forEach { (cat, iconRes) ->
                            val isSel = activeCategory == cat
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Morandi.panel else Color.Transparent)
                                    .then(
                                        if (isSel) Modifier.border(1.dp, Morandi.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        else Modifier
                                    )
                                    .clickable { activeCategory = cat }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = stringResource(cat.titleRes),
                                    tint = if (isSel) Morandi.accent else Morandi.subText,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(cat.titleRes),
                                    color = if (isSel) Morandi.text else Morandi.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    // Shortcuts List Card
                    val items = ALL_SHORTCUT_DEFINITIONS.filter { it.category == activeCategory }
                    val noneStr = stringResource(R.string.shortcut_none)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.55f))
                            .border(1.dp, Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        items.forEachIndexed { idx, def ->
                            val currentKey = vm.getShortcutKey(def.id)
                            val isNone = currentKey == "无" || currentKey == "None"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { recordingShortcut = def }
                                    .padding(horizontal = 4.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (def.nameRes != null) stringResource(def.nameRes) else def.name,
                                    color = Morandi.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panel)
                                        .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        if (isNone) noneStr else currentKey,
                                        color = if (isNone) Morandi.subText else Morandi.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (idx < items.lastIndex) {
                                SettingsInnerDivider()
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubPageHeader(
                        title = stringResource(R.string.settings_gestures),
                        onBack = { currentSubPage = null },
                    )

                    SettingsCard {
                        QuickShapeSettingRow(vm)
                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_two_finger_undo_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_two_finger_undo_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.gestureTwoFingerUndo,
                                onChecked = { vm.updateGestureTwoFingerUndo(it) },
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
                                Text(stringResource(R.string.settings_three_finger_redo_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_three_finger_redo_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.gestureThreeFingerRedo,
                                onChecked = { vm.updateGestureThreeFingerRedo(it) },
                            )
                        }

                        SettingsInnerDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.settings_three_finger_edit_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_three_finger_edit_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.gestureThreeFingerEditMenu,
                                onChecked = { vm.updateGestureThreeFingerEditMenu(it) },
                            )
                        }
                        TextButton(
                            onClick = { canvasEditMenuCustomizeOpen = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Morandi.accent),
                        ) {
                            Text(stringResource(R.string.canvas_edit_customize))
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
                                Text(stringResource(R.string.settings_quick_pinch_fit_title), color = Morandi.text, fontSize = 13.sp)
                                Text(stringResource(R.string.settings_quick_pinch_fit_desc), color = Morandi.subText, fontSize = 11.sp)
                            }
                            ReSwitch(
                                checked = vm.gestureQuickPinchFit,
                                onChecked = { vm.updateGestureQuickPinchFit(it) },
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
                                            color = if (isSelected) Color.White else Morandi.subText,
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
                                                color = if (isSelected) Color.White else Morandi.subText,
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

                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .pointerInput(vm.strokeSmoothingType) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val w = size.width.toFloat()
                                            if (w <= 0f) return@awaitEachGesture

                                            val thumbRadiusPx = 8.dp.toPx()
                                            val usableWidthPx = (w - thumbRadiusPx * 2).coerceAtLeast(1f)
                                            val touchSlop = viewConfiguration.touchSlop
                                            var isDragging = false
                                            var isScrollingVertically = false

                                            fun updateFromX(x: Float) {
                                                val frac = ((x - thumbRadiusPx) / usableWidthPx).coerceIn(0f, 1f)
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
                                            }

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                if (!change.pressed) {
                                                    if (!isScrollingVertically && !isDragging) {
                                                        updateFromX(change.position.x)
                                                    }
                                                    break
                                                }

                                                if (isScrollingVertically) break

                                                val dx = change.position.x - down.position.x
                                                val dy = change.position.y - down.position.y
                                                val absDx = kotlin.math.abs(dx)
                                                val absDy = kotlin.math.abs(dy)

                                                if (!isDragging) {
                                                    if (absDy > touchSlop && absDy > absDx) {
                                                        isScrollingVertically = true
                                                        break
                                                    } else if (absDx > touchSlop && absDx >= absDy) {
                                                        isDragging = true
                                                    }
                                                }

                                                if (isDragging) {
                                                    change.consume()
                                                    updateFromX(change.position.x)
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                val trackWidth = maxWidth
                                val thumbSize = 16.dp
                                val maxTravel = (trackWidth - thumbSize).coerceAtLeast(0.dp)
                                val thumbOffset = maxTravel * sliderFraction
                                val activeTrackWidth = if (sliderFraction <= 0f) {
                                    0.dp
                                } else {
                                    (thumbOffset + thumbSize / 2).coerceAtMost(trackWidth)
                                }

                                // Track
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Morandi.panel),
                                )
                                // Active Track
                                if (activeTrackWidth > 0.dp) {
                                    Box(
                                        modifier = Modifier
                                            .width(activeTrackWidth)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Morandi.accent),
                                    )
                                }
                                // Thumb
                                Box(
                                    modifier = Modifier
                                        .padding(start = thumbOffset)
                                        .size(thumbSize)
                                        .shadow(2.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(Morandi.text),
                                )
                            }

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

    // ---- Key Recording Dialog ----
    recordingShortcut?.let { def ->
        androidx.compose.runtime.DisposableEffect(Unit) {
            vm.isShortcutRecordingActive = true
            onDispose {
                vm.isShortcutRecordingActive = false
            }
        }
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
                        stringResource(R.string.settings_shortcut_dialog_title),
                        color = Morandi.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (def.nameRes != null) stringResource(def.nameRes) else def.name,
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    val isNone = recordedKey.isBlank() || recordedKey == "无" || recordedKey == "None"
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
                            if (isNone) stringResource(R.string.settings_shortcut_press_key) else recordedKey,
                            color = if (isNone) Morandi.subText else Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.settings_shortcut_set_none),
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
                                stringResource(R.string.common_cancel),
                                color = Morandi.subText,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { recordingShortcut = null }
                                    .padding(8.dp),
                            )
                            Text(
                                stringResource(R.string.common_save),
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
