package com.reverie.paint.ui.painting

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import java.util.Collections

@Composable
fun ToolbarCustomizeDialog(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    // Initialize ordered list: pinned tools first, then the rest
    val initialPinned = vm.pinnedTools
    val initialRest = Tool.entries.filter { it !in initialPinned }
    val orderedTools = remember { mutableStateListOf(*(initialPinned + initialRest).toTypedArray()) }
    
    // Set of enabled tools
    var enabledTools by remember { mutableStateOf(initialPinned.toSet()) }

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
                    .widthIn(max = 440.dp)
                    .heightIn(max = 580.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.78f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(20.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自定义侧边工具栏",
                                color = Morandi.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "开启常驻快捷栏，长按上下拖动排序",
                                color = Morandi.subText,
                                fontSize = 12.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Morandi.panel)
                                .clickable {
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
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Morandi.border)
                    )

                    // Draggable List
                    val listState = rememberLazyListState()
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffset by remember { mutableStateOf(0f) }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        itemsIndexed(orderedTools, key = { _, tool -> tool.id }) { index, tool ->
                            val isDragging = draggingIndex == index
                            val bgColor = if (isDragging) Morandi.accent.copy(alpha = 0.15f) else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bgColor)
                                    .pointerInput(tool.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                val cur = orderedTools.indexOfFirst { it.id == tool.id }
                                                if (cur >= 0) draggingIndex = cur
                                                dragOffset = 0f
                                            },
                                            onDragEnd = { draggingIndex = null; dragOffset = 0f },
                                            onDragCancel = { draggingIndex = null; dragOffset = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val currentIdx = orderedTools.indexOfFirst { it.id == tool.id }
                                                if (currentIdx < 0) return@detectDragGesturesAfterLongPress
                                                dragOffset += dragAmount.y

                                                val threshold = 52f
                                                if (dragOffset > threshold && currentIdx < orderedTools.size - 1) {
                                                    java.util.Collections.swap(orderedTools, currentIdx, currentIdx + 1)
                                                    dragOffset = 0f
                                                } else if (dragOffset < -threshold && currentIdx > 0) {
                                                    java.util.Collections.swap(orderedTools, currentIdx, currentIdx - 1)
                                                    dragOffset = 0f
                                                }
                                            }
                                        )
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (tool in enabledTools) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(toolIcon(tool)),
                                        contentDescription = tool.label,
                                        tint = if (tool in enabledTools) Morandi.accent else Morandi.icon,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = tool.label,
                                    color = Morandi.text,
                                    fontSize = 14.sp,
                                    fontWeight = if (tool in enabledTools) FontWeight.Medium else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )

                                // Switch for pinning
                                Switch(
                                    checked = tool in enabledTools,
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
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_menu),
                                    contentDescription = "拖动排序",
                                    tint = Morandi.subText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Footer with Done button
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Morandi.border)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.accent)
                                .clickable {
                                    val newPinned = orderedTools.filter { it in enabledTools }
                                    vm.savePinnedTools(newPinned)
                                    onClose()
                                }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "完成",
                                color = Morandi.onAccent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
