package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
                label = "反转",
                selected = reverse,
                onClick = { onReverse(!reverse) },
            )
        }
    }
}

/** Fill tool options: color tolerance (threshold) and sample layers (current vs all) */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int,
    onTolerance: (Int) -> Unit,
    sampleLayers: Int = vm.fillSampleLayers,
    onSampleLayers: (Int) -> Unit = { vm.updateFillSampleLayers(it) },
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(150.dp)) {
                ToolFloatSlider(
                    label = "容差",
                    valueText = "$tolerance",
                    range = 0f..255f,
                    value = tolerance.toFloat(),
                    onValue = { onTolerance(it.toInt()) },
                )
            }
            ToolFloatSegmented(
                options = listOf(0 to "当前图层", 1 to "全部图层"),
                selected = sampleLayers,
                onSelect = onSampleLayers,
            )
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
