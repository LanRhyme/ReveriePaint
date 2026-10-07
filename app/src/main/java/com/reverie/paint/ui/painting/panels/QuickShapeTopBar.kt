/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.cancelQuickShape
import com.reverie.paint.core.commitQuickShape
import com.reverie.paint.core.rotateQuickShapeBy
import com.reverie.paint.core.scaleQuickShapeBy
import com.reverie.paint.core.snapQuickShapeToAngle
import com.reverie.paint.model.QuickShapeConversions
import com.reverie.paint.model.QuickShapeType
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun QuickShapeSettingRow(vm: PaintViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickShapeOption(R.string.quick_shape_setting, R.string.quick_shape_setting_hint,
            vm.quickShapeEnabled, vm::updateQuickShapeEnabled)
        if (vm.quickShapeEnabled) {
            QuickShapeOption(R.string.quick_shape_quad_setting, R.string.quick_shape_quad_hint,
                vm.quickShapeQuadrilateralEnabled, vm::updateQuickShapeQuadrilateralEnabled)
            QuickShapeOption(R.string.quick_shape_conversions, R.string.quick_shape_conversions_hint,
                vm.quickShapeConversionsEnabled, vm::updateQuickShapeConversionsEnabled)
            QuickShapeOption(R.string.quick_shape_curve_setting, R.string.quick_shape_curve_hint,
                vm.quickShapeCurveEnabled, vm::updateQuickShapeCurveEnabled)
            QuickShapeOption(R.string.quick_shape_relaxed, R.string.quick_shape_relaxed_hint,
                vm.quickShapeRelaxedEnabled, vm::updateQuickShapeRelaxedEnabled)
            QuickShapeOption(R.string.quick_shape_angle_snap, R.string.quick_shape_angle_snap_hint,
                vm.quickShapeAngleSnapEnabled, vm::updateQuickShapeAngleSnapEnabled)
            QuickShapeOption(R.string.quick_shape_per_point_pressure, R.string.quick_shape_per_point_pressure_hint,
                vm.quickShapePerPointPressureEnabled, vm::updateQuickShapePerPointPressureEnabled)
            QuickShapeOption(R.string.quick_shape_arc_setting, R.string.quick_shape_arc_setting_hint,
                vm.quickShapeArcEnabled, vm::updateQuickShapeArcEnabled)
            QuickShapeOption(R.string.quick_shape_box_handles, R.string.quick_shape_box_handles_hint,
                vm.quickShapeBoxHandlesEnabled, vm::updateQuickShapeBoxHandlesEnabled)
            if (vm.quickShapeBoxHandlesEnabled) {
                QuickShapeOption(R.string.quick_shape_contour_setting, R.string.quick_shape_contour_setting_hint,
                    vm.quickShapeContourEnabled, vm::updateQuickShapeContourEnabled)
                if (vm.quickShapeContourEnabled) {
                    QuickShapeOption(R.string.quick_shape_curved, R.string.quick_shape_curved_hint,
                        vm.quickShapeCurvedContourEnabled, vm::updateQuickShapeCurvedContourEnabled)
                }
            }
        }
    }
}

@Composable
private fun QuickShapeOption(title: Int, hint: Int, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(title), color = Morandi.text, fontSize = 13.sp)
            Text(stringResource(hint), color = Morandi.subText, fontSize = 11.sp)
        }
        ReSwitch(checked = enabled, onChecked = onChanged)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickShapeTopBar(vm: PaintViewModel, modifier: Modifier = Modifier,
                     reshapeHeld: Boolean = false, onReshapePointer: (PointerId?) -> Unit = {}) {
    val shape = vm.activeQuickShape ?: return
    val reshapeCallback by rememberUpdatedState(onReshapePointer)
    DisposableEffect(vm) { onDispose { reshapeCallback(null) } }
    val boxShape = shape.type == QuickShapeType.RECTANGLE || shape.type == QuickShapeType.CONTOUR ||
        shape.type == QuickShapeType.QUADRILATERAL
    val name = when (shape.type) {
        QuickShapeType.LINE -> R.string.quick_shape_line
        QuickShapeType.QUADRILATERAL -> R.string.quick_shape_quad
        QuickShapeType.CURVE -> R.string.quick_shape_curve
        QuickShapeType.ARC -> R.string.quick_shape_arc
        QuickShapeType.CONTOUR -> R.string.quick_shape_contour
        QuickShapeType.CIRCLE -> R.string.quick_shape_circle
        QuickShapeType.ELLIPSE -> R.string.quick_shape_ellipse
        QuickShapeType.RECTANGLE -> R.string.quick_shape_rectangle
        else -> R.string.quick_shape_triangle
    }
    Column(modifier.widthIn(max = 480.dp).background(Morandi.panel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(stringResource(R.string.quick_shape_title, stringResource(name)), color = Morandi.text, fontSize = 14.sp)
        Text(stringResource(if (shape.type == QuickShapeType.CURVE) R.string.quick_shape_curve_edit_hint
            else if (!vm.quickShapeBoxHandlesEnabled && (shape.type == QuickShapeType.QUADRILATERAL ||
                shape.type == QuickShapeType.CONTOUR)) R.string.quick_shape_quad_edit_hint
            else if (shape.type == QuickShapeType.ARC) R.string.quick_shape_arc_edit_hint
            else if (vm.quickShapeBoxHandlesEnabled && boxShape) R.string.quick_shape_box_edit_hint
            else R.string.quick_shape_edit_hint), color = Morandi.subText, fontSize = 12.sp)
        if (vm.quickShapeBoxHandlesEnabled && vm.quickShapeContourEnabled && boxShape) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val curved = if (shape.type == QuickShapeType.CONTOUR) shape.contourCurved
                    else vm.quickShapeCurvedContourEnabled
                for (mode in listOf(false, true)) {
                    TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld, onClick = {
                        vm.updateQuickShapeCurvedContourEnabled(mode)
                        vm.activeQuickShape = QuickShapeGeometry.setContourCurved(shape, mode)
                    }) {
                        Text(stringResource(if (mode) R.string.quick_shape_contour_curves
                            else R.string.quick_shape_contour_lines),
                            color = if (mode == curved) Morandi.accent else Morandi.subText)
                    }
                }
            }
            val label = stringResource(R.string.quick_shape_hold_contour)
            Box(Modifier.semantics { contentDescription = label }
                .background(if (reshapeHeld) Morandi.accent else Morandi.panel, RoundedCornerShape(8.dp))
                .pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (!vm.quickShapeCommitting) reshapeCallback(down.id)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                pointer?.consume()
                            } while (pointer != null && pointer.pressed)
                        } finally { reshapeCallback(null) }
                    }
                }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(label, color = Morandi.text, fontSize = 13.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (boxShape && vm.quickShapeConversionsEnabled) {
                for (target in QuickShapeConversions.Target.entries) {
                    val label = when (target) {
                        QuickShapeConversions.Target.PARALLELOGRAM -> R.string.quick_shape_to_parallelogram
                        QuickShapeConversions.Target.RECTANGLE -> R.string.quick_shape_to_rectangle
                        QuickShapeConversions.Target.SQUARE -> R.string.quick_shape_to_square
                    }
                    TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld, onClick = {
                        vm.activeQuickShape = QuickShapeConversions.convert(shape, target)
                    }) { Text(stringResource(label), color = Morandi.accent) }
                }
            }
            when (shape.type) {
                QuickShapeType.CIRCLE, QuickShapeType.ELLIPSE -> {
                    val circle = shape.type == QuickShapeType.CIRCLE
                    TextButton(enabled = !vm.quickShapeCommitting, onClick = {
                        val radius = (shape.radiusX + shape.radiusY) / 2f
                        vm.activeQuickShape = if (circle) shape.copy(type = QuickShapeType.ELLIPSE)
                        else shape.copy(type = QuickShapeType.CIRCLE, radiusX = radius, radiusY = radius)
                    }) {
                        Text(stringResource(if (circle) R.string.quick_shape_to_ellipse else R.string.quick_shape_to_circle),
                            color = Morandi.accent)
                    }
                }
                QuickShapeType.RECTANGLE -> if (!vm.quickShapeConversionsEnabled) TextButton(
                    enabled = !vm.quickShapeCommitting && !reshapeHeld, onClick = {
                    val half = maxOf(shape.radiusX, shape.radiusY)
                    vm.activeQuickShape = shape.copy(radiusX = half, radiusY = half)
                }) { Text(stringResource(R.string.quick_shape_to_square), color = Morandi.accent) }
                else -> Unit
            }
            // 精确摆正: 直线/矩形/四边形/轮廓。不受识别期吸附容差限制, 用户点名要哪个角度就摆到哪个角度
            if (shape.type == QuickShapeType.LINE || shape.type == QuickShapeType.RECTANGLE ||
                shape.type == QuickShapeType.QUADRILATERAL || shape.type == QuickShapeType.CONTOUR
            ) {
                for (deg in listOf(0f, 90f, 45f)) {
                    TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld,
                        onClick = { vm.snapQuickShapeToAngle(deg) }) {
                        Text(
                            stringResource(
                                when (deg) {
                                    0f -> R.string.quick_shape_snap_horizontal
                                    90f -> R.string.quick_shape_snap_vertical
                                    else -> R.string.quick_shape_snap_diagonal
                                },
                            ),
                            color = Morandi.accent,
                        )
                    }
                }
            }
            // 旋转 / 缩放: 闭合形状
            val closedShape = shape.type == QuickShapeType.RECTANGLE || shape.type == QuickShapeType.CIRCLE ||
                shape.type == QuickShapeType.ELLIPSE || shape.type == QuickShapeType.QUADRILATERAL ||
                shape.type == QuickShapeType.CONTOUR
            if (closedShape) {
                TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld,
                    onClick = { vm.rotateQuickShapeBy(-15f) }) {
                    Text(stringResource(R.string.quick_shape_rotate_ccw), color = Morandi.accent)
                }
                TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld,
                    onClick = { vm.rotateQuickShapeBy(15f) }) {
                    Text(stringResource(R.string.quick_shape_rotate_cw), color = Morandi.accent)
                }
                TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld,
                    onClick = { vm.scaleQuickShapeBy(0.8f) }) {
                    Text(stringResource(R.string.quick_shape_scale_down), color = Morandi.accent)
                }
                TextButton(enabled = !vm.quickShapeCommitting && !reshapeHeld,
                    onClick = { vm.scaleQuickShapeBy(1.25f) }) {
                    Text(stringResource(R.string.quick_shape_scale_up), color = Morandi.accent)
                }
            }
            TextButton(enabled = !vm.quickShapeCommitting, onClick = vm::commitQuickShape) {
                Text(stringResource(R.string.common_done), color = Morandi.accent)
            }
            TextButton(enabled = !vm.quickShapeCommitting, onClick = vm::cancelQuickShape) {
                Text(stringResource(R.string.quick_shape_keep_freehand), color = Morandi.text)
            }
        }
    }
}
