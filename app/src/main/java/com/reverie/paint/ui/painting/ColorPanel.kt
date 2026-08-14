package com.reverie.paint.ui.painting

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ColorPickerMode { SQUARE, TRIANGLE, CIRCLE, SLIDERS }

@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
    hazeState: HazeState? = null,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }
    var isInteracting by remember { mutableStateOf(false) }

    val mode = remember(vm.colorPickerMode) {
        try {
            ColorPickerMode.valueOf(vm.colorPickerMode)
        } catch (e: Exception) {
            ColorPickerMode.SQUARE
        }
    }

    val updateVmColor = { h: Float, s: Float, v: Float ->
        val c = AColor.HSVToColor(floatArrayOf(h, s, v))
        val hex = "#%06X".format(c and 0xFFFFFF)
        vm.updateBrushColor(hex)
    }

    // Sync from vm.brushColor on open / external change
    LaunchedEffect(vm.brushColor) {
        if (isInteracting) return@LaunchedEffect
        try {
            val c = android.graphics.Color.parseColor(vm.brushColor)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            val isAchromatic = hsv[1] < 0.01f || hsv[2] < 0.01f
            val dh = if (isAchromatic) 0f else kotlin.math.abs(hsv[0] - hue)
            val ds = kotlin.math.abs(hsv[1] - sat)
            val dv = kotlin.math.abs(hsv[2] - valB)
            
            if (dh > 2f || ds > 0.02f || dv > 0.02f) {
                if (!isAchromatic) {
                    hue = hsv[0]
                }
                sat = hsv[1]
                valB = hsv[2]
            }
        } catch (e: Exception) { }
    }

    val panelShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose)
    ) {
        Column(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Default)
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 16.dp)
                .width(270.dp)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f))),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f
                            )
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(12.dp)
        ) {
            // Header Row: Title + Color Preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("颜色", color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                
                // Foreground / Background Colors
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            
            Spacer(Modifier.height(10.dp))

            // Body with smooth transition animation
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                     scaleIn(initialScale = 0.95f, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            fadeOut(animationSpec = tween(140)) +
                            scaleOut(targetScale = 0.95f, animationSpec = tween(140))
                        )
                },
                label = "ColorPickerModeAnimation"
            ) { targetMode ->
                Column {
                    when (targetMode) {
                        ColorPickerMode.SQUARE, ColorPickerMode.TRIANGLE, ColorPickerMode.CIRCLE -> {
                            WheelPicker(
                                mode = targetMode,
                                hue = hue,
                                sat = sat,
                                valB = valB,
                                onHue = { hue = it; updateVmColor(it, sat, valB) },
                                onSatVal = { s, v -> sat = s; valB = v; updateVmColor(hue, s, v) },
                                onInteractionStart = { isInteracting = true },
                                onInteractionEnd = { isInteracting = false }
                            )
                            Spacer(Modifier.height(10.dp))
                            HsvSliders(
                                hue = hue,
                                sat = sat,
                                valB = valB,
                                onHue = { hue = it; updateVmColor(it, sat, valB) },
                                onSat = { sat = it; updateVmColor(hue, it, valB) },
                                onVal = { valB = it; updateVmColor(hue, sat, it) },
                                onInteractionStart = { isInteracting = true },
                                onInteractionEnd = { isInteracting = false }
                            )
                        }
                        ColorPickerMode.SLIDERS -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                HsvSliders(
                                    hue = hue,
                                    sat = sat,
                                    valB = valB,
                                    onHue = { hue = it; updateVmColor(it, sat, valB) },
                                    onSat = { sat = it; updateVmColor(hue, it, valB) },
                                    onVal = { valB = it; updateVmColor(hue, sat, it) },
                                    onInteractionStart = { isInteracting = true },
                                    onInteractionEnd = { isInteracting = false }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon(ColorPickerMode.SQUARE, mode == ColorPickerMode.SQUARE) { vm.updateColorPickerMode("SQUARE") }
                NavIcon(ColorPickerMode.TRIANGLE, mode == ColorPickerMode.TRIANGLE) { vm.updateColorPickerMode("TRIANGLE") }
                NavIcon(ColorPickerMode.CIRCLE, mode == ColorPickerMode.CIRCLE) { vm.updateColorPickerMode("CIRCLE") }
                NavIcon(ColorPickerMode.SLIDERS, mode == ColorPickerMode.SLIDERS) { vm.updateColorPickerMode("SLIDERS") }
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
            ColorPickerMode.SQUARE -> Box(Modifier.size(18.dp).border(2.dp, tint, RoundedCornerShape(3.dp)))
            ColorPickerMode.CIRCLE -> Box(Modifier.size(18.dp).border(2.dp, tint, CircleShape))
            ColorPickerMode.TRIANGLE -> Canvas(Modifier.size(18.dp)) {
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
                Box(Modifier.size(18.dp, 2.dp).background(tint))
                Box(Modifier.size(12.dp, 2.dp).background(tint))
                Box(Modifier.size(18.dp, 2.dp).background(tint))
            }
        }
    }
}

private fun generateTriangleBitmap(size: Int, hue: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    val cx = size / 2f
    val cy = size / 2f
    val R = size / 2f
    val H = R * sqrt(3f) / 2f
    val W = 1.5f * R
    
    val left = cx - R/2f
    
    for (y in 0 until size) {
        val y0 = y - cy
        for (x in 0 until size) {
            val x0 = x - left
            val wC = x0 / W
            val wA = (1f - x0/W - y0/H) / 2f
            val wB = (1f - x0/W + y0/H) / 2f
            
            // Extend barycentric limits to allow drawing slightly outside, clipping path handles the edges
            if (wC >= -0.05f && wA >= -0.05f && wB >= -0.05f) {
                val cwC = wC.coerceIn(0f, 1f)
                val cwA = wA.coerceIn(0f, 1f)
                val v = cwA + cwC
                val s = if (v > 0) cwC / v else 0f
                
                val c = AColor.HSVToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
                pixels[y * size + x] = c
            } else {
                pixels[y * size + x] = AColor.TRANSPARENT
            }
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}

@Composable
private fun WheelPicker(
    mode: ColorPickerMode,
    hue: Float, sat: Float, valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    // Generate bitmap for triangle mode
    var triangleBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastSizeForBmp by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mode) {
                    awaitEachGesture {
                        val down = awaitFirstDown().also { it.consume() }
                        onInteractionStart()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val ringSize = size.height
                        val strokeWidth = 14.dp.toPx()
                        val R = (ringSize - strokeWidth) / 2f
                        val innerR = R - strokeWidth / 2f - 4.dp.toPx()
                        
                        val dx = down.position.x - cx
                        val dy = down.position.y - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        val isRing = dist >= R - strokeWidth / 2f
                        
                        fun update(pos: Offset) {
                            val x = pos.x
                            val y = pos.y
                            if (isRing) {
                                val px = x - cx
                                val py = y - cy
                                val rawAngle = (atan2(py, px) * 180 / Math.PI).toFloat()
                                val h = (rawAngle - 180f + 360f) % 360f
                                onHue(h)
                            } else {
                                when (mode) {
                                    ColorPickerMode.TRIANGLE -> {
                                        val x0 = x - (cx - innerR/2f)
                                        val y0 = y - cy
                                        val W = 1.5f * innerR
                                        val H = innerR * kotlin.math.sqrt(3f) / 2f
                                        val wC = x0 / W
                                        val wA = (1f - x0/W - y0/H) / 2f
                                        val wB = (1f - x0/W + y0/H) / 2f
                                        
                                        var cwC = wC.coerceIn(0f, 1f)
                                        var cwA = wA.coerceIn(0f, 1f)
                                        var cwB = wB.coerceIn(0f, 1f)
                                        val sum = cwC + cwA + cwB
                                        if (sum > 0) { cwC /= sum; cwA /= sum; cwB /= sum }
                                        else { cwA = 1f; cwB = 0f; cwC = 0f }
                                        
                                        val v = cwA + cwC
                                        val s = if (v > 0) cwC / v else 0f
                                        onSatVal(s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                                    }
                                    ColorPickerMode.SQUARE -> {
                                        val side = 2f * innerR / kotlin.math.sqrt(2f)
                                        val left = cx - side / 2f
                                        val top = cy - side / 2f
                                        val s = ((x - left) / side).coerceIn(0f, 1f)
                                        val v = (1f - (y - top) / side).coerceIn(0f, 1f)
                                        onSatVal(s, v)
                                    }
                                    ColorPickerMode.CIRCLE -> {
                                        val side = 2f * innerR
                                        val left = cx - innerR
                                        val top = cy - innerR
                                        val s = ((x - left) / side).coerceIn(0f, 1f)
                                        val v = (1f - (y - top) / side).coerceIn(0f, 1f)
                                        onSatVal(s, v)
                                    }
                                    else -> {}
                                }
                            }
                        }
                        
                        update(down.position)
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                        onInteractionEnd()
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val ringSize = size.height
            val strokeWidth = 14.dp.toPx()
            val R = (ringSize - strokeWidth) / 2f
            val innerR = R - strokeWidth / 2f - 4.dp.toPx()
            
            // Draw sweep gradient for hue rotated by 180 deg
            val hueColors = listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                Color.Blue, Color.Magenta, Color.Red
            )
            rotate(180f, pivot = Offset(cx, cy)) {
                drawCircle(
                    brush = Brush.sweepGradient(hueColors, center = Offset(cx, cy)),
                    radius = R,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokeWidth)
                )
            }
            
            // Hue selector position (accounting for 180 deg rotation)
            val angle = (hue + 180f) * Math.PI / 180f
            val sx = cx + cos(angle).toFloat() * R
            val sy = cy + sin(angle).toFloat() * R
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(sx, sy), style = Stroke(2.5.dp.toPx()))

            // Draw SV Shape
            var selX = cx
            var selY = cy

            when (mode) {
                ColorPickerMode.TRIANGLE -> {
                    val bmpSize = (innerR * 2).toInt()
                    val H = innerR * kotlin.math.sqrt(3f) / 2f
                    val W = 1.5f * innerR
                    
                    if (bmpSize > 0) {
                        if (triangleBmp == null || kotlin.math.abs(lastHueForBmp - hue) > 1f || lastSizeForBmp != bmpSize) {
                            triangleBmp = generateTriangleBitmap(bmpSize, hue).asImageBitmap()
                            lastHueForBmp = hue
                            lastSizeForBmp = bmpSize
                        }
                        
                        drawIntoCanvas { canvas ->
                            val path = android.graphics.Path().apply {
                                moveTo(cx - innerR/2f, cy - H)
                                lineTo(cx - innerR/2f, cy + H)
                                lineTo(cx + innerR, cy)
                                close()
                            }
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.clipPath(path)
                            triangleBmp?.let { bmp ->
                                drawImage(
                                    image = bmp,
                                    dstOffset = IntOffset((cx - innerR).toInt(), (cy - innerR).toInt()),
                                    dstSize = IntSize(bmpSize, bmpSize)
                                )
                            }
                            canvas.nativeCanvas.restore()
                        }
                    }
                    val wC = sat * valB
                    val wA = valB - wC
                    val wB = 1f - valB
                    val x0 = wC * W
                    val y0 = (wB - wA) * H
                    selX = cx - innerR/2f + x0
                    selY = cy + y0
                }
                ColorPickerMode.SQUARE -> {
                    val side = 2f * innerR / kotlin.math.sqrt(2f)
                    val left = cx - side / 2f
                    val top = cy - side / 2f
                    val cornerRadius = 6.dp.toPx()
                    
                    drawIntoCanvas { canvas ->
                        val path = android.graphics.Path().apply {
                            addRoundRect(left, top, left + side, top + side, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.clipPath(path)
                        
                        val hueColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        
                        val p1 = android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(
                                left, top, left + side, top,
                                android.graphics.Color.WHITE, hueColor,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                        canvas.nativeCanvas.drawRect(left, top, left + side, top + side, p1)
                        
                        val p2 = android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(
                                left, top, left, top + side,
                                android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                        canvas.nativeCanvas.drawRect(left, top, left + side, top + side, p2)
                        canvas.nativeCanvas.restore()
                    }
                    
                    selX = left + sat * side
                    selY = top + (1f - valB) * side
                }
                ColorPickerMode.CIRCLE -> {
                    val side = 2f * innerR
                    val left = cx - innerR
                    val top = cy - innerR
                    
                    drawIntoCanvas { canvas ->
                        val path = android.graphics.Path().apply {
                            addCircle(cx, cy, innerR, android.graphics.Path.Direction.CW)
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.clipPath(path)
                        
                        val hueColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        
                        val p1 = android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(
                                left, top, left + side, top,
                                android.graphics.Color.WHITE, hueColor,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                        canvas.nativeCanvas.drawRect(left, top, left + side, top + side, p1)
                        
                        val p2 = android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(
                                left, top, left, top + side,
                                android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                        canvas.nativeCanvas.drawRect(left, top, left + side, top + side, p2)
                        canvas.nativeCanvas.restore()
                    }
                    
                    selX = left + sat * side
                    selY = top + (1f - valB) * side
                }
                else -> {}
            }

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
    onVal: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    Column {
        HsvSlider("H", hue, 360f, listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), onInteractionStart, onInteractionEnd) { onHue(it) }
        Spacer(Modifier.height(6.dp))
        HsvSlider("S", sat, 1f, listOf(Color.White, Color(AColor.HSVToColor(floatArrayOf(hue, 1f, valB)))), onInteractionStart, onInteractionEnd) { onSat(it) }
        Spacer(Modifier.height(6.dp))
        HsvSlider("V", valB, 1f, listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(hue, sat, 1f)))), onInteractionStart, onInteractionEnd) { onVal(it) }
    }
}

@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    max: Float,
    colors: List<Color>,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.horizontalGradient(colors))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown().also { it.consume() }
                        onInteractionStart()
                        val updateVal = { pos: Offset ->
                            val frac = (pos.x / size.width).coerceIn(0f, 1f)
                            onValueChange(frac * max)
                        }
                        updateVal(down.position)
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            updateVal(change.position)
                            change.consume()
                        }
                        onInteractionEnd()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = (value / max) * size.width
                drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(cx, size.height / 2f))
                drawCircle(Color.Black, radius = 5.dp.toPx(), center = Offset(cx, size.height / 2f), style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("${value.toInt()}", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.width(24.dp))
    }
}
