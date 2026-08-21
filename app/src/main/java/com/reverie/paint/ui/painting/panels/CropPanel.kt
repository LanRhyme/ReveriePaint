package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState
import com.reverie.paint.core.*

/** Crop tool options: shows the pending crop size and commits / cancels */
@Composable
fun CropPanel(
    rect: Rect,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    vm: PaintViewModel? = null,
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ToolFloatChip(label = "应用裁剪", selected = true, onClick = onApply)
            ToolFloatChip(label = "取消", danger = true, onClick = onCancel)
        }
    }
}
