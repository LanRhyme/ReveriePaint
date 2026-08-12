package com.reverie.paint.ui.painting

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor

/** Left drawing rail with Tabler icon-pack vector icons. */
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
        Column(
            modifier = Modifier.width(60.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Tool.entries.forEach { t ->
                ToolBtn(t, t == tool) { onTool(t) }
            }
        }

        Spacer(Modifier.weight(1f))
        RailIcon(Tool.BRUSH, "笔刷", onOpenBrush)
        Spacer(Modifier.height(6.dp))
        RailSlider(
            label = brushSize.toInt().toString(),
            fraction = ((brushSize - 1) / 99.0).toFloat().coerceIn(0f, 1f),
            onFraction = { onBrushSize(1.0 + it * 99.0) },
        )
        Spacer(Modifier.height(10.dp))
        RailSlider(
            label = (opacity * 100).toInt().toString(),
            fraction = opacity.toFloat(),
            onFraction = { onOpacity(it.toDouble()) },
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(parseColor(brushColor))
                    .clickable(onClick = onOpenColor),
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
                .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(toolIcon(t)),
            contentDescription = t.label,
            tint = if (selected) Color.White else Morandi.icon,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun RailIcon(
    tool: Tool,
    desc: String,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(toolIcon(tool)),
            contentDescription = desc,
            tint = Morandi.icon,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Tabler icon-pack drawable for each tool (see app/src/main/res/drawable). */
@DrawableRes
private fun toolIcon(tool: Tool): Int =
    when (tool) {
        Tool.BRUSH -> R.drawable.ic_brush
        Tool.HAND -> R.drawable.ic_hand
        Tool.ERASER -> R.drawable.ic_eraser
        Tool.PICKER -> R.drawable.ic_picker
        Tool.FILL -> R.drawable.ic_fill
        Tool.LASSO -> R.drawable.ic_lasso
        Tool.MAGICWAND -> R.drawable.ic_magicwand
        Tool.LINE -> R.drawable.ic_line
        Tool.RECT -> R.drawable.ic_rect
        Tool.ELLIPSE -> R.drawable.ic_ellipse
        Tool.TEXT -> R.drawable.ic_text
        Tool.SMUDGE -> R.drawable.ic_smudge
        Tool.LIQUIFY -> R.drawable.ic_liquify
    }

@Composable
private fun RailSlider(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    var localFraction by remember(fraction) { mutableFloatStateOf(fraction) }
    // position.y from drag gestures is in pixels; trackHeight must be the
    // on-screen pixel height of the track, not the dp value
    var trackHeight by remember { mutableIntStateOf(1) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(120.dp)) {
        Text(label, color = Morandi.subText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier =
                Modifier
                    .width(26.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Morandi.panelHi)
                    .onSizeChanged { trackHeight = it.height }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val value = 1f - (change.position.y / trackHeight.toFloat()).coerceIn(0f, 1f)
                            localFraction = value
                            onFraction(value)
                            change.consume()
                        }
                    },
        )
    }
}
