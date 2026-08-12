package com.reverie.paint.ui.painting

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReVerticalSlider
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor

@Composable
fun ToolRail(
    modifier: Modifier = Modifier,
    tool: Tool,
    onTool: (Tool) -> Unit,
    moreToolsOpen: Boolean = false,
    onToggleMoreTools: () -> Unit = {},
    brushSize: Double,
    onBrushSize: (Double) -> Unit,
    opacity: Double,
    onOpacity: (Double) -> Unit,
    brushColor: String,
    onOpenBrush: () -> Unit,
    onOpenColor: () -> Unit,
) {
    val mainTools = listOf(Tool.BRUSH, Tool.HAND, Tool.ERASER, Tool.PICKER, Tool.FILL)
    val moreTools = Tool.entries.filter { it !in mainTools }

    Box(modifier = modifier.fillMaxHeight().width(36.dp)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(36.dp)
                .background(
                    color = Morandi.panel
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                mainTools.forEach { t ->
                    ReIconButton(toolIcon(t), t.label, { onTool(t) }, selected = t == tool)
                }
                
                // More tools button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (moreToolsOpen || tool in moreTools) Morandi.accent else Color.Transparent)
                        .clickable { onToggleMoreTools() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_layers), // Placeholder for 4-squares
                        contentDescription = "更多工具",
                        tint = if (moreToolsOpen || tool in moreTools) Morandi.onAccent else Morandi.icon,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            
            ReVerticalSlider(
                label = "S",
                fraction = ((brushSize - 1) / 99.0).toFloat().coerceIn(0f, 1f),
                onFraction = { onBrushSize(1.0 + it * 99.0) },
                trackHeight = 160
            )
            Spacer(Modifier.height(8.dp))
            ReVerticalSlider(
                label = "O",
                fraction = opacity.toFloat(),
                onFraction = { onOpacity(it.toDouble()) },
                trackHeight = 160
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(parseColor(brushColor))
                        .border(2.dp, Morandi.panelHi, CircleShape)
                        .clickable(onClick = onOpenColor),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (moreToolsOpen) {
            Popup(
                alignment = Alignment.CenterStart,
                offset = androidx.compose.ui.unit.IntOffset(160, 0) // offset from the rail
            ) {
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        val chunked = moreTools.chunked(2)
                        chunked.forEach { rowTools ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                rowTools.forEach { t ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onTool(t) }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(toolIcon(t)),
                                            contentDescription = t.label,
                                            tint = if (tool == t) Morandi.accentHi else Morandi.icon,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(t.label, color = if (tool == t) Morandi.accentHi else Morandi.text, fontSize = 12.sp)
                                    }
                                }
                                if (rowTools.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                        Spacer(Modifier.height(8.dp))
                        
                        // Fake tools from the screenshot
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            FakeToolIcon("动画", R.drawable.ic_rect)
                            FakeToolIcon("导入", R.drawable.ic_rect)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            FakeToolIcon("参考", R.drawable.ic_rect)
                            FakeToolIcon("对称", R.drawable.ic_rect)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FakeToolIcon(label: String, @DrawableRes icon: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .padding(vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = Morandi.icon,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = Morandi.text, fontSize = 12.sp)
    }
}

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
