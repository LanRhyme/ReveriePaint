/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AColor
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tab 3: Sliders / Numeric Tuning Page with:
 * - HSV, RGB, CMYK sliders with click-to-edit exact numeric values
 * - Hex code display with direct manual editing, quick paste from clipboard, and one-tap copy
 * - Complete Morandi theme styling without hardcoded dark colors
 */
@Composable
fun SlidersNumericPage(
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

    var showHexInputDialog by remember { mutableStateOf(false) }

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
            colors = RainbowHueColors,
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { onHsvChange(it, sat, valB) },
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
            onValueChange = { onHsvChange(hue, it / 100f, valB) },
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
            onValueChange = { onHsvChange(hue, sat, it / 100f) },
            unitSuffix = "%"
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

        // 3. Hex code row + Direct Edit + Paste + Copy buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hex display & tap to edit
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panelHi)
                    .clickable { showHexInputDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brush),
                    contentDescription = "编辑 Hex",
                    tint = Morandi.accent,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "# $hexStr",
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Paste & Copy buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Paste from clipboard
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.panelHi)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (!clip.isNullOrBlank()) {
                                val clean = clip.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                                if (clean.length == 6) {
                                    val formatted = "#$clean"
                                    try {
                                        val parsed = AColor.parseColor(formatted)
                                        val hsv = FloatArray(3)
                                        AColor.colorToHSV(parsed, hsv)
                                        onHsvChange(hsv[0], hsv[1], hsv[2])
                                        Toast.makeText(context, "已应用色值 $formatted", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "色值解析失败", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "剪贴板无有效色值", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "粘贴", color = Morandi.subText, fontSize = 11.sp)
                }

                // Copy to clipboard
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.panelHi)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("hex", "#$hexStr"))
                            Toast.makeText(context, "已复制 #$hexStr", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "复制", color = Morandi.subText, fontSize = 11.sp)
                }
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
            onValueChange = { updateFromCmyk(it / 100f, mVal, yVal, kVal) },
            unitSuffix = "%"
        )
        CompactHsvSlider(
            label = "M",
            value = mVal * 100f,
            max = 100f,
            colors = listOf(Color.Black, Color.Magenta),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, it / 100f, yVal, kVal) },
            unitSuffix = "%"
        )
        CompactHsvSlider(
            label = "Y",
            value = yVal * 100f,
            max = 100f,
            colors = listOf(Color.Black, Color.Yellow),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, mVal, it / 100f, kVal) },
            unitSuffix = "%"
        )
        CompactHsvSlider(
            label = "K",
            value = kVal * 100f,
            max = 100f,
            colors = listOf(Color.Red, Color.Black),
            onInteractionStart = onInteractionStart,
            onInteractionEnd = onInteractionEnd,
            onValueChange = { updateFromCmyk(cVal, mVal, yVal, it / 100f) },
            unitSuffix = "%"
        )
    }

    if (showHexInputDialog) {
        HexInputDialog(
            initialHex = "#$hexStr",
            onColorConfirmed = { hex ->
                try {
                    val parsed = AColor.parseColor(hex)
                    val hsv = FloatArray(3)
                    AColor.colorToHSV(parsed, hsv)
                    onHsvChange(hsv[0], hsv[1], hsv[2])
                } catch (e: Exception) {
                    Toast.makeText(context, "色值格式无效", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showHexInputDialog = false }
        )
    }
}
