package com.reverie.paint.ui.painting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

private val rowHeight = 48.dp

@Composable
fun ToolbarCustomizeDialog(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    val initialPinned = vm.pinnedTools
    val initialRest = Tool.entries.filter { it !in initialPinned }
    val orderedTools = remember { mutableStateListOf(*(initialPinned + initialRest).toTypedArray()) }
    var enabledTools by remember { mutableStateOf(initialPinned.toSet()) }

    var draggingToolId by remember { mutableStateOf<String?>(null) }
    var dragFingerY by remember { mutableStateOf(0f) }
    var dragTargetIdx by remember { mutableStateOf(-1) }
    var columnTop by remember { mutableStateOf(0f) }

    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.roundToPx() }
    val haptic = LocalHapticFeedback.current

    val displayList = remember(orderedTools, draggingToolId, dragTargetIdx) {
        if (draggingToolId == null || dragTargetIdx < 0) {
            orderedTools.toList()
        } else {
            val list = orderedTools.toMutableList()
            val from = list.indexOfFirst { it.id == draggingToolId }
            if (from >= 0) {
                val item = list.removeAt(from)
                list.add(dragTargetIdx.coerceIn(0, list.size), item)
            }
            list
        }
    }

    fun updateDragPos(fingerY: Float) {
        dragFingerY = fingerY
        val rowPos = (fingerY - columnTop) / rowPx
        val target = (rowPos + 0.4999f).toInt().coerceIn(0, orderedTools.size - 1)
        if (target != dragTargetIdx) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            dragTargetIdx = target
        }
    }

    fun endDrag() {
        val draggedId = draggingToolId
        val target = dragTargetIdx
        if (draggedId != null && target >= 0) {
            val from = orderedTools.indexOfFirst { it.id == draggedId }
            if (from >= 0 && from != target) {
                val item = orderedTools.removeAt(from)
                orderedTools.add(target.coerceIn(0, orderedTools.size), item)
            }
        }
        draggingToolId = null
        dragTargetIdx = -1
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable {
                    val newPinned = orderedTools.filter { it in enabledTools }
                    vm.savePinnedTools(newPinned)
                    onClose()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .heightIn(max = 560.dp)
                    .fillMaxWidth(0.90f)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
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
                            text = "自定义工具栏快捷键",
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
                                contentDescription = "关闭",
                                tint = Morandi.icon,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Morandi.border)
                    )

                    // Quick Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickActionPill("恢复默认") {
                            val defaultSet = listOf(
                                Tool.BRUSH, Tool.ERASER, Tool.TRANSFORM,
                                Tool.MOVE, Tool.LASSO, Tool.PICKER,
                                Tool.FILL, Tool.SELECT_RECT, Tool.RECT
                            ).filter { it in orderedTools }
                            enabledTools = defaultSet.toSet()
                        }
                        QuickActionPill("全部启用") {
                            enabledTools = orderedTools.toSet()
                        }
                        QuickActionPill("全部禁用") {
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

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Morandi.border)
                    )

                    // List Container
                    val listState = rememberLazyListState()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { columnTop = it.boundsInRoot().top }
                    ) {
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = draggingToolId == null,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(displayList, key = { it.id }) { tool ->
                                val isBeingDragged = draggingToolId == tool.id
                                val isEnabled = tool in enabledTools

                                val rowBg by animateColorAsState(
                                    targetValue = when {
                                        isBeingDragged -> Morandi.accent.copy(alpha = 0.20f)
                                        isEnabled -> Morandi.panel.copy(alpha = 0.35f)
                                        else -> Color.Transparent
                                    },
                                    animationSpec = tween(150),
                                    label = "row_bg"
                                )

                                ToolRowContent(
                                    tool = tool,
                                    isEnabled = isEnabled,
                                    onToggle = {
                                        enabledTools = if (it) enabledTools + tool else enabledTools - tool
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
                                        .graphicsLayer {
                                            if (isBeingDragged) alpha = 0.3f
                                        }
                                        .pointerInput(tool.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    draggingToolId = tool.id
                                                    val cur = orderedTools.indexOfFirst { it.id == tool.id }
                                                    dragTargetIdx = cur
                                                    dragFingerY = columnTop + (cur * rowPx) + offset.y
                                                },
                                                onDragEnd = { endDrag() },
                                                onDragCancel = { endDrag() },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    updateDragPos(dragFingerY + dragAmount.y)
                                                }
                                            )
                                        }
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
                                            val y = dragFingerY - columnTop - rowPx / 2f
                                            IntOffset(0, y.roundToInt())
                                        }
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .graphicsLayer {
                                            scaleX = 1.03f
                                            scaleY = 1.03f
                                            shadowElevation = with(density) { 14.dp.toPx() }
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
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Bar
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Morandi.border)
                    )
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
                                text = "完成",
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Drag handle icon
        Icon(
            painter = painterResource(R.drawable.ic_menu),
            contentDescription = "拖动排序",
            tint = Morandi.subText.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )

        // Tool Icon
        Icon(
            painter = painterResource(toolIcon(tool)),
            contentDescription = tool.label,
            tint = if (isEnabled) Morandi.accent else Morandi.icon,
            modifier = Modifier.size(20.dp)
        )

        // Tool Label
        Text(
            text = tool.label,
            color = if (isEnabled) Morandi.text else Morandi.subText,
            fontSize = 14.sp,
            fontWeight = if (isEnabled) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        // Switch
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Morandi.panelHi,
                checkedTrackColor = Morandi.accent,
                uncheckedThumbColor = Morandi.subText,
                uncheckedTrackColor = Morandi.panel
            )
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
