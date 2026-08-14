package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * Shape tools options panel - Krita tool-options floating capsule (same as
 * the selection panel): it never covers the canvas, only a compact capsule
 * at the bottom
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
    if (tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.SELECT_POLYGON || tool == Tool.PATH) {
        // Point-click tools: vertex count + 完成/取消
        ToolFloatPanel(title = tool.label, modifier = Modifier) {
            Text(
                "点击画布添加顶点 ($vertexCount)",
                color = Morandi.text,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReButton(text = "完成", onClick = onFinish)
                ReButton(text = "取消", onClick = onCancel, primary = false)
            }
        }
    } else {
        // line / rect / ellipse: stroke width + fill
        ToolFloatPanel(title = tool.label, modifier = Modifier) {
            ToolFloatSlider(
                label = "描边",
                valueText = "${strokeWidth.roundToInt()}px",
                range = 1f..100f,
                value = strokeWidth,
                onValue = onStrokeWidth,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("填充", color = Morandi.text, fontSize = 12.sp)
                ReSwitch(checked = filled, onChecked = onFilled)
            }
        }
    }
}
