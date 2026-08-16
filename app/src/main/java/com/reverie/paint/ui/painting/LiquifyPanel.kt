package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.*
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState

/**
 * Liquify tool options - floating capsule in the same flat style as the
 * shape/fill/gradient panels: mode chips on one row, two fixed-width
 * sliders side by side below (Krita's liquify modes: push/pull, bloat,
 * pucker, rotate CW, rotate CCW plus brush size and strength)
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
    val modes = listOf(0 to "推拉", 1 to "膨胀", 2 to "收缩", 3 to "顺时针", 4 to "逆时针")
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (m, label) ->
                    ToolFloatChip(label, selected = mode == m, onClick = { onMode(m) })
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(132.dp)) {
                    ToolFloatSlider(
                        label = "笔刷",
                        valueText = "${brushSize.roundToInt()}px",
                        range = 8f..300f,
                        value = brushSize,
                        onValue = onBrushSize,
                    )
                }
                Box(modifier = Modifier.width(132.dp)) {
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
}
