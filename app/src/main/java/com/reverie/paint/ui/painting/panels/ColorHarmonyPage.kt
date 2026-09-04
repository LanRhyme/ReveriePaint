/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.*

/**
 * Tab 2: Color Harmony Page (Procreate parity):
 * - 5 Harmony Modes: 互补色 (Complementary), 分裂互补 (Split Complementary), 类似色 (Analogous), 三等分 (Triadic), 四角形 (Tetradic)
 * - Interactive harmony wheel with synchronized node linking lines
 * - Primary node dragging rotates entire harmonious chord
 * - Direct tap to select any secondary harmonious color
 * - Harmonious chord preview cards with one-tap "存入色卡"
 * - Compact Saturation & Value sliders
 */
@Composable
fun ColorHarmonyPage(
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
    val context = LocalContext.current
    val harmonyMode = remember(vm.colorHarmonyModeName) {
        try {
            ColorHarmonyMode.valueOf(vm.colorHarmonyModeName)
        } catch (_: Exception) {
            ColorHarmonyMode.COMPLEMENTARY
        }
    }

    val harmonyHues = remember(hue, harmonyMode) {
        harmonyMode.getHarmoniousHues(hue)
    }

    val harmonyHexColors = remember(harmonyHues, sat, valB, vm.colorModel) {
        harmonyHues.map { h ->
            val rgb = hsvModelToRgb(h, sat, valB, vm.colorModel)
            "#%06X".format(rgb and 0xFFFFFF)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Harmony Mode Selector Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .padding(2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (mode in ColorHarmonyMode.entries) {
                val isSel = harmonyMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) Morandi.accent else Color.Transparent)
                        .clickable { vm.updateColorHarmonyMode(mode.name) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        color = if (isSel) Color.White else Morandi.subText,
                        fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // 2. Harmony Wheel Canvas (205dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            contentAlignment = Alignment.Center
        ) {
            HarmonyWheelCanvas(
                hues = harmonyHues,
                sat = sat,
                valB = valB,
                onPrimaryHue = onHue,
                onSelectSecondaryHue = { chosenHue -> onHue(chosenHue) },
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd
            )
        }

        // 3. Harmony Chord Swatches Row + "存入色卡" button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                harmonyHexColors.forEachIndexed { index, hex ->
                    val isPrimary = index == 0
                    val isCurrent = hex.equals(vm.brushColor, ignoreCase = true)
                    val chipColor = try {
                        Color(android.graphics.Color.parseColor(hex))
                    } catch (e: Exception) {
                        Color.Gray
                    }

                    Box(
                        modifier = Modifier
                            .size(if (isPrimary) 30.dp else 26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(chipColor)
                            .border(
                                width = if (isCurrent) 2.dp else 0.5.dp,
                                color = if (isCurrent) Color.White else Morandi.border,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { vm.updateBrushColor(hex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPrimary) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
                            )
                        }
                    }
                }
            }

            // Button: Save Harmony Chord to Default Palette
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .clickable {
                        val targetPal = vm.defaultPalette
                        if (targetPal != null) {
                            harmonyHexColors.forEach { c ->
                                vm.addColorToPalette(targetPal.id, c)
                            }
                            Toast.makeText(context, "已将配色方案存入 ${targetPal.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "暂无可用色卡", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark_plus),
                        contentDescription = "存入色卡",
                        tint = Morandi.accent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(text = "存入色卡", color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 4. Compact Saturation & Brightness Sliders
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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

        // 5. Bottom Quick Swatches
        BottomQuickSwatchesSection(
            vm = vm,
            currentColorHex = vm.brushColor,
            onColorSelect = { hex -> vm.updateBrushColor(hex) }
        )
    }
}

/**
 * Interactive canvas for Color Harmony Wheel:
 * - Swept hue gradient ring
 * - Primary node (double white ring)
 * - Secondary harmonious nodes (single ring)
 * - Geometry chords connecting harmonious nodes
 */
@Composable
private fun HarmonyWheelCanvas(
    hues: List<Float>,
    sat: Float,
    valB: Float,
    onPrimaryHue: (Float) -> Unit,
    onSelectSecondaryHue: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit
) {
    val currentOnPrimaryHue by rememberUpdatedState(onPrimaryHue)
    val currentOnSelectSecondaryHue by rememberUpdatedState(onSelectSecondaryHue)
    val currentOnInteractionStart by rememberUpdatedState(onInteractionStart)
    val currentOnInteractionEnd by rememberUpdatedState(onInteractionEnd)
    val currentHues by rememberUpdatedState(hues)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown().also { it.consume() }
                    currentOnInteractionStart()

                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringSize = size.height.toFloat()
                    val strokeWidth = 15.dp.toPx()
                    val R = (ringSize - strokeWidth) / 2f
                    val innerR = R - strokeWidth / 2f - 4.dp.toPx()
                    val nodeRadius = innerR * 0.72f

                    // Check if tapped directly on or near a secondary node
                    val secondaryTapped = currentHues.indices.firstOrNull { idx ->
                        if (idx == 0) return@firstOrNull false
                        val angle = (currentHues[idx] + 180f) * Math.PI / 180f
                        val nx = cx + cos(angle).toFloat() * nodeRadius
                        val ny = cy + sin(angle).toFloat() * nodeRadius
                        (down.position - Offset(nx, ny)).getDistance() < 24.dp.toPx()
                    }

                    if (secondaryTapped != null) {
                        currentOnSelectSecondaryHue(currentHues[secondaryTapped])
                    }

                    fun updateAngle(pos: Offset) {
                        val px = pos.x - cx
                        val py = pos.y - cy
                        val rawAngle = (atan2(py, px) * 180 / Math.PI).toFloat()
                        val h = (rawAngle - 180f + 360f) % 360f
                        currentOnPrimaryHue(h)
                    }

                    if (secondaryTapped == null) {
                        updateAngle(down.position)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        updateAngle(change.position)
                        change.consume()
                    }
                    currentOnInteractionEnd()
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ringSize = size.height
        val strokeWidth = 15.dp.toPx()
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

        // 2. Inner Disc background
        drawCircle(
            color = Morandi.panelHi.copy(alpha = 0.55f),
            radius = innerR,
            center = Offset(cx, cy)
        )

        // 3. Compute Node Positions
        val nodePoints = hues.map { h ->
            val angle = (h + 180f) * Math.PI / 180f
            val nodeRadius = innerR * 0.72f
            val nx = cx + cos(angle).toFloat() * nodeRadius
            val ny = cy + sin(angle).toFloat() * nodeRadius
            Offset(nx, ny)
        }

        // 4. Draw Geometry Connecting Lines
        if (nodePoints.size >= 2) {
            val path = Path()
            path.moveTo(nodePoints[0].x, nodePoints[0].y)
            for (i in 1 until nodePoints.size) {
                path.lineTo(nodePoints[i].x, nodePoints[i].y)
            }
            path.close()
            drawPath(
                path = path,
                color = Morandi.accent.copy(alpha = 0.45f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // 5. Draw Harmony Nodes
        nodePoints.forEachIndexed { index, pos ->
            val isPrimary = index == 0
            val nodeHue = hues[index]
            val pureColor = hueToPureColor(nodeHue)

            // Connect line to ring
            val angle = (nodeHue + 180f) * Math.PI / 180f
            val rx = cx + cos(angle).toFloat() * R
            val ry = cy + sin(angle).toFloat() * R
            drawLine(
                color = pureColor.copy(alpha = 0.6f),
                start = pos,
                end = Offset(rx, ry),
                strokeWidth = 1.dp.toPx()
            )

            // Node dot
            if (isPrimary) {
                // Primary node (larger with double stroke)
                drawCircle(pureColor, radius = 7.dp.toPx(), center = pos)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = pos, style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.5f), radius = 10.dp.toPx(), center = pos, style = Stroke(1.dp.toPx()))

                // Hue ring marker
                drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(rx, ry), style = Stroke(2.5.dp.toPx()))
            } else {
                // Secondary nodes
                drawCircle(pureColor, radius = 5.5.dp.toPx(), center = pos)
                drawCircle(Color.White, radius = 6.5.dp.toPx(), center = pos, style = Stroke(1.8.dp.toPx()))

                // Hue ring marker
                drawCircle(Color.White.copy(alpha = 0.75f), radius = 4.5.dp.toPx(), center = Offset(rx, ry), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
