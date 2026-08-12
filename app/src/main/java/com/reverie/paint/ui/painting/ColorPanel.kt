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
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class ColorPickerMode { SQUARE, TRIANGLE, CIRCLE, SLIDERS }

val TriangleShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(0f, size.height)
    close()
}

@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }

    var mode by remember { mutableStateOf(ColorPickerMode.SQUARE) }

    val updateVmColor = { h: Float, s: Float, v: Float ->
        val c = AColor.HSVToColor(floatArrayOf(h, s, v))
        val hex = "#%06X".format(c and 0xFFFFFF)
        vm.updateBrushColor(hex)
    }

    // Initialize from current brush color, but guard against infinite loops by only updating if sufficiently different
    LaunchedEffect(vm.brushColor) {
        try {
            val c = android.graphics.Color.parseColor(vm.brushColor)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            val dh = kotlin.math.abs(hsv[0] - hue)
            val ds = kotlin.math.abs(hsv[1] - sat)
            val dv = kotlin.math.abs(hsv[2] - valB)
            
            // Allow small tolerances due to HEX quantization
            if (dh > 2f || ds > 0.02f || dv > 0.02f) {
                hue = hsv[0]
                sat = hsv[1]
                valB = hsv[2]
            }
        } catch (e: Exception) { }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 16.dp) // Aligns with tool rail
                .width(260.dp) // Slightly tighter width
                .background(Morandi.panelHi.copy(alpha = opacity), RoundedCornerShape(16.dp))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(12.dp) // More compact inner padding
        ) {
            // Header Row: Title + Color Preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("颜色", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                
                // Foreground / Background Colors
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .noRippleClickable { vm.swapColors() },
                        contentAlignment = Alignment.Center
                    ) {
                        // Background Color (bottom right)
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)))
                                .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                        )
                        // Foreground Color (top left)
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.TopStart)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(android.graphics.Color.parseColor(vm.brushColor)))
                                .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))

            // Body
            when (mode) {
                ColorPickerMode.SQUARE -> {
                    WheelPicker(hue, sat, valB, RoundedCornerShape(2.dp), onHue = { hue = it; updateVmColor(it, sat, valB) }, onSatVal = { s, v -> sat = s; valB = v; updateVmColor(hue, s, v) })
                    Spacer(Modifier.height(12.dp))
                    HsvSliders(hue, sat, valB, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSat = { sat = it; updateVmColor(hue, it, valB) }, onVal = { valB = it; updateVmColor(hue, sat, it) })
                }
                ColorPickerMode.TRIANGLE -> {
                    WheelPicker(hue, sat, valB, TriangleShape, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSatVal = { s, v -> sat = s; valB = v; updateVmColor(hue, s, v) })
                    Spacer(Modifier.height(12.dp))
                    HsvSliders(hue, sat, valB, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSat = { sat = it; updateVmColor(hue, it, valB) }, onVal = { valB = it; updateVmColor(hue, sat, it) })
                }
                ColorPickerMode.CIRCLE -> {
                    WheelPicker(hue, sat, valB, CircleShape, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSatVal = { s, v -> sat = s; valB = v; updateVmColor(hue, s, v) })
                    Spacer(Modifier.height(12.dp))
                    HsvSliders(hue, sat, valB, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSat = { sat = it; updateVmColor(hue, it, valB) }, onVal = { valB = it; updateVmColor(hue, sat, it) })
                }
                ColorPickerMode.SLIDERS -> {
                    HsvSliders(hue, sat, valB, onHue = { hue = it; updateVmColor(it, sat, valB) }, onSat = { sat = it; updateVmColor(hue, it, valB) }, onVal = { valB = it; updateVmColor(hue, sat, it) })
                }
            }

            Spacer(Modifier.height(12.dp))

            // Bottom Navigation (Custom Drawn Icons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon(ColorPickerMode.SQUARE, mode == ColorPickerMode.SQUARE) { mode = ColorPickerMode.SQUARE }
                NavIcon(ColorPickerMode.TRIANGLE, mode == ColorPickerMode.TRIANGLE) { mode = ColorPickerMode.TRIANGLE }
                NavIcon(ColorPickerMode.CIRCLE, mode == ColorPickerMode.CIRCLE) { mode = ColorPickerMode.CIRCLE }
                NavIcon(ColorPickerMode.SLIDERS, mode == ColorPickerMode.SLIDERS) { mode = ColorPickerMode.SLIDERS }
            }
        }
    }
}

@Composable
private fun NavIcon(shapeMode: ColorPickerMode, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val tint = if (selected) Morandi.accent else Morandi.icon
        when (shapeMode) {
            ColorPickerMode.SQUARE -> Box(Modifier.size(16.dp).border(2.dp, tint, RoundedCornerShape(2.dp)))
            ColorPickerMode.CIRCLE -> Box(Modifier.size(16.dp).border(2.dp, tint, CircleShape))
            ColorPickerMode.TRIANGLE -> Canvas(Modifier.size(16.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = tint, style = Stroke(2.dp.toPx()))
            }
            ColorPickerMode.SLIDERS -> Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(16.dp, 2.dp).background(tint))
                Box(Modifier.size(12.dp, 2.dp).background(tint))
                Box(Modifier.size(16.dp, 2.dp).background(tint))
            }
        }
    }
}

@Composable
private fun WheelPicker(
    hue: Float, sat: Float, valB: Float,
    innerShape: Shape,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit
) {
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
                        onHue(((atan2(dy, dx) * 180 / Math.PI).toFloat() + 360f) % 360f)
                        change.consume()
                    }
                }
        ) {
            val strokeWidth = 14.dp.toPx()
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

        // SV Shape
        val squareSize = 110.dp
        Canvas(
            modifier = Modifier
                .size(squareSize)
                .clip(innerShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onSatVal(s, v)
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
}

@Composable
private fun HsvSliders(
    hue: Float, sat: Float, valB: Float,
    onHue: (Float) -> Unit,
    onSat: (Float) -> Unit,
    onVal: (Float) -> Unit
) {
    Column {
        HsvSlider("H", hue, 360f, listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) { onHue(it) }
        Spacer(Modifier.height(8.dp))
        HsvSlider("S", sat, 1f, listOf(Color.White, Color(AColor.HSVToColor(floatArrayOf(hue, 1f, valB))))) { onSat(it) }
        Spacer(Modifier.height(8.dp))
        HsvSlider("V", valB, 1f, listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(hue, sat, 1f))))) { onVal(it) }
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
