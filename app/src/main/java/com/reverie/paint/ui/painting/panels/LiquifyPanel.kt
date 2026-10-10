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



/**
 * Liquify tool options - floating capsule with compact mode dropdown
 * and vertically stacked precision sliders for brush size and strength
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
    hardness: Float = .5f,
    onHardness: (Float) -> Unit = {},
    hazeState: HazeState? = null,
) {
    val liquifyModeOptions = listOf(
        ToolDropdownItemData(5, R.drawable.ic_lq_push, androidx.compose.ui.res.stringResource(R.string.liquify_push_left)),
        ToolDropdownItemData(6, R.drawable.ic_lq_push, androidx.compose.ui.res.stringResource(R.string.liquify_push_right)),
        ToolDropdownItemData(0, R.drawable.ic_lq_push, androidx.compose.ui.res.stringResource(R.string.liquify_push)),
        ToolDropdownItemData(1, R.drawable.ic_lq_bloat, androidx.compose.ui.res.stringResource(R.string.liquify_bloat)),
        ToolDropdownItemData(2, R.drawable.ic_lq_pucker, androidx.compose.ui.res.stringResource(R.string.liquify_pucker)),
        ToolDropdownItemData(3, R.drawable.ic_rotate_cw, androidx.compose.ui.res.stringResource(R.string.liquify_cw)),
        ToolDropdownItemData(4, R.drawable.ic_rotate_ccw, androidx.compose.ui.res.stringResource(R.string.liquify_ccw)),
    )

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBubbleDropdown(
                items = liquifyModeOptions,
                selected = mode,
                onSelect = onMode,
                active = true,
            )
            Column(
                modifier = Modifier.width(240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToolFloatSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.liquify_size),
                    valueText = "${brushSize.roundToInt()}px",
                    range = 8f..300f,
                    value = brushSize,
                    onValue = onBrushSize,
                )
                ToolFloatSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.liquify_strength),
                    valueText = "${(strength * 100).roundToInt()}%",
                    range = 0.05f..1f,
                    value = strength,
                    onValue = onStrength,
                )
                ToolFloatSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.liquify_hardness),
                    valueText = "${(hardness * 100).roundToInt()}%",
                    range = 0f..1f,
                    value = hardness,
                    onValue = onHardness,
                )
            }
        }
    }
}
