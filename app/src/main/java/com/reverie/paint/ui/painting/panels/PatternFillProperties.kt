/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.saveToolOptions
import com.reverie.paint.ui.painting.layers.blendModeResId

@Composable
internal fun PatternFillProperties(vm: PaintViewModel) {
    ToolFloatSlider(
        label = stringResource(R.string.pattern_opacity),
        valueText = "${(vm.fillOpacity * 100).toInt()}%",
        range = 0f..1f,
        value = vm.fillOpacity.toFloat(),
        onValue = { vm.fillOpacity = it.toDouble(); vm.saveToolOptions() },
    )
    ToolBubbleDropdown(
        items = listOf("normal", "multiply", "screen", "overlay", "darken", "lighten", "difference")
            .map { ToolDropdownItemData(it, R.drawable.ic_layers, stringResource(blendModeResId(it))) },
        selected = vm.fillCompositeOp,
        onSelect = { vm.fillCompositeOp = it; vm.saveToolOptions() },
    )
}
