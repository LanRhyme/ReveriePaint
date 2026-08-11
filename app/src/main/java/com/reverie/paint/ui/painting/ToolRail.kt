package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor

/**
 * Left tool rail (画世界 Pro style):
 * - tools stacked vertically from the top, current tool highlighted
 * - bottom: brush size (S) and opacity (O) vertical capsule sliders
 * - color swatch opens the color panel
 * Merged flush with the top bar into one connected dark panel.
 */
@Composable
fun ToolRail(
    tool: Tool,
    onTool: (Tool) -> Unit,
    brushSize: Double,
    onBrushSize: (Double) -> Unit,
    opacity: Double,
    onOpacity: (Double) -> Unit,
    brushColor: String,
    onOpenBrush: () -> Unit,
    onOpenColor: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(60.dp)
                .background(Morandi.panel)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tool list (scrollable if the screen is short)
        Column(
            modifier =
                Modifier
                    .width(60.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (t in Tool.entries) {
                ToolBtn(t, t == tool) { onTool(t) }
            }
        }

        Spacer(Modifier.weight(1f))

        // Brush panel button (opens brush presets)
        RailIcon("笔", "笔刷", onOpenBrush)

        Spacer(Modifier.height(6.dp))

        // Brush size slider (S)
        RailSlider(
            label = "${brushSize.toInt()}",
            fraction = ((brushSize - 1) / 99.0).toFloat().coerceIn(0f, 1f),
            onFraction = { onBrushSize(1.0 + it * 99.0) },
        )

        Spacer(Modifier.height(10.dp))

        // Opacity slider (O)
        RailSlider(
            label = "${(opacity * 100).toInt()}",
            fraction = opacity.toFloat(),
            onFraction = { onOpacity(it.toDouble()) },
        )

        Spacer(Modifier.height(10.dp))

        // Color swatch (opens color panel)
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(parseColor(brushColor))
                    .clickable { onOpenColor() },
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ToolBtn(
    t: Tool,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Morandi.accent else Color.Transparent)
                .clickable { onSelect() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            t.label.take(1),
            color = if (selected) Color.White else Morandi.icon,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun RailIcon(
    symbol: String,
    desc: String,
    onTap: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Morandi.icon, fontSize = 14.sp)
    }
}

@Composable
private fun RailSlider(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    var localFraction by remember(fraction) { mutableFloatStateOf(fraction) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(120.dp)) {
        Text(label, color = Morandi.subText, fontSize = 10.sp)
        Box(
            modifier =
                Modifier
                    .width(26.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Morandi.panelHi)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val frac = 1f - (change.position.y / 96f).coerceIn(0f, 1f)
                            localFraction = frac
                            onFraction(frac)
                            change.consume()
                        }
                    },
        )
    }
}
