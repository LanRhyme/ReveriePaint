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
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * Transform tool options panel (Krita tool-options style)
 *
 * Precise rotation / scale inputs plus Apply / Cancel. The on-canvas rubber
 * band handles free transform; this panel gives exact values and commits the
 * transform (KisTransformWorker under the hood, selection-constrained).
 */
@Composable
fun TransformPanel(
    vm: PaintViewModel,
    rotationDeg: Float,
    scaleX: Float,
    scaleY: Float,
    onRotation: (Float) -> Unit,
    onScaleX: (Float) -> Unit,
    onScaleY: (Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    RePanel(title = "变换", onClose = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TransformSliderRow("旋转", "${rotationDeg.roundToInt()}°", -180f, 180f, rotationDeg, onRotation)
            TransformSliderRow("水平缩放", "${(scaleX * 100).roundToInt()}%", 0.1f, 8f, scaleX, onScaleX)
            TransformSliderRow("垂直缩放", "${(scaleY * 100).roundToInt()}%", 0.1f, 8f, scaleY, onScaleY)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReButton(
                    text = "应用",
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                )
                ReButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun TransformSliderRow(
    label: String,
    valueText: String,
    min: Float,
    max: Float,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        ReSlider(
            value = ((value - min) / (max - min)).coerceIn(0f, 1f),
            onValue = { frac -> onValue(min + frac * (max - min)) },
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(56.dp))
    }
}
