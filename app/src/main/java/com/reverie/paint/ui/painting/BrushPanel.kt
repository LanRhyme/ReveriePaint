package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.ReSlider

/**
 * Brush library panel with Krita's real bundled presets (.kpp).
 * Main view = category rail + preset list. Tapping the already-selected
 * preset opens the second-level property page (size/opacity/flow).
 */

private sealed interface BrushView {
    data object List : BrushView
    data class Detail(val index: Int) : BrushView
}

@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
    hazeState: HazeState? = null,
) {
    val categories = remember(vm.brushPresets, vm.customBrushGroups) {
        listOf("全部") +
            vm.brushPresets.map { it.group }.distinct() +
            vm.customBrushGroups.filter { g -> vm.brushPresets.none { it.group == g } }
    }
    var selectedCategory by remember { mutableStateOf(vm.brushPanelSelectedCategory) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var editingCategoryName by remember { mutableStateOf<String?>(null) }
    var groupPendingDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var movePresetName by remember { mutableStateOf<String?>(null) }
    var reorderPresetName by remember { mutableStateOf<String?>(null) }
    var showStudioDialog by remember { mutableStateOf(false) }
    var studioPresetIndex by remember { mutableStateOf(vm.brushPresetIndex) }

    var view by remember {
        mutableStateOf<BrushView>(
            vm.brushPanelDetailIndex?.let { BrushView.Detail(it) } ?: BrushView.List
        )
    }

    val categoryScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.brushCategoryScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.brushCategoryScrollOffset
    )

    val presetScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.brushPresetScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.brushPresetScrollOffset
    )

    // Save state on dispose / change
    DisposableEffect(selectedCategory, view) {
        onDispose {
            vm.brushPanelSelectedCategory = selectedCategory
            vm.brushCategoryScrollIndex = categoryScrollState.firstVisibleItemIndex
            vm.brushCategoryScrollOffset = categoryScrollState.firstVisibleItemScrollOffset
            vm.brushPresetScrollIndex = presetScrollState.firstVisibleItemIndex
            vm.brushPresetScrollOffset = presetScrollState.firstVisibleItemScrollOffset
            vm.brushPanelDetailIndex = (view as? BrushView.Detail)?.index
        }
    }

    val panelShape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .noRippleClickable(onClose),
    ) {
        Box(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Default)
                .padding(start = 48.dp)
                .align(Alignment.CenterStart)
                .width(320.dp)
                .fillMaxHeight(0.75f)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f))),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f
                            )
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
                .clickable(enabled = false) {}
        ) {
            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    if (targetState is BrushView.Detail && initialState is BrushView.List) {
                        (slideInHorizontally { it } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
                    } else if (targetState is BrushView.List && initialState is BrushView.Detail) {
                        (slideInHorizontally { -it / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { it } + fadeOut(tween(120)))
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "brushPages"
            ) { v ->
                when (v) {
                    is BrushView.List -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                // Left categories
                                LazyColumn(
                                    state = categoryScrollState,
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
                                        state = presetScrollState,
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
                                                            if (isSelected) view = BrushView.Detail(preset.index)
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
                                Icon(painterResource(R.drawable.ic_plus), contentDescription = "新建组", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable { showNewGroupDialog = true })
                                Spacer(Modifier.width(16.dp))
                                Icon(painterResource(R.drawable.ic_folder), contentDescription = "Folder", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                                Spacer(Modifier.width(16.dp))
                                Icon(painterResource(R.drawable.ic_menu), contentDescription = "Menu", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                            }
                        }
                    }
                    is BrushView.Detail -> {
                        BrushPropertyPage(
                            vm = vm,
                            presetIndex = v.index,
                            onBack = { view = BrushView.List },
                            onOpenStudio = {
                                vm.brushPresetIndex = v.index
                                vm.brushStudioOpen = true
                                onClose()
                            },
                        )
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
fun BrushPropertyPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
    onOpenStudio: () -> Unit = {},
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

    val scrollState = rememberScrollState(initial = vm.brushPropertyScrollValue)
    DisposableEffect(Unit) {
        onDispose { vm.brushPropertyScrollValue = scrollState.value }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
    ) {
        // Header
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
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .noRippleClickable(onOpenStudio),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sliders),
                    contentDescription = "工作室",
                    tint = Morandi.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .noRippleClickable { vm.resetBrushParams() }
                    .padding(end = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = "重置数值",
                    tint = Morandi.subText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Advanced Brush Studio entry button
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Button(
                onClick = onOpenStudio,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Morandi.accent.copy(alpha = 0.9f)),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_sliders), contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("进入高级笔刷工作室", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))

        // Blend mode row button
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
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
        }

        // Parameter sliders
        BrushParamReSlider("大小", vm.brushSize, 1.0, 200.0) { vm.updateBrushSize(it) }
        BrushParamReSlider("不透明度", vm.brushOpacity, 0.05, 1.0) { vm.updateBrushOpacity(it) }
        BrushParamReSlider("流量", vm.brushFlow, 0.05, 1.0) { vm.updateBrushFlow(it) }
        BrushParamReSlider("间距", vm.brushSpacing, 0.0, 1.0) { vm.updateBrushSpacing(it) }
        BrushParamReSlider("角度", vm.brushAngle, 0.0, 360.0) { vm.updateBrushAngle(it) }
        BrushParamReSlider("旋转", vm.brushRotation, 0.0, 360.0) { vm.updateBrushRotation(it) }
        BrushParamReSlider("散布", vm.brushScatter, 0.0, 1.0) { vm.updateBrushScatter(it) }
        BrushParamReSlider("渐隐", vm.brushFade, 0.0, 1.0) { vm.updateBrushFade(it) }
        BrushParamReSlider("硬度", vm.brushSoftness, 0.0, 1.0) { vm.updateBrushSoftness(it) }
        BrushParamReSlider("比例", vm.brushRatio, 0.0, 1.0) { vm.updateBrushRatio(it) }
        BrushParamReSlider("锐度", vm.brushSharpness, 0.0, 1.0) { vm.updateBrushSharpness(it) }
        
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun rememberBytes(bytes: ByteArray): android.graphics.Bitmap? {
    androidx.compose.runtime.remember(bytes) { }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@Composable
private fun BrushParamReSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Morandi.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (max > 100) "${value.toInt()}" else "${(value * 100).toInt()}%",
                color = Morandi.subText,
                fontSize = 13.sp,
            )
        }
        ReSlider(
            value = fraction,
            onValue = { f ->
                onChange(f * (max - min) + min)
            },
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
}

