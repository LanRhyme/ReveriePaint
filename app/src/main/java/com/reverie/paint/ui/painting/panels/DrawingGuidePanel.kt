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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * 绘图辅助中心 Tab 枚举
 */
enum class AssistCenterTab {
    GUIDES,
    QUICK_SHAPE,
}

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

    // 状态记忆：最后一次浏览的 Tab
    var selectedTab by rememberSaveable { mutableStateOf(AssistCenterTab.GUIDES) }

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
            // Header 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_grid),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_title),
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

            // 双 Tab 导航栏：空间参考线 vs 速创形状
            AssistCenterTabBar(
                currentTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )

            // Tab 1: 空间参考线
            if (selectedTab == AssistCenterTab.GUIDES) {
                // 4 档模式选择器 (支持点击已选项反选取消停用)
                SegmentedModeSelector(
                    currentMode = guide.mode,
                    onSelectMode = { targetMode ->
                        if (targetMode == guide.mode) {
                            // 再次轻触当前模式反选取消停用
                            vm.drawingGuide = guide.copy(mode = GuideMode.OFF, assistedDrawing = false)
                        } else {
                            val pts = if (guide.perspectiveVanishingPoints.isEmpty()) {
                                listOf(Point2D(vm.docWidth * 0.5f, vm.docHeight * 0.35f))
                            } else guide.perspectiveVanishingPoints
                            val enableAssist = !(targetMode == GuideMode.PERSPECTIVE && guide.perspective3d)
                            vm.drawingGuide = guide.copy(
                                mode = targetMode,
                                assistedDrawing = enableAssist,
                                perspectiveVanishingPoints = pts,
                            )
                        }
                    }
                )

                if (guide.mode != GuideMode.OFF) {
                    // 双胶囊快捷控制行：显隐参考线 + 辅助吸附
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GuideQuickPill(
                            iconRes = if (vm.drawingGuideLinesVisible) R.drawable.ic_eye else R.drawable.ic_eye_off,
                            label = stringResource(if (vm.drawingGuideLinesVisible) R.string.guide_pill_lines_visible else R.string.guide_pill_lines_hidden),
                            active = vm.drawingGuideLinesVisible,
                            onClick = { vm.toggleDrawingGuideLines() },
                            modifier = Modifier.weight(1f),
                        )

                        val canAssist = guide.mode != GuideMode.PERSPECTIVE || !guide.perspective3d
                        if (canAssist) {
                            GuideQuickPill(
                                iconRes = R.drawable.ic_check,
                                label = stringResource(if (guide.assistedDrawing) R.string.guide_pill_assisted_on else R.string.guide_pill_assisted_off),
                                active = guide.assistedDrawing,
                                onClick = { vm.drawingGuide = guide.copy(assistedDrawing = !guide.assistedDrawing) },
                                modifier = Modifier.weight(1f),
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
                                    label = stringResource(R.string.guide_perspective_ground),
                                    selected = guide.perspectiveShowGround,
                                    onClick = { vm.drawingGuide = guide.copy(perspectiveShowGround = !guide.perspectiveShowGround) },
                                    modifier = Modifier.weight(1f),
                                )
                                CompactToggleChip(
                                    label = stringResource(R.string.guide_perspective_cube),
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
                                    label = stringResource(R.string.guide_perspective_pitch),
                                    valueText = "${guide.perspectivePitchDeg.roundToInt()}°",
                                    range = -85f..85f,
                                    value = guide.perspectivePitchDeg,
                                    onValue = { vm.drawingGuide = guide.copy(perspectivePitchDeg = it) },
                                    modifier = Modifier.weight(1f),
                                )
                                CompactSliderItem(
                                    label = stringResource(R.string.guide_perspective_yaw),
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
                                    label = stringResource(R.string.guide_perspective_roll),
                                    valueText = "${guide.perspectiveRollDeg.roundToInt()}°",
                                    range = -90f..90f,
                                    value = guide.perspectiveRollDeg,
                                    onValue = { vm.drawingGuide = guide.copy(perspectiveRollDeg = it) },
                                    modifier = Modifier.weight(1f),
                                )
                                CompactSliderItem(
                                    label = stringResource(R.string.guide_perspective_fov),
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
                                    label = stringResource(R.string.guide_perspective_grid_div),
                                    valueText = "${guide.perspectiveGridDensity}",
                                    range = 4f..24f,
                                    value = guide.perspectiveGridDensity.toFloat(),
                                    onValue = { vm.drawingGuide = guide.copy(perspectiveGridDensity = it.roundToInt()) },
                                    modifier = Modifier.weight(1f),
                                )
                                CompactSliderItem(
                                    label = stringResource(R.string.guide_line_opacity),
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
                                    1 to stringResource(R.string.guide_perspective_1pt),
                                    2 to stringResource(R.string.guide_perspective_2pt),
                                    3 to stringResource(R.string.guide_perspective_3pt),
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
                                    label = stringResource(R.string.guide_perspective_ray_density),
                                    valueText = "${guide.perspectiveRayCount}",
                                    range = 2f..96f,
                                    value = guide.perspectiveRayCount.toFloat(),
                                    onValue = { vm.drawingGuide = guide.copy(perspectiveRayCount = it.roundToInt()) },
                                    modifier = Modifier.weight(1f),
                                )
                                CompactSliderItem(
                                    label = stringResource(R.string.guide_line_opacity),
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
                                stringResource(R.string.guide_symmetry_type),
                                color = Morandi.subText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.guide_symmetry_reset_center),
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
                                SymmetryType.VERTICAL to stringResource(R.string.guide_symmetry_vertical),
                                SymmetryType.HORIZONTAL to stringResource(R.string.guide_symmetry_horizontal),
                                SymmetryType.ROTATIONAL to stringResource(R.string.guide_symmetry_rotational),
                                SymmetryType.KALEIDOSCOPE to stringResource(R.string.guide_symmetry_kaleidoscope),
                            ),
                            selectedKey = guide.symmetryType,
                            onSelect = { vm.drawingGuide = guide.copy(symmetryType = it) }
                        )

                        if (guide.symmetryType == SymmetryType.ROTATIONAL || guide.symmetryType == SymmetryType.KALEIDOSCOPE) {
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
                                        text = stringResource(R.string.guide_symmetry_rays),
                                        color = Morandi.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    val raysText = if (guide.symmetryType == SymmetryType.ROTATIONAL) {
                                        stringResource(R.string.guide_symmetry_rays_rotational_format, guide.symmetryRayCount)
                                    } else {
                                        stringResource(R.string.guide_symmetry_rays_mirrored_format, guide.symmetryRayCount, guide.symmetryRayCount * 2)
                                    }
                                    Text(
                                        text = raysText,
                                        color = Morandi.accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    listOf(2, 3, 4, 5, 6, 8, 12, 16).forEach { presetRays ->
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
                                label = stringResource(R.string.guide_symmetry_rotation),
                                valueText = "${((guide.symmetryRotationDeg % 180f + 180f) % 180f).roundToInt()}°",
                                range = 0f..180f,
                                value = (guide.symmetryRotationDeg % 180f + 180f) % 180f,
                                onValue = { vm.drawingGuide = guide.copy(symmetryRotationDeg = it) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = stringResource(R.string.guide_symmetry_center),
                                valueText = "${(if (guide.symmetryType == SymmetryType.HORIZONTAL) guide.symmetryCenterY * 100f else guide.symmetryCenterX * 100f).roundToInt()}%",
                                range = 0.1f..0.9f,
                                value = if (guide.symmetryType == SymmetryType.HORIZONTAL) guide.symmetryCenterY else guide.symmetryCenterX,
                                onValue = {
                                    when (guide.symmetryType) {
                                        SymmetryType.HORIZONTAL -> vm.drawingGuide = guide.copy(symmetryCenterX = 0.5f, symmetryCenterY = it)
                                        SymmetryType.VERTICAL -> vm.drawingGuide = guide.copy(symmetryCenterX = it, symmetryCenterY = 0.5f)
                                        else -> vm.drawingGuide = guide.copy(symmetryCenterX = it, symmetryCenterY = it)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        CompactSliderItem(
                            label = stringResource(R.string.guide_line_opacity),
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
                                label = stringResource(R.string.guide_grid_size),
                                valueText = "${guide.gridSize.roundToInt()}px",
                                range = 16f..240f,
                                value = guide.gridSize,
                                onValue = { vm.drawingGuide = guide.copy(gridSize = it) },
                                modifier = Modifier.weight(1f),
                            )
                            CompactSliderItem(
                                label = stringResource(R.string.guide_line_opacity),
                                valueText = "${(guide.opacity * 100f).roundToInt()}%",
                                range = 0.1f..1f,
                                value = guide.opacity,
                                onValue = { vm.drawingGuide = guide.copy(opacity = it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    // 模式处于关闭停用态时的轻量提示卡片
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.border.copy(alpha = 0.18f))
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.guide_inactive_hint),
                            color = Morandi.subText,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            } else {
                // Tab 2: 速创形状
                QuickShapeTabContent(vm = vm)
            }
        }
    }
}

/**
 * 绘图辅助中心双 Tab 切换胶囊条
 */
@Composable
private fun AssistCenterTabBar(
    currentTab: AssistCenterTab,
    onTabSelected: (AssistCenterTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.border.copy(alpha = 0.25f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val tabs = listOf(
            AssistCenterTab.GUIDES to Pair(R.drawable.ic_grid, R.string.guide_tab_spatial),
            AssistCenterTab.QUICK_SHAPE to Pair(R.drawable.ic_shapes, R.string.guide_tab_quickshape),
        )

        for ((tab, resPair) in tabs) {
            val (iconRes, textRes) = resPair
            val isSelected = currentTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Morandi.accent else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = if (isSelected) Morandi.onAccent else Morandi.subText,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(textRes),
                        color = if (isSelected) Morandi.onAccent else Morandi.text,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 紧凑型胶囊药丸 (显隐 / 吸附等核心状态快速切换)
 */
@Composable
private fun GuideQuickPill(
    iconRes: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Morandi.accent.copy(alpha = 0.22f) else Morandi.border.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (active) Morandi.accent else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                color = if (active) Morandi.accent else Morandi.text,
                fontSize = 11.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 现代分段模式选择器 (4 档模式，轻触已选模式即可反选取消停用)
 */
@Composable
private fun SegmentedModeSelector(
    currentMode: GuideMode,
    onSelectMode: (GuideMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        GuideMode.GRID_2D to stringResource(R.string.guide_mode_2d_grid),
        GuideMode.ISOMETRIC to stringResource(R.string.guide_mode_isometric),
        GuideMode.PERSPECTIVE to stringResource(R.string.guide_mode_perspective),
        GuideMode.SYMMETRY to stringResource(R.string.guide_mode_symmetry),
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
            true to stringResource(R.string.guide_perspective_mode_3d),
            false to stringResource(R.string.guide_perspective_mode_vanish),
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
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(12.dp),
                )
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
