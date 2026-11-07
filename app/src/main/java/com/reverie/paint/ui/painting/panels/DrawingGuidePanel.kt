/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.GuideMode
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.SymmetryType
import com.reverie.paint.ui.components.reportUiSlot
import com.reverie.paint.ui.components.evadablePanelSurface
import com.reverie.paint.ui.components.evadablePanelContent
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
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
            // 前缓冲 setZOrderOnTop: 必须登记槽位, 贴墨时 clipOutUi 挖孔露出本面板, 否则按住会盖住辅助设置窗。
            .reportUiSlot(vm, UiPanelSlot.DRAWING_GUIDE_PANEL)
            .clip(panelShape)
            .evadablePanelSurface(
                vm = vm,
                slot = UiPanelSlot.DRAWING_GUIDE_PANEL,
                shape = panelShape,
                baseOpacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
            .evadablePanelContent(vm, UiPanelSlot.DRAWING_GUIDE_PANEL)
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                        modifier = Modifier.size(17.dp),
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

            // Mode Selector: 现代胶囊分段器 (顶部五档单选)
            SegmentedModeSelector(
                currentMode = guide.mode,
                onSelectMode = { newMode ->
                    if (newMode == guide.mode) {
                        return@SegmentedModeSelector
                    }
                    if (newMode == GuideMode.OFF) {
                        vm.drawingGuide = guide.copy(mode = GuideMode.OFF, assistedDrawing = false)
                    } else {
                        val pts = if (guide.perspectiveVanishingPoints.isEmpty()) {
                            listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                        } else guide.perspectiveVanishingPoints
                        val enableAssist = !(newMode == GuideMode.PERSPECTIVE && guide.perspective3d)
                        vm.drawingGuide = guide.copy(
                            mode = newMode,
                            assistedDrawing = enableAssist,
                            perspectiveVanishingPoints = pts,
                        )
                    }
                }
            )

            if (guide.mode != GuideMode.OFF) {
                // 辅助吸附开关 (仅在非 3D 透视模式下呈现)
                if (guide.mode != GuideMode.PERSPECTIVE || !guide.perspective3d) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.border.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.guide_assist_title),
                                color = Morandi.text,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (guide.mode == GuideMode.SYMMETRY) androidx.compose.ui.res.stringResource(R.string.guide_assist_symmetry_desc) else androidx.compose.ui.res.stringResource(R.string.guide_assist_align_desc),
                                color = Morandi.subText,
                                fontSize = 10.5.sp,
                            )
                        }
                        Switch(
                            checked = guide.assistedDrawing,
                            onCheckedChange = { vm.drawingGuide = guide.copy(assistedDrawing = it) },
                            modifier = Modifier.height(28.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Morandi.accent,
                                uncheckedThumbColor = Morandi.subText,
                                uncheckedTrackColor = Morandi.border,
                            ),
                        )
                    }
                }

                // 1. PERSPECTIVE 模式
                if (guide.mode == GuideMode.PERSPECTIVE) {
                    // 3D 空间网格 vs 传统灭点射线 子分段器
                    PerspectiveSubModeSelector(
                        is3d = guide.perspective3d,
                        onToggle = { is3d ->
                            if (is3d == guide.perspective3d) return@PerspectiveSubModeSelector
                            vm.drawingGuide = guide.copy(perspective3d = is3d, assistedDrawing = !is3d)
                        }
                    )

                    if (guide.perspective3d) {
                        // 地面网格 / 立体立方体 开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactToggleChip(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_ground),
                                selected = guide.perspectiveShowGround,
                                onClick = { vm.drawingGuide = guide.copy(perspectiveShowGround = !guide.perspectiveShowGround) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactToggleChip(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_cube),
                                selected = guide.perspectiveShowCube,
                                onClick = { vm.drawingGuide = guide.copy(perspectiveShowCube = !guide.perspectiveShowCube) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 3D 参数卡片: 双列紧凑网格
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_pitch),
                                valueText = "${guide.perspectivePitchDeg.roundToInt()}°",
                                range = -85f..85f,
                                value = guide.perspectivePitchDeg,
                                onValue = { vm.drawingGuide = guide.copy(perspectivePitchDeg = it) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_yaw),
                                valueText = "${guide.perspectiveYawDeg.roundToInt()}°",
                                range = -180f..180f,
                                value = guide.perspectiveYawDeg,
                                onValue = { vm.drawingGuide = guide.copy(perspectiveYawDeg = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_roll),
                                valueText = "${guide.perspectiveRollDeg.roundToInt()}°",
                                range = -90f..90f,
                                value = guide.perspectiveRollDeg,
                                onValue = { vm.drawingGuide = guide.copy(perspectiveRollDeg = it) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_fov),
                                valueText = "${guide.perspectiveFovDeg.roundToInt()}°",
                                range = 20f..110f,
                                value = guide.perspectiveFovDeg,
                                onValue = { vm.drawingGuide = guide.copy(perspectiveFovDeg = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_grid_div),
                                valueText = "${guide.perspectiveGridDensity}",
                                range = 4f..24f,
                                value = guide.perspectiveGridDensity.toFloat(),
                                onValue = { vm.drawingGuide = guide.copy(perspectiveGridDensity = it.roundToInt()) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_line_opacity),
                                valueText = "${(guide.opacity * 100f).roundToInt()}%",
                                range = 0.1f..1f,
                                value = guide.opacity,
                                onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // 传统 1~3 点灭点预设分段器
                        SegmentedOptionSelector(
                            options = listOf(
                                1 to androidx.compose.ui.res.stringResource(R.string.guide_perspective_1pt),
                                2 to androidx.compose.ui.res.stringResource(R.string.guide_perspective_2pt),
                                3 to androidx.compose.ui.res.stringResource(R.string.guide_perspective_3pt),
                            ),
                            selectedKey = guide.perspectiveVanishingPoints.size.coerceIn(1, 3),
                            onSelect = { cnt ->
                                val pts = when (cnt) {
                                    1 -> listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                                    2 -> listOf(Point2D(vm.docWidth * 0.1f, vm.docHeight * 0.38f), Point2D(vm.docWidth * 0.9f, vm.docHeight * 0.38f))
                                    else -> listOf(
                                        Point2D(vm.docWidth * 0.15f, vm.docHeight * 0.35f),
                                        Point2D(vm.docWidth * 0.85f, vm.docHeight * 0.35f),
                                        Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.95f),
                                    )
                                }
                                vm.drawingGuide = guide.copy(perspectiveVanishingPoints = pts)
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_perspective_ray_density),
                                valueText = "${guide.perspectiveRayCount}",
                                range = 2f..96f,
                                value = guide.perspectiveRayCount.toFloat(),
                                onValue = { vm.drawingGuide = guide.copy(perspectiveRayCount = it.roundToInt()) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = androidx.compose.ui.res.stringResource(R.string.guide_line_opacity),
                                valueText = "${(guide.opacity * 100f).roundToInt()}%",
                                range = 0.1f..1f,
                                value = guide.opacity,
                                onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // 2. SYMMETRY 模式
                if (guide.mode == GuideMode.SYMMETRY) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.guide_symmetry_type),
                            color = Morandi.subText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.guide_symmetry_reset_center),
                            color = Morandi.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    vm.drawingGuide = guide.copy(symmetryCenterX = 0.5f, symmetryCenterY = 0.5f, symmetryRotationDeg = 0f)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    SegmentedOptionSelector(
                        options = listOf(
                            SymmetryType.VERTICAL to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_vertical),
                            SymmetryType.HORIZONTAL to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_horizontal),
                            SymmetryType.QUADRANT to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_quadrant),
                            SymmetryType.RADIAL to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_radial),
                        ),
                        selectedKey = guide.symmetryType,
                        onSelect = { vm.drawingGuide = guide.copy(symmetryType = it) }
                    )

                    if (guide.symmetryType == SymmetryType.RADIAL) {
                        SegmentedOptionSelector(
                            options = listOf(
                                true to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_mode_rotational),
                                false to androidx.compose.ui.res.stringResource(R.string.guide_symmetry_mode_mirrored),
                            ),
                            selectedKey = guide.symmetryRotational,
                            onSelect = { vm.drawingGuide = guide.copy(symmetryRotational = it) },
                        )

                        // 对称轴数量与快速预设
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.border.copy(alpha = 0.22f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.guide_symmetry_rays),
                                    color = Morandi.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                val raysText = if (guide.symmetryRotational) {
                                    androidx.compose.ui.res.stringResource(R.string.guide_symmetry_rays_rotational_format, guide.symmetryRayCount)
                                } else {
                                    androidx.compose.ui.res.stringResource(R.string.guide_symmetry_rays_mirrored_format, guide.symmetryRayCount, guide.symmetryRayCount * 2)
                                }
                                Text(
                                    text = raysText,
                                    color = Morandi.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            // 快捷常用轴数预设: 3(三叶陀螺), 4(十字), 5(五叶陀螺), 6(六角), 8(八角), 12(十二角), 16
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(3, 4, 5, 6, 8, 12, 16).forEach { presetRays ->
                                    val isSelected = guide.symmetryRayCount == presetRays
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Morandi.accent else Morandi.panel.copy(alpha = 0.65f))
                                            .clickable {
                                                vm.drawingGuide = guide.copy(symmetryRayCount = presetRays)
                                            }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "$presetRays",
                                            color = if (isSelected) Morandi.onAccent else Morandi.text,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        )
                                    }
                                }
                            }

                            // 滑块 + 步进器微调
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Morandi.panel)
                                        .clickable {
                                            val next = (guide.symmetryRayCount - 1).coerceAtLeast(com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN)
                                            vm.drawingGuide = guide.copy(symmetryRayCount = next)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("-", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }

                                val span = (com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX - com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN).toFloat()
                                val minRays = com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN.toFloat()
                                com.reverie.paint.ui.components.ReSlider(
                                    value = ((guide.symmetryRayCount - minRays) / span).coerceIn(0f, 1f),
                                    onValue = { frac ->
                                        val newRays = (minRays + frac * span).roundToInt().coerceIn(
                                            com.reverie.paint.model.SYMMETRY_RAY_COUNT_MIN,
                                            com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX,
                                        )
                                        vm.drawingGuide = guide.copy(symmetryRayCount = newRays)
                                    },
                                    modifier = Modifier.weight(1f),
                                )

                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Morandi.panel)
                                        .clickable {
                                            val next = (guide.symmetryRayCount + 1).coerceAtMost(com.reverie.paint.model.SYMMETRY_RAY_COUNT_MAX)
                                            vm.drawingGuide = guide.copy(symmetryRayCount = next)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    SegmentedOptionSelector(
                        options = listOf(
                            0 to "0°",
                            45 to "45°",
                            90 to "90°",
                            135 to "135°",
                        ),
                        selectedKey = ((guide.symmetryRotationDeg % 180f + 180f) % 180f).roundToInt(),
                        onSelect = { vm.drawingGuide = guide.copy(symmetryRotationDeg = it.toFloat()) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactSliderItem(
                            label = androidx.compose.ui.res.stringResource(R.string.guide_symmetry_rotation),
                            valueText = "${((guide.symmetryRotationDeg % 180f + 180f) % 180f).roundToInt()}°",
                            range = 0f..180f,
                            value = (guide.symmetryRotationDeg % 180f + 180f) % 180f,
                            onValue = { vm.drawingGuide = guide.copy(symmetryRotationDeg = it) },
                            modifier = Modifier.weight(1f),
                        )
                        CompactSliderItem(
                            label = androidx.compose.ui.res.stringResource(R.string.guide_symmetry_center),
                            valueText = "${(guide.symmetryCenterX * 100f).roundToInt()}%",
                            range = 0.1f..0.9f,
                            value = guide.symmetryCenterX,
                            onValue = {
                                val newY = if (guide.symmetryType == SymmetryType.VERTICAL) 0.5f else it
                                vm.drawingGuide = guide.copy(symmetryCenterX = it, symmetryCenterY = newY)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    CompactSliderItem(
                        label = androidx.compose.ui.res.stringResource(R.string.guide_line_opacity),
                        valueText = "${(guide.opacity * 100f).roundToInt()}%",
                        range = 0.1f..1f,
                        value = guide.opacity,
                        onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3. GRID_2D & ISOMETRIC 模式
                if (guide.mode == GuideMode.GRID_2D || guide.mode == GuideMode.ISOMETRIC) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactSliderItem(
                            label = androidx.compose.ui.res.stringResource(R.string.guide_grid_size),
                            valueText = "${guide.gridSize.roundToInt()}px",
                            range = 16f..240f,
                            value = guide.gridSize,
                            onValue = { vm.drawingGuide = guide.copy(gridSize = it) },
                            modifier = Modifier.weight(1f),
                        )
                        CompactSliderItem(
                            label = androidx.compose.ui.res.stringResource(R.string.guide_line_opacity),
                            valueText = "${(guide.opacity * 100f).roundToInt()}%",
                            range = 0.1f..1f,
                            value = guide.opacity,
                            onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 速创形状折叠抽屉
            QuickShapeCollapsibleDrawer(vm)
        }
    }
}

/**
 * 现代分段模式选择器 (胶囊底槽 + 浮起选中态)
 */
@Composable
private fun SegmentedModeSelector(
    currentMode: GuideMode,
    onSelectMode: (GuideMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        GuideMode.OFF to androidx.compose.ui.res.stringResource(R.string.guide_mode_off_short),
        GuideMode.GRID_2D to androidx.compose.ui.res.stringResource(R.string.guide_mode_2d_grid),
        GuideMode.ISOMETRIC to androidx.compose.ui.res.stringResource(R.string.guide_mode_isometric),
        GuideMode.PERSPECTIVE to androidx.compose.ui.res.stringResource(R.string.guide_mode_perspective),
        GuideMode.SYMMETRY to androidx.compose.ui.res.stringResource(R.string.guide_mode_symmetry),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.border.copy(alpha = 0.25f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((m, label) in modes) {
            val isSelected = currentMode == m
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Morandi.accent else Color.Transparent)
                    .clickable { onSelectMode(m) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Morandi.onAccent else Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 透视子模式胶囊分段器 (3D 空间网格 / 传统灭点射线)
 */
@Composable
private fun PerspectiveSubModeSelector(
    is3d: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.border.copy(alpha = 0.22f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val options = listOf(
            true to androidx.compose.ui.res.stringResource(R.string.guide_perspective_mode_3d),
            false to androidx.compose.ui.res.stringResource(R.string.guide_perspective_mode_vanish),
        )
        for ((flag, label) in options) {
            val isSelected = is3d == flag
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onToggle(flag) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Morandi.onAccent else Morandi.text,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 通用分段选择器
 */
@Composable
private fun <T> SegmentedOptionSelector(
    options: List<Pair<T, String>>,
    selectedKey: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.border.copy(alpha = 0.22f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((key, label) in options) {
            val isSelected = selectedKey == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Morandi.onAccent else Morandi.text,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 紧凑型开关药丸
 */
@Composable
private fun CompactToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.22f) else Morandi.border.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                Text("✓", color = Morandi.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = label,
                color = if (selected) Morandi.accent else Morandi.text,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 紧凑卡片式滑块项 (上排标签+数值，下排滑轨，两列并排布局友好)
 */
@Composable
private fun CompactSliderItem(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = maxOf(0.001f, range.endInclusive - range.start)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.border.copy(alpha = 0.22f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = valueText,
                color = Morandi.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        com.reverie.paint.ui.components.ReSlider(
            value = ((value - range.start) / span).coerceIn(0f, 1f),
            onValue = { frac -> onValue(range.start + frac * span) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
