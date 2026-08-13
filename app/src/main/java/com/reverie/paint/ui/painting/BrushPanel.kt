package com.reverie.paint.ui.painting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.reverie.paint.R
import androidx.compose.ui.res.painterResource
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.noRippleClickable

/**
 * Brush library panel with Krita's real bundled presets (.kpp).
 * Main view = category rail + preset list. Tapping the already-selected
 * preset opens the second-level property page (size/opacity/flow).
 */
@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    // Real Krita-style categories plus user-created groups
    val categories = remember(vm.brushPresets, vm.customBrushGroups) {
        listOf("全部") +
            vm.brushPresets.map { it.group }.distinct() +
            vm.customBrushGroups.filter { g -> vm.brushPresets.none { it.group == g } }
    }
    var selectedCategory by remember { mutableStateOf("全部") }
    // new-group dialog state
    var showNewGroupDialog by remember { mutableStateOf(false) }
    // move-preset-to-group dialog state (preset name)
    var movePresetName by remember { mutableStateOf<String?>(null) }
    // reorder menu state (preset name)
    var reorderPresetName by remember { mutableStateOf<String?>(null) }
    // null = list view; non-null = second-level property page for that preset
    var detailIndex by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .noRippleClickable(onClose),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp)
                .align(Alignment.CenterStart)
                .width(320.dp)
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi.copy(alpha = opacity))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(12.dp))
                .clickable(enabled = false) {}
        ) {
            if (detailIndex != null && detailIndex!! < vm.brushPresets.size) {
                BrushPropertyPage(
                    vm = vm,
                    presetIndex = detailIndex!!,
                    onBack = { detailIndex = null },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Left categories
                        LazyColumn(
                            modifier = Modifier
                                .width(88.dp)
                                .fillMaxHeight()
                                .background(Morandi.panel.copy(alpha = opacity))
                        ) {
                            items(categories) { cat ->
                                val sel = cat == selectedCategory
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .background(if (sel) Morandi.panelHi.copy(alpha = opacity) else Color.Transparent)
                                        .clickable { selectedCategory = cat },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cat,
                                        color = if (sel) Morandi.accent else Morandi.subText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }

                        // Right preset list
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("笔刷库", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${vm.brushPresets.size} 个 Krita 预设",
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val filtered = if (selectedCategory == "全部") {
                                    vm.brushPresets
                                } else {
                                    vm.brushPresets.filter { it.group == selectedCategory }
                                }
                                items(filtered) { preset ->
                                    val isSelected = preset.index == vm.brushPresetIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Morandi.accent.copy(alpha = 0.15f) else Morandi.panel.copy(alpha = 0.5f))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.dp,
                                                color = if (isSelected) Morandi.accent else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    // Tap selects; tap the selected one again -> property page
                                                    if (isSelected) detailIndex = preset.index
                                                    else vm.selectBrushPreset(preset.index)
                                                },
                                                onLongClick = {
                                                    reorderPresetName = preset.name
                                                },
                                            )
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val bmp = rememberBytes(preset.thumbBytes)
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = preset.name,
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Morandi.panelHi)
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                preset.name,
                                                color = if (isSelected) Morandi.accent else Morandi.subText,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (isSelected) {
                                                Text("使用中 · 点按调属性", color = Morandi.accent, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom toolbar (kept from the original panel design)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Morandi.panel.copy(alpha = opacity))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Add, contentDescription = "新建组", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable { showNewGroupDialog = true })
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                    }
                }
            }
        }
    }

    // ---- dialogs -----------------------------------------------------
    if (showNewGroupDialog) {
        NewBrushGroupDialog(
            existing = categories.filter { it != "全部" },
            onDismiss = { showNewGroupDialog = false },
            onCreate = { name ->
                if (vm.createBrushGroup(name)) {
                    selectedCategory = name
                }
                showNewGroupDialog = false
            },
        )
    }
    if (reorderPresetName != null) {
        val rp = reorderPresetName!!
        ReorderBrushMenu(
            presetName = rp,
            onDismiss = { reorderPresetName = null },
            onUp = { vm.moveBrushUp(rp) },
            onDown = { vm.moveBrushDown(rp) },
            onMoveGroup = { movePresetName = rp },
        )
    }
    if (movePresetName != null) {
        MoveBrushGroupDialog(
            presetName = movePresetName!!,
            groups = categories.filter { it != "全部" },
            onDismiss = { movePresetName = null },
            onMove = { g ->
                vm.moveBrushToGroup(movePresetName!!, g)
                selectedCategory = g
                movePresetName = null
            },
        )
    }
}

/** Long-press menu: move up/down within the list, or move to a group. */
@Composable
private fun ReorderBrushMenu(
    presetName: String,
    onDismiss: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveGroup: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(presetName, color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                listOf("上移" to onUp, "下移" to onDown, "移动到组..." to onMoveGroup).forEach { (label, act) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDismiss()
                                act()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(label, color = Morandi.text, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Morandi.subText) }
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to create a new user brush group. */
@Composable
private fun NewBrushGroupDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建笔刷组", color = Morandi.text) },
        text = {
            Column {
                Text("输入组名称", color = Morandi.subText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Morandi.text, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (existing.contains(name.trim())) {
                    Text("组已存在", color = Color(0xFFB05552), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = name.isNotBlank() && !existing.contains(name.trim()),
                onClick = { onCreate(name.trim()) }
            ) { Text("创建", color = Morandi.accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Morandi.subText) }
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to move a preset into a group. */
@Composable
private fun MoveBrushGroupDialog(
    presetName: String,
    groups: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到组", color = Morandi.text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(presetName, color = Morandi.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(groups) { g ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onMove(g) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(g, color = Morandi.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Morandi.subText) }
        },
        containerColor = Morandi.panelHi,
    )
}

/** Second-level page: brush property sliders for the active preset. */
@Composable
private fun BrushPropertyPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
) {
    val preset = vm.brushPresets.getOrNull(presetIndex)
    var showBlendModes by remember { mutableStateOf(false) }

    val blendModeList = listOf(
        "normal" to "正常",
        "multiply" to "正片叠底",
        "screen" to "滤色",
        "overlay" to "叠加",
        "darken" to "变暗",
        "lighten" to "变亮",
        "dodge" to "颜色减淡",
        "burn" to "颜色加深",
        "hard_light" to "强光",
        "soft_light" to "柔光",
        "difference" to "差值",
        "exclusion" to "排除",
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
    ) {
        // Header: < 笔刷设置 (same style as the layer panel detail page)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = "返回",
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "笔刷设置",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                preset?.name ?: "",
                color = Morandi.subText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Blend mode row button (expands the mode list, like the layer panel)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .noRippleClickable { showBlendModes = !showBlendModes }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_layerstack),
                contentDescription = null,
                tint = Morandi.accent,
                modifier = Modifier.size(18.dp),
            )
            Text("混合模式", color = Morandi.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                blendModeList.firstOrNull { it.first == vm.brushCompositeOp }?.second ?: "正常",
                color = Morandi.subText,
                fontSize = 13.sp,
            )
            Icon(
                painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = Morandi.subText,
                modifier = Modifier.size(16.dp).rotate(if (showBlendModes) 90f else 0f),
            )
        }

        if (showBlendModes) {
            for ((opId, name) in blendModeList) {
                val sel = vm.brushCompositeOp == opId
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .noRippleClickable { vm.updateBrushCompositeOp(opId) }
                            .background(if (sel) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = 26.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        color = if (sel) Morandi.accent else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    if (sel) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        }

        // Parameter sliders (capsule style, consistent with the rest of the app)
        BrushParamSlider("大小", vm.brushSize, 1.0, 200.0) { vm.updateBrushSize(it) }
        BrushParamSlider("不透明度", vm.brushOpacity, 0.05, 1.0) { vm.updateBrushOpacity(it) }
        BrushParamSlider("流量", vm.brushFlow, 0.05, 1.0) { vm.updateBrushFlow(it) }
        BrushParamSlider("间距", vm.brushSpacing, 0.0, 1.0) { vm.updateBrushSpacing(it) }
        BrushParamSlider("角度", vm.brushAngle, 0.0, 360.0) { vm.updateBrushAngle(it) }
        BrushParamSlider("旋转", vm.brushRotation, 0.0, 360.0) { vm.updateBrushRotation(it) }
        BrushParamSlider("散布", vm.brushScatter, 0.0, 1.0) { vm.updateBrushScatter(it) }
        BrushParamSlider("渐隐", vm.brushFade, 0.0, 1.0) { vm.updateBrushFade(it) }
        BrushParamSlider("硬度", vm.brushSoftness, 0.0, 1.0) { vm.updateBrushSoftness(it) }
        BrushParamSlider("比例", vm.brushRatio, 0.0, 1.0) { vm.updateBrushRatio(it) }
        BrushParamSlider("锐度", vm.brushSharpness, 0.0, 1.0) { vm.updateBrushSharpness(it) }
    }
}

@Composable
private fun rememberBytes(bytes: ByteArray): android.graphics.Bitmap? {
    androidx.compose.runtime.remember(bytes) { }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/** Capsule parameter slider in the same style as ReSlider / the layer panel. */
@Composable
private fun BrushParamSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label, color = Morandi.text, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                        ) { change, _ ->
                            val w = size.width.toFloat()
                            if (w > 0f) {
                                onChange((change.position.x / w).coerceIn(0f, 1f) * (max - min) + min)
                                change.consume()
                            }
                        }
                    },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Morandi.accent,
                    size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
                )
            }
        }
        Text(
            text = if (max > 100) "${value.toInt()}" else "${(value * 100).toInt()}%",
            color = Morandi.text,
            fontSize = 12.sp,
            modifier = Modifier.width(42.dp),
            textAlign = TextAlign.End,
        )
    }
}
