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

@Composable
private fun getGradientTypeOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_grad_linear, androidx.compose.ui.res.stringResource(R.string.gradient_type_linear)),
    ToolDropdownItemData(1, R.drawable.ic_grad_radial, androidx.compose.ui.res.stringResource(R.string.gradient_type_radial)),
    ToolDropdownItemData(2, R.drawable.ic_grad_angle, androidx.compose.ui.res.stringResource(R.string.gradient_type_angle)),
)

@Composable
private fun getGradientRepeatOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_repeat_none, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_none)),
    ToolDropdownItemData(1, R.drawable.ic_repeat_loop, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_loop)),
    ToolDropdownItemData(2, R.drawable.ic_repeat_mirror, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_mirror)),
)

@Composable
private fun getFillSampleOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_rect, androidx.compose.ui.res.stringResource(R.string.selection_current_layer)),
    ToolDropdownItemData(1, R.drawable.ic_layers, androidx.compose.ui.res.stringResource(R.string.selection_all_layers)),
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
    val gradientTypeOptions = getGradientTypeOptions()
    val gradientRepeatOptions = getGradientRepeatOptions()

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
                label = androidx.compose.ui.res.stringResource(R.string.gradient_reverse),
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
    val fillSampleOptions = getFillSampleOptions()

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(320.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBubbleDropdown(
                    items = fillSampleOptions,
                    selected = sampleLayers,
                    labelOverride = if (sampleLayers == 0) androidx.compose.ui.res.stringResource(R.string.fill_sample_current_short) else androidx.compose.ui.res.stringResource(R.string.fill_sample_all_short),
                    onSelect = onSampleLayers,
                    active = true,
                )
                Box(modifier = Modifier.weight(1f)) {
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_tolerance),
                        valueText = "$tolerance",
                        range = 1f..100f,
                        value = tolerance.toFloat().coerceIn(1f, 100f),
                        onValue = { onTolerance(it.toInt()) },
                    )
                }
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) androidx.compose.ui.res.stringResource(R.string.selection_collapse) else androidx.compose.ui.res.stringResource(R.string.selection_props),
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_expand),
                        valueText = "${expand}px",
                        range = -16f..32f,
                        value = expand.toFloat().coerceIn(-16f, 32f),
                        onValue = { onExpand(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_feather),
                        valueText = "${feather}px",
                        range = 0f..32f,
                        value = feather.toFloat().coerceIn(0f, 32f),
                        onValue = { onFeather(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_close_gap),
                        valueText = "${closeGap}px",
                        range = 0f..16f,
                        value = closeGap.toFloat().coerceIn(0f, 16f),
                        onValue = { onCloseGap(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                }
            }
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
