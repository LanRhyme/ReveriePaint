/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.graphics.Color as AColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.theme.systemHoverIcon
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full 5-tab color picker dialog reusing ColorPanel's pages (Wheel, Square, Harmony/3D Sphere,
 * Palettes, Sliders/Hex) with isolated local editing state so brushColor is never mutated.
 */
@Composable
internal fun FullColorPickerDialog(
    vm: PaintViewModel,
    title: String,
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    onResetToAuto: (() -> Unit)? = null,
    resetToAutoText: String = stringResource(R.string.color_sphere_reset_auto),
    allowShadingSphere: Boolean = true,
) {
    val context = LocalContext.current
    val initialRgb = remember(initialColor) {
        AColor.rgb(
            (initialColor.red * 255f).roundToInt().coerceIn(0, 255),
            (initialColor.green * 255f).roundToInt().coerceIn(0, 255),
            (initialColor.blue * 255f).roundToInt().coerceIn(0, 255),
        )
    }
    val initialHex = remember(initialRgb) {
        String.format(Locale.US, "#%06X", initialRgb and 0xFFFFFF)
    }

    var currentRgb by remember(initialColor) { mutableIntStateOf(initialRgb) }
    val initialHsv = remember(initialRgb, vm.colorModel) {
        rgbToHsvModel(initialRgb, vm.colorModel)
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var valB by remember(initialColor) { mutableFloatStateOf(initialHsv[2]) }
    var activeTab by remember { mutableIntStateOf(vm.colorPanelTab.coerceIn(0, 4)) }
    var isInteracting by remember { mutableStateOf(false) }
    var showHexInputDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm.colorModel) {
        if (!isInteracting) {
            val modelHsv = rgbToHsvModel(currentRgb, vm.colorModel)
            hue = modelHsv[0]
            sat = modelHsv[1]
            valB = modelHsv[2]
        }
    }

    val currentColor = remember(currentRgb) {
        Color(currentRgb or 0xFF000000.toInt())
    }
    val currentHex = remember(currentRgb) {
        String.format(Locale.US, "#%06X", currentRgb and 0xFFFFFF)
    }

    fun updateFromHsv(h: Float, s: Float, v: Float) {
        hue = h
        sat = s
        valB = v
        currentRgb = hsvModelToRgb(h, s, v, vm.colorModel)
    }

    fun updateFromHex(hex: String) {
        try {
            val parsed = AColor.parseColor(hex)
            val opaqueRgb = parsed or 0xFF000000.toInt()
            currentRgb = opaqueRgb
            val modelHsv = rgbToHsvModel(opaqueRgb, vm.colorModel)
            hue = modelHsv[0]
            sat = modelHsv[1]
            valB = modelHsv[2]
        } catch (_: Exception) {
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0.22f)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .noRippleClickable(onDismiss)
                .systemHoverIcon(context),
            contentAlignment = Alignment.Center,
        ) {
            val bodyMaxHeight = (maxHeight - 170.dp).coerceIn(180.dp, 420.dp)
            val cardShape = RoundedCornerShape(18.dp)

            Column(
                modifier = Modifier
                    .width(292.dp)
                    .noRippleClickable { /* consume click */ }
                    .clip(cardShape)
                    .background(Morandi.panel)
                    .glassBorder(cardShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header: Title + Original/Current Swatch + Clickable Hex + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = Morandi.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Original vs Current split preview; tapping left reverts to original color
                        Row(
                            modifier = Modifier
                                .size(34.dp, 22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Morandi.border, RoundedCornerShape(6.dp)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(initialRgb or 0xFF000000.toInt()))
                                    .clickable { updateFromHex(initialHex) },
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(currentColor),
                            )
                        }

                        // Clickable Hex chip -> opens HexInputDialog
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panelHi)
                                .clickable { showHexInputDialog = true }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = currentHex,
                                color = Morandi.text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Morandi.panelHi)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Morandi.subText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // Body: 5 ColorPanel tabs with isolated local state
                Box(modifier = Modifier.fillMaxWidth()) {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            (fadeIn(tween(110, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(70))) using SizeTransform(clip = false)
                        },
                        label = "FullColorPickerTabAnimation",
                    ) { tab ->
                        when (tab) {
                            0 -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyMaxHeight)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                WheelColorPage(
                                    vm = vm,
                                    hue = hue,
                                    sat = sat,
                                    valB = valB,
                                    onHue = { updateFromHsv(it, sat, valB) },
                                    onSatVal = { s, v -> updateFromHsv(hue, s, v) },
                                    onSat = { updateFromHsv(hue, it, valB) },
                                    onVal = { updateFromHsv(hue, sat, it) },
                                    onInteractionStart = { isInteracting = true },
                                    onInteractionEnd = { isInteracting = false },
                                    currentColorHex = currentHex,
                                    compareBaselineHex = initialHex,
                                    onDirectHexSelected = ::updateFromHex,
                                )
                            }
                            1 -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyMaxHeight)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                SquareHsbColorPage(
                                    vm = vm,
                                    hue = hue,
                                    sat = sat,
                                    valB = valB,
                                    onHue = { updateFromHsv(it, sat, valB) },
                                    onSatVal = { s, v -> updateFromHsv(hue, s, v) },
                                    onInteractionStart = { isInteracting = true },
                                    onInteractionEnd = { isInteracting = false },
                                    currentColorHex = currentHex,
                                    compareBaselineHex = initialHex,
                                    onDirectHexSelected = ::updateFromHex,
                                )
                            }
                            2 -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyMaxHeight)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                ColorHarmonyPage(
                                    vm = vm,
                                    hue = hue,
                                    sat = sat,
                                    valB = valB,
                                    onHue = { updateFromHsv(it, sat, valB) },
                                    onSatVal = { s, v -> updateFromHsv(hue, s, v) },
                                    onSat = { updateFromHsv(hue, it, valB) },
                                    onVal = { updateFromHsv(hue, sat, it) },
                                    onInteractionStart = { isInteracting = true },
                                    onInteractionEnd = { isInteracting = false },
                                    currentColorHex = currentHex,
                                    onDirectHexSelected = ::updateFromHex,
                                    isolatedState = true,
                                    allowShadingSphere = allowShadingSphere,
                                )
                            }
                            3 -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyMaxHeight),
                            ) {
                                PalettesPage(
                                    vm = vm,
                                    onColorSelected = ::updateFromHex,
                                    currentColorHex = currentHex,
                                )
                            }
                            else -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = bodyMaxHeight),
                            ) {
                                SlidersNumericPage(
                                    vm = vm,
                                    hue = hue,
                                    sat = sat,
                                    valB = valB,
                                    onHsvChange = { h, s, v -> updateFromHsv(h, s, v) },
                                    onInteractionStart = { isInteracting = true },
                                    onInteractionEnd = { isInteracting = false },
                                )
                            }
                        }
                    }
                }

                // Bottom 5 Navigation Tabs
                ColorPanelBottomTabs(
                    selectedTab = activeTab,
                    onTabSelect = { activeTab = it },
                )

                // Footer Action Row: Optional Reset + Cancel / Confirm
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onResetToAuto != null) {
                        ReTextButton(
                            text = resetToAutoText,
                            onClick = {
                                onResetToAuto()
                                onDismiss()
                            },
                            textColor = Morandi.subText,
                            fontSize = 12.sp,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            textColor = Morandi.subText,
                            fontSize = 12.sp,
                        )
                        ReTextButton(
                            text = stringResource(R.string.common_confirm),
                            onClick = {
                                onColorSelected(currentColor)
                                onDismiss()
                            },
                            primary = true,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    if (showHexInputDialog) {
        HexInputDialog(
            initialHex = currentHex,
            onColorConfirmed = { updateFromHex(it) },
            onDismiss = { showHexInputDialog = false },
        )
    }
}
