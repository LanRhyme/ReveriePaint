/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.systemHoverIcon
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt
import com.reverie.paint.ui.painting.panels.toolIcon
import com.reverie.paint.ui.painting.panels.displayName

private val rowHeight = 48.dp

@Composable
fun ToolbarCustomizeDialog(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    val initialPinned = remember(vm.pinnedTools) { vm.pinnedTools.distinct().filter { it != Tool.SYMMETRY && it != Tool.PERSPECTIVE } }
    val initialRest = remember(initialPinned) { Tool.entries.filter { it !in initialPinned && it != Tool.SYMMETRY && it != Tool.PERSPECTIVE } }
    val orderedTools = remember { mutableStateListOf(*(initialPinned + initialRest).distinctBy { it.id }.toTypedArray()) }
    var enabledTools by remember { mutableStateOf(initialPinned.toSet()) }

    var draggingToolId by remember { mutableStateOf<String?>(null) }
    var floatingItemTop by remember { mutableFloatStateOf(0f) }
    var fingerYInViewport by remember { mutableFloatStateOf(0f) }
    var dragYOffsetInItem by remember { mutableFloatStateOf(0f) }
    var snapshotBeforeDrag by remember { mutableStateOf<List<Tool>?>(null) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.roundToPx() }
    val haptic = LocalHapticFeedback.current
    val maxScrollSpeed = with(density) { 12.dp.toPx() }
    val autoScrollThreshold = with(density) { 48.dp.toPx() }

    val listState = rememberLazyListState()

    fun checkAndPerformReorder() {
        val draggedId = draggingToolId ?: return
        val fromIndex = orderedTools.indexOfFirst { it.id == draggedId }
        if (fromIndex < 0) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return

        val centerY = floatingItemTop + rowPx / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            centerY >= item.offset && centerY < item.offset + item.size
        }

        val targetIndex = when {
            targetItem != null -> targetItem.index
            centerY < visibleItems.first().offset -> visibleItems.first().index
            centerY >= visibleItems.last().let { it.offset + it.size } -> visibleItems.last().index
            else -> fromIndex
        }.coerceIn(0, orderedTools.size - 1)

        if (targetIndex != fromIndex) {
            val item = orderedTools.removeAt(fromIndex)
            orderedTools.add(targetIndex.coerceIn(0, orderedTools.size), item)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(draggingToolId) {
        if (draggingToolId == null) return@LaunchedEffect
        while (isActive && draggingToolId != null) {
            val speed = autoScrollSpeed
            if (speed != 0f) {
                val scrolled = listState.scrollBy(speed)
                if (scrolled != 0f) {
                    checkAndPerformReorder()
                }
            }
            delay(16)
        }
    }

    Dialog(
        onDismissRequest = {
            val newPinned = orderedTools.filter { it in enabledTools }
            vm.savePinnedTools(newPinned)
            onClose()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .systemHoverIcon(context)
                .clickable {
                    val newPinned = orderedTools.filter { it in enabledTools }
                    vm.savePinnedTools(newPinned)
                    onClose()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .systemHoverIcon(context)
                    .widthIn(max = 420.dp)
                    .heightIn(max = 560.dp)
                    .fillMaxWidth(0.90f)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(16.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tool_customize_title),
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .noRippleClickable {
                                    val newPinned = orderedTools.filter { it in enabledTools }
                                    vm.savePinnedTools(newPinned)
                                    onClose()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_x),
                                contentDescription = stringResource(R.string.common_close),
                                tint = Morandi.icon,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Quick Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickActionPill(stringResource(R.string.tool_customize_reset_default)) {
                            val defaultList = PaintViewModel.DEFAULT_PINNED_TOOLS.filter { it != Tool.SYMMETRY && it != Tool.PERSPECTIVE }
                            val rest = Tool.entries.filter { it !in defaultList && it != Tool.SYMMETRY && it != Tool.PERSPECTIVE }
                            orderedTools.clear()
                            orderedTools.addAll(defaultList + rest)
                            enabledTools = defaultList.toSet()
                        }
                        QuickActionPill(stringResource(R.string.tool_customize_enable_all)) {
                            enabledTools = orderedTools.toSet()
                        }
                        QuickActionPill(stringResource(R.string.tool_customize_disable_all)) {
                            enabledTools = emptySet()
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${enabledTools.size}/${orderedTools.size}",
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // List Container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                viewportHeightPx = coordinates.size.height.toFloat()
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = draggingToolId == null,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(orderedTools, key = { it.id }) { tool ->
                                val isBeingDragged = draggingToolId == tool.id
                                val isEnabled = tool in enabledTools

                                val rowBg by animateColorAsState(
                                    targetValue = when {
                                        isBeingDragged -> Morandi.accent.copy(alpha = 0.12f)
                                        isEnabled -> Morandi.panel.copy(alpha = 0.35f)
                                        else -> Color.Transparent
                                    },
                                    animationSpec = tween(150),
                                    label = "row_bg"
                                )

                                ToolRowContent(
                                    tool = tool,
                                    isEnabled = isEnabled,
                                    onToggle = { checked ->
                                        enabledTools = if (checked) enabledTools + tool else enabledTools - tool
                                    },
                                    isPlaceholder = isBeingDragged,
                                    dragHandleModifier = Modifier.pointerInput(tool.id) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tool.id }
                                                val itemTop = itemInfo?.offset?.toFloat()
                                                    ?: (orderedTools.indexOfFirst { it.id == tool.id } * rowPx).toFloat()
                                                dragYOffsetInItem = offset.y
                                                floatingItemTop = itemTop
                                                fingerYInViewport = itemTop + offset.y
                                                snapshotBeforeDrag = orderedTools.toList()
                                                draggingToolId = tool.id
                                            },
                                            onDragEnd = {
                                                draggingToolId = null
                                                snapshotBeforeDrag = null
                                                autoScrollSpeed = 0f
                                            },
                                            onDragCancel = {
                                                snapshotBeforeDrag?.let { original ->
                                                    orderedTools.clear()
                                                    orderedTools.addAll(original)
                                                }
                                                draggingToolId = null
                                                snapshotBeforeDrag = null
                                                autoScrollSpeed = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                fingerYInViewport += dragAmount.y
                                                val minY = 0f
                                                val maxY = (viewportHeightPx - rowPx).coerceAtLeast(0f)
                                                floatingItemTop = (fingerYInViewport - dragYOffsetInItem).coerceIn(minY, maxY)

                                                val topDist = autoScrollThreshold - floatingItemTop
                                                val bottomDist = if (viewportHeightPx > 0f) {
                                                    (floatingItemTop + rowPx) - (viewportHeightPx - autoScrollThreshold)
                                                } else 0f
                                                autoScrollSpeed = when {
                                                    topDist > 0 -> -((topDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                    bottomDist > 0 -> ((bottomDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                    else -> 0f
                                                }

                                                checkAndPerformReorder()
                                            }
                                        )
                                    },
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = null,
                                            placementSpec = tween(180)
                                        )
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .background(rowBg)
                                        .padding(horizontal = 14.dp)
                                )
                            }
                        }

                        // Floating overlay row following the finger directly
                        if (draggingToolId != null) {
                            val draggedTool = orderedTools.firstOrNull { it.id == draggingToolId }
                            if (draggedTool != null) {
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(0, floatingItemTop.roundToInt())
                                        }
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .graphicsLayer {
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                        }
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panelHi)
                                        .border(1.dp, Morandi.accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 14.dp)
                                ) {
                                    ToolRowContent(
                                        tool = draggedTool,
                                        isEnabled = draggedTool in enabledTools,
                                        onToggle = {},
                                        isPlaceholder = false,
                                        dragHandleModifier = Modifier,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.accent)
                                .clickable {
                                    val newPinned = orderedTools.filter { it in enabledTools }
                                    vm.savePinnedTools(newPinned)
                                    onClose()
                                }
                                .padding(horizontal = 24.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.common_done),
                                color = Morandi.onAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRowContent(
    tool: Tool,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    dragHandleModifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .graphicsLayer {
                if (isPlaceholder) alpha = 0.25f
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Drag handle (dedicated hit target for instant drag)
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = rowHeight)
                .pointerHoverIcon(PointerIcon.Hand)
                .then(dragHandleModifier)
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = stringResource(R.string.tool_customize_drag_reorder),
                tint = Morandi.subText.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }

        // Row body: clicking anywhere here toggles the tool
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .noRippleClickable { onToggle(!isEnabled) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Tool Icon
            Icon(
                painter = painterResource(toolIcon(tool)),
                contentDescription = tool.displayName,
                tint = if (isEnabled) Morandi.accent else Morandi.icon,
                modifier = Modifier.size(20.dp)
            )

            // Tool Label
            Text(
                text = tool.displayName,
                color = if (isEnabled) Morandi.text else Morandi.subText,
                fontSize = 14.sp,
                fontWeight = if (isEnabled) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }

        // Switch
        ReSwitch(
            checked = isEnabled,
            onChecked = onToggle,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
private fun QuickActionPill(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Morandi.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
