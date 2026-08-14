package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi

/**
 * Krita tool-options style floating panel - the compact capsule used by the
 * selection tools, shared by every tool's property panel so no tool opens a
 * full-screen modal that blocks the canvas
 */
@Composable
fun ToolFloatPanel(
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.85f))
                .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Morandi.subText, fontSize = 11.sp)
        content()
    }
}

@Composable
fun ToolFloatChip(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        selected -> Morandi.accent
                        danger -> Color(0x33B05552)
                        else -> Morandi.panel
                    }
                )
                .noRippleClickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) Morandi.onAccent else Morandi.text,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun ToolFloatSlider(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Morandi.text, fontSize = 12.sp)
        com.reverie.paint.ui.components.ReSlider(
            value = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
            onValue = { frac -> onValue(range.start + frac * (range.endInclusive - range.start)) },
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = Morandi.text, fontSize = 12.sp)
    }
}
