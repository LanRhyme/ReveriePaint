/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AColor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            label = label.ifEmpty { "数值" },
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
    var text by remember { mutableStateOf(currentValue.roundToInt().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                text = "设置 $label 数值",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "有效范围: ${min.roundToInt()} ~ ${max.roundToInt()}$unitSuffix",
                    color = Morandi.subText,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it == '.' } && input.length <= 6) {
                            text = input
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
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
                        unfocusedBorderColor = Morandi.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            ReTextButton(
                text = "确定",
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
            ReTextButton(text = "取消", onClick = onDismiss, textColor = Morandi.subText)
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
                text = "输入十六进制色值",
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
                            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
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
                            unfocusedBorderColor = Morandi.border
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
                        text = "从剪贴板粘贴",
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (!clip.isNullOrBlank()) {
                                val clean = clip.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                                if (clean.length == 6) {
                                    hexText = clean
                                    Toast.makeText(context, "已粘贴 #$clean", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "剪贴板无有效色值", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        textColor = Morandi.accent
                    )
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = "确定",
                onClick = {
                    if (parsedColor != null) {
                        val finalHex = if (hexText.startsWith("#")) hexText else "#$hexText"
                        onColorConfirmed(finalHex)
                        onDismiss()
                    } else {
                        Toast.makeText(context, "请输入 6 位有效十六进制颜色代码", Toast.LENGTH_SHORT).show()
                    }
                },
                textColor = if (parsedColor != null) Morandi.accent else Morandi.subText
            )
        },
        dismissButton = {
            ReTextButton(text = "取消", onClick = onDismiss, textColor = Morandi.subText)
        }
    )
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
            selectedColor = currentColorHex,
            onColorSelect = onColorSelect
        )
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
    onColorLongPress: ((Int) -> Unit)? = null
) {
    val cols = 8
    val count = maxOf(16, colors.size)
    val rows = (count + cols - 1) / cols

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
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
                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                color = if (isSelected) Morandi.accent else Morandi.border.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(3.dp)
                            )
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
