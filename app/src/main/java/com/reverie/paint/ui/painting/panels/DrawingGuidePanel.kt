/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.DrawingGuideConfig
import com.reverie.paint.model.GuideMode
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.SymmetryType
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * 绘图辅助与参考线控制面板 (Procreate Drawing Guides & Assist)
 */
@Composable
fun DrawingGuidePanel(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null,
) {
    val guide = vm.drawingGuide
    val panelShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .width(320.dp)
            .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(panelShape)
            .then(
                if (vm.blurBackground && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(vm.popupPanelOpacity))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
                }
            )
            .glassBorder(panelShape)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_canvas_resize),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.guide_title),
                        color = Morandi.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Morandi.panelHi.copy(alpha = 0.6f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Morandi.subText, fontSize = 12.sp)
                }
            }

            // Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GuideModeChip(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_mode_off),
                    selected = guide.mode == GuideMode.OFF,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.drawingGuide = guide.copy(mode = GuideMode.OFF)
                }
                GuideModeChip(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_mode_2d_grid),
                    selected = guide.mode == GuideMode.GRID_2D,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.drawingGuide = guide.copy(mode = GuideMode.GRID_2D, assistedDrawing = true)
                }
                GuideModeChip(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_mode_isometric),
                    selected = guide.mode == GuideMode.ISOMETRIC,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.drawingGuide = guide.copy(mode = GuideMode.ISOMETRIC, assistedDrawing = true)
                }
                GuideModeChip(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_mode_perspective),
                    selected = guide.mode == GuideMode.PERSPECTIVE,
                    modifier = Modifier.weight(1f),
                ) {
                    val pts = if (guide.perspectiveVanishingPoints.isEmpty()) {
                        listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                    } else guide.perspectiveVanishingPoints
                    vm.drawingGuide = guide.copy(
                        mode = GuideMode.PERSPECTIVE,
                        assistedDrawing = true,
                        perspectiveVanishingPoints = pts,
                    )
                }
                GuideModeChip(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_mode_symmetry),
                    selected = guide.mode == GuideMode.SYMMETRY,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.drawingGuide = guide.copy(mode = GuideMode.SYMMETRY, assistedDrawing = true)
                }
            }

            if (guide.mode != GuideMode.OFF) {
                // Assisted Drawing Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.border.copy(alpha = 0.25f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(androidx.compose.ui.res.stringResource(R.string.guide_assist_title), color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (guide.mode == GuideMode.SYMMETRY) androidx.compose.ui.res.stringResource(R.string.guide_assist_symmetry_desc) else androidx.compose.ui.res.stringResource(R.string.guide_assist_align_desc),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = guide.assistedDrawing,
                        onCheckedChange = {
                            vm.drawingGuide = guide.copy(assistedDrawing = it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Morandi.accent,
                            uncheckedThumbColor = Morandi.subText,
                            uncheckedTrackColor = Morandi.border,
                        ),
                    )
                }

                // Perspective vanishing point presets (1-point, 2-point, 3-point)
                if (guide.mode == GuideMode.PERSPECTIVE) {
                    Text(androidx.compose.ui.res.stringResource(R.string.guide_perspective_config), color = Morandi.subText, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val ptCount = guide.perspectiveVanishingPoints.size.coerceIn(1, 3)
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_perspective_1pt), selected = ptCount == 1, Modifier.weight(1f)) {
                            vm.drawingGuide = guide.copy(
                                perspectiveVanishingPoints = listOf(
                                    Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f)
                                )
                            )
                        }
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_perspective_2pt), selected = ptCount == 2, Modifier.weight(1f)) {
                            val horizonY = vm.docHeight * 0.38f
                            vm.drawingGuide = guide.copy(
                                perspectiveVanishingPoints = listOf(
                                    Point2D(vm.docWidth * 0.1f, horizonY),
                                    Point2D(vm.docWidth * 0.9f, horizonY),
                                )
                            )
                        }
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_perspective_3pt), selected = ptCount == 3, Modifier.weight(1f)) {
                            val horizonY = vm.docHeight * 0.35f
                            vm.drawingGuide = guide.copy(
                                perspectiveVanishingPoints = listOf(
                                    Point2D(vm.docWidth * 0.15f, horizonY),
                                    Point2D(vm.docWidth * 0.85f, horizonY),
                                    Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.95f),
                                )
                            )
                        }
                    }
                }

                // Symmetry Type Selector
                if (guide.mode == GuideMode.SYMMETRY) {
                    Text(androidx.compose.ui.res.stringResource(R.string.guide_symmetry_type), color = Morandi.subText, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_symmetry_vertical), selected = guide.symmetryType == SymmetryType.VERTICAL, Modifier.weight(1f)) {
                            vm.drawingGuide = guide.copy(symmetryType = SymmetryType.VERTICAL)
                        }
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_symmetry_horizontal), selected = guide.symmetryType == SymmetryType.HORIZONTAL, Modifier.weight(1f)) {
                            vm.drawingGuide = guide.copy(symmetryType = SymmetryType.HORIZONTAL)
                        }
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_symmetry_quadrant), selected = guide.symmetryType == SymmetryType.QUADRANT, Modifier.weight(1f)) {
                            vm.drawingGuide = guide.copy(symmetryType = SymmetryType.QUADRANT)
                        }
                        SymmetryChip(androidx.compose.ui.res.stringResource(R.string.guide_symmetry_radial), selected = guide.symmetryType == SymmetryType.RADIAL, Modifier.weight(1f)) {
                            vm.drawingGuide = guide.copy(symmetryType = SymmetryType.RADIAL)
                        }
                    }

                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.guide_symmetry_center),
                        valueText = "${(guide.symmetryCenterX * 100f).roundToInt()}%",
                        range = 0.1f..0.9f,
                        value = guide.symmetryCenterX,
                        onValue = { vm.drawingGuide = guide.copy(symmetryCenterX = it, symmetryCenterY = it) },
                    )
                }

                // Grid Size / Density
                if (guide.mode == GuideMode.GRID_2D || guide.mode == GuideMode.ISOMETRIC) {
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.guide_grid_size),
                        valueText = "${guide.gridSize.roundToInt()}px",
                        range = 16f..240f,
                        value = guide.gridSize,
                        onValue = { vm.drawingGuide = guide.copy(gridSize = it) },
                    )
                }

                // Opacity Slider
                ToolFloatSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.guide_line_opacity),
                    valueText = "${(guide.opacity * 100f).roundToInt()}%",
                    range = 0.1f..1f,
                    value = guide.opacity,
                    onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                )
            }
        }
    }
}

@Composable
private fun GuideModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Morandi.text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SymmetryChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.25f) else Morandi.panelHi.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Morandi.accent else Morandi.text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
