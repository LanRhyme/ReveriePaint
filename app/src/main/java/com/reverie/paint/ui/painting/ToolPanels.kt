package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("形态:", color = Morandi.subText, fontSize = 11.sp)
                types.forEach { (t, label) ->
                    ToolFloatChip(label, selected = type == t, onClick = { onType(t) })
                }
                Spacer(Modifier.width(4.dp))
                ToolFloatChip("反转", selected = reverse, onClick = { onReverse(!reverse) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("延伸:", color = Morandi.subText, fontSize = 11.sp)
                repeats.forEach { (r, label) ->
                    ToolFloatChip(label, selected = repeat == r, onClick = { onRepeat(r) })
                }
            }
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(160.dp)) {
                ToolFloatSlider(
                    label = "容差",
                    valueText = "$tolerance",
                    range = 0f..255f,
                    value = tolerance.toFloat(),
                    onValue = { onTolerance(it.toInt()) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("参考:", color = Morandi.subText, fontSize = 11.sp)
                ToolFloatChip("当前图层", selected = sampleLayers == 0, onClick = { onSampleLayers(0) })
                ToolFloatChip("全部图层", selected = sampleLayers == 1, onClick = { onSampleLayers(1) })
            }
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
