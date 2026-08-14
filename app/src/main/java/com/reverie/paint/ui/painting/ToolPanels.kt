package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/** Gradient tool options: type (linear / radial / conical) */
@Composable
fun GradientPanel(
    vm: PaintViewModel,
    type: Int,
    onType: (Int) -> Unit,
) {
    val types = listOf(0 to "线性", 1 to "径向", 2 to "角度")
    ToolFloatPanel(title = "渐变", modifier = Modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { (t, label) ->
                ToolFloatChip(label, selected = type == t, onClick = { onType(t) })
            }
        }
        Text(
            "拖拽画布设置渐变方向, 颜色为前景→背景",
            color = Morandi.subText,
            fontSize = 11.sp,
        )
    }
}

/** Fill tool options: color tolerance (Krita's fill threshold) */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int,
    onTolerance: (Int) -> Unit,
) {
    ToolFloatPanel(title = "填充", modifier = Modifier) {
        ToolFloatSlider(
            label = "容差",
            valueText = "$tolerance",
            range = 0f..255f,
            value = tolerance.toFloat(),
            onValue = { onTolerance(it.toInt()) },
        )
        Text(
            "点击画布填充同色区域",
            color = Morandi.subText,
            fontSize = 11.sp,
        )
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
