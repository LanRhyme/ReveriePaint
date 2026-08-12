package com.reverie.paint.ui.painting

import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }

    // Initialize from current brush color
    LaunchedEffect(Unit) {
        try {
            val c = android.graphics.Color.parseColor(vm.brushColor)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hue = hsv[0]
            sat = hsv[1]
            valB = hsv[2]
        } catch (e: Exception) { }
    }

    val current = AColor.HSVToColor(floatArrayOf(hue, sat, valB))
    val currentHex = "#%06X".format(current and 0xFFFFFF)

    // Automatically apply color when HSV changes
    LaunchedEffect(currentHex) {
        if (vm.brushColor != currentHex) {
            vm.updateBrushColor(currentHex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 16.dp)
                .width(280.dp)
                .background(Morandi.panelHi, RoundedCornerShape(16.dp))
                .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(16.dp)
        ) {
            // Title
            Text("颜色", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // Hue Ring + SV Square
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                // Hue Ring
                Canvas(
                    modifier = Modifier
                        .size(180.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = change.position.x - cx
                                val dy = change.position.y - cy
                                hue = ((atan2(dy, dx) * 180 / Math.PI).toFloat() + 360f) % 360f
                                change.consume()
                            }
                        }
                ) {
                    val strokeWidth = 24.dp.toPx()
                    val radius = size.width / 2f - strokeWidth / 2f
                    // Draw sweep gradient for hue
                    val hueColors = listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red
                    )
                    drawCircle(
                        brush = Brush.sweepGradient(hueColors, center = center),
                        radius = radius,
                        style = Stroke(width = strokeWidth)
                    )
                    
                    // Hue selector
                    val angle = hue * Math.PI / 180f
                    val sx = center.x + cos(angle).toFloat() * radius
                    val sy = center.y + sin(angle).toFloat() * radius
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(sx, sy), style = Stroke(3.dp.toPx()))
                }

                // SV Square
                val squareSize = 100.dp
                Canvas(
                    modifier = Modifier
                        .size(squareSize)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                valB = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                ) {
                    val hueColor = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    // Saturation gradient (white to hue color)
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    // Value gradient (transparent to black)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    
                    // SV selector
                    val selX = sat * size.width
                    val selY = (1f - valB) * size.height
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(selX, selY), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, radius = 4.dp.toPx(), center = Offset(selX, selY), style = Stroke(1.dp.toPx()))
                }
            }

            Spacer(Modifier.height(16.dp))

            // H, S, V Sliders
            HsvSlider("H", hue, 360f, listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) { hue = it }
            Spacer(Modifier.height(8.dp))
            HsvSlider("S", sat, 1f, listOf(Color.White, Color(AColor.HSVToColor(floatArrayOf(hue, 1f, valB))))) { sat = it }
            Spacer(Modifier.height(8.dp))
            HsvSlider("V", valB, 1f, listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(hue, sat, 1f))))) { valB = it }
        }
    }
}

@Composable
private fun HsvSlider(label: String, value: Float, max: Float, colors: List<Color>, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(colors))
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(frac * max)
                        change.consume()
                    }
                }
        ) {
            // Canvas for thumb
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = (value / max) * size.width
                drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cx, size.height / 2f))
                drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(cx, size.height / 2f), style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${value.toInt()}", color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(24.dp))
    }
}
