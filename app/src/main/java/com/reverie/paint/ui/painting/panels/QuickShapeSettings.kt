/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt
import com.reverie.paint.core.updateQuickShapeAngleSnapEnabled
import com.reverie.paint.core.updateQuickShapeArcEnabled
import com.reverie.paint.core.updateQuickShapeBoxHandlesEnabled
import com.reverie.paint.core.updateQuickShapeContourEnabled
import com.reverie.paint.core.updateQuickShapeCurveEnabled
import com.reverie.paint.core.updateQuickShapeEnabled
import com.reverie.paint.core.updateQuickShapeHoldDelayMs
import com.reverie.paint.core.updateQuickShapePerPointPressureEnabled
import com.reverie.paint.core.updateQuickShapeQuadrilateralEnabled
import com.reverie.paint.core.updateQuickShapeRelaxedEnabled

/**
 * 速创形状 Tab 内容 (作为绘图辅助中心的二级独立分栏，平铺一级呈现并支持内部卡片独立折叠)
 */
@Composable
fun QuickShapeTabContent(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 第一层：总开关与停顿延迟卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.border.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickShapeOption(
                title = R.string.quick_shape_setting,
                hint = R.string.quick_shape_settings_master_hint,
                enabled = vm.quickShapeEnabled,
                onChanged = vm::updateQuickShapeEnabled,
            )

            if (vm.quickShapeEnabled) {
                QuickShapeHoldDelaySlider(
                    delayMs = vm.quickShapeHoldDelayMs,
                    onDelayChange = vm::updateQuickShapeHoldDelayMs,
                )
            }
        }

        // 第二层：图元识别分类折叠卡片
        if (vm.quickShapeEnabled) {
            QuickShapeFoldableSection(
                title = R.string.quick_shape_group_recognition,
                initialExpanded = true,
            ) {
                QuickShapeOption(
                    title = R.string.quick_shape_quad_setting,
                    hint = R.string.quick_shape_quad_hint,
                    enabled = vm.quickShapeQuadrilateralEnabled,
                    onChanged = vm::updateQuickShapeQuadrilateralEnabled,
                )
                QuickShapeOption(
                    title = R.string.quick_shape_curve_setting,
                    hint = R.string.quick_shape_curve_hint,
                    enabled = vm.quickShapeCurveEnabled,
                    onChanged = vm::updateQuickShapeCurveEnabled,
                )
                QuickShapeOption(
                    title = R.string.quick_shape_arc_setting,
                    hint = R.string.quick_shape_arc_setting_hint,
                    enabled = vm.quickShapeArcEnabled,
                    onChanged = vm::updateQuickShapeArcEnabled,
                )
                QuickShapeOption(
                    title = R.string.quick_shape_relaxed,
                    hint = R.string.quick_shape_settings_relaxed_hint,
                    enabled = vm.quickShapeRelaxedEnabled,
                    onChanged = vm::updateQuickShapeRelaxedEnabled,
                )
            }

            // 第三层：编辑手柄与对齐分类折叠卡片
            QuickShapeFoldableSection(
                title = R.string.quick_shape_group_edit_handles,
                initialExpanded = false,
            ) {
                QuickShapeOption(
                    title = R.string.quick_shape_angle_snap,
                    hint = R.string.quick_shape_angle_snap_hint,
                    enabled = vm.quickShapeAngleSnapEnabled,
                    onChanged = vm::updateQuickShapeAngleSnapEnabled,
                )
                QuickShapeOption(
                    title = R.string.quick_shape_per_point_pressure,
                    hint = R.string.quick_shape_per_point_pressure_hint,
                    enabled = vm.quickShapePerPointPressureEnabled,
                    onChanged = vm::updateQuickShapePerPointPressureEnabled,
                )
                QuickShapeOption(
                    title = R.string.quick_shape_box_handles,
                    hint = R.string.quick_shape_settings_box_handles_hint,
                    enabled = vm.quickShapeBoxHandlesEnabled,
                    onChanged = vm::updateQuickShapeBoxHandlesEnabled,
                )
                if (vm.quickShapeBoxHandlesEnabled) {
                    Box(modifier = Modifier.padding(start = 14.dp)) {
                        QuickShapeOption(
                            title = R.string.quick_shape_contour_setting,
                            hint = R.string.quick_shape_settings_contour_hint,
                            enabled = vm.quickShapeContourEnabled,
                            onChanged = vm::updateQuickShapeContourEnabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分类独立折叠卡片
 */
@Composable
private fun QuickShapeFoldableSection(
    title: Int,
    initialExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initialExpanded) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.border.copy(alpha = 0.14f))
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(title),
                color = Morandi.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = Morandi.subText,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(if (isExpanded) 180f else 0f),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * 兼容旧抽屉组件调用点
 */
@Composable
fun QuickShapeCollapsibleDrawer(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    QuickShapeTabContent(vm = vm, modifier = modifier)
}

/**
 * 兼容旧行组件调用点
 */
@Composable
internal fun QuickShapeSettingRow(vm: PaintViewModel) {
    QuickShapeCollapsibleDrawer(vm)
}

@Composable
private fun QuickShapeOption(
    title: Int,
    hint: Int,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(stringResource(title), color = Morandi.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(hint), color = Morandi.subText, fontSize = 10.5.sp, lineHeight = 13.sp)
        }
        ReSwitch(checked = enabled, onChecked = onChanged)
    }
}

@Composable
private fun QuickShapeHoldDelaySlider(
    delayMs: Int,
    onDelayChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.border.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_shape_hold_delay),
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.quick_shape_hold_delay_ms, delayMs),
                color = Morandi.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ReSlider(
            value = delayMs.toFloat(),
            onValue = { raw ->
                val stepped = ((raw / 50f).roundToInt() * 50).coerceIn(150, 1200)
                onDelayChange(stepped)
            },
            valueRange = 150f..1200f,
            height = 18,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.quick_shape_hold_delay_hint),
            color = Morandi.subText,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
        )
    }
}
