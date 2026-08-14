package com.reverie.paint.ui.painting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import java.util.Collections

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

    val haptic = LocalHapticFeedback.current

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
                    // Header (LayerPanel style)
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

                    // Quick Actions (恢复默认 / 全部启用 / 全部禁用)
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

                    // Draggable Items List
                    val listState = rememberLazyListState()
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    var dragAccumulatedY by remember { mutableStateOf(0f) }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(orderedTools, key = { _, tool -> tool.id }) { index, tool ->
                            val isDragging = draggingIndex == index
                            val isEnabled = tool in enabledTools

                            val rowBg by animateColorAsState(
                                targetValue = when {
                                    isDragging -> Morandi.accent.copy(alpha = 0.25f)
                                    isEnabled -> Morandi.panel.copy(alpha = 0.35f)
                                    else -> Color.Transparent
                                },
                                animationSpec = tween(150),
                                label = "row_bg"
                            )

                            Row(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                            dampingRatio = 0.85f
                                        )
                                    )
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .background(rowBg)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            alpha = 0.85f
                                        }
                                    }
                                    .pointerInput(tool.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val cur = orderedTools.indexOfFirst { it.id == tool.id }
                                                if (cur >= 0) draggingIndex = cur
                                                dragAccumulatedY = 0f
                                            },
                                            onDragEnd = {
                                                draggingIndex = null
                                                dragAccumulatedY = 0f
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragAccumulatedY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val currentIdx = orderedTools.indexOfFirst { it.id == tool.id }
                                                if (currentIdx < 0) return@detectDragGesturesAfterLongPress
                                                dragAccumulatedY += dragAmount.y

                                                val rowThreshold = 44f
                                                if (dragAccumulatedY > rowThreshold && currentIdx < orderedTools.size - 1) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    java.util.Collections.swap(orderedTools, currentIdx, currentIdx + 1)
                                                    dragAccumulatedY -= rowThreshold
                                                } else if (dragAccumulatedY < -rowThreshold && currentIdx > 0) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    java.util.Collections.swap(orderedTools, currentIdx, currentIdx - 1)
                                                    dragAccumulatedY += rowThreshold
                                                }
                                            }
                                        )
                                    }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Drag handle
                                Icon(
                                    painter = painterResource(R.drawable.ic_menu),
                                    contentDescription = "拖动排序",
                                    tint = if (isDragging) Morandi.accent else Morandi.subText.copy(alpha = 0.6f),
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

                                // Switch for pinning (Clean Morandi style)
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        enabledTools = if (checked) {
                                            enabledTools + tool
                                        } else {
                                            enabledTools - tool
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Morandi.panelHi,
                                        checkedTrackColor = Morandi.accent,
                                        uncheckedThumbColor = Morandi.subText,
                                        uncheckedTrackColor = Morandi.panel
                                    )
                                )
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
