/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState

private val gradientTypeOptions = listOf(
    ToolDropdownItemData(0, R.drawable.ic_grad_linear, "线性"),
    ToolDropdownItemData(1, R.drawable.ic_grad_radial, "径向"),
    ToolDropdownItemData(2, R.drawable.ic_grad_angle, "角度"),
)

private val gradientRepeatOptions = listOf(
    ToolDropdownItemData(0, R.drawable.ic_repeat_none, "单次"),
    ToolDropdownItemData(1, R.drawable.ic_repeat_loop, "重复"),
    ToolDropdownItemData(2, R.drawable.ic_repeat_mirror, "往返"),
)

private val fillSampleOptions = listOf(
    ToolDropdownItemData(0, R.drawable.ic_rect, "当前图层"),
    ToolDropdownItemData(1, R.drawable.ic_layers, "全部图层"),
)

/** Gradient tool options: type (linear / radial / conical), repeat, reverse */
@Composable
fun GradientPanel(
    vm: PaintViewModel,
    type: Int,
    onType: (Int) -> Unit,
    repeat: Int = vm.gradientRepeat,
    onRepeat: (Int) -> Unit = { vm.updateGradientRepeat(it) },
    reverse: Boolean = vm.gradientReverse,
    onReverse: (Boolean) -> Unit = { vm.updateGradientReverse(it) },
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBubbleDropdown(
                items = gradientTypeOptions,
                selected = type,
                onSelect = onType,
            )
            ToolBubbleDropdown(
                items = gradientRepeatOptions,
                selected = repeat,
                onSelect = onRepeat,
            )
            ToolActionButton(
                iconRes = R.drawable.ic_refresh,
                label = "反向",
                active = reverse,
                onClick = { onReverse(!reverse) },
            )
        }
    }
}

/** Fill tool options: color tolerance (threshold), sample layers, expand, feather, and close gap */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int = vm.fillTolerance,
    onTolerance: (Int) -> Unit = { vm.updateFillTolerance(it) },
    sampleLayers: Int = vm.fillSampleLayers,
    onSampleLayers: (Int) -> Unit = { vm.updateFillSampleLayers(it) },
    expand: Int = vm.fillExpand,
    onExpand: (Int) -> Unit = { vm.updateFillExpand(it) },
    feather: Int = vm.fillFeather,
    onFeather: (Int) -> Unit = { vm.updateFillFeather(it) },
    closeGap: Int = vm.fillCloseGap,
    onCloseGap: (Int) -> Unit = { vm.updateFillCloseGap(it) },
    hazeState: HazeState? = null,
) {
    var propsOpen by remember { mutableStateOf(false) }

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBubbleDropdown(
                    items = fillSampleOptions,
                    selected = sampleLayers,
                    labelOverride = if (sampleLayers == 0) "当前" else "全部",
                    onSelect = onSampleLayers,
                    active = true,
                )
                Box(modifier = Modifier.width(140.dp)) {
                    ToolFloatSlider(
                        label = "容差",
                        valueText = "$tolerance",
                        range = 1f..100f,
                        value = tolerance.toFloat().coerceIn(1f, 100f),
                        onValue = { onTolerance(it.toInt()) },
                    )
                }
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) "收起" else "属性",
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    ToolFloatSlider(
                        label = "拓展",
                        valueText = "${expand}px",
                        range = -16f..32f,
                        value = expand.toFloat().coerceIn(-16f, 32f),
                        onValue = { onExpand(it.toInt()) },
                    )
                    ToolFloatSlider(
                        label = "羽化",
                        valueText = "${feather}px",
                        range = 0f..32f,
                        value = feather.toFloat().coerceIn(0f, 32f),
                        onValue = { onFeather(it.toInt()) },
                    )
                    ToolFloatSlider(
                        label = "空隙",
                        valueText = "${closeGap}px",
                        range = 0f..16f,
                        value = closeGap.toFloat().coerceIn(0f, 16f),
                        onValue = { onCloseGap(it.toInt()) },
                    )
                }
            }
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
