/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.updateQuickShapePreview
import com.reverie.paint.core.cancelQuickShape
import com.reverie.paint.core.commitQuickShape
import com.reverie.paint.core.rotateQuickShapeBy
import com.reverie.paint.core.scaleQuickShapeBy
import com.reverie.paint.core.snapQuickShapeToAngle
import com.reverie.paint.model.QuickShapeConversions
import com.reverie.paint.model.QuickShapeEditHint
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.model.QuickShapeType
import com.reverie.paint.model.quickShapeEditCapabilities
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickShapeTopBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    reshapeHeld: Boolean = false,
    onReshapePointer: (PointerId?) -> Unit = {},
) {
    val shape = vm.activeQuickShape ?: return
    val reshapeCallback by rememberUpdatedState(onReshapePointer)
    DisposableEffect(vm) { onDispose { reshapeCallback(null) } }

    val capabilities = quickShapeEditCapabilities(
        type = shape.type,
        boxHandles = vm.quickShapeBoxHandlesEnabled,
        conversions = vm.quickShapeConversionsEnabled,
        contour = vm.quickShapeContourEnabled,
    )

    val nameRes = when (shape.type) {
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

    val hintRes = when (capabilities.hint) {
        QuickShapeEditHint.LINE -> R.string.quick_shape_hint_line
        QuickShapeEditHint.TRIANGLE -> R.string.quick_shape_hint_triangle
        QuickShapeEditHint.CIRCLE -> R.string.quick_shape_hint_circle
        QuickShapeEditHint.ELLIPSE -> R.string.quick_shape_hint_ellipse
        QuickShapeEditHint.CONTOUR -> R.string.quick_shape_hint_contour
        QuickShapeEditHint.QUAD -> R.string.quick_shape_hint_quad
        QuickShapeEditHint.CURVE -> R.string.quick_shape_curve_edit_hint
        QuickShapeEditHint.ARC -> R.string.quick_shape_arc_edit_hint
        QuickShapeEditHint.BOX -> R.string.quick_shape_box_edit_hint
        QuickShapeEditHint.GENERIC -> R.string.quick_shape_hint_generic
    }

    val actionEnabled = !vm.quickShapeCommitting && !reshapeHeld

    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .background(Morandi.panel.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .glassBorder(RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_shape_bar_title, stringResource(nameRes)),
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(hintRes),
                color = Morandi.subText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 8.dp),
            )
        }

        val showSquareOnly = shape.type == QuickShapeType.RECTANGLE && !capabilities.showConversions
        val hasActions = capabilities.showConversions ||
            capabilities.showCircleEllipse ||
            showSquareOnly ||
            capabilities.showSnap ||
            capabilities.showRotate ||
            capabilities.showScale ||
            capabilities.showContourMode

        val hasConversions = capabilities.showConversions || capabilities.showCircleEllipse || showSquareOnly
        if (hasConversions) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // 形状转换（平行四边形/矩形/正方形）
                if (capabilities.showConversions) {
                    for (target in QuickShapeConversions.Target.entries) {
                        val labelRes = when (target) {
                            QuickShapeConversions.Target.PARALLELOGRAM -> R.string.quick_shape_to_parallelogram
                            QuickShapeConversions.Target.RECTANGLE -> R.string.quick_shape_to_rectangle
                            QuickShapeConversions.Target.SQUARE -> R.string.quick_shape_to_square
                        }
                        QuickShapeChip(
                            label = stringResource(labelRes),
                            enabled = actionEnabled,
                            onClick = {
                                vm.activeQuickShape = QuickShapeConversions.convert(shape, target)
                                vm.updateQuickShapePreview()
                            },
                        )
                    }
                }
                // 圆/椭圆切换
                if (capabilities.showCircleEllipse) {
                    val isCircle = shape.type == QuickShapeType.CIRCLE
                    QuickShapeChip(
                        label = stringResource(
                            if (isCircle) R.string.quick_shape_to_ellipse else R.string.quick_shape_to_circle
                        ),
                        enabled = actionEnabled,
                        onClick = {
                            val radius = (shape.radiusX + shape.radiusY) / 2f
                            vm.activeQuickShape = if (isCircle) {
                                shape.copy(type = QuickShapeType.ELLIPSE)
                            } else {
                                shape.copy(type = QuickShapeType.CIRCLE, radiusX = radius, radiusY = radius)
                            }
                            vm.updateQuickShapePreview()
                        },
                    )
                }
                // 矩形独立转正方
                if (showSquareOnly) {
                    QuickShapeChip(
                        label = stringResource(R.string.quick_shape_to_square),
                        enabled = actionEnabled,
                        onClick = {
                            val half = maxOf(shape.radiusX, shape.radiusY)
                            vm.activeQuickShape = shape.copy(radiusX = half, radiusY = half)
                            vm.updateQuickShapePreview()
                        },
                    )
                }
            }
        }

        // 朝向吸附大类（水平 / 垂直 / 45°）
        if (capabilities.showSnap) {
            Spacer(Modifier.height(5.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                QuickShapeChip(
                    label = stringResource(R.string.quick_shape_snap_horizontal),
                    enabled = actionEnabled,
                    onClick = { vm.snapQuickShapeToAngle(0f) },
                )
                QuickShapeChip(
                    label = stringResource(R.string.quick_shape_snap_vertical),
                    enabled = actionEnabled,
                    onClick = { vm.snapQuickShapeToAngle(90f) },
                )
                QuickShapeChip(
                    label = stringResource(R.string.quick_shape_snap_diagonal),
                    enabled = actionEnabled,
                    onClick = { vm.snapQuickShapeToAngle(45f) },
                )
            }
        }

        // 几何微调大类（旋转 ↺ 15° / ↻ 15°，缩放 － / ＋）
        val hasTransformTweak = capabilities.showRotate || capabilities.showScale
        if (hasTransformTweak) {
            Spacer(Modifier.height(5.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (capabilities.showRotate) {
                    QuickShapeChip(
                        label = stringResource(R.string.quick_shape_rotate_ccw),
                        enabled = actionEnabled,
                        onClick = { vm.rotateQuickShapeBy(-15f) },
                    )
                    QuickShapeChip(
                        label = stringResource(R.string.quick_shape_rotate_cw),
                        enabled = actionEnabled,
                        onClick = { vm.rotateQuickShapeBy(15f) },
                    )
                }
                if (capabilities.showScale) {
                    QuickShapeChip(
                        label = stringResource(R.string.quick_shape_scale_down),
                        enabled = actionEnabled,
                        onClick = { vm.scaleQuickShapeBy(0.8f) },
                    )
                    QuickShapeChip(
                        label = stringResource(R.string.quick_shape_scale_up),
                        enabled = actionEnabled,
                        onClick = { vm.scaleQuickShapeBy(1.25f) },
                    )
                }
            }
        }

        // 轮廓编辑大类（仅 QUAD/CONTOUR 显示，矩形排除）
        if (capabilities.showContourMode) {
            Spacer(Modifier.height(5.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val curved = shape.contourCurved
                QuickShapeChip(
                    label = stringResource(R.string.quick_shape_contour_straight),
                    enabled = actionEnabled,
                    selected = !curved,
                    onClick = {
                        vm.updateQuickShapeCurvedContourEnabled(false)
                        vm.activeQuickShape = QuickShapeGeometry.setContourCurved(shape, false)
                        vm.updateQuickShapePreview()
                    },
                )
                QuickShapeChip(
                    label = stringResource(R.string.quick_shape_contour_curved),
                    enabled = actionEnabled,
                    selected = curved,
                    onClick = {
                        vm.updateQuickShapeCurvedContourEnabled(true)
                        vm.activeQuickShape = QuickShapeGeometry.setContourCurved(shape, true)
                        vm.updateQuickShapePreview()
                    },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (capabilities.showHoldReshape) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.End
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (capabilities.showHoldReshape) {
                val holdLabel = stringResource(R.string.quick_shape_hold_contour)
                val holdEnabled = !vm.quickShapeCommitting
                QuickShapeHoldChip(
                    label = holdLabel,
                    held = reshapeHeld,
                    enabled = holdEnabled,
                    onPointer = reshapeCallback,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickShapeActionButton(
                    text = stringResource(R.string.quick_shape_keep_freehand),
                    primary = false,
                    enabled = !vm.quickShapeCommitting,
                    onClick = vm::cancelQuickShape,
                )
                QuickShapeActionButton(
                    text = stringResource(R.string.common_done),
                    primary = true,
                    enabled = !vm.quickShapeCommitting,
                    onClick = vm::commitQuickShape,
                )
            }
        }
    }
}

@Composable
private fun QuickShapeChip(
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (selected) Morandi.accent else Morandi.panelHi
    val fg = if (selected) Morandi.onAccent else Morandi.text
    Box(
        modifier = Modifier
            .height(30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun QuickShapeActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (primary) Morandi.accent else Morandi.panelHi
    val fg = if (primary) Morandi.onAccent else Morandi.text
    Box(
        modifier = Modifier
            .height(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun QuickShapeHoldChip(
    label: String,
    held: Boolean,
    enabled: Boolean,
    onPointer: (PointerId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (held) Morandi.accent else Morandi.panelHi
    val fg = if (held) Morandi.onAccent else Morandi.text
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .height(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            // 追踪第二指按压与抬起状态，配合控制点拖拽实现双指变形
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (enabled) onPointer(down.id)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            pointer?.consume()
                        } while (pointer != null && pointer.pressed)
                    } finally {
                        onPointer(null)
                    }
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (held) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
