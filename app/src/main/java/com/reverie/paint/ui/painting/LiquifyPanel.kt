package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReButton
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState

/**
 * Liquify tool options - floating capsule with Krita's liquify modes:
 * push/pull, bloat, pucker, rotate CW, rotate CCW plus brush size and
 * strength
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
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (m, label) ->
                    ToolFloatChip(label, selected = mode == m, onClick = { onMode(m) })
                }
            }
            ToolFloatSlider(
                label = "笔刷",
                valueText = "${brushSize.roundToInt()}px",
                range = 8f..300f,
                value = brushSize,
                onValue = onBrushSize,
            )
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
