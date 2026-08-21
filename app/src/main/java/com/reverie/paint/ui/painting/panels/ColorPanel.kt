/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.*
import com.reverie.paint.ui.painting.layers.LayerListView
import com.reverie.paint.ui.painting.layers.LayerPanel

/**
 * Calculates RGB integer from (hue, s, v) based on the given color model.
 * Models: "hsv" (Default/Standard), "v-hsv" (SAI vibrant darks), "hsl" (Lightness), "hsy" (Perceptual Luma)
 */
private fun hsvModelToRgb(hue: Float, s: Float, val_y: Float, mode: String): Int {
    val hp = (hue % 360f + 360f) % 360f / 60.0
    val c: Double
    val m: Double
    val x_val: Double

    when (mode) {
        "v-hsv" -> {
            val s_adj = if (s > 0f) s.toDouble().pow((val_y + 0.5) / 1.5) else 0.0
            c = val_y * s_adj
            m = val_y - c
            x_val = c * (1.0 - abs(hp % 2.0 - 1.0))
        }
        "hsl" -> {
            c = (1.0 - abs(2.0 * val_y - 1.0)) * s
            x_val = c * (1.0 - abs(hp % 2.0 - 1.0))
            m = val_y - c / 2.0
        }
        "hsy" -> {
            c = val_y.toDouble() * s
            x_val = c * (1.0 - abs(hp % 2.0 - 1.0))
            m = val_y - (0.299 * c + 0.587 * x_val)
        }
        else -> { // "hsv"
            c = val_y.toDouble() * s
            x_val = c * (1.0 - abs(hp % 2.0 - 1.0))
            m = val_y - c
        }
    }

    val r: Double
    val g: Double
    val b: Double
    when {
        hp < 1.0 -> { r = c; g = x_val; b = 0.0 }
        hp < 2.0 -> { r = x_val; g = c; b = 0.0 }
        hp < 3.0 -> { r = 0.0; g = c; b = x_val }
        hp < 4.0 -> { r = 0.0; g = x_val; b = c }
        hp < 5.0 -> { r = x_val; g = 0.0; b = c }
        else -> { r = c; g = 0.0; b = x_val }
    }

    val R = ((r + m) * 255.0).roundToInt().coerceIn(0, 255)
    val G = ((g + m) * 255.0).roundToInt().coerceIn(0, 255)
    val B = ((b + m) * 255.0).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (R shl 16) or (G shl 8) or B
}

@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.96f,
    hazeState: HazeState? = null,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }
    var isInteracting by remember { mutableStateOf(false) }

    // Sync from vm.brushColor
    LaunchedEffect(vm.brushColor) {
        if (isInteracting) return@LaunchedEffect
        try {
            val c = android.graphics.Color.parseColor(vm.brushColor)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            val isAchromatic = hsv[1] < 0.01f || hsv[2] < 0.01f
            val dh = if (isAchromatic) 0f else abs(hsv[0] - hue)
            val ds = abs(hsv[1] - sat)
            val dv = abs(hsv[2] - valB)

            if (dh > 2f || ds > 0.02f || dv > 0.02f) {
                if (!isAchromatic) {
                    hue = hsv[0]
                }
                sat = hsv[1]
                valB = hsv[2]
            }
        } catch (e: Exception) { }
    }

    val updateColorHsv = { h: Float, s: Float, v: Float ->
        val rgb = hsvModelToRgb(h, s, v, vm.colorModel)
        val hex = "#%06X".format(rgb and 0xFFFFFF)
        vm.updateBrushColor(hex)
    }

    val context = LocalContext.current
    val panelShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose)
            .systemHoverIcon(context)
    ) {
        Column(
            modifier = Modifier
                .systemHoverIcon(context)
                .align(Alignment.BottomStart)
                .padding(start = 44.dp, bottom = 16.dp)
                .width(280.dp)
                .shadow(16.dp, panelShape)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.1f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.1f, 0.98f))),
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
            // 1. Header: Drag handle pill + Title + Color Preview Swatches
            ColorPanelHeader(
                activeTab = vm.colorPanelTab,
                brushColor = vm.brushColor,
                secondaryColor = vm.brushSecondaryColor,
                onSwapColors = { vm.swapColors() }
            )

            Spacer(Modifier.height(8.dp))

            // 2. Dynamic Content Body (Snappy transition like LayerPanel)
            Box(modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = vm.colorPanelTab,
                    transitionSpec = {
                        fadeIn(tween(110, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(70))
                    },
                    label = "ColorPanelTabAnimation"
                ) { tab ->
                    when (tab) {
                        0 -> WheelColorPage(
                            vm = vm,
                            hue = hue,
                            sat = sat,
                            valB = valB,
                            onHue = { hue = it; updateColorHsv(it, sat, valB) },
                            onSatVal = { s, v -> sat = s; valB = v; updateColorHsv(hue, s, v) },
                            onSat = { sat = it; updateColorHsv(hue, it, valB) },
                            onVal = { valB = it; updateColorHsv(hue, sat, it) },
                            onInteractionStart = { isInteracting = true },
                            onInteractionEnd = { isInteracting = false }
                        )
                        1 -> SquareHsbColorPage(
                            vm = vm,
                            hue = hue,
                            sat = sat,
                            valB = valB,
                            onHue = { hue = it; updateColorHsv(it, sat, valB) },
                            onSatVal = { s, v -> sat = s; valB = v; updateColorHsv(hue, s, v) },
                            onInteractionStart = { isInteracting = true },
                            onInteractionEnd = { isInteracting = false }
                        )
                        2 -> PalettesPage(
                            vm = vm,
                            onColorSelected = { hex ->
                                vm.updateBrushColor(hex)
                            }
                        )
                        3 -> SlidersNumericPage(
                            vm = vm,
                            hue = hue,
                            sat = sat,
                            valB = valB,
                            onHsvChange = { h, s, v ->
                                hue = h; sat = s; valB = v
                                updateColorHsv(h, s, v)
                            },
                            onInteractionStart = { isInteracting = true },
                            onInteractionEnd = { isInteracting = false }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 3. Bottom 4 Navigation Tabs (Wheel, Square/Card, Palettes Grid, Sliders)
            ColorPanelBottomTabs(
                selectedTab = vm.colorPanelTab,
                onTabSelect = { vm.updateColorPanelTab(it) }
            )
        }
    }
}

@Composable
private fun ColorPanelHeader(
    activeTab: Int,
    brushColor: String,
    secondaryColor: String,
    onSwapColors: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Drag Handle Pill
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(36.dp, 3.5.dp)
                .clip(CircleShape)
                .background(Morandi.subText.copy(alpha = 0.45f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (activeTab == 2) "色卡" else "颜色",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // Foreground / Background Colors Swap Box
            Box(
                modifier = Modifier
                    .size(34.dp, 24.dp)
                    .noRippleClickable(onSwapColors),
                contentAlignment = Alignment.Center
            ) {
                // Background Color Box (bottom right)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(android.graphics.Color.parseColor(secondaryColor)))
                        .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                )
                // Foreground Color Box (top left)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(android.graphics.Color.parseColor(brushColor)))
                        .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

/**
 * Tab 0: Color Wheel View with larger wheel (205dp), transparent corner buttons,
 * dynamic color model switching, snug sliders, and compact square memory colors.
 */
@Composable
private fun WheelColorPage(
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
        // 1. Center Wheel Picker (larger 205dp) with transparent corner buttons
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

            // [⋯] Shape Menu Button at bottom-left corner of the wheel
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
                        // 1. Square (■)
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

                        // 2. Triangle (▶)
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

                        // 3. Circle (●)
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

            // [Color Model Switcher Button] at bottom-right corner of the wheel
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
                colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                onValueChange = onHue
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
                onValueChange = { onSat(it / 100f) }
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
                onValueChange = { onVal(it / 100f) }
            )
        }

        // 3. "记忆色" (Recents) Grid (2 rows x 8 colors, Compact Square chips)
        RecentColorsSection(
            recentColors = vm.recentColors,
            onColorSelect = { hex -> vm.updateBrushColor(hex) },
            onClear = { vm.clearRecentColors() }
        )
    }
}

/**
 * Tab 1: Square / HSB Color Page (Image 2)
 */
@Composable
private fun SquareHsbColorPage(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHue: (Float) -> Unit,
    onSatVal: (Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit
) {
    var sqBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastModelForBmp by remember { mutableStateOf("") }
    var lastSizeForBmp by remember { mutableStateOf(0) }

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
                .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(hue, vm.colorModel) {
                        awaitEachGesture {
                            val down = awaitFirstDown().also { it.consume() }
                            onInteractionStart()
                            val updateSv = { pos: Offset ->
                                val s = (pos.x / size.width).coerceIn(0f, 1f)
                                val v = (1f - pos.y / size.height).coerceIn(0f, 1f)
                                onSatVal(s, v)
                            }
                            updateSv(down.position)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                updateSv(change.position)
                                change.consume()
                            }
                            onInteractionEnd()
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val res = 80
                if (sqBmp == null || abs(lastHueForBmp - hue) > 1f || lastModelForBmp != vm.colorModel || lastSizeForBmp != res) {
                    val bmp = Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(res * res)
                    for (y in 0 until res) {
                        val vy = 1.0f - (y / (res - 1.0f))
                        for (x in 0 until res) {
                            val sx = x / (res - 1.0f)
                            pixels[y * res + x] = hsvModelToRgb(hue, sx, vy, vm.colorModel)
                        }
                    }
                    bmp.setPixels(pixels, 0, res, 0, 0, res, res)
                    sqBmp = bmp.asImageBitmap()
                    lastHueForBmp = hue
                    lastModelForBmp = vm.colorModel
                    lastSizeForBmp = res
                }

                sqBmp?.let { bmp ->
                    drawImage(
                        image = bmp,
                        dstSize = IntSize(w.toInt(), h.toInt())
                    )
                }

                // Selector Reticle
                val selX = sat * w
                val selY = (1f - valB) * h
                drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(selX, selY), style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black, radius = 5.dp.toPx(), center = Offset(selX, selY), style = Stroke(1.dp.toPx()))
            }
        }

        // 2. Rainbow Hue Slider
        CompactHsvSlider(
            label = "",
            value = hue,
            max = 360f,
            colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = onHue,
            showValueText = false
        )

        // 3. Memory Colors (Compact Square chips)
        RecentColorsSection(
            recentColors = vm.recentColors,
            onColorSelect = { hex -> vm.updateBrushColor(hex) },
            onClear = { vm.clearRecentColors() }
        )
    }
}

/**
 * Tab 2: Palettes Page (Image 3)
 * Full capabilities:
 * - Default palettes: 基本色, 莫兰迪 (all fully editable and deletable)
 * - Standard animated DropdownMenus (aligned with HomePage & LayerListView)
 * - Compact square color chips (support deletion of individual colors)
 * - [💧+] Add current color to chosen palette dialog (Morandi themed)
 * - [+] Create new palette / Import from image menu
 */
@Composable
private fun PalettesPage(
    vm: PaintViewModel,
    onColorSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showCreatePaletteDialog by remember { mutableStateOf(false) }
    var newPaletteName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf<PaintViewModel.ColorPaletteItem?>(null) }
    var renamePaletteText by remember { mutableStateOf("") }
    var showAddColorPalettePicker by remember { mutableStateOf(false) }
    var showTopPlusMenu by remember { mutableStateOf(false) }
    var activeMenuPalette by remember { mutableStateOf<PaintViewModel.ColorPaletteItem?>(null) }

    // Image Picker Launcher for palette extraction
    val importPaletteImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(input)
                input?.close()
                if (bitmap != null) {
                    vm.importPaletteFromBitmap(bitmap, "图片色卡")
                    Toast.makeText(context, "已从图片导入色卡", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top Action Bar on Palettes Page: [💧+ Add Color to Palette] and [+ Create / Import]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [💧+] Button: Add color to chosen palette
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { showAddColorPalettePicker = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = "添加颜色至色卡",
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // [+] Button: Create / Import Palette
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { showTopPlusMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Morandi.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Light
                    )
                }

                DropdownMenu(
                    expanded = showTopPlusMenu,
                    onDismissRequest = { showTopPlusMenu = false },
                    modifier = Modifier
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("新建色卡", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder_plus),
                                contentDescription = null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            showTopPlusMenu = false
                            newPaletteName = ""
                            showCreatePaletteDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从图片导入色卡", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_bookmark_plus),
                                contentDescription = null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            showTopPlusMenu = false
                            importPaletteImageLauncher.launch("image/*")
                        }
                    )
                }
            }
        }

        // Scrollable list of palettes
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (vm.allPalettes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无色卡，点击右上角 + 创建", color = Morandi.subText, fontSize = 12.sp)
                }
            }

            for (palette in vm.allPalettes) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header: Palette Name + [⋯] menu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = palette.name,
                            color = Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Box {
                            Text(
                                text = "⋯",
                                color = Morandi.subText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { activeMenuPalette = palette }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )

                            DropdownMenu(
                                expanded = activeMenuPalette?.id == palette.id,
                                onDismissRequest = { activeMenuPalette = null },
                                modifier = Modifier
                                    .background(Morandi.panel)
                                    .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制色卡", color = Morandi.text, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_copy),
                                            contentDescription = null,
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        vm.duplicatePalette(palette.id)
                                        activeMenuPalette = null
                                        Toast.makeText(context, "已复制色卡", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名", color = Morandi.text, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_brush),
                                            contentDescription = null,
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        renamePaletteText = palette.name
                                        showRenameDialog = palette
                                        activeMenuPalette = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除色卡", color = Color(0xFFE55858), fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_erase),
                                            contentDescription = null,
                                            tint = Color(0xFFE55858),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        vm.deletePalette(palette.id)
                                        activeMenuPalette = null
                                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    // Dynamic row count Grid with Compact Square Swatches (long-press to delete color)
                    SquarePaletteSwatchesGrid(
                        colors = palette.colors,
                        onColorSelect = onColorSelected,
                        onColorLongPress = { colorIdx ->
                            vm.removeColorFromPalette(palette.id, colorIdx)
                            Toast.makeText(context, "已移除颜色", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Dialog: Add Current Color to Palette Picker (Morandi Themed)
    if (showAddColorPalettePicker) {
        AlertDialog(
            onDismissRequest = { showAddColorPalettePicker = false },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("添加当前颜色至色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (pal in vm.allPalettes) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF222428))
                                .border(1.dp, Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .clickable {
                                    vm.addColorToPalette(pal.id, vm.brushColor)
                                    showAddColorPalettePicker = false
                                    Toast.makeText(context, "已存入 ${pal.name}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = pal.name, color = Morandi.text, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddColorPalettePicker = false }) {
                    Text("取消", color = Morandi.subText)
                }
            }
        )
    }

    // Dialog: Create New Palette (Morandi Themed)
    if (showCreatePaletteDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePaletteDialog = false },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("新建色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPaletteName,
                    onValueChange = { newPaletteName = it },
                    placeholder = { Text("请输入色卡名称", color = Morandi.subText, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPaletteName.isNotBlank()) {
                        vm.createNewPalette(newPaletteName.trim(), listOf(vm.brushColor))
                        showCreatePaletteDialog = false
                    }
                }) {
                    Text("创建", color = Morandi.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePaletteDialog = false }) {
                    Text("取消", color = Morandi.subText)
                }
            }
        )
    }

    // Dialog: Rename Palette (Morandi Themed)
    if (showRenameDialog != null) {
        val palToRename = showRenameDialog!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("重命名色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renamePaletteText,
                    onValueChange = { renamePaletteText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renamePaletteText.isNotBlank()) {
                        vm.renamePalette(palToRename.id, renamePaletteText.trim())
                        showRenameDialog = null
                    }
                }) {
                    Text("确定", color = Morandi.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("取消", color = Morandi.subText)
                }
            }
        )
    }
}

/**
 * Tab 3: Sliders / Numeric Tuning Page (Image 4)
 */
@Composable
private fun SlidersNumericPage(
    vm: PaintViewModel,
    hue: Float,
    sat: Float,
    valB: Float,
    onHsvChange: (Float, Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val currentColorInt = remember(hue, sat, valB, vm.colorModel) {
        hsvModelToRgb(hue, sat, valB, vm.colorModel)
    }
    val r = AColor.red(currentColorInt)
    val g = AColor.green(currentColorInt)
    val b = AColor.blue(currentColorInt)
    val hexStr = remember(currentColorInt) {
        "%06X".format(currentColorInt and 0xFFFFFF)
    }

    // RGB -> CMYK
    val rPrime = r / 255f
    val gPrime = g / 255f
    val bPrime = b / 255f
    val kVal = 1f - max(rPrime, max(gPrime, bPrime))
    val cVal = if (kVal >= 0.999f) 0f else (1f - rPrime - kVal) / (1f - kVal)
    val mVal = if (kVal >= 0.999f) 0f else (1f - gPrime - kVal) / (1f - kVal)
    val yVal = if (kVal >= 0.999f) 0f else (1f - bPrime - kVal) / (1f - kVal)

    val updateFromRgb = { newR: Int, newG: Int, newB: Int ->
        val c = AColor.rgb(newR, newG, newB)
        val hsv = FloatArray(3)
        AColor.colorToHSV(c, hsv)
        onHsvChange(hsv[0], hsv[1], hsv[2])
    }

    val updateFromCmyk = { newC: Float, newM: Float, newY: Float, newK: Float ->
        val cr = (255 * (1f - newC) * (1f - newK)).roundToInt().coerceIn(0, 255)
        val cg = (255 * (1f - newM) * (1f - newK)).roundToInt().coerceIn(0, 255)
        val cb = (255 * (1f - newY) * (1f - newK)).roundToInt().coerceIn(0, 255)
        updateFromRgb(cr, cg, cb)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. HSV Sliders
        CompactHsvSlider(
            label = "H",
            value = hue,
            max = 360f,
            colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { onHsvChange(it, sat, valB) }
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
            onValueChange = { onHsvChange(hue, it / 100f, valB) }
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
            onValueChange = { onHsvChange(hue, sat, it / 100f) }
        )

        Spacer(Modifier.height(2.dp))

        // 2. RGB Sliders
        CompactHsvSlider(
            label = "R",
            value = r.toFloat(),
            max = 255f,
            colors = listOf(Color.Black, Color.Red),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromRgb(it.roundToInt(), g, b) }
        )
        CompactHsvSlider(
            label = "G",
            value = g.toFloat(),
            max = 255f,
            colors = listOf(Color.Black, Color.Green),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromRgb(r, it.roundToInt(), b) }
        )
        CompactHsvSlider(
            label = "B",
            value = b.toFloat(),
            max = 255f,
            colors = listOf(Color.Black, Color.Blue),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromRgb(r, g, it.roundToInt()) }
        )

        // 3. Hex code row + Copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF222428))
                    .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("hex", "#$hexStr"))
                        Toast.makeText(context, "已复制 #$hexStr", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "复制", color = Morandi.subText, fontSize = 11.sp)
                Text(text = "# $hexStr", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 4. CMYK Sliders
        CompactHsvSlider(
            label = "C",
            value = cVal * 100f,
            max = 100f,
            colors = listOf(Color.Black, Color.Cyan),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(it / 100f, mVal, yVal, kVal) }
        )
        CompactHsvSlider(
            label = "M",
            value = mVal * 100f,
            max = 100f,
            colors = listOf(Color.Black, Color.Magenta),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, it / 100f, yVal, kVal) }
        )
        CompactHsvSlider(
            label = "Y",
            value = yVal * 100f,
            max = 100f,
            colors = listOf(Color.Black, Color.Yellow),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, mVal, it / 100f, kVal) }
        )
        CompactHsvSlider(
            label = "K",
            value = kVal * 100f,
            max = 100f,
            colors = listOf(Color.Red, Color.Black),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, mVal, yVal, it / 100f) }
        )
    }
}

/**
 * 2x8 Swatches Grid component for Recent Colors (Compact 1:1 Square Chips)
 */
@Composable
private fun RecentColorsSection(
    recentColors: List<String>,
    onColorSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "记忆色",
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "清除",
                color = Morandi.subText,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        SquarePaletteSwatchesGrid(
            colors = recentColors,
            onColorSelect = onColorSelect
        )
    }
}

/**
 * Dynamic row count Grid with Compact Square Swatches (8 columns)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SquarePaletteSwatchesGrid(
    colors: List<String>,
    onColorSelect: (String) -> Unit,
    onColorLongPress: ((Int) -> Unit)? = null
) {
    val cols = 8
    val count = maxOf(16, colors.size)
    val rows = (count + cols - 1) / cols

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1D20))
            .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val hex = colors.getOrNull(idx)
                    val bg = if (hex != null) {
                        try {
                            Color(android.graphics.Color.parseColor(hex))
                        } catch (e: Exception) {
                            Color.Transparent
                        }
                    } else {
                        Color(0xFF26282C)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bg)
                            .border(0.5.dp, Morandi.border.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                            .then(
                                if (hex != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onColorSelect(hex) },
                                        onLongClick = { onColorLongPress?.invoke(idx) }
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * Bottom 4 Navigation Tabs (Wheel, Square/Card, Palettes Grid, Sliders)
 */
@Composable
private fun ColorPanelBottomTabs(
    selectedTab: Int,
    onTabSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E2024)),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab 0: Wheel (◎)
        BottomTabButton(
            selected = selectedTab == 0,
            onClick = { onTabSelect(0) }
        ) { tint ->
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(tint, radius = size.minDimension / 2f, style = Stroke(2.2.dp.toPx()))
                drawCircle(tint, radius = size.minDimension / 4.5f, style = Stroke(2.dp.toPx()))
            }
        }

        // Tab 1: Square / Card (□)
        BottomTabButton(
            selected = selectedTab == 1,
            onClick = { onTabSelect(1) }
        ) { tint ->
            Box(
                modifier = Modifier
                    .size(16.dp, 18.dp)
                    .border(2.dp, tint, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(tint, CircleShape)
                )
            }
        }

        // Tab 2: Palettes Grid (田)
        BottomTabButton(
            selected = selectedTab == 2,
            onClick = { onTabSelect(2) }
        ) { tint ->
            Canvas(modifier = Modifier.size(18.dp)) {
                val gap = 2.dp.toPx()
                val itemW = (size.width - gap * 2) / 3f
                val itemH = (size.height - gap * 2) / 3f
                for (i in 0..2) {
                    for (j in 0..2) {
                        drawRect(
                            color = tint,
                            topLeft = Offset(i * (itemW + gap), j * (itemH + gap)),
                            size = Size(itemW, itemH)
                        )
                    }
                }
            }
        }

        // Tab 3: Sliders (三)
        BottomTabButton(
            selected = selectedTab == 3,
            onClick = { onTabSelect(3) }
        ) { tint ->
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(18.dp, 2.2.dp).background(tint, RoundedCornerShape(1.dp)))
                Box(Modifier.size(13.dp, 2.2.dp).background(tint, RoundedCornerShape(1.dp)))
                Box(Modifier.size(18.dp, 2.2.dp).background(tint, RoundedCornerShape(1.dp)))
            }
        }
    }
}

@Composable
private fun BottomTabButton(
    selected: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp, 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        iconContent(if (selected) Morandi.accent else Morandi.icon)
    }
}

/**
 * Inner Canvas for Color Wheel + Selected SV shape (Square / Triangle / Circle)
 * Supports dynamic Color Models (HSV, v-HSV, HSL, HSY') and true circular polar math.
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
    var shapeBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastHueForBmp by remember { mutableFloatStateOf(-1f) }
    var lastShapeForBmp by remember { mutableStateOf("") }
    var lastModelForBmp by remember { mutableStateOf("") }
    var lastSizeForBmp by remember { mutableStateOf(0) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(shape, colorModel) {
                awaitEachGesture {
                    val down = awaitFirstDown().also { it.consume() }
                    onInteractionStart()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringSize = size.height
                    val strokeWidth = 16.dp.toPx()
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
                                    onSatVal(s, v)
                                }
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
        val strokeWidth = 16.dp.toPx()
        val R = (ringSize - strokeWidth) / 2f
        val innerR = R - strokeWidth / 2f - 4.dp.toPx()

        // 1. Hue Ring Sweep Gradient
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

        // 2. Hue Ring Indicator
        val angle = (hue + 180f) * Math.PI / 180f
        val sx = cx + cos(angle).toFloat() * R
        val sy = cy + sin(angle).toFloat() * R
        drawCircle(Color.White, radius = 7.5.dp.toPx(), center = Offset(sx, sy), style = Stroke(2.5.dp.toPx()))

        // 3. Inner SV Shape (Square / Triangle / Circle)
        val bmpRes = 72
        if (shapeBmp == null || abs(lastHueForBmp - hue) > 1f || lastShapeForBmp != shape || lastModelForBmp != colorModel || lastSizeForBmp != bmpRes) {
            val bmp = Bitmap.createBitmap(bmpRes, bmpRes, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(bmpRes * bmpRes)

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
                                pixels[y * bmpRes + x] = hsvModelToRgb(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), colorModel)
                            } else {
                                pixels[y * bmpRes + x] = AColor.TRANSPARENT
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
                                pixels[y * bmpRes + x] = hsvModelToRgb(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), colorModel)
                            } else {
                                pixels[y * bmpRes + x] = AColor.TRANSPARENT
                            }
                        }
                    }
                }
                else -> { // "SQUARE"
                    for (y in 0 until bmpRes) {
                        val vy = 1f - (y / (bmpRes - 1f))
                        for (x in 0 until bmpRes) {
                            val sx = x / (bmpRes - 1f)
                            pixels[y * bmpRes + x] = hsvModelToRgb(hue, sx, vy, colorModel)
                        }
                    }
                }
            }
            bmp.setPixels(pixels, 0, bmpRes, 0, 0, bmpRes, bmpRes)
            shapeBmp = bmp.asImageBitmap()
            lastHueForBmp = hue
            lastShapeForBmp = shape
            lastModelForBmp = colorModel
            lastSizeForBmp = bmpRes
        }

        var selX = cx
        var selY = cy

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
                    shapeBmp?.let { bmp ->
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset((cx - innerR).toInt(), (cy - innerR).toInt()),
                            dstSize = IntSize((innerR * 2).toInt(), (innerR * 2).toInt())
                        )
                    }
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
                    shapeBmp?.let { bmp ->
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset((cx - innerR).toInt(), (cy - innerR).toInt()),
                            dstSize = IntSize((innerR * 2).toInt(), (innerR * 2).toInt())
                        )
                    }
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
                    shapeBmp?.let { bmp ->
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(side.toInt(), side.toInt())
                        )
                    }
                    canvas.nativeCanvas.restore()
                }

                selX = left + sat * side
                selY = top + (1f - valB) * side
            }
        }

        // Inner Reticle
        drawCircle(Color.White, radius = 6.5.dp.toPx(), center = Offset(selX, selY), style = Stroke(2.2.dp.toPx()))
        drawCircle(Color.Black, radius = 4.5.dp.toPx(), center = Offset(selX, selY), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun CompactHsvSlider(
    label: String,
    value: Float,
    max: Float,
    colors: List<Color>,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    onValueChange: (Float) -> Unit,
    showValueText: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(14.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(9.dp)
                .clip(RoundedCornerShape(4.5.dp))
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
                val cx = ((value / max).coerceIn(0f, 1f)) * size.width
                drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(cx, size.height / 2f))
                drawCircle(Color(0xFF222222), radius = 5.dp.toPx(), center = Offset(cx, size.height / 2f), style = Stroke(1.2.dp.toPx()))
            }
        }
        if (showValueText) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${value.roundToInt()}",
                color = Morandi.text,
                fontSize = 11.sp,
                modifier = Modifier.width(26.dp)
            )
        }
    }
}
