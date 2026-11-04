/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.PanelAlignment
import com.reverie.paint.model.PanelEdge
import com.reverie.paint.model.PanelPosition
import com.reverie.paint.model.QuickAction
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder
import kotlin.math.roundToInt

private enum class DragTargetPanel {
    NONE,
    TOPBAR,
    TOOLRAIL,
    SLIDERRAIL,
}

/**
 * 悬浮模式可视化排布与尺寸调整对话框 (平板专业分栏设计，莫兰迪沉浸美学)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloatingLayoutConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    var topPos by remember { mutableStateOf(vm.floatingUiConfig.topBarPosition) }
    var toolPos by remember { mutableStateOf(vm.floatingUiConfig.toolRailPosition) }
    var sliderPos by remember { mutableStateOf(vm.floatingUiConfig.sliderRailPosition) }
    var marginDp by remember { mutableFloatStateOf(vm.floatingUiConfig.marginDp.toFloat()) }
    var floatingScale by remember { mutableFloatStateOf(vm.floatingUiConfig.scale) }
    var currentTopBarActions by remember { mutableStateOf(vm.floatingUiConfig.topBarActions) }
    var currentPanelOrder by remember { mutableStateOf(vm.floatingUiConfig.panelOrder) }

    // 0 = 布局与尺寸, 1 = 顶部快捷栏
    var activeTab by remember { mutableIntStateOf(0) }

    fun getLayerIndex(panelId: String, pos: PanelPosition): Int {
        val sharing = listOf(
            "TOPBAR" to topPos,
            "TOOLRAIL" to toolPos,
            "SLIDERRAIL" to sliderPos
        ).filter { it.second == pos }
         .map { it.first }
         .sortedBy { id ->
             val idx = currentPanelOrder.indexOf(id)
             if (idx < 0) 99 else idx
         }
        return sharing.indexOf(panelId).coerceAtLeast(0)
    }

    fun onPanelMoved(panelId: String, newPos: PanelPosition) {
        when (panelId) {
            "TOPBAR" -> topPos = newPos
            "TOOLRAIL" -> toolPos = newPos
            "SLIDERRAIL" -> sliderPos = newPos
        }
        currentPanelOrder = currentPanelOrder.filter { it != panelId } + panelId
    }

    var selectedPanel by remember { mutableStateOf(DragTargetPanel.TOPBAR) }
    var draggingPanel by remember { mutableStateOf(DragTargetPanel.NONE) }
    var dragGlobalPos by remember { mutableStateOf<Offset?>(null) }
    var slotBoundsMap by remember { mutableStateOf(mapOf<PanelPosition, Rect>()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .glassBorder(RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                // 1. Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                            )
                            Text(
                                text = stringResource(R.string.panel_dock_dialog_title),
                                color = colors.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.panel_dock_drag_tip),
                            color = colors.subText,
                            fontSize = 11.5.sp,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReButton(
                            text = stringResource(R.string.panel_dock_reset),
                            primary = false,
                            onClick = {
                                topPos = PanelPosition(PanelEdge.TOP, PanelAlignment.CENTER)
                                toolPos = PanelPosition(PanelEdge.LEFT, PanelAlignment.START)
                                sliderPos = PanelPosition(PanelEdge.LEFT, PanelAlignment.END)
                                marginDp = 12f
                                floatingScale = 1.0f
                                currentTopBarActions = emptyList()
                                currentPanelOrder = com.reverie.paint.model.FloatingUiConfig.DEFAULT_PANEL_ORDER
                                selectedPanel = DragTargetPanel.TOPBAR
                            },
                        )
                        ReIconButton(
                            icon = R.drawable.ic_x,
                            desc = stringResource(R.string.common_close),
                            onTap = onDismiss,
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 2. Main Content: Left Tablet Mockup vs Right Config Panel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(395.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left: Realistic Tablet Mockup Preview
                    Box(
                        modifier = Modifier
                            .weight(1.22f)
                            .fillMaxHeight()
                            .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.28f))
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.panelHi.copy(alpha = 0.55f))
                            .glassBorder(RoundedCornerShape(18.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Tablet Screen Body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.canvasBg)
                                .border(1.dp, colors.border.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
                        ) {
                            // Camera Notch Dot (Top Center)
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(colors.subText.copy(alpha = 0.35f))
                                    .align(Alignment.TopCenter)
                                    .offset(y = 3.dp),
                            )

                            // Center Canvas Placeholder Sheet
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(0.62f)
                                    .align(Alignment.Center)
                                    .shadow(4.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.08f))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panel.copy(alpha = 0.35f))
                                    .border(1.dp, colors.border.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_canvas_tab),
                                        contentDescription = null,
                                        tint = colors.subText.copy(alpha = 0.35f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stringResource(R.string.panel_tab_canvas),
                                        color = colors.subText.copy(alpha = 0.45f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            // 12 Dock Slots & Draggable Capsules
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                val allSlots = remember {
                                    val list = mutableListOf<PanelPosition>()
                                    PanelEdge.entries.forEach { edge ->
                                        PanelAlignment.entries.forEach { align ->
                                            list.add(PanelPosition(edge, align))
                                        }
                                    }
                                    list
                                }

                                allSlots.forEach { slot ->
                                    val isSlotOccupied = slot == topPos || slot == toolPos || slot == sliderPos
                                    val isDropHovered = draggingPanel != DragTargetPanel.NONE &&
                                        dragGlobalPos?.let { pos ->
                                            slotBoundsMap[slot]?.contains(pos) == true
                                        } == true

                                    val slotModifier = resolveSlotAlignmentModifier(slot.edge, slot.alignment, layerIndex = 0)
                                    val slotShape = RoundedCornerShape(7.dp)

                                    val slotBg by animateColorAsState(
                                        targetValue = when {
                                            isDropHovered -> colors.accent.copy(alpha = 0.32f)
                                            isSlotOccupied -> colors.accent.copy(alpha = 0.08f)
                                            else -> colors.panelHi.copy(alpha = 0.22f)
                                        },
                                        label = "slotBg"
                                    )
                                    val slotBorderColor by animateColorAsState(
                                        targetValue = when {
                                            isDropHovered -> colors.accent
                                            isSlotOccupied -> colors.accent.copy(alpha = 0.3f)
                                            else -> colors.border.copy(alpha = 0.22f)
                                        },
                                        label = "slotBorder"
                                    )

                                    Box(
                                        modifier = slotModifier
                                            .onGloballyPositioned { coords ->
                                                slotBoundsMap = slotBoundsMap + (slot to coords.boundsInRoot())
                                            }
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {
                                                    when (selectedPanel) {
                                                        DragTargetPanel.TOPBAR -> onPanelMoved("TOPBAR", slot)
                                                        DragTargetPanel.TOOLRAIL -> onPanelMoved("TOOLRAIL", slot)
                                                        DragTargetPanel.SLIDERRAIL -> onPanelMoved("SLIDERRAIL", slot)
                                                        DragTargetPanel.NONE -> {}
                                                    }
                                                }
                                            )
                                            .width(if (slot.edge.isHorizontal) 78.dp else 34.dp)
                                            .height(if (slot.edge.isHorizontal) 28.dp else 60.dp)
                                            .clip(slotShape)
                                            .background(slotBg)
                                            .border(if (isDropHovered) 1.5.dp else 1.dp, slotBorderColor, slotShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (!isSlotOccupied) {
                                            Box(
                                                modifier = Modifier
                                                    .size(
                                                        width = if (slot.edge.isHorizontal) 14.dp else 3.dp,
                                                        height = if (slot.edge.isHorizontal) 3.dp else 14.dp,
                                                    )
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isDropHovered) colors.accent else colors.subText.copy(alpha = 0.28f)
                                                    ),
                                            )
                                        }
                                    }
                                }

                                // 3 Draggable Capsules
                                DraggablePanelCapsule(
                                    title = stringResource(R.string.panel_dock_topbar),
                                    icon = R.drawable.ic_canvas_tab,
                                    pos = topPos,
                                    layerIndex = getLayerIndex("TOPBAR", topPos),
                                    isSelected = selectedPanel == DragTargetPanel.TOPBAR,
                                    isDragging = draggingPanel == DragTargetPanel.TOPBAR,
                                    onSelect = { selectedPanel = DragTargetPanel.TOPBAR },
                                    onDragStart = {
                                        selectedPanel = DragTargetPanel.TOPBAR
                                        draggingPanel = DragTargetPanel.TOPBAR
                                    },
                                    onDrag = { global -> dragGlobalPos = global },
                                    onDragEnd = {
                                        dragGlobalPos?.let { pos ->
                                            slotBoundsMap.entries.firstOrNull { it.value.contains(pos) }?.key?.let { targetSlot ->
                                                onPanelMoved("TOPBAR", targetSlot)
                                            }
                                        }
                                        draggingPanel = DragTargetPanel.NONE
                                        dragGlobalPos = null
                                    },
                                )

                                DraggablePanelCapsule(
                                    title = stringResource(R.string.panel_dock_toolrail),
                                    icon = R.drawable.ic_brush,
                                    pos = toolPos,
                                    layerIndex = getLayerIndex("TOOLRAIL", toolPos),
                                    isSelected = selectedPanel == DragTargetPanel.TOOLRAIL,
                                    isDragging = draggingPanel == DragTargetPanel.TOOLRAIL,
                                    onSelect = { selectedPanel = DragTargetPanel.TOOLRAIL },
                                    onDragStart = {
                                        selectedPanel = DragTargetPanel.TOOLRAIL
                                        draggingPanel = DragTargetPanel.TOOLRAIL
                                    },
                                    onDrag = { global -> dragGlobalPos = global },
                                    onDragEnd = {
                                        dragGlobalPos?.let { pos ->
                                            slotBoundsMap.entries.firstOrNull { it.value.contains(pos) }?.key?.let { targetSlot ->
                                                onPanelMoved("TOOLRAIL", targetSlot)
                                            }
                                        }
                                        draggingPanel = DragTargetPanel.NONE
                                        dragGlobalPos = null
                                    },
                                )

                                DraggablePanelCapsule(
                                    title = stringResource(R.string.panel_dock_sliderrail),
                                    icon = R.drawable.ic_eye,
                                    pos = sliderPos,
                                    layerIndex = getLayerIndex("SLIDERRAIL", sliderPos),
                                    isSelected = selectedPanel == DragTargetPanel.SLIDERRAIL,
                                    isDragging = draggingPanel == DragTargetPanel.SLIDERRAIL,
                                    onSelect = { selectedPanel = DragTargetPanel.SLIDERRAIL },
                                    onDragStart = {
                                        selectedPanel = DragTargetPanel.SLIDERRAIL
                                        draggingPanel = DragTargetPanel.SLIDERRAIL
                                    },
                                    onDrag = { global -> dragGlobalPos = global },
                                    onDragEnd = {
                                        dragGlobalPos?.let { pos ->
                                            slotBoundsMap.entries.firstOrNull { it.value.contains(pos) }?.key?.let { targetSlot ->
                                                onPanelMoved("SLIDERRAIL", targetSlot)
                                            }
                                        }
                                        draggingPanel = DragTargetPanel.NONE
                                        dragGlobalPos = null
                                    },
                                )
                            }
                        }
                    }

                    // Right: Segmented Switcher & Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Segmented Pill Tab Switcher
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panelHi.copy(alpha = 0.5f))
                                .glassBorder(RoundedCornerShape(12.dp))
                                .padding(3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (activeTab == 0) colors.accent else Color.Transparent)
                                    .clickable { activeTab = 0 }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.floating_tab_layout),
                                    color = if (activeTab == 0) colors.onAccent else colors.text,
                                    fontSize = 12.sp,
                                    fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (activeTab == 1) colors.accent else Color.Transparent)
                                    .clickable { activeTab = 1 }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.floating_tab_shortcuts),
                                        color = if (activeTab == 1) colors.onAccent else colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    if (currentTopBarActions.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(if (activeTab == 1) colors.onAccent.copy(alpha = 0.2f) else colors.accent),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "${currentTopBarActions.size}",
                                                color = colors.onAccent,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Tab 0: Layout, Margin, Scale & Stacking
                        if (activeTab == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Margin Slider Card
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.panelHi.copy(alpha = 0.38f))
                                        .glassBorder(RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.panel_dock_margin_title),
                                            color = colors.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(colors.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "${marginDp.roundToInt()} dp",
                                                color = colors.accent,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stringResource(R.string.panel_dock_margin_desc),
                                        color = colors.subText,
                                        fontSize = 10.5.sp,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    ReSlider(
                                        value = ((marginDp - 8f) / (48f - 8f)).coerceIn(0f, 1f),
                                        onValue = { frac ->
                                            marginDp = 8f + frac * (48f - 8f)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Scale Slider Card
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.panelHi.copy(alpha = 0.38f))
                                        .glassBorder(RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.panel_dock_scale_title),
                                            color = colors.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(colors.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "${(floatingScale * 100).roundToInt()}%",
                                                color = colors.accent,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = stringResource(R.string.panel_dock_scale_desc),
                                        color = colors.subText,
                                        fontSize = 10.5.sp,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    ReSlider(
                                        value = ((floatingScale - 0.70f) / (1.40f - 0.70f)).coerceIn(0f, 1f),
                                        onValue = { frac ->
                                            floatingScale = 0.70f + frac * 0.70f
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Shared Slot Card (if 2+ panels share a slot)
                                val sharedGroups = remember(topPos, toolPos, sliderPos, currentPanelOrder) {
                                    listOf(
                                        "TOPBAR" to topPos,
                                        "TOOLRAIL" to toolPos,
                                        "SLIDERRAIL" to sliderPos
                                    ).groupBy({ it.second }, { it.first })
                                     .filter { it.value.size > 1 }
                                }

                                if (sharedGroups.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(colors.panelHi.copy(alpha = 0.38f))
                                            .glassBorder(RoundedCornerShape(14.dp))
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.floating_shared_slot_title),
                                                color = colors.text,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(colors.accent.copy(alpha = 0.15f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = "${sharedGroups.size}",
                                                    color = colors.accent,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.floating_shared_slot_desc),
                                            color = colors.subText,
                                            fontSize = 10.5.sp,
                                        )

                                        sharedGroups.forEach { (_, panelIds) ->
                                            val sortedPanels = panelIds.sortedBy { id ->
                                                val idx = currentPanelOrder.indexOf(id)
                                                if (idx < 0) 99 else idx
                                            }
                                            if (sortedPanels.size >= 2) {
                                                val outer = sortedPanels[0]
                                                val inner = sortedPanels[1]
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(colors.panel.copy(alpha = 0.7f))
                                                        .glassBorder(RoundedCornerShape(10.dp))
                                                        .padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            val outerName = when (outer) {
                                                                "TOPBAR" -> stringResource(R.string.panel_dock_topbar)
                                                                "TOOLRAIL" -> stringResource(R.string.panel_dock_toolrail)
                                                                else -> stringResource(R.string.panel_dock_sliderrail)
                                                            }
                                                            val innerName = when (inner) {
                                                                "TOPBAR" -> stringResource(R.string.panel_dock_topbar)
                                                                "TOOLRAIL" -> stringResource(R.string.panel_dock_toolrail)
                                                                else -> stringResource(R.string.panel_dock_sliderrail)
                                                            }
                                                            Text(
                                                                text = "${stringResource(R.string.floating_shared_slot_outer)}: $outerName",
                                                                color = colors.text,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium,
                                                            )
                                                            Text(
                                                                text = "${stringResource(R.string.floating_shared_slot_inner)}: $innerName",
                                                                color = colors.subText,
                                                                fontSize = 11.sp,
                                                            )
                                                        }
                                                        ReButton(
                                                            text = stringResource(R.string.floating_shared_slot_swap),
                                                            primary = false,
                                                            onClick = {
                                                                val list = currentPanelOrder.toMutableList()
                                                                val idx1 = list.indexOf(outer)
                                                                val idx2 = list.indexOf(inner)
                                                                if (idx1 >= 0 && idx2 >= 0) {
                                                                    list[idx1] = inner
                                                                    list[idx2] = outer
                                                                } else {
                                                                    list.remove(outer)
                                                                    list.remove(inner)
                                                                    list.add(inner)
                                                                    list.add(outer)
                                                                }
                                                                currentPanelOrder = list
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Tab 1: Embedded Top Bar Shortcuts Manager
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Subtitle & Clear All Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.floating_topbar_shortcuts_desc),
                                        color = colors.subText,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (currentTopBarActions.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.floating_topbar_shortcuts_clear_all),
                                            color = colors.subText,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { currentTopBarActions = emptyList() }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                // Added Shortcuts Section
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.panelHi.copy(alpha = 0.38f))
                                        .glassBorder(RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.floating_topbar_shortcuts_added_title),
                                            color = colors.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (currentTopBarActions.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(colors.accent.copy(alpha = 0.15f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = "${currentTopBarActions.size}",
                                                    color = colors.accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }

                                    if (currentTopBarActions.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(64.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.floating_topbar_shortcuts_empty),
                                                color = colors.subText.copy(alpha = 0.6f),
                                                fontSize = 11.5.sp,
                                            )
                                        }
                                    } else {
                                        currentTopBarActions.forEachIndexed { index, action ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(colors.panel.copy(alpha = 0.9f))
                                                    .glassBorder(RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(action.iconRes),
                                                        contentDescription = null,
                                                        tint = colors.accent,
                                                        modifier = Modifier.size(15.dp),
                                                    )
                                                    Text(
                                                        text = stringResource(action.titleRes),
                                                        color = colors.text,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    ReIconButton(
                                                        icon = R.drawable.ic_arrow_up,
                                                        desc = "Move Up",
                                                        onTap = {
                                                            if (index > 0) {
                                                                val list = currentTopBarActions.toMutableList()
                                                                val temp = list[index]
                                                                list[index] = list[index - 1]
                                                                list[index - 1] = temp
                                                                currentTopBarActions = list
                                                            }
                                                        },
                                                        size = 26.dp,
                                                        iconSize = 13.dp,
                                                        tint = if (index > 0) colors.icon else colors.subText.copy(alpha = 0.3f),
                                                    )
                                                    ReIconButton(
                                                        icon = R.drawable.ic_arrow_down,
                                                        desc = "Move Down",
                                                        onTap = {
                                                            if (index < currentTopBarActions.size - 1) {
                                                                val list = currentTopBarActions.toMutableList()
                                                                val temp = list[index]
                                                                list[index] = list[index + 1]
                                                                list[index + 1] = temp
                                                                currentTopBarActions = list
                                                            }
                                                        },
                                                        size = 26.dp,
                                                        iconSize = 13.dp,
                                                        tint = if (index < currentTopBarActions.size - 1) colors.icon else colors.subText.copy(alpha = 0.3f),
                                                    )
                                                    ReIconButton(
                                                        icon = R.drawable.ic_trash,
                                                        desc = "Remove",
                                                        onTap = {
                                                            currentTopBarActions = currentTopBarActions.filterIndexed { i, _ -> i != index }
                                                        },
                                                        size = 26.dp,
                                                        iconSize = 13.dp,
                                                        tint = colors.subText,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Available Shortcuts Section
                                val candidateActions = remember {
                                    QuickAction.entries.filter {
                                        it != QuickAction.UNDO &&
                                        it != QuickAction.REDO &&
                                        it != QuickAction.TOGGLE_QUICK_LAYER
                                    }
                                }
                                val availableActions = candidateActions.filter { it !in currentTopBarActions }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.panelHi.copy(alpha = 0.38f))
                                        .glassBorder(RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.floating_topbar_shortcuts_add_title),
                                        color = colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )

                                    if (availableActions.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.floating_topbar_shortcuts_all_added),
                                            color = colors.subText.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(vertical = 6.dp),
                                        )
                                    } else {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            availableActions.forEach { candidate ->
                                                Row(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(colors.panel.copy(alpha = 0.88f))
                                                        .glassBorder(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            currentTopBarActions = currentTopBarActions + candidate
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(candidate.iconRes),
                                                        contentDescription = null,
                                                        tint = colors.text,
                                                        modifier = Modifier.size(13.dp),
                                                    )
                                                    Text(
                                                        text = stringResource(candidate.titleRes),
                                                        color = colors.text,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_plus),
                                                        contentDescription = null,
                                                        tint = colors.accent,
                                                        modifier = Modifier.size(11.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 3. Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReButton(
                        text = stringResource(R.string.common_cancel),
                        primary = false,
                        onClick = onDismiss,
                    )
                    Spacer(Modifier.width(10.dp))
                    ReButton(
                        text = stringResource(R.string.common_confirm),
                        primary = true,
                        onClick = {
                            vm.updateFloatingUiPositions(
                                topBarPosition = topPos,
                                toolRailPosition = toolPos,
                                sliderRailPosition = sliderPos,
                                panelOrder = currentPanelOrder,
                            )
                            vm.updateFloatingUiMargin(marginDp.roundToInt())
                            vm.updateFloatingUiScale(floatingScale)
                            vm.updateFloatingUiTopBarActions(currentTopBarActions)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.DraggablePanelCapsule(
    title: String,
    icon: Int,
    pos: PanelPosition,
    layerIndex: Int = 0,
    isSelected: Boolean,
    isDragging: Boolean,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = Theme.current
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var currentGlobalBounds by remember { mutableStateOf<Rect?>(null) }

    val slotModifier = resolveSlotAlignmentModifier(pos.edge, pos.alignment, layerIndex)
    val isH = pos.edge.isHorizontal

    val elevation by animateDpAsState(
        targetValue = if (isDragging) 10.dp else if (isSelected) 5.dp else 1.5.dp,
        label = "capsuleElevation"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (isDragging) 1.06f else if (isSelected) 1.02f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "capsuleScale"
    )

    Box(
        modifier = slotModifier
            .onGloballyPositioned { coords ->
                currentGlobalBounds = coords.boundsInRoot()
            }
            .offset {
                if (isDragging) {
                    IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                } else {
                    IntOffset.Zero
                }
            }
            .zIndex(if (isDragging) 100f else if (isSelected) 20f else 10f)
            .pointerInput(pos) {
                detectDragGestures(
                    onDragStart = {
                        dragOffset = Offset.Zero
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        currentGlobalBounds?.let { b ->
                            onDrag(Offset(b.center.x + dragOffset.x, b.center.y + dragOffset.y))
                        }
                    },
                    onDragEnd = {
                        dragOffset = Offset.Zero
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragOffset = Offset.Zero
                        onDragEnd()
                    },
                )
            }
            .clickable(onClick = onSelect)
            .width(if (isH) 78.dp else 34.dp)
            .height(if (isH) 28.dp else 60.dp)
            .shadow(elevation, RoundedCornerShape(7.dp), spotColor = Color.Black.copy(alpha = 0.32f))
            .clip(RoundedCornerShape(7.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isSelected || isDragging) {
                        listOf(colors.accent, colors.accent.copy(alpha = 0.88f))
                    } else {
                        listOf(colors.panel.copy(alpha = 0.95f), colors.panelHi.copy(alpha = 0.9f))
                    }
                )
            )
            .then(
                if (isSelected || isDragging) {
                    Modifier.border(1.2.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
                } else {
                    Modifier.border(1.dp, colors.border.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
                }
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val textColor = if (isSelected || isDragging) Color.White else colors.text
        val iconColor = if (isSelected || isDragging) Color.White else colors.accent

        if (isH) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = title,
                    tint = iconColor,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = title.substringBefore(" "),
                    color = textColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = title,
                    tint = iconColor,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = title.substringBefore(" "),
                    color = textColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.resolveSlotAlignmentModifier(
    edge: PanelEdge,
    alignment: PanelAlignment,
    layerIndex: Int = 0,
): Modifier {
    val align = when (edge) {
        PanelEdge.TOP -> {
            when (alignment) {
                PanelAlignment.START -> Alignment.TopStart
                PanelAlignment.CENTER -> Alignment.TopCenter
                PanelAlignment.END -> Alignment.TopEnd
            }
        }
        PanelEdge.BOTTOM -> {
            when (alignment) {
                PanelAlignment.START -> Alignment.BottomStart
                PanelAlignment.CENTER -> Alignment.BottomCenter
                PanelAlignment.END -> Alignment.BottomEnd
            }
        }
        PanelEdge.LEFT -> {
            when (alignment) {
                PanelAlignment.START -> Alignment.TopStart
                PanelAlignment.CENTER -> Alignment.CenterStart
                PanelAlignment.END -> Alignment.BottomStart
            }
        }
        PanelEdge.RIGHT -> {
            when (alignment) {
                PanelAlignment.START -> Alignment.TopEnd
                PanelAlignment.CENTER -> Alignment.CenterEnd
                PanelAlignment.END -> Alignment.BottomEnd
            }
        }
    }

    val parallelShift = (layerIndex * (if (edge.isHorizontal) 32 else 38)).dp

    val offsetModifier = when (edge) {
        PanelEdge.TOP -> {
            when (alignment) {
                PanelAlignment.START -> Modifier.padding(start = 8.dp, top = 6.dp + parallelShift)
                PanelAlignment.CENTER -> Modifier.padding(top = 6.dp + parallelShift)
                PanelAlignment.END -> Modifier.padding(end = 8.dp, top = 6.dp + parallelShift)
            }
        }
        PanelEdge.BOTTOM -> {
            when (alignment) {
                PanelAlignment.START -> Modifier.padding(start = 8.dp, bottom = 6.dp + parallelShift)
                PanelAlignment.CENTER -> Modifier.padding(bottom = 6.dp + parallelShift)
                PanelAlignment.END -> Modifier.padding(end = 8.dp, bottom = 6.dp + parallelShift)
            }
        }
        PanelEdge.LEFT -> {
            when (alignment) {
                PanelAlignment.START -> Modifier.padding(start = 6.dp + parallelShift, top = 8.dp)
                PanelAlignment.CENTER -> Modifier.padding(start = 6.dp + parallelShift)
                PanelAlignment.END -> Modifier.padding(start = 6.dp + parallelShift, bottom = 8.dp)
            }
        }
        PanelEdge.RIGHT -> {
            when (alignment) {
                PanelAlignment.START -> Modifier.padding(end = 6.dp + parallelShift, top = 8.dp)
                PanelAlignment.CENTER -> Modifier.padding(end = 6.dp + parallelShift)
                PanelAlignment.END -> Modifier.padding(end = 6.dp + parallelShift, bottom = 8.dp)
            }
        }
    }

    return Modifier.align(align).then(offsetModifier)
}
