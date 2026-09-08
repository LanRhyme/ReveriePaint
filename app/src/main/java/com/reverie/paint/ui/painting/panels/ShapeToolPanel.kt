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

private val shapeTypeOptions = listOf(
    ToolDropdownItemData(ShapeType.LINE, R.drawable.ic_minus, "直线"),
    ToolDropdownItemData(ShapeType.RECT, R.drawable.ic_rect, "矩形"),
    ToolDropdownItemData(ShapeType.ROUNDED_RECT, R.drawable.ic_rect, "圆角矩形"),
    ToolDropdownItemData(ShapeType.ELLIPSE, R.drawable.ic_ellipse, "椭圆"),
    ToolDropdownItemData(ShapeType.REGULAR_POLYGON, R.drawable.ic_triangle, "正多边形"),
    ToolDropdownItemData(ShapeType.STAR, R.drawable.ic_star, "星形"),
    ToolDropdownItemData(ShapeType.POLYLINE, R.drawable.ic_line, "折线"),
    ToolDropdownItemData(ShapeType.POLYGON, R.drawable.ic_polyline, "多边形"),
    ToolDropdownItemData(ShapeType.BEZIER, R.drawable.ic_copy, "贝塞尔曲线"),
)

private val shapeFillOptions = listOf(
    ToolDropdownItemData(ShapeFillMode.STROKE, R.drawable.ic_shape_stroke, "仅描边"),
    ToolDropdownItemData(ShapeFillMode.FILL, R.drawable.ic_shape_fill, "仅填充"),
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
                            label = "等比",
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
                            label = "正圆",
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
                            ToolFloatChip(label = "撤销点", onClick = { vm.undoShapeNode() })
                        }
                        if (currentType != ShapeType.POLYGON) {
                            ToolFloatChip(
                                label = "闭合",
                                selected = state.closed,
                                onClick = { state.closed = !state.closed },
                            )
                        }
                        Text(
                            "点数 ${state.nodes.size}",
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
                        label = if (propsOpen) "收起" else "属性",
                        active = propsOpen,
                        onClick = { propsOpen = !propsOpen },
                    )
                }

                // 5. 动作操作: 确认完成 (✔) 与 取消 (✕)
                if (state.active) {
                    ToolActionButton(
                        iconRes = R.drawable.ic_check,
                        label = "完成",
                        primary = true,
                        onClick = { vm.commitActiveShape() },
                    )
                    ToolActionButton(
                        iconRes = R.drawable.ic_x,
                        label = "取消",
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
                        .fillMaxWidth()
                        .widthIn(min = 240.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (hasStrokeSlider) {
                        ToolFloatSlider(
                            label = "粗细",
                            valueText = "${strokeWidth.roundToInt()}px",
                            range = 1f..100f,
                            value = strokeWidth,
                            onValue = { w ->
                                state.strokeWidth = w
                                vm.updateShapeStrokeWidth(w.toDouble())
                            },
                        )
                    }
                    when (currentType) {
                        ShapeType.ROUNDED_RECT -> {
                            ToolFloatSlider(
                                label = "圆角",
                                valueText = "${state.cornerRadius.roundToInt()}px",
                                range = 0f..120f,
                                value = state.cornerRadius,
                                onValue = { state.cornerRadius = it },
                            )
                        }
                        ShapeType.REGULAR_POLYGON -> {
                            ToolFloatSlider(
                                label = "边数",
                                valueText = "${state.polygonSides}",
                                range = 3f..16f,
                                value = state.polygonSides.toFloat(),
                                onValue = { state.polygonSides = it.roundToInt() },
                            )
                        }
                        ShapeType.STAR -> {
                            ToolFloatSlider(
                                label = "角数",
                                valueText = "${state.starPoints}",
                                range = 3f..12f,
                                value = state.starPoints.toFloat(),
                                onValue = { state.starPoints = it.roundToInt() },
                            )
                            ToolFloatSlider(
                                label = "内径",
                                valueText = "${(state.starInnerRatio * 100).roundToInt()}%",
                                range = 0.1f..0.9f,
                                value = state.starInnerRatio,
                                onValue = { state.starInnerRatio = it },
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
