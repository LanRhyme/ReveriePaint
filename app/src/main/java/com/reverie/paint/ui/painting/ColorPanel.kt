package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.ColorSwatches
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import android.graphics.Color as AColor

/**
 * Color panel: HSV wheel + brightness slider + preset swatches.
 * The wheel is drawn with a Canvas using radial hue sweeps (a true hue
 * wheel with saturation=1; inner area maps saturation).
 */
@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0.5f) }
    var valB by remember { mutableFloatStateOf(1f) }

    // Current HSV color
    val current = AColor.HSVToColor(floatArrayOf(hue, sat, valB))
    val currentHex = "#%06X".format(current and 0xFFFFFF)

    RePanel(title = "颜色", onClose = onClose, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            // current color preview + hex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(parseColor(currentHex))
                            .border(2.dp, Morandi.border, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(currentHex, color = Morandi.subText, fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))

            // HSV wheel (saturation/hue) - a full disc where the radius
            // maps saturation and the angle maps hue.
            var wheelSize by remember { mutableStateOf(0) }
            Canvas(
                modifier =
                    Modifier
                        .size(160.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = change.position.x - cx
                                val dy = change.position.y - cy
                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                val radius = size.width / 2f
                                sat = (dist / radius).coerceIn(0f, 1f)
                                hue = ((atan2(dy, dx) * 180 / Math.PI).toFloat() + 90f + 360f) % 360f
                                change.consume()
                            }
                        },
            ) {
                val radius = size.width / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Draw hue wheel: 360 thin pie slices
                val slices = 120
                for (i in 0 until slices) {
                    val h = i * 360f / slices
                    val a0 = (h - 90f) * Math.PI / 180f
                    val a1 = (h + 360f / slices - 90f) * Math.PI / 180f
                    val p0 = Offset(cx + cos(a0).toFloat() * radius, cy + sin(a0).toFloat() * radius)
                    val p1 = Offset(cx + cos(a1).toFloat() * radius, cy + sin(a1).toFloat() * radius)
                    drawLine(
                        color = Color(AColor.HSVToColor(floatArrayOf(h, 1f, 1f))),
                        start = Offset(cx, cy),
                        end = p0,
                        strokeWidth = 4f,
                    )
                    drawLine(
                        color = Color(AColor.HSVToColor(floatArrayOf(h, 1f, 1f))),
                        start = Offset(cx, cy),
                        end = p1,
                        strokeWidth = 4f,
                    )
                }
                // Saturation overlay: fade from transparent center to opaque edge
                drawCircle(
                    color = Color(0x22000000),
                    radius = radius,
                    center = Offset(cx, cy),
                )
                // Selector ring
                val selX = cx + cos((hue - 90f) * Math.PI / 180f).toFloat() * sat * radius
                val selY = cy + sin((hue - 90f) * Math.PI / 180f).toFloat() * sat * radius
                drawCircle(
                    color = Morandi.onAccent,
                    radius = 8f,
                    center = Offset(selX, selY),
                    style = Stroke(width = 3f),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Brightness slider (black -> current hue)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮度", color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.size(36.dp))
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                BrushGradient(listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(hue, sat, 1f))))),
                            ).pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                                    valB = frac
                                    change.consume()
                                }
                            },
                )
                Text("${(valB * 100).toInt()}", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.size(30.dp))
            }

            Spacer(Modifier.height(12.dp))

            // Apply + swatches
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Preset swatches (compact)
                ColorSwatches.take(8).forEach { c ->
                    Box(
                        modifier =
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(parseColor(c))
                                .border(
                                    2.dp,
                                    if (vm.brushColor == c) Morandi.accentHi else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                ).clickable { vm.updateBrushColor(c) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                // Apply current HSV
                Box(
                    modifier =
                        Modifier
                            .size(64.dp, 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.accent)
                            .clickable {
                                vm.updateBrushColor(currentHex)
                                onClose()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("使用", color = Morandi.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Horizontal gradient brush helper. */
@Composable
private fun BrushGradient(colors: List<Color>): androidx.compose.ui.graphics.Brush =
    androidx.compose.ui.graphics.Brush
        .horizontalGradient(colors)
