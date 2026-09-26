/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.ShapeFillMode
import com.reverie.paint.model.ShapeNode
import com.reverie.paint.model.ShapeType
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

@Composable
private fun getShapeTypeOptions(): List<ToolDropdownItemData<ShapeType>> = listOf(
    ToolDropdownItemData(ShapeType.LINE, R.drawable.ic_minus, androidx.compose.ui.res.stringResource(R.string.shape_type_line)),
    ToolDropdownItemData(ShapeType.RECT, R.drawable.ic_rect, androidx.compose.ui.res.stringResource(R.string.shape_type_rect)),
    ToolDropdownItemData(ShapeType.ROUNDED_RECT, R.drawable.ic_rect, androidx.compose.ui.res.stringResource(R.string.shape_type_round_rect)),
    ToolDropdownItemData(ShapeType.ELLIPSE, R.drawable.ic_ellipse, androidx.compose.ui.res.stringResource(R.string.shape_type_ellipse)),
    ToolDropdownItemData(ShapeType.REGULAR_POLYGON, R.drawable.ic_triangle, androidx.compose.ui.res.stringResource(R.string.shape_type_regular_poly)),
    ToolDropdownItemData(ShapeType.STAR, R.drawable.ic_star, androidx.compose.ui.res.stringResource(R.string.shape_type_star)),
    ToolDropdownItemData(ShapeType.POLYLINE, R.drawable.ic_line, androidx.compose.ui.res.stringResource(R.string.shape_type_polyline)),
    ToolDropdownItemData(ShapeType.POLYGON, R.drawable.ic_polyline, androidx.compose.ui.res.stringResource(R.string.shape_type_polygon)),
    ToolDropdownItemData(ShapeType.BEZIER, R.drawable.ic_copy, androidx.compose.ui.res.stringResource(R.string.shape_type_bezier)),
)

@Composable
private fun getShapeFillOptions(): List<ToolDropdownItemData<ShapeFillMode>> = listOf(
    ToolDropdownItemData(ShapeFillMode.STROKE, R.drawable.ic_shape_stroke, androidx.compose.ui.res.stringResource(R.string.shape_style_stroke)),
    ToolDropdownItemData(ShapeFillMode.FILL, R.drawable.ic_shape_fill, androidx.compose.ui.res.stringResource(R.string.shape_style_fill)),
)

/**
 * 形状工具浮窗属性面板
 */
@Composable
fun ShapeToolPanel(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
) {
    val state = vm.shapeState
    val currentType = state.type
    val fillMode = state.fillMode
    val strokeWidth = state.strokeWidth
    var propsOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val hasStrokeSlider = fillMode != ShapeFillMode.FILL || currentType == ShapeType.LINE || currentType == ShapeType.POLYLINE
    val hasShapeSliders = currentType == ShapeType.ROUNDED_RECT ||
        currentType == ShapeType.REGULAR_POLYGON ||
        currentType == ShapeType.STAR
    val hasProps = hasStrokeSlider || hasShapeSliders

    val shapeTypeOptions = getShapeTypeOptions()
    val shapeFillOptions = getShapeFillOptions()

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. 形状类型下拉
                ToolBubbleDropdown(
                    items = shapeTypeOptions,
                    selected = currentType,
                    onSelect = { newType ->
                        val oldType = state.type
                        state.type = newType
                        if (newType == ShapeType.POLYGON) {
                            state.closed = true
                        }
                        if (state.active) {
                            val wasMulti = oldType == ShapeType.POLYLINE || oldType == ShapeType.POLYGON || oldType == ShapeType.BEZIER
                            val isMulti = newType == ShapeType.POLYLINE || newType == ShapeType.POLYGON || newType == ShapeType.BEZIER
                            if (!wasMulti && isMulti) {
                                state.nodes.clear()
                                state.nodes.add(ShapeNode(Point2D(state.p1.x, state.p1.y)))
                                state.nodes.add(ShapeNode(Point2D(state.p2.x, state.p2.y)))
                                state.selectedNodeIndex = 1
                            } else if (wasMulti && !isMulti) {
                                if (state.nodes.isNotEmpty()) {
                                    state.p1 = androidx.compose.ui.geometry.Offset(state.nodes.first().pos.x, state.nodes.first().pos.y)
                                    state.p2 = if (state.nodes.size >= 2) androidx.compose.ui.geometry.Offset(state.nodes.last().pos.x, state.nodes.last().pos.y) else state.p1
                                }
                            }
                        }
                    },
                )

                // 2. 样式填充模式下拉 (线段类图元除外)
                if (currentType != ShapeType.LINE && currentType != ShapeType.POLYLINE) {
                    ToolBubbleDropdown(
                        items = shapeFillOptions,
                        selected = if (fillMode == ShapeFillMode.FILL) ShapeFillMode.FILL else ShapeFillMode.STROKE,
                        onSelect = { newFill ->
                            state.fillMode = newFill
                            vm.updateShapeFillMode(newFill.id)
                        },
                    )
                }

                // 3. 各形状专属开关与约束
                when (currentType) {
                    ShapeType.RECT, ShapeType.ROUNDED_RECT -> {
                        ToolFloatChip(
                            label = androidx.compose.ui.res.stringResource(R.string.shape_keep_aspect),
                            selected = state.keepAspect,
                            onClick = {
                                val next = !state.keepAspect
                                state.keepAspect = next
                                vm.updateShapeKeepAspect(next)
                            },
                        )
                    }
                    ShapeType.ELLIPSE -> {
                        ToolFloatChip(
                            label = androidx.compose.ui.res.stringResource(R.string.shape_perfect_circle),
                            selected = state.keepAspect,
                            onClick = {
                                val next = !state.keepAspect
                                state.keepAspect = next
                                vm.updateShapeKeepAspect(next)
                            },
                        )
                    }
                    ShapeType.POLYLINE, ShapeType.POLYGON, ShapeType.BEZIER -> {
                        if (state.nodes.isNotEmpty()) {
                            ToolFloatChip(label = androidx.compose.ui.res.stringResource(R.string.selection_undo_point), onClick = { vm.undoShapeNode() })
                        }
                        if (currentType != ShapeType.POLYGON) {
                            ToolFloatChip(
                                label = androidx.compose.ui.res.stringResource(R.string.shape_close),
                                selected = state.closed,
                                onClick = { state.closed = !state.closed },
                            )
                        }
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.shape_node_count, state.nodes.size),
                            color = Morandi.subText,
                            fontSize = 12.sp,
                        )
                    }
                    ShapeType.LINE, ShapeType.REGULAR_POLYGON, ShapeType.STAR -> Unit
                }

                // 4. 属性展开按钮 (仅在有可调节滑块属性时显示)
                if (hasProps) {
                    ToolActionButton(
                        iconRes = R.drawable.ic_sliders,
                        label = if (propsOpen) androidx.compose.ui.res.stringResource(R.string.selection_collapse) else androidx.compose.ui.res.stringResource(R.string.selection_props),
                        active = propsOpen,
                        onClick = { propsOpen = !propsOpen },
                    )
                }

                // 5. 动作操作: 确认完成 (✔) 与 取消 (✕)
                if (state.active) {
                    ToolActionButton(
                        iconRes = R.drawable.ic_check,
                        label = androidx.compose.ui.res.stringResource(R.string.confirm),
                        primary = true,
                        onClick = { vm.commitActiveShape() },
                    )
                    ToolActionButton(
                        iconRes = R.drawable.ic_x,
                        label = androidx.compose.ui.res.stringResource(R.string.cancel),
                        danger = true,
                        onClick = { vm.cancelActiveShape() },
                    )
                }
            }

            // 纵向属性面板展开区
            AnimatedVisibility(
                visible = propsOpen && hasProps,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(min = 260.dp, max = 340.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (hasStrokeSlider) {
                        ToolFloatSlider(
                            label = androidx.compose.ui.res.stringResource(R.string.shape_stroke_width),
                            valueText = "${strokeWidth.roundToInt()}px",
                            range = 1f..100f,
                            value = strokeWidth,
                            onValue = { w ->
                                state.strokeWidth = w
                                vm.updateShapeStrokeWidth(w.toDouble())
                            },
                            labelWidth = 56.dp,
                        )
                    }
                    when (currentType) {
                        ShapeType.ROUNDED_RECT -> {
                            ToolFloatSlider(
                                label = androidx.compose.ui.res.stringResource(R.string.shape_corner_radius),
                                valueText = "${state.cornerRadius.roundToInt()}px",
                                range = 0f..120f,
                                value = state.cornerRadius,
                                onValue = { state.cornerRadius = it },
                                labelWidth = 56.dp,
                            )
                        }
                        ShapeType.REGULAR_POLYGON -> {
                            ToolFloatSlider(
                                label = androidx.compose.ui.res.stringResource(R.string.shape_polygon_sides),
                                valueText = "${state.polygonSides}",
                                range = 3f..16f,
                                value = state.polygonSides.toFloat(),
                                onValue = { state.polygonSides = it.roundToInt() },
                                labelWidth = 56.dp,
                            )
                        }
                        ShapeType.STAR -> {
                            ToolFloatSlider(
                                label = androidx.compose.ui.res.stringResource(R.string.shape_star_points),
                                valueText = "${state.starPoints}",
                                range = 3f..12f,
                                value = state.starPoints.toFloat(),
                                onValue = { state.starPoints = it.roundToInt() },
                                labelWidth = 56.dp,
                            )
                            ToolFloatSlider(
                                label = androidx.compose.ui.res.stringResource(R.string.shape_star_inner_ratio),
                                valueText = "${(state.starInnerRatio * 100).roundToInt()}%",
                                range = 0.1f..0.9f,
                                value = state.starInnerRatio,
                                onValue = { state.starInnerRatio = it },
                                labelWidth = 56.dp,
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
