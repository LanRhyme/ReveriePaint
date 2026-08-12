package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReVerticalSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * Layer panel (画世界 Pro style)
 *
 * - top toolbar: add paint layer / add group / selection / close
 * - layer rows: swipe left reveals 复制/独显/删除; tap opens the detail panel
 * - detail panel: opacity slider, blend modes, color labels, more actions
 *   (clear / rename / copy / delete / flip / merge down / selection /
 *   alpha lock / clip mask / filters)
 */
@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    val layerRevision = vm.layerRevision
    var detailIndex by remember { mutableIntStateOf(-1) }
    var renameRequest by remember { mutableStateOf<String?>(null) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .noRippleClickable(onClose),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 44.dp, end = 8.dp)
                    .width(300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi.copy(alpha = opacity))
                    .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        ) {
            // Top actions
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayerTopButton(Icons.Default.Add, "添加图层") { vm.addLayer() }
                LayerTopButton(Icons.Default.Folder, "添加图层组") { vm.addGroupLayer() }
                Spacer(Modifier.weight(1f))
                LayerTopButton(Icons.Default.Close, "关闭") { onClose() }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            // Layer list (top layer first)
            Column(
                modifier =
                    Modifier
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                for (i in 0 until vm.layerCount) {
                    val idx = vm.layerCount - 1 - i
                    LayerRow(
                        vm = vm,
                        index = idx,
                        selected = idx == vm.currentLayerIndex,
                        onClick = {
                            vm.setCurrentLayer(idx)
                            detailIndex = if (detailIndex == idx) -1 else idx
                        },
                        onMore = { detailIndex = if (detailIndex == idx) -1 else idx },
                    )
                    if (i < vm.layerCount - 1) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp)
                                .height(1.dp)
                                .background(Morandi.border.copy(alpha = 0.5f)),
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            // Detail panel for the selected layer
            if (detailIndex in 0 until vm.layerCount) {
                LayerDetailPanel(vm = vm, index = detailIndex, onRename = { renameRequest = it })
            }
        }
    }

    renameRequest?.let { name ->
        RenameDialog(
            initial = name,
            onConfirm = { newName ->
                vm.renameLayer(detailIndex, newName)
                renameRequest = null
            },
            onDismiss = { renameRequest = null },
        )
    }
}

@Composable
private fun LayerTopButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Morandi.icon, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun LayerRow(
    vm: PaintViewModel,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val isBg = vm.layerBackground(index)
    val locked = vm.layerLocked(index)
    val alphaLocked = vm.layerAlphaLocked(index)
    val isGroup = vm.layerIsGroup(index)
    val depth = vm.layerDepth(index)
    val soloed = vm.layerSoloed(index)
    var reveal by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealPx = with(density) { 130.dp.roundToPx() }
    val dragThresholdPx = with(density) { 8.dp.roundToPx() }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
    ) {
        // Swipe-reveal action buttons (right side, 3 x 40dp)
        Row(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeAction(Morandi.accent, Icons.Default.ContentCopy, "复制") { vm.copyLayer(index) }
            SwipeAction(Morandi.accentHi, Icons.Default.Visibility, "独显") { vm.soloLayer(index) }
            SwipeAction(Color(0xFFC0504D), Icons.Default.Delete, "删除") {
                if (!isBg) vm.removeLayer()
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(if (selected) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .offset { IntOffset(if (reveal) -revealPx else 0, 0) }.pointerInput(index) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                reveal = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                reveal = reveal || dragAmount < -dragThresholdPx
                            },
                        )
                    }.clickable(onClick = onClick)
                    .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Color label stripe
            Box(
                Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .background(layerLabelColor(vm.layerColorLabel(index)), RoundedCornerShape(2.dp)),
            )
            // Group indentation
            Spacer(Modifier.width((depth * 10).dp))
            // Visibility
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .noRippleClickable { vm.toggleLayerVisible(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (vm.layerVisible(index)) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "可见性",
                    tint = if (vm.layerVisible(index)) Morandi.icon else Morandi.subText,
                    modifier = Modifier.size(16.dp),
                )
            }
            // Group icon or lock icon
            if (isGroup) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "图层组",
                    tint = Morandi.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = vm.layerName(index),
                color = if (selected) Morandi.accent else Morandi.text,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (locked || isBg) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "锁定",
                    tint = Morandi.subText,
                    modifier = Modifier.size(13.dp),
                )
            }
            if (alphaLocked && !isBg) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Morandi.accent),
                )
            }
            if (soloed) {
                Text("独", color = Morandi.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .noRippleClickable(onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Morandi.subText, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SwipeAction(
    color: Color,
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun layerLabelColor(label: Int): Color =
    when (label) {
        1 -> Color(0xFFEF5350)
        2 -> Color(0xFFFFA726)
        3 -> Color(0xFFFFEE58)
        4 -> Color(0xFF66BB6A)
        5 -> Color(0xFF42A5F5)
        6 -> Color(0xFFAB47BC)
        7 -> Color(0xFF8D6E63)
        8 -> Color(0xFF78909C)
        else -> Color.Transparent
    }

@Composable
private fun LayerDetailPanel(
    vm: PaintViewModel,
    index: Int,
    onRename: (String) -> Unit,
) {
    val opacityValue = vm.layerOpacity(index)
    val isBg = vm.layerBackground(index)
    val alphaLocked = vm.layerAlphaLocked(index)
    val clipped = vm.layerClipped(index)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Morandi.panel.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Opacity
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("不透明度", color = Morandi.subText, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("${(opacityValue * 100).roundToInt()}%", color = Morandi.text, fontSize = 12.sp)
        }
        ReVerticalSlider(
            label = "",
            fraction = opacityValue.toFloat(),
            onFraction = { vm.setLayerOpacity(index, it.toDouble()) },
            trackHeight = 56,
        )
        // Blend mode chips
        Text("混合模式", color = Morandi.subText, fontSize = 12.sp)
        val currentOp = vm.layerBlendMode(index)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((opId, name) in vm.blendModes) {
                val on = opId == currentOp
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) Morandi.accent else Morandi.panelHi)
                            .noRippleClickable { vm.setLayerBlendMode(index, opId) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(name, color = if (on) Morandi.onAccent else Morandi.text, fontSize = 11.sp)
                }
            }
        }
        // Color labels
        Text("颜色标记", color = Morandi.subText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (label in 0..8) {
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(layerLabelColor(label))
                            .border(
                                2.dp,
                                if (vm.layerColorLabel(index) == label) Morandi.accent else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            ).noRippleClickable { vm.setLayerColorLabel(index, label) },
                )
            }
        }
        // More actions
        Text("更多操作", color = Morandi.subText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MoreChip("清除") { vm.clearLayer(index) }
            MoreChip("重命名") { onRename(vm.layerName(index)) }
            MoreChip("复制") { vm.copyLayer(index) }
            MoreChip("删除") { if (!isBg) vm.removeLayer() }
            MoreChip("水平翻转") { vm.flipLayerHorizontal(index) }
            MoreChip("垂直翻转") { vm.flipLayerVertical(index) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MoreChip("向下合并") { vm.mergeDown(index) }
            MoreChip("创建选区") { vm.selectionFromLayer(index) }
            MoreChip("灰度") { vm.applyFilter(index, 0) }
            MoreChip("反色") { vm.applyFilter(index, 1) }
            MoreChip("模糊") { vm.applyFilter(index, 2) }
            MoreChip("锐化") { vm.applyFilter(index, 3) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isBg) {
                ToggleChip("锁定透明度", alphaLocked) { vm.setLayerAlphaLocked(index, !alphaLocked) }
                ToggleChip("剪切蒙版", clipped) { vm.setLayerClipped(index, !clipped) }
            }
        }
    }
}

@Composable
private fun MoreChip(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Morandi.panelHi)
                .noRippleClickable(onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, color = Morandi.text, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleChip(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) Morandi.accent.copy(alpha = 0.25f) else Morandi.panelHi)
                .noRippleClickable(onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, color = if (on) Morandi.accent else Morandi.text, fontSize = 11.sp)
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Morandi.scrim)
                .noRippleClickable(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("重命名图层", color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle =
                    androidx.compose.ui.text
                        .TextStyle(fontSize = 14.sp, color = Morandi.text),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi)
                            .noRippleClickable(onDismiss)
                            .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", color = Morandi.subText, fontSize = 13.sp)
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.accent)
                            .noRippleClickable { onConfirm(text) }
                            .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("确定", color = Morandi.onAccent, fontSize = 13.sp)
                }
            }
        }
    }
}
