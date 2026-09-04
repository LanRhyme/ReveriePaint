/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.*

/**
 * Tab 0: Color Wheel View with:
 * - 205dp wide outer hue ring with double-tap primary hue snapping (0°, 60°, 120°, 180°, 240°, 300°)
 * - Inner SV shape (Square, Triangle, Circle) with GPU zero-allocation dual-gradient shader for HSV square
 * - Reusable bitmap buffer pool for Triangle, Circle, and non-linear color models
 * - Popup shape and color model selectors with Morandi styling
 * - Compact HSV sliders with click-to-edit values
 * - Memory colors section
 */
@Composable
fun WheelColorPage(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onSat: (Float) -> Unit,
    onVal: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit
) {
    var showShapePopup by remember { mutableStateOf(false) }
    var showModelPopup by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Center Wheel Picker (205dp) with corner menus
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(205.dp),
            contentAlignment = Alignment.Center
        ) {
            WheelPickerCanvas(
                shape = vm.colorWheelInnerShape,
                colorModel = vm.colorModel,
                hue = hue,
                sat = sat,
                valB = valB,
                onHue = onHue,
                onSatVal = onSatVal,
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd
            )

            // [⋯] Inner Shape Menu Button at bottom-left corner
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { showShapePopup = !showShapePopup },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⋯",
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                DropdownMenu(
                    expanded = showShapePopup,
                    onDismissRequest = { showShapePopup = false },
                    modifier = Modifier
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Square
                        val isSquare = vm.colorWheelInnerShape == "SQUARE"
                        Box(
                            modifier = Modifier
                                .size(28.dp, 24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isSquare) Morandi.accent else Color.Transparent)
                                .clickable {
                                    vm.updateColorWheelInnerShape("SQUARE")
                                    showShapePopup = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .background(if (isSquare) Color.White else Morandi.icon, RoundedCornerShape(2.dp))
                            )
                        }

                        // 2. Triangle
                        val isTriangle = vm.colorWheelInnerShape == "TRIANGLE"
                        Box(
                            modifier = Modifier
                                .size(28.dp, 24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isTriangle) Morandi.accent else Color.Transparent)
                                .clickable {
                                    vm.updateColorWheelInnerShape("TRIANGLE")
                                    showShapePopup = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(11.dp)) {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(size.width, size.height / 2f)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                drawPath(path, color = if (isTriangle) Color.White else Morandi.icon)
                            }
                        }

                        // 3. Circle
                        val isCircle = vm.colorWheelInnerShape == "CIRCLE"
                        Box(
                            modifier = Modifier
                                .size(28.dp, 24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isCircle) Morandi.accent else Color.Transparent)
                                .clickable {
                                    vm.updateColorWheelInnerShape("CIRCLE")
                                    showShapePopup = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .background(if (isCircle) Color.White else Morandi.icon, CircleShape)
                            )
                        }
                    }
                }
            }

            // [Color Model Switcher Button] at bottom-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showModelPopup = !showModelPopup }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val modelLabel = when (vm.colorModel) {
                        "v-hsv" -> "v-HSV"
                        "hsl" -> "HSL"
                        "hsy" -> "HSY'"
                        else -> "HSV"
                    }
                    Text(
                        text = modelLabel,
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                DropdownMenu(
                    expanded = showModelPopup,
                    onDismissRequest = { showModelPopup = false },
                    modifier = Modifier
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val models = listOf(
                            "hsv" to "HSV",
                            "v-hsv" to "v-HSV",
                            "hsl" to "HSL",
                            "hsy" to "HSY'"
                        )
                        for ((key, label) in models) {
                            val isSel = vm.colorModel == key
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (isSel) Morandi.accent else Color.Transparent)
                                    .clickable {
                                        vm.updateColorModel(key)
                                        showModelPopup = false
                                    }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) Color.White else Morandi.subText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Compact HSV Sliders
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
                onValueChange = { onSat(it / 100f) },
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
                onValueChange = { onVal(it / 100f) },
                unitSuffix = "%"
            )
        }

        // 3. Memory Colors Grid
        RecentColorsSection(
            recentColors = vm.recentColors,
            currentColorHex = vm.brushColor,
            onColorSelect = { hex -> vm.updateBrushColor(hex) },
            onClear = { vm.clearRecentColors() }
        )
    }
}

/**
 * Inner Canvas for Color Wheel + Selected SV shape (Square / Triangle / Circle)
 * Supports dynamic Color Models (HSV, v-HSV, HSL, HSY'), hardware-accelerated dual-gradient,
 * and double-tap snap for primary hues and corners.
 */
@Composable
private fun WheelPickerCanvas(
    shape: String,
    colorModel: String,
    hue: Float,
    sat: Float,
    valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val bmpRes = 72
    var cachedShapeBmp by remember { mutableStateOf<Bitmap?>(null) }
    val shapePixelBuffer = remember { IntArray(bmpRes * bmpRes) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastShapeForBmp by remember { mutableStateOf("") }
    var lastModelForBmp by remember { mutableStateOf("") }

    // Tap tracking for double-tap snap
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapOffset by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(shape, colorModel) {
                awaitEachGesture {
                    val down = awaitFirstDown().also { it.consume() }
                    onInteractionStart()

                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringSize = size.height.toFloat()
                    val strokeWidth = 16.dp.toPx()
                    val R = (ringSize - strokeWidth) / 2f
                    val innerR = R - strokeWidth / 2f - 4.dp.toPx()

                    val dx = down.position.x - cx
                    val dy = down.position.y - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    val isRing = dist >= R - strokeWidth / 2f

                    val now = System.currentTimeMillis()
                    val tapDist = (down.position - lastTapOffset).getDistance()
                    val isDoubleTap = (now - lastTapTime < 320) && (tapDist < 40.dp.toPx())

                    fun updatePos(pos: Offset, snapIfDoubleTap: Boolean) {
                        val x = pos.x
                        val y = pos.y
                        if (isRing) {
                            val px = x - cx
                            val py = y - cy
                            val rawAngle = (atan2(py, px) * 180 / Math.PI).toFloat()
                            val h = (rawAngle - 180f + 360f) % 360f
                            if (snapIfDoubleTap) {
                                // Snap to nearest 60° primary hue (0, 60, 120, 180, 240, 300)
                                val snapped = ((round(h / 60f) * 60f) % 360f + 360f) % 360f
                                onHue(snapped)
                            } else {
                                onHue(h)
                            }
                        } else {
                            when (shape) {
                                "TRIANGLE" -> {
                                    val x0 = x - (cx - innerR / 2f)
                                    val y0 = y - cy
                                    val W = 1.5f * innerR
                                    val H = innerR * sqrt(3f) / 2f
                                    val wC = x0 / W
                                    val wA = (1f - x0 / W - y0 / H) / 2f
                                    val wB = (1f - x0 / W + y0 / H) / 2f

                                    var cwC = wC.coerceIn(0f, 1f)
                                    var cwA = wA.coerceIn(0f, 1f)
                                    var cwB = wB.coerceIn(0f, 1f)
                                    val sum = cwC + cwA + cwB
                                    if (sum > 0) {
                                        cwC /= sum; cwA /= sum; cwB /= sum
                                    } else {
                                        cwA = 1f; cwB = 0f; cwC = 0f
                                    }

                                    val v = cwA + cwC
                                    val s = if (v > 0) cwC / v else 0f
                                    onSatVal(s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                                }
                                "CIRCLE" -> {
                                    var cdx = x - cx
                                    var cdy = y - cy
                                    val cdist = sqrt(cdx * cdx + cdy * cdy)
                                    if (cdist > innerR && cdist > 0f) {
                                        cdx = cdx * (innerR / cdist)
                                        cdy = cdy * (innerR / cdist)
                                    }
                                    val s = ((cdx / innerR) + 1f) / 2f
                                    val v = (1f - (cdy / innerR)) / 2f
                                    onSatVal(s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                                }
                                else -> { // "SQUARE"
                                    val side = 2f * innerR / sqrt(2f)
                                    val left = cx - side / 2f
                                    val top = cy - side / 2f
                                    val s = ((x - left) / side).coerceIn(0f, 1f)
                                    val v = (1f - (y - top) / side).coerceIn(0f, 1f)

                                    if (snapIfDoubleTap) {
                                        when {
                                            s <= 0.28f && v >= 0.72f -> onSatVal(0f, 1f)
                                            s >= 0.72f && v >= 0.72f -> onSatVal(1f, 1f)
                                            v <= 0.22f -> onSatVal(s, 0f)
                                            else -> onSatVal(s, v)
                                        }
                                    } else {
                                        onSatVal(s, v)
                                    }
                                }
                            }
                        }
                    }

                    if (isDoubleTap) {
                        updatePos(down.position, snapIfDoubleTap = true)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                        lastTapOffset = down.position
                        updatePos(down.position, snapIfDoubleTap = false)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        updatePos(change.position, snapIfDoubleTap = false)
                        change.consume()
                    }
                    onInteractionEnd()
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ringSize = size.height
        val strokeWidth = 16.dp.toPx()
        val R = (ringSize - strokeWidth) / 2f
        val innerR = R - strokeWidth / 2f - 4.dp.toPx()

        // 1. Hue Ring Sweep Gradient
        rotate(180f, pivot = Offset(cx, cy)) {
            drawCircle(
                brush = Brush.sweepGradient(RainbowHueColors, center = Offset(cx, cy)),
                radius = R,
                center = Offset(cx, cy),
                style = Stroke(width = strokeWidth)
            )
        }

        // 2. Hue Ring Indicator
        val angle = (hue + 180f) * Math.PI / 180f
        val sx = cx + cos(angle).toFloat() * R
        val sy = cy + sin(angle).toFloat() * R
        drawCircle(Color.White, radius = 7.5.dp.toPx(), center = Offset(sx, sy), style = Stroke(2.5.dp.toPx()))

        // 3. Inner SV Shape
        var selX = cx
        var selY = cy

        if (shape == "SQUARE" && colorModel == "hsv") {
            // --- GPU Hardware-Accelerated Zero-Allocation Shading for HSV Square ---
            val side = 2f * innerR / sqrt(2f)
            val left = cx - side / 2f
            val top = cy - side / 2f
            val cornerRadius = 6.dp.toPx()

            val rectPath = android.graphics.Path().apply {
                addRoundRect(left, top, left + side, top + side, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
            }

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.clipPath(rectPath)

                // Layer 1: Horizontal gradient White -> Pure Hue
                val pureHueColor = hueToPureColor(hue)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, pureHueColor),
                        startX = left,
                        endX = left + side
                    ),
                    topLeft = Offset(left, top),
                    size = Size(side, side)
                )
                // Layer 2: Vertical gradient Transparent -> Black
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = top,
                        endY = top + side
                    ),
                    topLeft = Offset(left, top),
                    size = Size(side, side)
                )

                canvas.nativeCanvas.restore()
            }

            selX = left + sat * side
            selY = top + (1f - valB) * side
        } else {
            // --- Reusable Bitmap Buffer for Triangle, Circle, and Non-HSV Models ---
            val bmp = cachedShapeBmp ?: Bitmap.createBitmap(bmpRes, bmpRes, Bitmap.Config.ARGB_8888).also {
                cachedShapeBmp = it
            }

            if (abs(lastHueForBmp - hue) > 0.8f || lastShapeForBmp != shape || lastModelForBmp != colorModel) {
                when (shape) {
                    "TRIANGLE" -> {
                        val H = bmpRes * sqrt(3f) / 2f
                        val W = 1.5f * (bmpRes / 2f)
                        val left = (bmpRes / 2f) - (bmpRes / 4f)
                        for (y in 0 until bmpRes) {
                            val y0 = y - (bmpRes / 2f)
                            for (x in 0 until bmpRes) {
                                val x0 = x - left
                                val wC = x0 / W
                                val wA = (1f - x0 / W - y0 / H) / 2f
                                val wB = (1f - x0 / W + y0 / H) / 2f
                                if (wC >= -0.04f && wA >= -0.04f && wB >= -0.04f) {
                                    val cwC = wC.coerceIn(0f, 1f)
                                    val cwA = wA.coerceIn(0f, 1f)
                                    val v = cwA + cwC
                                    val s = if (v > 0) cwC / v else 0f
                                    shapePixelBuffer[y * bmpRes + x] = hsvModelToRgb(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), colorModel)
                                } else {
                                    shapePixelBuffer[y * bmpRes + x] = AColor.TRANSPARENT
                                }
                            }
                        }
                    }
                    "CIRCLE" -> {
                        val rRadius = bmpRes / 2f
                        for (y in 0 until bmpRes) {
                            val dy = y - rRadius
                            for (x in 0 until bmpRes) {
                                val dx = x - rRadius
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist <= rRadius) {
                                    val s = ((dx / rRadius) + 1f) / 2f
                                    val v = (1f - (dy / rRadius)) / 2f
                                    shapePixelBuffer[y * bmpRes + x] = hsvModelToRgb(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), colorModel)
                                } else {
                                    shapePixelBuffer[y * bmpRes + x] = AColor.TRANSPARENT
                                }
                            }
                        }
                    }
                    else -> { // "SQUARE"
                        for (y in 0 until bmpRes) {
                            val vy = 1f - (y / (bmpRes - 1f))
                            for (x in 0 until bmpRes) {
                                val sx = x / (bmpRes - 1f)
                                shapePixelBuffer[y * bmpRes + x] = hsvModelToRgb(hue, sx, vy, colorModel)
                            }
                        }
                    }
                }
                bmp.setPixels(shapePixelBuffer, 0, bmpRes, 0, 0, bmpRes, bmpRes)
                lastHueForBmp = hue
                lastShapeForBmp = shape
                lastModelForBmp = colorModel
            }

            when (shape) {
                "TRIANGLE" -> {
                    val H = innerR * sqrt(3f) / 2f
                    val W = 1.5f * innerR
                    drawIntoCanvas { canvas ->
                        val path = android.graphics.Path().apply {
                            moveTo(cx - innerR / 2f, cy - H)
                            lineTo(cx - innerR / 2f, cy + H)
                            lineTo(cx + innerR, cy)
                            close()
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.clipPath(path)
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset((cx - innerR).toInt(), (cy - innerR).toInt()),
                            dstSize = IntSize((innerR * 2).toInt(), (innerR * 2).toInt())
                        )
                        canvas.nativeCanvas.restore()
                    }
                    val wC = sat * valB
                    val wA = valB - wC
                    val wB = 1f - valB
                    val x0 = wC * W
                    val y0 = (wB - wA) * H
                    selX = cx - innerR / 2f + x0
                    selY = cy + y0
                }
                "CIRCLE" -> {
                    drawIntoCanvas { canvas ->
                        val path = android.graphics.Path().apply {
                            addCircle(cx, cy, innerR, android.graphics.Path.Direction.CW)
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.clipPath(path)
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset((cx - innerR).toInt(), (cy - innerR).toInt()),
                            dstSize = IntSize((innerR * 2).toInt(), (innerR * 2).toInt())
                        )
                        canvas.nativeCanvas.restore()
                    }
                    selX = cx + (2f * sat - 1f) * innerR
                    selY = cy + (1f - 2f * valB) * innerR
                }
                else -> { // "SQUARE"
                    val side = 2f * innerR / sqrt(2f)
                    val left = cx - side / 2f
                    val top = cy - side / 2f
                    val cornerRadius = 6.dp.toPx()

                    drawIntoCanvas { canvas ->
                        val path = android.graphics.Path().apply {
                            addRoundRect(left, top, left + side, top + side, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.clipPath(path)
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(side.toInt(), side.toInt())
                        )
                        canvas.nativeCanvas.restore()
                    }

                    selX = left + sat * side
                    selY = top + (1f - valB) * side
                }
            }
        }

        // Inner Reticle
        drawCircle(Color.White, radius = 6.5.dp.toPx(), center = Offset(selX, selY), style = Stroke(2.2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.65f), radius = 4.5.dp.toPx(), center = Offset(selX, selY), style = Stroke(1.dp.toPx()))
    }
}
