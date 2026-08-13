package com.reverie.paint.ui.painting

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
            // Save on dismiss
            val newPinned = orderedTools.filter { it in enabledTools }
            vm.savePinnedTools(newPinned)
            onClose()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Morandi.panelHi) // Dark background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = "关闭",
                        tint = Morandi.icon,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable {
                                val newPinned = orderedTools.filter { it in enabledTools }
                                vm.savePinnedTools(newPinned)
                                onClose()
                            }
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "自定义工具栏快捷键",
                        color = Morandi.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // Draggable List
                val listState = rememberLazyListState()
                var draggingIndex by remember { mutableStateOf<Int?>(null) }
                var dragOffset by remember { mutableStateOf(0f) }
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    itemsIndexed(orderedTools, key = { _, tool -> tool.id }) { index, tool ->
                        val isDragging = draggingIndex == index
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        val bgColor = if (isDragging) Morandi.panel.copy(alpha = 0.8f) else Color.Transparent
                           Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
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
                                            
                                            val threshold = 60f
                                            if (dragOffset > threshold && currentIdx < orderedTools.size - 1) {
                                                java.util.Collections.swap(orderedTools, currentIdx, currentIdx + 1)
                                                dragOffset = 0f
                                            } else if (dragOffset < -threshold && currentIdx > 0) {
                                                java.util.Collections.swap(orderedTools, currentIdx, currentIdx - 1)
                                                dragOffset = 0f
                                            }
                                        }
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(toolIcon(tool)),
                                contentDescription = tool.label,
                                tint = Morandi.icon,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = tool.label,
                                color = Morandi.text,
                                fontSize = 16.sp,
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
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_menu),
                                contentDescription = "拖动排序",
                                tint = Morandi.subText.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                // Footer
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("长按任意工具行或右侧图标可上下拖动排序", color = Morandi.subText, fontSize = 12.sp)
                }
            }
        }
    }
}
