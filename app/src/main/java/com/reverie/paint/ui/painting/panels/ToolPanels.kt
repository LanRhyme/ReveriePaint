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
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState

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
    val types = listOf(0 to "线性", 1 to "径向", 2 to "角度")
    val repeats = listOf(0 to "单次", 1 to "重复", 2 to "往返")
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolFloatSegmented(
                options = types,
                selected = type,
                onSelect = onType,
            )
            ToolFloatSegmented(
                options = repeats,
                selected = repeat,
                onSelect = onRepeat,
            )
            ToolFloatChip(
                label = "反向",
                selected = reverse,
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(130.dp)) {
                    ToolFloatSlider(
                        label = "容差",
                        valueText = "$tolerance",
                        range = 1f..100f,
                        value = tolerance.toFloat().coerceIn(1f, 100f),
                        onValue = { onTolerance(it.toInt()) },
                    )
                }
                ToolFloatSegmented(
                    options = listOf(0 to "当前", 1 to "全部"),
                    selected = sampleLayers,
                    onSelect = onSampleLayers,
                )
                ToolFloatChip(
                    label = "高级",
                    selected = propsOpen,
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
