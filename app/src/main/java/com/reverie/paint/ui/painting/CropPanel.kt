package com.reverie.paint.ui.painting

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

/** Crop tool options: shows the pending crop size and commits / cancels */
@Composable
fun CropPanel(
    rect: Rect,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    ToolFloatPanel(title = "裁剪", modifier = Modifier) {
        Text(
            "裁剪尺寸: ${rect.width.roundToInt()} × ${rect.height.roundToInt()} px",
            color = Morandi.text,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReButton(text = "确认", onClick = onApply)
            ReButton(text = "取消", onClick = onCancel, primary = false)
        }
    }
}
