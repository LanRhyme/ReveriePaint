package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState

/** Gradient tool options: type (linear / radial / conical) */
@Composable
fun GradientPanel(
    vm: PaintViewModel,
    type: Int,
    onType: (Int) -> Unit,
    hazeState: HazeState? = null,
) {
    val types = listOf(0 to "线性", 1 to "径向", 2 to "角度")
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            types.forEach { (t, label) ->
                ToolFloatChip(label, selected = type == t, onClick = { onType(t) })
            }
        }
    }
}

/** Fill tool options: color tolerance (Krita's fill threshold) */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int,
    onTolerance: (Int) -> Unit,
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Box(modifier = Modifier.width(200.dp)) {
            ToolFloatSlider(
                label = "容差",
                valueText = "$tolerance",
                range = 0f..255f,
                value = tolerance.toFloat(),
                onValue = { onTolerance(it.toInt()) },
            )
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
