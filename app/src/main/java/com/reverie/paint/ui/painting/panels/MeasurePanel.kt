/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.saveToolOptions
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

@Composable
fun MeasurePanel(
    vm: PaintViewModel,
    strokeWidth: Float,
    onStrokeWidth: (Float) -> Unit,
    onClear: () -> Unit,
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 清除测量线按钮
            ToolActionButton(
                iconRes = R.drawable.ic_trash,
                label = stringResource(R.string.measure_clear),
                onClick = onClear,
            )

            // 竖向分割细线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(Morandi.border.copy(alpha = 0.5f))
            )

            // 粗细滑块
            Box(modifier = Modifier.width(180.dp)) {
                ToolFloatSlider(
                    label = stringResource(R.string.measure_stroke_width),
                    valueText = "${strokeWidth.roundToInt()}px",
                    range = 1f..8f,
                    value = strokeWidth,
                    onValue = {
                        onStrokeWidth(it)
                        vm.saveToolOptions()
                    },
                )
            }
        }
    }
}
