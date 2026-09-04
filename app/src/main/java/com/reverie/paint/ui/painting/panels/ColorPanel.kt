/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * Main Color Panel container:
 * - Header: Drag handle (supports moving the panel freely across screen), Title, Pin toggle, Foreground/Secondary swap, ColorDrop target, and Close button when pinned
 * - Body: 5 animated tabs (0: Wheel, 1: Square, 2: Harmony, 3: Palettes, 4: Sliders)
 * - Pinned Mode: Non-blocking backdrop allows drawing directly on the canvas while panel is open
 * - Footer: Bottom 5 navigation tabs with Morandi styling
 */
@Composable
fun ColorPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.96f,
    hazeState: HazeState? = null,
    onColorDropStart: ((Offset) -> Unit)? = null,
    onColorDropMove: ((Offset) -> Unit)? = null,
    onColorDropEnd: ((Offset) -> Unit)? = null,
    onColorDropCancel: (() -> Unit)? = null,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var valB by remember { mutableFloatStateOf(1f) }
    var isInteracting by remember { mutableStateOf(false) }

    // Floating panel offset for drag-repositioning
    var panelOffset by remember { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current

    // Sync from vm.brushColor & vm.colorModel (supports canvas eyedropper in any color model)
    LaunchedEffect(vm.brushColor, vm.colorModel) {
        if (!isInteracting) {
            try {
                val c = android.graphics.Color.parseColor(vm.brushColor)
                val modelHsv = rgbToHsvModel(c, vm.colorModel)
                hue = modelHsv[0]
                sat = modelHsv[1]
                valB = modelHsv[2]
            } catch (_: Exception) { }
        }
    }

    val updateColorHsv = { h: Float, s: Float, v: Float ->
        val rgb = hsvModelToRgb(h, s, v, vm.colorModel)
        val hex = "#%06X".format(rgb and 0xFFFFFF)
        vm.updateBrushColor(hex)
    }

    val panelShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .then(
                // When pinned, clicks outside pass directly through to canvas
                if (!vm.isColorPanelPinned) {
                    Modifier.noRippleClickable(onClose)
                } else {
                    Modifier
                }
            )
            .systemHoverIcon(context)
    ) {
        Column(
            modifier = Modifier
                .systemHoverIcon(context)
                .align(Alignment.BottomStart)
                .offset { IntOffset(panelOffset.x.roundToInt(), panelOffset.y.roundToInt()) }
                .padding(start = 44.dp, bottom = 16.dp)
                .width(280.dp)
                .shadow(16.dp, panelShape)
                .clip(panelShape)
                .background(Morandi.panel.copy(alpha = opacity))
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.popupStyle(opacity),
                        )
                    } else {
                        Modifier
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
            // 1. Header: Drag handle pill + Title + Pin button + Color Preview Swatches
            ColorPanelHeader(
                activeTab = vm.colorPanelTab,
                brushColor = vm.brushColor,
                secondaryColor = vm.brushSecondaryColor,
                isPinned = vm.isColorPanelPinned,
                onTogglePin = {
                    vm.isColorPanelPinned = !vm.isColorPanelPinned
                    Toast.makeText(
                        context,
                        if (vm.isColorPanelPinned) "已钉住颜色面板，可在画布上自由绘制" else "已取消钉住",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onClose = onClose,
                onSwapColors = { vm.swapColors() },
                onDragHandle = { dragAmount -> panelOffset += dragAmount },
                onColorDropStart = onColorDropStart,
                onColorDropMove = onColorDropMove,
                onColorDropEnd = onColorDropEnd,
                onColorDropCancel = onColorDropCancel,
            )

            Spacer(Modifier.height(8.dp))

            // 2. Dynamic Content Body (Snappy transition across 5 tabs)
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
                        2 -> ColorHarmonyPage(
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
                        3 -> PalettesPage(
                            vm = vm,
                            onColorSelected = { hex ->
                                vm.updateBrushColor(hex)
                            }
                        )
                        4 -> SlidersNumericPage(
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

            // 3. Bottom 5 Navigation Tabs (Wheel, Square, Harmony, Palettes, Sliders)
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
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onClose: () -> Unit,
    onSwapColors: () -> Unit,
    onDragHandle: (Offset) -> Unit,
    onColorDropStart: ((Offset) -> Unit)? = null,
    onColorDropMove: ((Offset) -> Unit)? = null,
    onColorDropEnd: ((Offset) -> Unit)? = null,
    onColorDropCancel: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Drag Handle Pill (supports dragging to move panel freely)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(60.dp, 16.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragHandle(dragAmount)
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp, 3.5.dp)
                    .clip(CircleShape)
                    .background(Morandi.subText.copy(alpha = 0.45f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Title + Pin toggle button
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (activeTab) {
                        0 -> "色轮"
                        1 -> "方块"
                        2 -> "和谐"
                        3 -> "色卡"
                        else -> "滑块"
                    },
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                // Pin toggle icon button
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isPinned) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable(onClick = onTogglePin),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        val tint = if (isPinned) Morandi.accent else Morandi.subText
                        // Pin icon geometry
                        val path = Path().apply {
                            moveTo(size.width * 0.3f, 0f)
                            lineTo(size.width * 0.7f, 0f)
                            lineTo(size.width * 0.6f, size.height * 0.45f)
                            lineTo(size.width * 0.85f, size.height * 0.55f)
                            lineTo(size.width * 0.55f, size.height * 0.55f)
                            lineTo(size.width * 0.5f, size.height)
                            lineTo(size.width * 0.45f, size.height * 0.55f)
                            lineTo(size.width * 0.15f, size.height * 0.55f)
                            lineTo(size.width * 0.4f, size.height * 0.45f)
                            close()
                        }
                        drawPath(path, color = tint)
                    }
                }
            }

            // Right controls: Foreground / Background Colors Swap Box & Optional Close Button when Pinned
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp, 24.dp)
                        .tapOrDragGesture(
                            onTap = onSwapColors,
                            onDragStart = onColorDropStart,
                            onDragMove = onColorDropMove,
                            onDragEnd = onColorDropEnd,
                            onDragCancel = onColorDropCancel,
                        ),
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

                if (isPinned) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom 5 Navigation Tabs (0: Wheel, 1: Square, 2: Harmony, 3: Palettes, 4: Sliders)
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
            .background(Morandi.panelHi),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab 0: Wheel (◎)
        BottomTabButton(
            selected = selectedTab == 0,
            onClick = { onTabSelect(0) }
        ) { tint ->
            Canvas(modifier = Modifier.size(17.dp)) {
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
                    .size(15.dp, 16.dp)
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

        // Tab 2: Harmony (3 connected chord nodes)
        BottomTabButton(
            selected = selectedTab == 2,
            onClick = { onTabSelect(2) }
        ) { tint ->
            Canvas(modifier = Modifier.size(17.dp)) {
                val r = size.minDimension / 2f
                drawCircle(tint, radius = r, style = Stroke(1.8.dp.toPx()))
                val cx = size.width / 2f
                val cy = size.height / 2f
                val innerR = r * 0.52f
                val p1 = Offset(cx, cy - innerR)
                val p2 = Offset(cx + innerR * 0.866f, cy + innerR * 0.5f)
                val p3 = Offset(cx - innerR * 0.866f, cy + innerR * 0.5f)
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    lineTo(p3.x, p3.y)
                    close()
                }
                drawPath(path, color = tint.copy(alpha = 0.35f))
                drawPath(path, color = tint, style = Stroke(1.2.dp.toPx()))
                drawCircle(tint, radius = 1.8.dp.toPx(), center = p1)
                drawCircle(tint, radius = 1.8.dp.toPx(), center = p2)
                drawCircle(tint, radius = 1.8.dp.toPx(), center = p3)
            }
        }

        // Tab 3: Palettes Grid (田)
        BottomTabButton(
            selected = selectedTab == 3,
            onClick = { onTabSelect(3) }
        ) { tint ->
            Canvas(modifier = Modifier.size(17.dp)) {
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

        // Tab 4: Sliders (三)
        BottomTabButton(
            selected = selectedTab == 4,
            onClick = { onTabSelect(4) }
        ) { tint ->
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(16.dp, 2.dp).background(tint, RoundedCornerShape(1.dp)))
                Box(Modifier.size(11.dp, 2.dp).background(tint, RoundedCornerShape(1.dp)))
                Box(Modifier.size(16.dp, 2.dp).background(tint, RoundedCornerShape(1.dp)))
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
            .size(44.dp, 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        iconContent(if (selected) Morandi.accent else Morandi.icon)
    }
}
