/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.SphereShading
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.*

/**
 * 3D Shading Sphere Color Picker:
 * - Real-time 3D lighting calculation on a sphere (ambient bounce, wrapped diffuse, Blinn-Phong specular)
 * - Three key color pins: 🌙 Shadow/Ambient, 💧 Local/Base, ☀️ Key Light
 * - Auto cold/warm color temperature lighting derivation with manual customization and long-press reset
 * - Interactive reticle cursor for 120 FPS fluid color sampling without feedback-loop distortion
 * - Softness slider to adjust light-to-shadow gradient transition curve
 * - One-tap save of lighting trio to active palette
 */
@Composable
fun ColorShadingSpherePage(
    vm: PaintViewModel,
    onColorSelected: (String) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 1. Base color (💧): derived from vm.colorSphereBaseHex, completely decoupled from sphere touch-sampling
    var isSamplingSphere by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (vm.colorSphereBaseHex.isEmpty()) {
            vm.updateColorSphereBaseHex(vm.brushColor)
        }
    }

    val baseHex = if (vm.colorSphereBaseHex.isNotEmpty()) vm.colorSphereBaseHex else vm.brushColor

    // 2. Custom color overrides for 🌙 and ☀️
    var shadowOverrideHex by remember { mutableStateOf<String?>(null) }
    var lightOverrideHex by remember { mutableStateOf<String?>(null) }

    // Derive auto colors from baseHex
    val (autoShadowHex, autoLightHex) = remember(baseHex) {
        val baseInt = try {
            android.graphics.Color.parseColor(baseHex)
        } catch (_: Exception) {
            android.graphics.Color.GREEN
        }
        val (sInt, lInt) = computeAutoLightingColors(baseInt)
        Pair(
            "#%06X".format(sInt and 0xFFFFFF),
            "#%06X".format(lInt and 0xFFFFFF)
        )
    }

    val activeShadowHex = shadowOverrideHex ?: autoShadowHex
    val activeLightHex = lightOverrideHex ?: autoLightHex

    // 3. Shading parameters
    // 0.5 对应展宽系数 1.0, 即参考图原始传递曲线
    var softness by remember { mutableFloatStateOf(0.5f) }
    var cursorNormX by remember { mutableFloatStateOf(0.0f) }
    var cursorNormY by remember { mutableFloatStateOf(0.0f) }

    // 4. Editing popover slot (0: None, 1: Shadow, 2: Light)
    var editingSlot by remember { mutableIntStateOf(0) }

    // 5. Pre-rendered 3D sphere bitmap (cached, only recomputed on color or softness changes)
    val sphereBitmap = remember(baseHex, activeShadowHex, activeLightHex, softness) {
        renderSphereBitmap(baseHex, activeShadowHex, activeLightHex, softness, 320).asImageBitmap()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 3D Shading Sphere Canvas ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(baseHex, activeShadowHex, activeLightHex, softness) {
                        awaitEachGesture {
                            val down = awaitFirstDown().also { it.consume() }
                            isSamplingSphere = true
                            onInteractionStart()

                            fun sampleAt(pos: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val sphereR = min(size.width, size.height) / 2f - 4.dp.toPx()
                                val dx = (pos.x - cx) / sphereR
                                val dy = (pos.y - cy) / sphereR
                                val dist = sqrt(dx * dx + dy * dy)
                                val (nx, ny) = if (dist > 0.98f) Pair(dx / dist * 0.98f, dy / dist * 0.98f) else Pair(dx, dy)
                                cursorNormX = nx
                                cursorNormY = ny
                                val hex = calculateSphereColorHex(nx, ny, baseHex, activeShadowHex, activeLightHex, softness)
                                onColorSelected(hex)
                            }

                            sampleAt(down.position)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                sampleAt(change.position)
                            }

                            onInteractionEnd()
                            isSamplingSphere = false
                        }
                    }
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val sphereR = min(size.width, size.height) / 2f - 4.dp.toPx()

                // Render pre-shaded sphere bitmap (no offset black circle to eliminate black rim/clipping)
                drawImage(
                    image = sphereBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - sphereR).roundToInt(), (cy - sphereR).roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((sphereR * 2f).roundToInt(), (sphereR * 2f).roundToInt())
                )

                // Reticle cursor: clean hollow circle matching reference image
                val cursorX = cx + cursorNormX * sphereR
                val cursorY = cy + cursorNormY * sphereR
                val cursorOffset = Offset(cursorX, cursorY)

                drawCircle(
                    color = Color.Black.copy(alpha = 0.30f),
                    radius = 13.dp.toPx(),
                    center = cursorOffset,
                    style = Stroke(1.2.dp.toPx())
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.90f),
                    radius = 12.dp.toPx(),
                    center = cursorOffset,
                    style = Stroke(1.2.dp.toPx())
                )
            }
        }

        // --- Key Color Pins Row (🌙 Shadow, 💧 Base, ☀️ Key Light) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 🌙 Shadow / Ambient Pin
            ColorPinItem(
                iconRes = R.drawable.ic_moon,
                hexColor = activeShadowHex,
                isOverridden = shadowOverrideHex != null,
                onClick = { editingSlot = 1 },
                onLongClick = {
                    shadowOverrideHex = null
                    Toast.makeText(context, context.getString(R.string.color_sphere_reset_auto), Toast.LENGTH_SHORT).show()
                }
            )

            // 2. 💧 Local / Base Color Pin
            ColorPinItem(
                iconRes = R.drawable.ic_droplet,
                hexColor = baseHex,
                isOverridden = false,
                onClick = {
                    vm.updateColorSphereBaseHex(vm.brushColor)
                    Toast.makeText(context, context.getString(R.string.color_sphere_base_updated), Toast.LENGTH_SHORT).show()
                },
                onLongClick = {
                    vm.updateColorSphereBaseHex(vm.brushColor)
                    Toast.makeText(context, context.getString(R.string.color_sphere_base_updated), Toast.LENGTH_SHORT).show()
                }
            )

            // 3. ☀️ Key Light Pin
            ColorPinItem(
                iconRes = R.drawable.ic_sun,
                hexColor = activeLightHex,
                isOverridden = lightOverrideHex != null,
                onClick = { editingSlot = 2 },
                onLongClick = {
                    lightOverrideHex = null
                    Toast.makeText(context, context.getString(R.string.color_sphere_reset_auto), Toast.LENGTH_SHORT).show()
                }
            )
        }

        // --- Softness / Diffuse Contrast Slider ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MinimalSoftnessSlider(
                value = softness,
                onValueChange = { softness = it },
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                modifier = Modifier.weight(1f)
            )

            // Save Lighting Set to Palette Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panelHi)
                    .clickable {
                        val targetPal = vm.defaultPalette
                        if (targetPal != null) {
                            vm.addColorToPalette(targetPal.id, activeShadowHex)
                            vm.addColorToPalette(targetPal.id, baseHex)
                            vm.addColorToPalette(targetPal.id, activeLightHex)
                            Toast.makeText(
                                context,
                                context.getString(R.string.color_sphere_saved_toast, targetPal.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.color_harmony_no_pal_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = stringResource(R.string.color_harmony_save_btn),
                    tint = Morandi.accent,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    // --- Color Slot Edit Dialog for 🌙 or ☀️ ---
    if (editingSlot != 0) {
        val isShadow = editingSlot == 1
        val titleText = if (isShadow) stringResource(R.string.color_sphere_edit_shadow) else stringResource(R.string.color_sphere_edit_light)
        val initialHex = if (isShadow) activeShadowHex else activeLightHex

        ColorSlotEditDialog(
            title = titleText,
            initialHex = initialHex,
            onDismiss = { editingSlot = 0 },
            onColorConfirmed = { newHex ->
                if (isShadow) {
                    shadowOverrideHex = newHex
                } else {
                    lightOverrideHex = newHex
                }
                editingSlot = 0
            },
            onResetToAuto = {
                if (isShadow) {
                    shadowOverrideHex = null
                } else {
                    lightOverrideHex = null
                }
                editingSlot = 0
                Toast.makeText(context, context.getString(R.string.color_sphere_reset_auto), Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColorPinItem(
    iconRes: Int,
    hexColor: String,
    isOverridden: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val chipColor = remember(hexColor) {
        try {
            Color(android.graphics.Color.parseColor(hexColor))
        } catch (_: Exception) {
            Color.Gray
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(16.dp)
        )

        Box(
            modifier = Modifier
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(chipColor)
                .border(
                    width = if (isOverridden) 1.5.dp else 0.8.dp,
                    color = if (isOverridden) Morandi.accent else Color.White.copy(alpha = 0.4f),
                    shape = CircleShape
                )
        )
    }
}

/**
 * Sleek minimal slider matching the uploaded reference UI
 */
@Composable
private fun MinimalSoftnessSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown().also { it.consume() }
                    onInteractionStart()
                    val updateFraction = { pos: Offset ->
                        val f = (pos.x / size.width).coerceIn(0.05f, 0.95f)
                        onValueChange(f)
                    }
                    updateFraction(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        updateFraction(change.position)
                    }
                    onInteractionEnd()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val h = 2.dp.toPx()
            val y = size.height / 2f
            val thumbR = 7.dp.toPx()
            val thumbX = (value * size.width).coerceIn(thumbR, size.width - thumbR)

            // Minimal track matching reference image
            drawLine(
                color = Morandi.border.copy(alpha = 0.8f),
                start = Offset(thumbR, y),
                end = Offset(size.width - thumbR, y),
                strokeWidth = h,
                cap = StrokeCap.Round
            )

            // Thumb shadow
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = thumbR + 1.dp.toPx(),
                center = Offset(thumbX, y + 1.dp.toPx())
            )

            // Thumb body
            drawCircle(
                color = Color.White,
                radius = thumbR,
                center = Offset(thumbX, y)
            )
        }
    }
}

/**
 * Square Saturation-Value (S-V) Color Picker Dialog for 🌙 or ☀️
 */
@Composable
private fun ColorSlotEditDialog(
    title: String,
    initialHex: String,
    onDismiss: () -> Unit,
    onColorConfirmed: (String) -> Unit,
    onResetToAuto: () -> Unit
) {
    var hsv by remember {
        val c = try { android.graphics.Color.parseColor(initialHex) } catch (_: Exception) { android.graphics.Color.WHITE }
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(c, arr)
        mutableStateOf(arr)
    }

    val currentInt = remember(hsv) {
        android.graphics.Color.HSVToColor(hsv)
    }
    val currentHex = remember(currentInt) {
        "#%06X".format(currentInt and 0xFFFFFF)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = title, color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(currentInt))
                            .border(1.dp, Morandi.border, RoundedCornerShape(6.dp))
                    )
                    Text(
                        text = currentHex,
                        color = Morandi.subText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Square 2D Saturation-Value (S-V) Panel
                val hue = hsv[0]
                val sat = hsv[1]
                val valB = hsv[2]

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(hue) {
                                awaitEachGesture {
                                    val down = awaitFirstDown().also { it.consume() }
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val s = (down.position.x / w).coerceIn(0f, 1f)
                                    val v = (1f - down.position.y / h).coerceIn(0f, 1f)
                                    hsv = floatArrayOf(hue, s, v)

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        val dragS = (change.position.x / w).coerceIn(0f, 1f)
                                        val dragV = (1f - change.position.y / h).coerceIn(0f, 1f)
                                        hsv = floatArrayOf(hue, dragS, dragV)
                                        change.consume()
                                    }
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height

                        // Layer 1: Horizontal gradient from White to Pure Hue
                        val pureHueColor = hueToPureColor(hue)
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

                        // Reticle indicator ring at (sat, 1 - valB)
                        val cursorX = sat * w
                        val cursorY = (1f - valB) * h
                        val cursorOffset = Offset(cursorX, cursorY)

                        drawCircle(
                            color = Color.Black.copy(alpha = 0.45f),
                            radius = 8.dp.toPx(),
                            center = cursorOffset,
                            style = Stroke(1.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 7.dp.toPx(),
                            center = cursorOffset,
                            style = Stroke(2.dp.toPx())
                        )
                    }
                }

                // 2. Rainbow Hue Slider
                CompactHsvSlider(
                    label = "H",
                    value = hsv[0],
                    max = 360f,
                    colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                    onInteractionStart = {},
                    onInteractionEnd = {},
                    onValueChange = { newH -> hsv = floatArrayOf(newH, hsv[1], hsv[2]) },
                    unitSuffix = "°"
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReTextButton(
                    text = stringResource(R.string.color_sphere_reset_auto),
                    onClick = onResetToAuto,
                    textColor = Morandi.subText
                )
                ReTextButton(
                    text = stringResource(R.string.confirm),
                    onClick = { onColorConfirmed(currentHex) },
                    textColor = Morandi.accent
                )
            }
        },
        dismissButton = {
            ReTextButton(text = stringResource(R.string.cancel), onClick = onDismiss, textColor = Morandi.subText)
        }
    )
}
// ---------------------------------------------------------------------------
// 3D 光影球着色: 委托给 model/SphereShading (参数由参考图实测像素拟合得到)。
// 页面只保留调用约定, 数学与常量集中在一处, 便于单测与调参。
// ---------------------------------------------------------------------------

/**
 * 自动光色/阴影色。返回顺序沿用本页约定: Pair(阴影色, 主光色)。
 */
private fun computeAutoLightingColors(baseColorInt: Int): Pair<Int, Int> {
    val (lightInt, shadowInt) = SphereShading.computeAutoLightingColors(baseColorInt)
    return Pair(shadowInt, lightInt)
}

/**
 * 采样球面上归一化坐标 (nx, ny) 处的颜色, 返回 "#RRGGBB"。
 */
private fun calculateSphereColorHex(
    nx: Float,
    ny: Float,
    baseHex: String,
    shadowHex: String,
    lightHex: String,
    softness: Float
): String = "#" + SphereShading.calculateSphereColorHex(nx, ny, baseHex, shadowHex, lightHex, softness)
