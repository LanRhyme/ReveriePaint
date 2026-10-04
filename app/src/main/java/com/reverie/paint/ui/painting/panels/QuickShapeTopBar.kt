/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.cancelQuickShape
import com.reverie.paint.core.commitQuickShape
import com.reverie.paint.model.QuickShapeType
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun QuickShapeSettingRow(vm: PaintViewModel) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(R.string.quick_shape_setting), color = Morandi.text, fontSize = 13.sp)
            Text(stringResource(R.string.quick_shape_setting_hint), color = Morandi.subText, fontSize = 11.sp)
        }
        ReSwitch(checked = vm.quickShapeEnabled, onChecked = vm::updateQuickShapeEnabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickShapeTopBar(vm: PaintViewModel, modifier: Modifier = Modifier) {
    val shape = vm.activeQuickShape ?: return
    val name = when (shape.type) {
        QuickShapeType.LINE -> R.string.quick_shape_line
        QuickShapeType.CIRCLE -> R.string.quick_shape_circle
        QuickShapeType.ELLIPSE -> R.string.quick_shape_ellipse
        QuickShapeType.RECTANGLE -> R.string.quick_shape_rectangle
        else -> R.string.quick_shape_triangle
    }
    Column(modifier.widthIn(max = 480.dp).background(Morandi.panel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(stringResource(R.string.quick_shape_title, stringResource(name)), color = Morandi.text, fontSize = 14.sp)
        Text(stringResource(R.string.quick_shape_edit_hint), color = Morandi.subText, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                QuickShapeType.RECTANGLE -> TextButton(enabled = !vm.quickShapeCommitting, onClick = {
                    val half = maxOf(shape.radiusX, shape.radiusY)
                    vm.activeQuickShape = shape.copy(radiusX = half, radiusY = half)
                }) { Text(stringResource(R.string.quick_shape_to_square), color = Morandi.accent) }
                else -> Unit
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
