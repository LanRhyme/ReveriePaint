package com.reverie.paint.ui.painting

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
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
import com.reverie.paint.core.PaintViewModel

@Composable
fun ToolRail(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    tool: Tool,
    onTool: (Tool) -> Unit,
    moreToolsOpen: Boolean = false,
    onToggleMoreTools: () -> Unit = {},
    brushSize: Double,
    onBrushSize: (Double) -> Unit,
    opacity: Double,
    popupOpacity: Float = 1f,
    brushOpacity: Double,
    onOpacity: (Double) -> Unit,
    brushColor: String,
    onOpenBrush: () -> Unit,
    onOpenColor: () -> Unit,
) {
    val mainTools = listOf(Tool.BRUSH, Tool.HAND, Tool.ERASER, Tool.PICKER, Tool.FILL)
    val moreTools = Tool.entries.filter { it !in mainTools }

    var tooltipTool by remember { mutableStateOf<Tool?>(null) }
    LaunchedEffect(tooltipTool) {
        if (tooltipTool != null) {
            delay(1500)
            tooltipTool = null
        }
    }

    Box(modifier = modifier.fillMaxHeight().width(36.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Upper panel
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(Morandi.panel.copy(alpha = opacity.toFloat()))
                    .padding(vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                mainTools.forEach { t ->
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        ReIconButton(
                            toolIcon(t),
                            t.label,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            onTap = { 
                                tooltipTool = t
                                if (t == tool && t == Tool.BRUSH) onOpenBrush() 
                                else onTool(t) 
                            },
                            selected = t == tool
                        )
                        if (tooltipTool == t) {
                            Popup(alignment = Alignment.CenterEnd, offset = IntOffset(110, 0)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Morandi.panelHi)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(t.label, color = Morandi.text, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                // More tools button
                val isMoreToolsActive = moreToolsOpen || tool in moreTools
                val moreToolsTint by androidx.compose.animation.animateColorAsState(if (isMoreToolsActive) Morandi.accent else Morandi.icon, androidx.compose.animation.core.tween(200))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleMoreTools() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_layers), // Placeholder for 4-squares
                        contentDescription = "更多工具",
                        tint = moreToolsTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            
            Spacer(Modifier.height(48.dp))
            
            // Lower panel
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .clip(RoundedCornerShape(topEnd = 16.dp)) // no bottom-right corner
                    .background(Morandi.panel.copy(alpha = opacity.toFloat()))
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clickable(onClick = onOpenColor),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(parseColor(brushColor))
                            .border(1.5.dp, Morandi.panelHi, CircleShape)
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Brush size: Krita top-bar style - always-visible value,
                // step buttons (+/-) that repeat while held, and the slider
                BrushSizeGroup(
                    brushSize = brushSize,
                    onBrushSize = onBrushSize,
                )
                Spacer(Modifier.height(10.dp))
                OpacityGroup(
                    opacity = brushOpacity,
                    onOpacity = onOpacity,
                )
            }
        }

        if (moreToolsOpen) {
            Popup(
                alignment = Alignment.CenterStart,
                offset = androidx.compose.ui.unit.IntOffset(110, 0) // Moved closer
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = moreToolsOpen,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutHorizontally()
                ) {
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi.copy(alpha = popupOpacity))
                            .border(1.dp, Morandi.border.copy(alpha = popupOpacity), RoundedCornerShape(12.dp))
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

/** Vertical brush-size control: value + logarithmic slider, like Krita's
 * top bar. The slider is logarithmic (1..500), so small sizes get fine
 * resolution and large sizes coarse resolution - exactly Krita's
 * KisLogarithmicSliderSpinBox mapping: value = 500^fraction.
 */
@Composable
private fun BrushSizeGroup(
    brushSize: Double,
    onBrushSize: (Double) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("S", color = Morandi.subText, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Text(
            text = "${brushSize.roundToInt()}",
            color = Morandi.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 1.dp),
        )
        ReVerticalSlider(
            label = "",
            fraction = (kotlin.math.ln(brushSize.coerceAtLeast(1.0)) / kotlin.math.ln(500.0)).toFloat().coerceIn(0f, 1f),
            onFraction = { onBrushSize(kotlin.math.exp(kotlin.math.ln(500.0) * it)) },
            trackHeight = 100,
        )
    }
}

/** Vertical opacity control: value + slider, 0..1 linear. */
@Composable
private fun OpacityGroup(
    opacity: Double,
    onOpacity: (Double) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("O", color = Morandi.subText, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Text(
            text = "${(opacity * 100).roundToInt()}%",
            color = Morandi.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 1.dp),
        )
        ReVerticalSlider(
            label = "",
            fraction = opacity.toFloat(),
            onFraction = { onOpacity(it.toDouble()) },
            trackHeight = 100,
        )
    }
}
