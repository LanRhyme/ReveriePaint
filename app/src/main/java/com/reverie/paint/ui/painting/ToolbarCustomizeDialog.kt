package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

@Composable
fun ToolbarCustomizeDialog(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    val initialPinned = vm.pinnedTools
    val initialRest = Tool.entries.filter { it !in initialPinned }
    val orderedTools = remember { mutableStateListOf(*(initialPinned + initialRest).toTypedArray()) }
    var enabledTools by remember { mutableStateOf(initialPinned.toSet()) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
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
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable {
                    val newPinned = orderedTools.filter { it in enabledTools }
                    vm.savePinnedTools(newPinned)
                    onClose()
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(250)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), initialScale = 0.90f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.90f)
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .heightIn(max = 600.dp)
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.80f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, RoundedCornerShape(24.dp))
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "自定义侧边工具栏",
                                        color = Morandi.text,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    // Active pill badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Morandi.accent.copy(alpha = 0.15f))
                                            .border(1.dp, Morandi.accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "已启用 ${enabledTools.size} 项",
                                            color = Morandi.accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "开关控制常驻，长按卡片或右侧手柄拖动排序",
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

                        // Quick action bar (全部开启 / 恢复默认 / 全部关闭)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel)
                                    .clickable {
                                        val defaultSet = listOf(
                                            Tool.BRUSH, Tool.ERASER, Tool.TRANSFORM,
                                            Tool.MOVE, Tool.LASSO, Tool.PICKER,
                                            Tool.FILL, Tool.SELECT_RECT, Tool.RECT
                                        ).filter { it in orderedTools }
                                        enabledTools = defaultSet.toSet()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("恢复默认", color = Morandi.subText, fontSize = 11.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel)
                                    .clickable {
                                        enabledTools = orderedTools.toSet()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("全部开启", color = Morandi.subText, fontSize = 11.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel)
                                    .clickable {
                                        enabledTools = emptySet()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("全部关闭", color = Morandi.subText, fontSize = 11.sp)
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
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(orderedTools, key = { _, tool -> tool.id }) { index, tool ->
                                val isDragging = draggingIndex == index
                                val itemScale by animateFloatAsState(
                                    targetValue = if (isDragging) 1.025f else 1f,
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                                    label = "item_scale"
                                )
                                val bgColor = if (isDragging) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.45f)
                                val isEnabled = tool in enabledTools

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .scale(itemScale)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bgColor)
                                        .border(
                                            1.dp,
                                            if (isDragging) Morandi.accent.copy(alpha = 0.6f) else Morandi.border.copy(alpha = 0.3f),
                                            RoundedCornerShape(12.dp)
                                        )
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

                                                    val threshold = 50f
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
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Tool icon box
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isEnabled) Morandi.accent.copy(alpha = 0.18f) else Morandi.panelHi)
                                            .border(1.dp, if (isEnabled) Morandi.accent.copy(alpha = 0.35f) else Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(toolIcon(tool)),
                                            contentDescription = tool.label,
                                            tint = if (isEnabled) Morandi.accent else Morandi.icon,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = tool.label,
                                        color = if (isEnabled) Morandi.text else Morandi.subText,
                                        fontSize = 14.sp,
                                        fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Switch for pinning
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
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_menu),
                                        contentDescription = "拖动排序",
                                        tint = if (isDragging) Morandi.accent else Morandi.subText.copy(alpha = 0.5f),
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
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.accent)
                                    .clickable {
                                        val newPinned = orderedTools.filter { it in enabledTools }
                                        vm.savePinnedTools(newPinned)
                                        onClose()
                                    }
                                    .padding(horizontal = 28.dp, vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "完成并保存",
                                    color = Morandi.onAccent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
