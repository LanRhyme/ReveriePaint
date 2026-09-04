/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

/**
 * Main Color Panel container:
 * - Header: Drag handle, Title, Foreground / Secondary color swap & ColorDrop target
 * - Body: Animated tab transitions (0: Wheel, 1: Square, 2: Palettes, 3: Sliders)
 * - Footer: Bottom 4 navigation tabs with Morandi styling
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

    val context = LocalContext.current

    // Sync from vm.brushColor
    LaunchedEffect(vm.brushColor) {
        if (!isInteracting) {
            val c = android.graphics.Color.parseColor(vm.brushColor)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hue = hsv[0]
            sat = hsv[1]
            valB = hsv[2]
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
            // 1. Header: Drag handle pill + Title + Color Preview Swatches
            ColorPanelHeader(
                activeTab = vm.colorPanelTab,
                brushColor = vm.brushColor,
                secondaryColor = vm.brushSecondaryColor,
                onSwapColors = { vm.swapColors() },
                onColorDropStart = onColorDropStart,
                onColorDropMove = onColorDropMove,
                onColorDropEnd = onColorDropEnd,
                onColorDropCancel = onColorDropCancel,
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
    onSwapColors: () -> Unit,
    onColorDropStart: ((Offset) -> Unit)? = null,
    onColorDropMove: ((Offset) -> Unit)? = null,
    onColorDropEnd: ((Offset) -> Unit)? = null,
    onColorDropCancel: (() -> Unit)? = null,
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

            // Foreground / Background Colors Swap Box & Drag Source
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
            .background(Morandi.panelHi),
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
