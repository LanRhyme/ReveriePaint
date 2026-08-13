package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/** Gradient tool options: type (linear / radial / conical) */
@Composable
fun GradientPanel(
    vm: PaintViewModel,
    type: Int,
    onType: (Int) -> Unit,
) {
    RePanel(title = "渐变", onClose = {}) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("渐变类型", color = Morandi.text, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReChip("线性", selected = type == 0, onTap = { onType(0) })
                ReChip("径向", selected = type == 1, onTap = { onType(1) })
                ReChip("角度", selected = type == 2, onTap = { onType(2) })
            }
            Text(
                "拖拽画布设置渐变方向, 颜色为前景→背景",
                color = Morandi.subText,
                fontSize = 11.sp,
            )
        }
    }
}

/** Fill tool options: color tolerance (Krita's fill threshold) */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int,
    onTolerance: (Int) -> Unit,
) {
    RePanel(title = "填充", onClose = {}) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("容差", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                ReSlider(
                    value = tolerance / 255f,
                    onValue = { frac -> onTolerance((frac * 255f).roundToInt()) },
                    modifier = Modifier.weight(1f),
                )
                Text("$tolerance", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
            Text(
                "点击画布填充连续同色区域",
                color = Morandi.subText,
                fontSize = 11.sp,
            )
        }
    }
}

/** Liquify tool options: displacement strength */
@Composable
fun LiquifyPanel(
    vm: PaintViewModel,
    strength: Float,
    onStrength: (Float) -> Unit,
) {
    RePanel(title = "液化", onClose = {}) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("强度", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                ReSlider(
                    value = (strength / 2f).coerceIn(0f, 1f),
                    onValue = { frac -> onStrength(frac * 2f) },
                    modifier = Modifier.weight(1f),
                )
                Text("${(strength * 100).roundToInt()}%", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            }
            Text(
                "在画布上拖动, 像素沿拖动方向位移",
                color = Morandi.subText,
                fontSize = 11.sp,
            )
        }
    }
}
