package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/** Crop tool options: shows the pending crop size and commits / cancels */
@Composable
fun CropPanel(
    rect: Rect,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    RePanel(title = "裁剪", onClose = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "裁剪尺寸: ${rect.width.roundToInt()} × ${rect.height.roundToInt()} px",
                color = Morandi.text,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReButton(text = "确认", onClick = onApply, modifier = Modifier.weight(1f))
                ReButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f), primary = false)
            }
        }
    }
}
