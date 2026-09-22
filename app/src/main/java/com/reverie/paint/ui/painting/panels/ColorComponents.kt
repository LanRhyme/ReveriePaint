/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AColor
import android.widget.Toast
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.components.noRippleClickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.*

/**
 * Calculates RGB integer from (hue, s, v) based on the given color model.
 * Models: "hsv" (Default/Standard), "v-hsv" (SAI vibrant darks), "hsl" (Lightness), "hsy" (Perceptual Luma)
 */
fun hsvModelToRgb(hue: Float, s: Float, val_y: Float, mode: String): Int {
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
            val lumaChroma = when {
                hp < 1.0 -> 0.299 * c + 0.587 * x_val
                hp < 2.0 -> 0.299 * x_val + 0.587 * c
                hp < 3.0 -> 0.587 * c + 0.114 * x_val
                hp < 4.0 -> 0.587 * x_val + 0.114 * c
                hp < 5.0 -> 0.299 * x_val + 0.114 * c
                else -> 0.299 * c + 0.114 * x_val
            }
            m = val_y - lumaChroma
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

/**
 * Inverts an RGB integer color into (hue, s, val_y) for the specified color model.
 * Models: "hsv" (Default), "v-hsv" (SAI vibrant darks), "hsl" (Lightness), "hsy" (Perceptual Luma)
 */
fun rgbToHsvModel(colorInt: Int, mode: String): FloatArray {
    val r = ((colorInt shr 16) and 0xFF) / 255.0
    val g = ((colorInt shr 8) and 0xFF) / 255.0
    val b = (colorInt and 0xFF) / 255.0

    val maxVal = max(r, max(g, b))
    val minVal = min(r, min(g, b))
    val delta = maxVal - minVal

    // 1. Hue calculation (standard 0..360)
    val hue = if (delta < 1e-5) {
        0f
    } else {
        val h = when (maxVal) {
            r -> ((g - b) / delta) % 6.0
            g -> ((b - r) / delta) + 2.0
            else -> ((r - g) / delta) + 4.0
        } * 60.0
        ((h % 360.0 + 360.0) % 360.0).toFloat()
    }

    // 2. Saturation and Value/Lightness/Luma per model
    return when (mode) {
        "v-hsv" -> {
            val v = maxVal.toFloat()
            val sHsv = if (v > 1e-5f) (delta / maxVal).toFloat() else 0f
            val s = if (sHsv > 0f && v > 0f) {
                sHsv.toDouble().pow(1.5 / (v + 0.5)).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            floatArrayOf(hue, s, v)
        }
        "hsl" -> {
            val l = ((maxVal + minVal) / 2.0).toFloat()
            val s = if (delta < 1e-5 || l <= 1e-5f || l >= 0.9999f) {
                0f
            } else {
                val denom = 1.0 - abs(2.0 * l - 1.0)
                (delta / denom).toFloat().coerceIn(0f, 1f)
            }
            floatArrayOf(hue, s, l)
        }
        "hsy" -> {
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
            val s = if (y > 1e-5f) {
                (delta / y).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            floatArrayOf(hue, s, y)
        }
        else -> { // "hsv"
            val v = maxVal.toFloat()
            val s = if (v > 1e-5f) (delta / maxVal).toFloat().coerceIn(0f, 1f) else 0f
            floatArrayOf(hue, s, v)
        }
    }
}

/** Pure hue color for GPU gradient shading */
fun hueToPureColor(hue: Float): Color {
    val hp = (hue % 360f + 360f) % 360f / 60f
    val x = 1f - abs(hp % 2f - 1f)
    val (r, g, b) = when {
        hp < 1f -> Triple(1f, x, 0f)
        hp < 2f -> Triple(x, 1f, 0f)
        hp < 3f -> Triple(0f, 1f, x)
        hp < 4f -> Triple(0f, x, 1f)
        hp < 5f -> Triple(x, 0f, 1f)
        else -> Triple(1f, 0f, x)
    }
    return Color(r, g, b)
}

/** Standard 7-color rainbow spectrum for Hue sliders */
val RainbowHueColors = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan,
    Color.Blue, Color.Magenta, Color.Red
)

/**
 * Compact HSV / RGB / CMYK Slider with touch dragging and optional click-to-edit numeric value
 */
@Composable
fun CompactHsvSlider(
    label: String,
    value: Float,
    max: Float,
    colors: List<Color>,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    onValueChange: (Float) -> Unit,
    showValueText: Boolean = true,
    unitSuffix: String = "",
    allowDirectInput: Boolean = true
) {
    var showDirectInputDialog by remember { mutableStateOf(false) }

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
                .pointerInput(max) {
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
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = 5.dp.toPx(),
                    center = Offset(cx, size.height / 2f),
                    style = Stroke(1.2.dp.toPx())
                )
            }
        }
        if (showValueText) {
            Spacer(Modifier.width(6.dp))
            val textDisplay = "${value.roundToInt()}$unitSuffix"
            Text(
                text = textDisplay,
                color = Morandi.text,
                fontSize = 11.sp,
                modifier = Modifier
                    .width(32.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(enabled = allowDirectInput) {
                        showDirectInputDialog = true
                    }
                    .padding(vertical = 1.dp)
            )
        }
    }

    if (showDirectInputDialog) {
        NumericValueInputDialog(
            label = label.ifEmpty { stringResource(R.string.brush_preview_slider_value) },
            currentValue = value,
            min = 0f,
            max = max,
            unitSuffix = unitSuffix,
            onValueConfirmed = { onValueChange(it) },
            onDismiss = { showDirectInputDialog = false }
        )
    }
}

/**
 * Compact dialog to input an exact numeric value for a slider
 */
@Composable
fun NumericValueInputDialog(
    label: String,
    currentValue: Float,
    min: Float,
    max: Float,
    unitSuffix: String,
    onValueConfirmed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val isIntegerOnly = remember(min, max, currentValue) {
        min == min.roundToInt().toFloat() && max == max.roundToInt().toFloat() && currentValue == currentValue.roundToInt().toFloat()
    }
    val initialText = remember(currentValue, isIntegerOnly) {
        if (isIntegerOnly) {
            currentValue.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", currentValue).trimEnd('0').trimEnd('.')
        }
    }
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                text = stringResource(R.string.color_num_dialog_title, label),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rangeHint = if (isIntegerOnly) {
                    stringResource(R.string.color_num_dialog_range, min.roundToInt().toString(), max.roundToInt().toString(), unitSuffix)
                } else {
                    val minStr = String.format(Locale.US, "%.2f", min).trimEnd('0').trimEnd('.')
                    val maxStr = String.format(Locale.US, "%.2f", max).trimEnd('0').trimEnd('.')
                    stringResource(R.string.color_num_dialog_range, minStr, maxStr, unitSuffix)
                }
                Text(
                    text = rangeHint,
                    color = Morandi.subText,
                    fontSize = 11.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (min < 0f) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi)
                                .noRippleClickable {
                                    text = if (text.startsWith("-")) {
                                        text.removePrefix("-")
                                    } else if (text.isNotEmpty() && text != "0") {
                                        "-$text"
                                    } else {
                                        "-"
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("±", color = Morandi.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input ->
                            val allowed = if (min < 0f) {
                                (input.isEmpty() || input == "-" || input == "-." || input.toFloatOrNull() != null)
                            } else {
                                input.all { it.isDigit() || it == '.' }
                            }
                            if (allowed && input.length <= 7) {
                                text = input
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (min < 0f) KeyboardType.Text else if (isIntegerOnly) KeyboardType.Number else KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val v = text.toFloatOrNull()
                                if (v != null) {
                                    onValueConfirmed(v.coerceIn(min, max))
                                    onDismiss()
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Morandi.panelHi,
                            unfocusedContainerColor = Morandi.panelHi,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    val v = text.toFloatOrNull()
                    if (v != null) {
                        onValueConfirmed(v.coerceIn(min, max))
                    }
                    onDismiss()
                },
                textColor = Morandi.accent
            )
        },
        dismissButton = {
            ReTextButton(text = stringResource(R.string.cancel), onClick = onDismiss, textColor = Morandi.subText)
        }
    )
}

/**
 * Dialog to view, edit, or paste a Hex color string (e.g. #FF7D54)
 */
@Composable
fun HexInputDialog(
    initialHex: String,
    onColorConfirmed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hexText by remember {
        val clean = initialHex.removePrefix("#").uppercase()
        mutableStateOf(clean)
    }

    val parsedColor = remember(hexText) {
        runCatching {
            val formatted = if (hexText.startsWith("#")) hexText else "#$hexText"
            if (formatted.length == 7) Color(AColor.parseColor(formatted)) else null
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                text = stringResource(R.string.color_hex_dialog_title),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Color Preview swatch
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(parsedColor ?: Color.Transparent)
                    )

                    // Hex Text Field
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexText = filtered
                        },
                        prefix = { Text("# ", color = Morandi.subText, fontSize = 13.sp) },
                        placeholder = { Text("FFFFFF", color = Morandi.subText, fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Morandi.panelHi,
                            unfocusedContainerColor = Morandi.panelHi,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Paste from clipboard helper button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ReTextButton(
                        text = stringResource(R.string.color_hex_paste),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (!clip.isNullOrBlank()) {
                                val clean = clip.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                                if (clean.length == 6) {
                                    hexText = clean
                                    Toast.makeText(context, context.getString(R.string.color_hex_pasted, clean), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.color_hex_no_valid), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.color_hex_empty), Toast.LENGTH_SHORT).show()
                            }
                        },
                        textColor = Morandi.accent
                    )
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    if (parsedColor != null) {
                        val finalHex = if (hexText.startsWith("#")) hexText else "#$hexText"
                        onColorConfirmed(finalHex)
                        onDismiss()
                    } else {
                        Toast.makeText(context, context.getString(R.string.color_hex_invalid_tip), Toast.LENGTH_SHORT).show()
                    }
                },
                textColor = if (parsedColor != null) Morandi.accent else Morandi.subText
            )
        },
        dismissButton = {
            ReTextButton(text = stringResource(R.string.cancel), onClick = onDismiss, textColor = Morandi.subText)
        }
    )
}

/**
 * Supported color harmony modes (Procreate parity)
 */
enum class ColorHarmonyMode(val labelRes: Int) {
    COMPLEMENTARY(com.reverie.paint.R.string.color_harmony_complementary),
    SPLIT_COMPLEMENTARY(com.reverie.paint.R.string.color_harmony_split),
    ANALOGOUS(com.reverie.paint.R.string.color_harmony_analogous),
    TRIADIC(com.reverie.paint.R.string.color_harmony_triadic),
    TETRADIC(com.reverie.paint.R.string.color_harmony_tetradic);

    fun getHarmoniousHues(baseHue: Float): List<Float> {
        val h = (baseHue % 360f + 360f) % 360f
        return when (this) {
            COMPLEMENTARY -> listOf(h, (h + 180f) % 360f)
            SPLIT_COMPLEMENTARY -> listOf(h, (h + 150f) % 360f, (h + 210f) % 360f)
            ANALOGOUS -> listOf((h - 30f + 360f) % 360f, h, (h + 30f) % 360f)
            TRIADIC -> listOf(h, (h + 120f) % 360f, (h + 240f) % 360f)
            TETRADIC -> listOf(h, (h + 90f) % 360f, (h + 180f) % 360f, (h + 270f) % 360f)
        }
    }
}

/**
 * 2x8 Swatches Grid component for Recent Colors (Compact 1:1 Square Chips)
 */
@Composable
fun RecentColorsSection(
    recentColors: List<String>,
    currentColorHex: String? = null,
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
                text = stringResource(R.string.color_recent_colors),
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.clear),
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
            selectedColor = currentColorHex,
            onColorSelect = onColorSelect
        )
    }
}

/**
 * Bottom Quick Swatches Section: Supports toggling between "Recent Colors" (记忆色)
 * and the user's pinned "Default Palette" (常驻色卡) across Wheel and Square tabs!
 */
@Composable
fun BottomQuickSwatchesSection(
    vm: com.reverie.paint.core.PaintViewModel,
    currentColorHex: String,
    onColorSelect: (String) -> Unit
) {
    var showPaletteMode by remember { mutableStateOf(false) }
    val defaultPal = vm.defaultPalette

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle Pills: [记忆色] | [常用色卡]
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.color_recent_colors),
                    color = if (!showPaletteMode) Morandi.text else Morandi.subText,
                    fontSize = 11.sp,
                    fontWeight = if (!showPaletteMode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showPaletteMode = false }
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                )
                Text(
                    text = "|",
                    color = Morandi.subText.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
                Text(
                    text = defaultPal?.name ?: stringResource(R.string.color_default_palette),
                    color = if (showPaletteMode) Morandi.text else Morandi.subText,
                    fontSize = 11.sp,
                    fontWeight = if (showPaletteMode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showPaletteMode = true }
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                )
            }

            if (!showPaletteMode) {
                Text(
                    text = stringResource(R.string.clear),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { vm.clearRecentColors() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            } else if (defaultPal != null) {
                Text(
                    text = stringResource(R.string.color_save_to_palette),
                    color = Morandi.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            vm.addColorToPalette(defaultPal.id, vm.brushColor)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        if (!showPaletteMode) {
            SquarePaletteSwatchesGrid(
                colors = vm.recentColors,
                selectedColor = currentColorHex,
                onColorSelect = onColorSelect
            )
        } else {
            SquarePaletteSwatchesGrid(
                colors = defaultPal?.colors ?: emptyList(),
                selectedColor = currentColorHex,
                onColorSelect = onColorSelect,
                onEmptySlotClick = if (defaultPal != null) {
                    { vm.addColorToPalette(defaultPal.id, vm.brushColor) }
                } else null
            )
        }
    }
}

/**
 * Dynamic row count Grid with Compact Square Swatches (8 columns)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SquarePaletteSwatchesGrid(
    colors: List<String>,
    selectedColor: String? = null,
    onColorSelect: (String) -> Unit,
    onColorLongPress: ((Int) -> Unit)? = null,
    onEmptySlotClick: (() -> Unit)? = null
) {
    val cols = 8
    val count = maxOf(16, colors.size)
    val rows = (count + cols - 1) / cols

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi)
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
                            Color(AColor.parseColor(hex))
                        } catch (e: Exception) {
                            Color.Transparent
                        }
                    } else {
                        Morandi.panel.copy(alpha = 0.6f)
                    }

                    val isSelected = hex != null && selectedColor != null &&
                            hex.equals(selectedColor, ignoreCase = true)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bg)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) Morandi.accent else Color.Transparent,
                                shape = RoundedCornerShape(3.dp)
                            )
                            .then(
                                if (hex != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onColorSelect(hex) },
                                        onLongClick = { onColorLongPress?.invoke(idx) }
                                    )
                                } else if (onEmptySlotClick != null) {
                                    Modifier.clickable(onClick = onEmptySlotClick)
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hex == null && onEmptySlotClick != null && idx == colors.size) {
                            Text(
                                text = "+",
                                color = Morandi.subText.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Light
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Maps unit square (s, v) in [0, 1]^2 to unit disk (nx, ny) via FG-Squircular mapping.
 * Pure color (1, 1) -> (1/sqrt(2), -1/sqrt(2)) [top-right arc]
 * Pure white (0, 1) -> (-1/sqrt(2), -1/sqrt(2)) [top-left arc]
 * Pure black (0, 0) -> (-1/sqrt(2), 1/sqrt(2)) [bottom-left arc]
 */
fun squircularForward(s: Float, v: Float): Pair<Float, Float> {
    val u = 2f * s.coerceIn(0f, 1f) - 1f
    val w = 1f - 2f * v.coerceIn(0f, 1f)
    val nx = u * sqrt(max(0f, 1f - w * w / 2f))
    val ny = w * sqrt(max(0f, 1f - u * u / 2f))
    return Pair(nx, ny)
}

/**
 * Maps unit disk coordinate (nxRaw, nyRaw) to unit square (s, v) in [0, 1]^2 via FG-Squircular inverse mapping.
 */
fun squircularInverse(nxRaw: Float, nyRaw: Float): Pair<Float, Float> {
    var nx = nxRaw
    var ny = nyRaw
    val d = sqrt(nx * nx + ny * ny)
    if (d > 1f && d > 0f) {
        nx /= d
        ny /= d
    }
    val nx2 = nx * nx
    val ny2 = ny * ny
    val sqrt2 = sqrt(2f)

    val t1 = max(0f, 2f + 2f * sqrt2 * nx + nx2 - ny2)
    val t2 = max(0f, 2f - 2f * sqrt2 * nx + nx2 - ny2)
    val t3 = max(0f, 2f + 2f * sqrt2 * ny - nx2 + ny2)
    val t4 = max(0f, 2f - 2f * sqrt2 * ny - nx2 + ny2)

    val u = 0.5f * (sqrt(t1) - sqrt(t2))
    val w = 0.5f * (sqrt(t3) - sqrt(t4))

    val s = ((u + 1f) / 2f).coerceIn(0f, 1f)
    val v = ((1f - w) / 2f).coerceIn(0f, 1f)
    return Pair(s, v)
}

/**
 * Converts barycentric weights (wC: pure hue, wA: white, wB: black) to (S, V).
 */
fun triangleBarycentricToSv(wC: Float, wA: Float, wB: Float): Pair<Float, Float> {
    val cwC = wC.coerceIn(0f, 1f)
    val cwA = wA.coerceIn(0f, 1f)
    val cwB = wB.coerceIn(0f, 1f)
    val sum = cwC + cwA + cwB
    val (normC, normA) = if (sum > 0f) (cwC / sum) to (cwA / sum) else 0f to 1f
    val v = (normA + normC).coerceIn(0f, 1f)
    val s = (if (v > 0f) normC / v else 0f).coerceIn(0f, 1f)
    return Pair(s, v)
}

/**
 * Converts (S, V) to barycentric weights (wC: pure hue, wA: white, wB: black).
 */
fun triangleSvToBarycentric(s: Float, v: Float): Triple<Float, Float, Float> {
    val sc = s.coerceIn(0f, 1f)
    val vc = v.coerceIn(0f, 1f)
    val wC = sc * vc
    val wA = vc - wC
    val wB = 1f - vc
    return Triple(wC, wA, wB)
}

/**
 * 统一通用轻量级取色浮窗 (CompactColorPickerPopup)，基于 Popup 实现。
 * 支持传入 Color 或 Hex 字符串，无系统 Dialog 沉重压暗与模态感，呈现 Procreate 风格的浮动 Panel。
 */
@Composable
fun CompactColorPickerPopup(
    title: String = stringResource(R.string.gradient_pick_color),
    initialColor: Color = Color.White,
    anchorBounds: androidx.compose.ui.geometry.Rect? = null,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    onResetToAuto: (() -> Unit)? = null,
    resetToAutoText: String = stringResource(R.string.color_sphere_reset_auto),
    showFastSwatches: Boolean = false,
) {
    val hsv = remember(initialColor) {
        val arr = FloatArray(3)
        AColor.colorToHSV(
            AColor.argb(
                (initialColor.alpha * 255).toInt(),
                (initialColor.red * 255).toInt(),
                (initialColor.green * 255).toInt(),
                (initialColor.blue * 255).toInt()
            ),
            arr
        )
        arr
    }
    var hue by remember(initialColor) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(initialColor) { mutableFloatStateOf(hsv[1]) }
    var valB by remember(initialColor) { mutableFloatStateOf(hsv[2]) }

    val currentColor = remember(hue, sat, valB) {
        val colorInt = AColor.HSVToColor(floatArrayOf(hue, sat, valB))
        Color(colorInt)
    }
    val hexString = remember(currentColor) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            (currentColor.red * 255).toInt(),
            (currentColor.green * 255).toInt(),
            (currentColor.blue * 255).toInt()
        )
    }

    var isVisible by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val dismissWithAnimation: () -> Unit = {
        if (isVisible) {
            isVisible = false
            coroutineScope.launch {
                kotlinx.coroutines.delay(160)
                onDismiss()
            }
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = dismissWithAnimation,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        // 轻量化完全透明外围交互层，无遮罩变灰，点击外部轻触即触发丝滑退出动画
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .noRippleClickable(dismissWithAnimation),
        ) {
            val density = LocalDensity.current
            val screenW = maxWidth
            val screenH = maxHeight

            val popupW = 290.dp
            val estimatedH = 290.dp // 面板预估高
            val margin = 12.dp

            val offset = remember(anchorBounds, screenW, screenH) {
                if (anchorBounds != null) {
                    with(density) {
                        val anchorLeftDp = anchorBounds.left.toDp()
                        val anchorRightDp = anchorBounds.right.toDp()
                        val anchorTopDp = anchorBounds.top.toDp()
                        val anchorBottomDp = anchorBounds.bottom.toDp()
                        val anchorCenterXDp = (anchorLeftDp + anchorRightDp) / 2f

                        // 水平方向：居中对齐锚点，并防止超出屏幕边缘
                        val idealX = anchorCenterXDp - popupW / 2f
                        val clampedX = idealX.coerceIn(margin, (screenW - popupW - margin).coerceAtLeast(margin))

                        // 垂直方向：优先放置在锚点下方；若下方空间不足且上方空间更大，则放置在上方
                        val spaceBelow = screenH - anchorBottomDp
                        val spaceAbove = anchorTopDp
                        val idealY = if (spaceBelow >= estimatedH + margin || spaceBelow >= spaceAbove) {
                            (anchorBottomDp + 8.dp).coerceIn(margin, (screenH - estimatedH - margin).coerceAtLeast(margin))
                        } else {
                            (anchorTopDp - estimatedH - 8.dp).coerceIn(margin, (screenH - estimatedH - margin).coerceAtLeast(margin))
                        }

                        androidx.compose.ui.unit.DpOffset(clampedX, idealY)
                    }
                } else {
                    // 无锚点时居中
                    val cx = ((screenW - popupW) / 2f).coerceAtLeast(margin)
                    val cy = ((screenH - estimatedH) / 2f).coerceAtLeast(margin)
                    androidx.compose.ui.unit.DpOffset(cx, cy)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.TopStart
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isVisible,
                    modifier = Modifier.offset(x = offset.x, y = offset.y),
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                            androidx.compose.animation.scaleIn(
                                initialScale = 0.88f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                )
                            ),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                            androidx.compose.animation.scaleOut(
                                targetScale = 0.88f,
                                animationSpec = androidx.compose.animation.core.tween(120)
                            )
                ) {
                    Box(
                        modifier = Modifier
                            .width(popupW)
                            .noRippleClickable { /* consume click */ }
                            .shadow(24.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.45f))
                            .clip(RoundedCornerShape(18.dp))
                            .background(Morandi.panel)
                            .glassBorder(RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Header: Title + Color Preview Swatch + Hex Readout + Close Icon
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                        Text(
                            text = title,
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(currentColor)
                                    .border(1.dp, Morandi.border, RoundedCornerShape(6.dp))
                            )
                            Text(
                                text = hexString,
                                color = Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Morandi.panelHi)
                                    .clickable(onClick = dismissWithAnimation),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // 2D Saturation-Value Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                            .pointerInput(hue) {
                                awaitEachGesture {
                                    val down = awaitFirstDown().also { it.consume() }
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    sat = (down.position.x / w).coerceIn(0f, 1f)
                                    valB = (1f - (down.position.y / h)).coerceIn(0f, 1f)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        sat = (change.position.x / w).coerceIn(0f, 1f)
                                        valB = (1f - (change.position.y / h)).coerceIn(0f, 1f)
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        val pureHueColor = remember(hue) { hueToPureColor(hue) }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            // Layer 1: Horizontal gradient from White to Pure Hue
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.White, pureHueColor),
                                    startX = 0f,
                                    endX = w
                                ),
                                size = size
                            )

                            // Layer 2: Vertical gradient from Transparent to Black
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black),
                                    startY = 0f,
                                    endY = h
                                ),
                                size = size
                            )

                            // High-contrast Reticle indicator ring at (sat, 1 - valB)
                            val cursorX = sat * w
                            val cursorY = (1f - valB) * h
                            val cursorOffset = Offset(cursorX, cursorY)

                            drawCircle(
                                color = Color.Black.copy(alpha = 0.55f),
                                radius = 8.dp.toPx(),
                                center = cursorOffset,
                                style = Stroke(1.5.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 6.5.dp.toPx(),
                                center = cursorOffset,
                                style = Stroke(2.dp.toPx())
                            )
                        }
                    }

                    // Rainbow Hue Slider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Morandi.border, RoundedCornerShape(6.dp))
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown().also { it.consume() }
                                    val w = size.width.toFloat()
                                    hue = (down.position.x / w).coerceIn(0f, 1f) * 360f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        hue = (change.position.x / w).coerceIn(0f, 1f) * 360f
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Red, Color.Yellow, Color.Green,
                                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                    )
                                )
                            )
                            // Hue indicator line / thumb
                            val thumbX = (hue / 360f).coerceIn(0f, 1f) * w
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.5f),
                                radius = 7.dp.toPx(),
                                center = Offset(thumbX, h / 2f),
                                style = Stroke(1.5.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 5.5.dp.toPx(),
                                center = Offset(thumbX, h / 2f),
                                style = Stroke(2.dp.toPx())
                            )
                        }
                    }

                    // Bottom Action Bar: Reset (optional) + Apply / Confirm button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onResetToAuto != null) {
                            ReTextButton(
                                text = resetToAutoText,
                                onClick = {
                                    onResetToAuto()
                                    dismissWithAnimation()
                                },
                                textColor = Morandi.subText,
                                fontSize = 12.sp
                            )
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        ReTextButton(
                            text = stringResource(R.string.common_confirm),
                            onClick = {
                                onColorSelected(currentColor)
                                dismissWithAnimation()
                            },
                            primary = true,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
}
}

/** Hex 字符串重载版本 */
@Composable
fun CompactColorPickerPopup(
    title: String = stringResource(R.string.gradient_pick_color),
    initialHex: String,
    anchorBounds: androidx.compose.ui.geometry.Rect? = null,
    onColorConfirmed: (String) -> Unit,
    onDismiss: () -> Unit,
    onResetToAuto: (() -> Unit)? = null,
    resetToAutoText: String = stringResource(R.string.color_sphere_reset_auto),
    showFastSwatches: Boolean = false,
) {
    val initialCol = remember(initialHex) {
        try {
            Color(AColor.parseColor(initialHex.ifBlank { "#FFFFFF" }))
        } catch (_: Exception) {
            Color.White
        }
    }
    CompactColorPickerPopup(
        title = title,
        initialColor = initialCol,
        anchorBounds = anchorBounds,
        onColorSelected = { col ->
            val hex = String.format(
                Locale.US,
                "#%02X%02X%02X",
                (col.red * 255).toInt(),
                (col.green * 255).toInt(),
                (col.blue * 255).toInt()
            )
            onColorConfirmed(hex)
        },
        onDismiss = onDismiss,
        onResetToAuto = onResetToAuto,
        resetToAutoText = resetToAutoText,
        showFastSwatches = showFastSwatches,
    )
}


