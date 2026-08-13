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
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * Shape tools options panel (Krita tool-options style)
 *
 * Point-click tools (polygon / polyline / polygon-select) show the vertex
 * count and 完成/取消 buttons; drag shapes (line / rect / ellipse) expose an
 * independent stroke width and a fill toggle
 */
@Composable
fun ShapeToolPanel(
    vm: PaintViewModel,
    tool: Tool,
    vertexCount: Int,
    strokeWidth: Float,
    filled: Boolean,
    onStrokeWidth: (Float) -> Unit,
    onFilled: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    RePanel(title = tool.label, onClose = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.SELECT_POLYGON) {
                Text(
                    "点击画布添加顶点 ($vertexCount)",
                    color = Morandi.text,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ReButton(
                        text = "完成",
                        onClick = onFinish,
                        modifier = Modifier.weight(1f),
                    )
                    ReButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        primary = false,
                    )
                }
            } else {
                // line / rect / ellipse: stroke width + fill
                ShapeSliderRow("描边", "${strokeWidth.roundToInt()}px", 1f, 100f, strokeWidth, onStrokeWidth)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("填充", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    com.reverie.paint.ui.components.ReSwitch(
                        checked = filled,
                        onChecked = onFilled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeSliderRow(
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
