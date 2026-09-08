/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.*
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState

private val liquifyModeOptions = listOf(
    ToolDropdownItemData(0, R.drawable.ic_liquify, "推拉"),
    ToolDropdownItemData(1, R.drawable.ic_lq_bloat, "膨胀"),
    ToolDropdownItemData(2, R.drawable.ic_lq_pucker, "收缩"),
    ToolDropdownItemData(3, R.drawable.ic_rotate_cw, "顺时针"),
    ToolDropdownItemData(4, R.drawable.ic_rotate_ccw, "逆时针"),
)

/**
 * Liquify tool options - floating capsule in the same flat style as the
 * shape/fill/gradient panels: mode bubble dropdown, brush size, strength
 */
@Composable
fun LiquifyPanel(
    vm: PaintViewModel,
    strength: Float,
    onStrength: (Float) -> Unit,
    mode: Int,
    onMode: (Int) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBubbleDropdown(
                items = liquifyModeOptions,
                selected = mode,
                onSelect = onMode,
            )
            Box(modifier = Modifier.width(130.dp)) {
                ToolFloatSlider(
                    label = "笔刷",
                    valueText = "${brushSize.roundToInt()}px",
                    range = 8f..300f,
                    value = brushSize,
                    onValue = onBrushSize,
                )
            }
            Box(modifier = Modifier.width(130.dp)) {
                ToolFloatSlider(
                    label = "强度",
                    valueText = "${(strength * 100).roundToInt()}%",
                    range = 0.05f..2f,
                    value = strength,
                    onValue = onStrength,
                )
            }
        }
    }
}
