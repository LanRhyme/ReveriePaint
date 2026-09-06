/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs

/**
 * Tab 1: Square / HSB Color Page with:
 * - GPU hardware accelerated zero-allocation dual-gradient shader for standard HSV mode
 * - Reusable bitmap buffer pool for non-linear color models (v-HSV, HSL, HSY')
 * - Double-tap snap to pure White, pure Black, and maximum Saturation
 * - Compact rainbow Hue slider
 * - Recent colors grid with active color highlight
 */
@Composable
fun SquareHsbColorPage(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit
) {
    // Reusable buffers for non-HSV color models to eliminate GC churn
    val res = 80
    var cachedBmp by remember { mutableStateOf<Bitmap?>(null) }
    val pixelBuffer = remember { IntArray(res * res) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastModelForBmp by remember { mutableStateOf("") }

    // Tap tracking for double-tap snap
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Large 2D SV Rect
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(hue, vm.colorModel) {
                        awaitEachGesture {
                            val down = awaitFirstDown().also { it.consume() }
                            onInteractionStart()

                            val now = System.currentTimeMillis()
                            val dist = (down.position - lastTapOffset).getDistance()
                            val isDoubleTap = (now - lastTapTime < 320) && (dist < 48.dp.toPx())

                            val w = size.width.toFloat()
                            val h = size.height.toFloat()

                            if (isDoubleTap) {
                                val sNorm = (down.position.x / w).coerceIn(0f, 1f)
                                val vNorm = (1f - down.position.y / h).coerceIn(0f, 1f)

                                when {
                                    // Top-left double tap: Snap to pure White (S=0, V=1)
                                    sNorm <= 0.28f && vNorm >= 0.72f -> {
                                        onSatVal(0f, 1f)
                                    }
                                    // Top-right double tap: Snap to pure Vivid color (S=1, V=1)
                                    sNorm >= 0.72f && vNorm >= 0.72f -> {
                                        onSatVal(1f, 1f)
                                    }
                                    // Bottom double tap: Snap to pure Black (V=0)
                                    vNorm <= 0.22f -> {
                                        onSatVal(sNorm, 0f)
                                    }
                                    // Default fallback to tap position
                                    else -> {
                                        onSatVal(sNorm, vNorm)
                                    }
                                }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapOffset = down.position
                                val s = (down.position.x / w).coerceIn(0f, 1f)
                                val v = (1f - down.position.y / h).coerceIn(0f, 1f)
                                onSatVal(s, v)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val s = (change.position.x / w).coerceIn(0f, 1f)
                                val v = (1f - change.position.y / h).coerceIn(0f, 1f)
                                onSatVal(s, v)
                                change.consume()
                            }
                            onInteractionEnd()
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                if (vm.colorModel == "hsv") {
                    // --- GPU Hardware-Accelerated Zero-Allocation Dual Gradient ---
                    // Layer 1: Horizontal gradient from pure White to pure Hue color
                    val pureHueColor = hueToPureColor(hue)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, pureHueColor),
                            startX = 0f,
                            endX = w
                        ),
                        size = size
                    )
                    // Layer 2: Vertical gradient from Transparent to Black (alpha multiplies brightness)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = 0f,
                            endY = h
                        ),
                        size = size
                    )
                } else {
                    // --- Non-linear Color Models (v-HSV, HSL, HSY') with Reusable Buffer ---
                    val bmp = cachedBmp ?: Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888).also {
                        cachedBmp = it
                    }

                    if (abs(lastHueForBmp - hue) > 0.8f || lastModelForBmp != vm.colorModel) {
                        for (y in 0 until res) {
                            val vy = 1.0f - (y / (res - 1.0f))
                            for (x in 0 until res) {
                                val sx = x / (res - 1.0f)
                                pixelBuffer[y * res + x] = hsvModelToRgb(hue, sx, vy, vm.colorModel)
                            }
                        }
                        bmp.setPixels(pixelBuffer, 0, res, 0, 0, res, res)
                        lastHueForBmp = hue
                        lastModelForBmp = vm.colorModel
                    }

                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstSize = IntSize(w.toInt(), h.toInt())
                    )
                }

                // Selector Reticle
                val selX = sat * w
                val selY = (1f - valB) * h
                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = Offset(selX, selY),
                    style = Stroke(2.5.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.65f),
                    radius = 5.dp.toPx(),
                    center = Offset(selX, selY),
                    style = Stroke(1.dp.toPx())
                )
            }
        }

        // 2. Precision H, S, V Sliders
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CompactHsvSlider(
                label = "H",
                value = hue,
                max = 360f,
                colors = RainbowHueColors,
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                onValueChange = onHue,
                unitSuffix = "°"
            )
            CompactHsvSlider(
                label = "S",
                value = sat * 100f,
                max = 100f,
                colors = listOf(
                    Color(hsvModelToRgb(hue, 0f, valB, vm.colorModel)),
                    Color(hsvModelToRgb(hue, 1f, valB, vm.colorModel))
                ),
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                onValueChange = { onSatVal(it / 100f, valB) },
                unitSuffix = "%"
            )
            CompactHsvSlider(
                label = "V",
                value = valB * 100f,
                max = 100f,
                colors = listOf(
                    Color(hsvModelToRgb(hue, sat, 0f, vm.colorModel)),
                    Color(hsvModelToRgb(hue, sat, 1f, vm.colorModel))
                ),
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                onValueChange = { onSatVal(sat, it / 100f) },
                unitSuffix = "%"
            )
        }

        // 3. Bottom Quick Swatches (Recent Colors / Default Palette Switcher)
        BottomQuickSwatchesSection(
            vm = vm,
            currentColorHex = vm.brushColor,
            onColorSelect = { hex -> vm.updateBrushColor(hex) }
        )
    }
}
