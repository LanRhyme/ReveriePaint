package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReButton
import kotlin.math.roundToInt

/**
 * Transform tool options - Krita tool-options floating capsule. Precise
 * rotation / scale inputs plus Apply / Cancel; the on-canvas rubber band
 * handles free transform
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
    ToolFloatPanel(title = "变换", modifier = Modifier) {
        ToolFloatSlider(
            label = "旋转",
            valueText = "${rotationDeg.roundToInt()}°",
            range = -180f..180f,
            value = rotationDeg,
            onValue = onRotation,
        )
        ToolFloatSlider(
            label = "水平缩放",
            valueText = "${(scaleX * 100).roundToInt()}%",
            range = 0.1f..8f,
            value = scaleX,
            onValue = onScaleX,
        )
        ToolFloatSlider(
            label = "垂直缩放",
            valueText = "${(scaleY * 100).roundToInt()}%",
            range = 0.1f..8f,
            value = scaleY,
            onValue = onScaleY,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReButton(text = "应用", onClick = onApply)
            ReButton(text = "取消", onClick = onCancel, primary = false)
        }
    }
}
