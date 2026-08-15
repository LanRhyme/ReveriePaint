package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState

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
    hazeState: HazeState? = null,
) {
    if (tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.SELECT_POLYGON || tool == Tool.PATH) {
        // Point-click tools: vertex count + finish/cancel
        ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolFloatChip(label = "完成", selected = true, onClick = onFinish)
                ToolFloatChip(label = "取消", danger = true, onClick = onCancel)
                Text(
                    "顶点: $vertexCount",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            }
        }
    } else {
        // line / rect / ellipse: stroke width + fill
        ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolFloatChip(label = "填充", selected = filled, onClick = { onFilled(!filled) })
                Box(modifier = Modifier.width(180.dp)) {
                    ToolFloatSlider(
                        label = "描边",
                        valueText = "${strokeWidth.roundToInt()}px",
                        range = 1f..100f,
                        value = strokeWidth,
                        onValue = onStrokeWidth,
                    )
                }
            }
        }
    }
}
