package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState

/**
 * Shape tools options panel - Krita tool-options floating capsule
 */
@Composable
fun ShapeToolPanel(
    vm: PaintViewModel,
    tool: Tool,
    vertexCount: Int,
    strokeWidth: Float = vm.shapeStrokeWidth.toFloat(),
    filled: Boolean = vm.shapeFillMode == 1,
    keepAspect: Boolean = vm.shapeKeepAspect,
    onStrokeWidth: (Float) -> Unit = { vm.updateShapeStrokeWidth(it.toDouble()) },
    onFilled: (Boolean) -> Unit = { vm.updateShapeFillMode(if (it) 1 else 0) },
    onKeepAspect: (Boolean) -> Unit = { vm.updateShapeKeepAspect(it) },
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
                    "顶点 $vertexCount",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            }
        }
    } else {
        // line / rect / ellipse: stroke width + fill + aspect ratio constraint
        ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tool == Tool.RECT || tool == Tool.ELLIPSE) {
                    ToolFloatSegmented(
                        options = listOf(0 to "描边", 1 to "填充"),
                        selected = if (filled) 1 else 0,
                        onSelect = { onFilled(it == 1) },
                    )
                    ToolFloatChip(label = "等比", selected = keepAspect, onClick = { onKeepAspect(!keepAspect) })
                }
                Box(modifier = Modifier.width(140.dp)) {
                    ToolFloatSlider(
                        label = "粗细",
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
