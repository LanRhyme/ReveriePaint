/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.quicklayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.DragGripSpec
import com.reverie.paint.model.LayerNodeType
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.model.isFloatingWindowUnpositioned
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.components.reportUiSlot
import com.reverie.paint.ui.components.evadablePanelContent
import com.reverie.paint.ui.components.evadablePanelSurface
import com.reverie.paint.ui.painting.layers.DrawerAction
import com.reverie.paint.ui.painting.layers.LayerDragTarget
import com.reverie.paint.ui.painting.layers.LayerDropPlacement
import com.reverie.paint.ui.painting.layers.LightCheckerboard
import com.reverie.paint.ui.painting.layers.getAllDescendantIndices
import com.reverie.paint.ui.painting.layers.layerDisplayName
import com.reverie.paint.ui.painting.layers.layerLabelColor
import com.reverie.paint.ui.painting.layers.resolveDisplayDropSlot
import com.reverie.paint.ui.painting.layers.resolveLayerDropTarget
import com.reverie.paint.ui.painting.layers.shouldSwipeClearInsteadOfDelete
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 悬浮快捷图层面板 (QuickLayerWindow - 参考画世界设计)
 * 1. 紧凑型竖向条带设计 (~156dp 宽度, 清晰展示图层缩略图与图层名称)
 * 2. 顶部工具栏: [+] 新建图层 / [📁] 新建组 / 居中拖动手柄 / [✕] 关闭按钮
 * 3. 丰富的微动效与反馈:
 *    - 选中图层高亮背景平滑弹簧颜色过渡 (animateColorAsState)
 *    - 列表项轻触反馈 (pressScale) 与图层增删排版动态重排 (animateItem)
 *    - 图层眼睛显隐切换缩放淡入动效 (AnimatedContent)
 *    - 组展开折叠箭头平滑旋转 (animateFloatAsState)
 * 4. 图层行结构:
 *    - 左侧独立眼睛显隐列 (一键切换, 触感反馈)
 *    - 缩略图画板 (棋盘底格 + 实时笔画内容)
 *    - 图层名称文本 (单行截断并自适应显示名称)
 *    - 剪贴图层折角向下箭头 [⤷] 与缩进层次
 *    - 图层组折叠展开角标与文件夹图标
 *    - 底部专用 "背景" 行
 */
@Composable
fun QuickLayerWindow(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
    onClose: () -> Unit,
    onOpenFullLayerPanel: () -> Unit,
    onOpenLayerDetails: (Long) -> Unit = { onOpenFullLayerPanel() },
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }

    val isCollapsed = vm.quickLayerCollapsed
    val selectedLayer = vm.layers.firstOrNull { it.index == vm.currentLayerIndex }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    var windowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val marginPx = with(density) { 8.dp.toPx() }
    val defaultW = with(density) { if (isCollapsed) 48.dp.toPx() else 156.dp.toPx() }
    val defaultH = with(density) { if (isCollapsed) 48.dp.toPx() else 290.dp.toPx() }

    val windowShape = remember(isCollapsed) {
        if (isCollapsed) CircleShape else RoundedCornerShape(16.dp)
    }


    // 层次排序逻辑 (倒序排列保持树形完整，顶层在最上方)
    val displayLayers = remember(vm.layers, vm.collapsedGroupKeys) {
        val n = vm.layers.size
        fun collectBlock(
            lo: Int,
            hi: Int,
            parentDepth: Int,
            out: MutableList<PaintViewModel.LayerUiState>,
        ) {
            val siblings = mutableListOf<Int>()
            for (j in lo until hi) {
                if (vm.layers[j].depth == parentDepth + 1) siblings.add(j)
            }
            for (j in siblings.reversed()) {
                val c = vm.layers[j]
                out.add(c)
                if (c.isGroup) {
                    if (!vm.isGroupCollapsed(c.id)) {
                        val e = (j + 1 until hi).firstOrNull { vm.layers[it].depth <= c.depth } ?: hi
                        collectBlock(j + 1, e, c.depth, out)
                    }
                } else {
                    // 非组图层 (如拥有透明度蒙版/滤镜蒙版的图层): 若存在子级项，直接递归收集
                    val e = (j + 1 until hi).firstOrNull { vm.layers[it].depth <= c.depth } ?: hi
                    if (e > j + 1) {
                        collectBlock(j + 1, e, c.depth, out)
                    }
                }
            }
        }
        buildList { collectBlock(0, n, -1, this) }
    }

    val currentLayer = remember(vm.layers, vm.currentLayerIndex) {
        vm.layers.firstOrNull { it.index == vm.currentLayerIndex }
    }

    Box(
        modifier = modifier
            .offset {
                val unpositioned = isFloatingWindowUnpositioned(vm.quickLayerWindowX, vm.quickLayerWindowY)
                val x = if (!unpositioned) {
                    vm.quickLayerWindowX.roundToInt()
                } else {
                    val curW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                    ((screenWidthPx - curW) / 2f).coerceAtLeast(0f).roundToInt()
                }
                val y = if (!unpositioned) {
                    vm.quickLayerWindowY.roundToInt()
                } else {
                    val curH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                    ((screenHeightPx - curH) / 2f).coerceAtLeast(0f).roundToInt()
                }
                IntOffset(x, y)
            }
    ) {
        Box(
            modifier = Modifier
                .reportUiSlot(vm, UiPanelSlot.QUICK_LAYER)
                .onSizeChanged { size ->
                    windowSize = size
                    vm.quickLayerWindowWidth = size.width.toFloat()
                    vm.quickLayerWindowHeight = size.height.toFloat()
                }
                .systemHoverIcon(context)
                .clip(windowShape)
                .evadablePanelSurface(
                    vm = vm,
                    slot = UiPanelSlot.QUICK_LAYER,
                    shape = windowShape,
                    baseOpacity = opacity,
                    hazeState = hazeState,
                )
                .evadablePanelContent(vm, UiPanelSlot.QUICK_LAYER)
                .animateContentSize(Motion.enterSpring())
                .onGloballyPositioned { windowCoords = it }
        ) {
            if (isCollapsed) {
                DisposableEffect(Unit) {
                    onDispose { vm.quickLayerGrip.clear() }
                }
                // ---- 折叠微缩态：微型浮球 ----
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .onGloballyPositioned { coords ->
                            val root = windowCoords ?: return@onGloballyPositioned
                            val pos = root.localPositionOf(coords, Offset.Zero)
                            vm.quickLayerGrip.update(
                                pos.x, pos.y, coords.size.width.toFloat(), coords.size.height.toFloat(),
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    if (isFloatingWindowUnpositioned(vm.quickLayerWindowX, vm.quickLayerWindowY)) {
                                        val curW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                        val curH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                        vm.quickLayerWindowX = ((screenWidthPx - curW) / 2f).coerceAtLeast(0f)
                                        vm.quickLayerWindowY = ((screenHeightPx - curH) / 2f).coerceAtLeast(0f)
                                    }
                                },
                                onDragEnd = { vm.persistQuickLayerState() },
                                onDragCancel = { vm.persistQuickLayerState() },
                            ) { change, dragAmount ->
                                change.consume()
                                vm.quickLayerWindowX += dragAmount.x
                                vm.quickLayerWindowY += dragAmount.y
                                vm.clampQuickWindowsToSafeArea(
                                    vm.floatingHostWidthPx,
                                    vm.floatingHostHeightPx,
                                    density.density,
                                    persist = false,
                                )
                            }
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.quickLayerCollapsed = false
                            vm.persistQuickLayerState()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = currentLayer?.let { vm.thumbFor(it.index, it.name) }
                    val collapsedColor = if ((currentLayer?.colorLabel ?: 0) > 0) layerLabelColor(currentLayer!!.colorLabel) else Morandi.accent
                    if (thumb != null && !thumb.isRecycled) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = currentLayer?.name,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, collapsedColor, RoundedCornerShape(6.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(collapsedColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_layers),
                                contentDescription = null,
                                tint = collapsedColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            } else {
                DisposableEffect(Unit) {
                    onDispose { vm.quickLayerGrip.clear() }
                }
                // ---- 展开态：画世界条带图层面板 (适度加宽显示名称，带微动效) ----
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 1. 顶部操作栏 (新建图层 / 更多操作菜单 / 居中拖动手柄 / 关闭按钮 - 极简单层结构)
                    var showQuickMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DragGripSpec.QUICK_LAYER_TOP_BAR_HEIGHT_DP.dp)
                            .onGloballyPositioned { coords ->
                                val root = windowCoords ?: return@onGloballyPositioned
                                val pos = root.localPositionOf(coords, Offset.Zero)
                                vm.quickLayerGrip.update(
                                    pos.x, pos.y, coords.size.width.toFloat(), coords.size.height.toFloat(),
                                )
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        if (isFloatingWindowUnpositioned(vm.quickLayerWindowX, vm.quickLayerWindowY)) {
                                            val curW = if (windowSize.width > 0) windowSize.width.toFloat() else defaultW
                                            val curH = if (windowSize.height > 0) windowSize.height.toFloat() else defaultH
                                            vm.quickLayerWindowX = ((screenWidthPx - curW) / 2f).coerceAtLeast(0f)
                                            vm.quickLayerWindowY = ((screenHeightPx - curH) / 2f).coerceAtLeast(0f)
                                        }
                                    },
                                    onDragEnd = { vm.persistQuickLayerState() },
                                    onDragCancel = { vm.persistQuickLayerState() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    vm.quickLayerWindowX += dragAmount.x
                                    vm.quickLayerWindowY += dragAmount.y
                                    vm.clampQuickWindowsToSafeArea(
                                        vm.floatingHostWidthPx,
                                        vm.floatingHostHeightPx,
                                        density.density,
                                        persist = false,
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 左侧操作按钮组: [+] 新建图层 (短按新建, 长按呼出更多菜单) + [···] 更多操作
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // [+] 新建图层
                            val addSource = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .pressScale(addSource)
                                    .clip(RoundedCornerShape(6.dp))
                                    .combinedClickable(
                                        interactionSource = addSource,
                                        indication = null,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.addLayer()
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showQuickMenu = true
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_plus),
                                    contentDescription = stringResource(R.string.quick_layer_new),
                                    tint = Morandi.icon,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            // [···] 更多图层操作菜单
                            Box {
                                val moreSource = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .pressScale(moreSource)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(
                                            interactionSource = moreSource,
                                            indication = null,
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showQuickMenu = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_dots_vertical),
                                        contentDescription = stringResource(R.string.quick_layer_more_options),
                                        tint = Morandi.icon,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }

                                val canChangeClipping = selectedLayer != null && !selectedLayer.isBackground &&
                                    selectedLayer.index > 0 && selectedLayer.nodeType !in 10..13

                                ReDropdownMenu(
                                    expanded = showQuickMenu,
                                    onDismissRequest = { showQuickMenu = false },
                                    modifier = Modifier.width(180.dp),
                                ) {
                                    // 1. 新建组
                                    ReDropdownMenuItem(
                                        text = stringResource(R.string.layer_add_group),
                                        icon = R.drawable.ic_folder_plus,
                                        onClick = {
                                            showQuickMenu = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.addGroupLayer()
                                        },
                                    )

                                    // 分割线
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .fillMaxWidth()
                                            .height(0.5.dp)
                                            .background(Morandi.border.copy(alpha = 0.25f))
                                    )

                                    // 2. 剪贴蒙版开关
                                    val isClipped = selectedLayer?.clipped == true
                                    ReDropdownMenuItem(
                                        text = stringResource(R.string.layer_op_clip),
                                        icon = R.drawable.ic_clip,
                                        selected = isClipped,
                                        onClick = {
                                            showQuickMenu = false
                                            if (canChangeClipping) {
                                                vm.layers.firstOrNull {
                                                    it.index == vm.currentLayerIndex && it.index > 0 &&
                                                        !it.isBackground && it.nodeType !in 10..13
                                                }?.let {
                                                    vm.setLayerClipped(it.index, !it.clipped)
                                                }
                                            }
                                        },
                                    )

                                    // 3. 锁定透明度 / 继承透明度开关
                                    val isAlphaInherited = selectedLayer?.alphaInherited == true
                                    ReDropdownMenuItem(
                                        text = stringResource(R.string.layer_op_alpha_inherit),
                                        icon = R.drawable.ic_alpha_inherit,
                                        selected = isAlphaInherited,
                                        onClick = {
                                            showQuickMenu = false
                                            if (canChangeClipping) {
                                                vm.layers.firstOrNull {
                                                    it.index == vm.currentLayerIndex && it.index > 0 &&
                                                        !it.isBackground && it.nodeType !in 10..13
                                                }?.let {
                                                    vm.setLayerAlphaInherited(it.index, !it.alphaInherited)
                                                }
                                            }
                                        },
                                    )

                                    // 4. 图层详情设置
                                    if (selectedLayer != null) {
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                                .fillMaxWidth()
                                                .height(0.5.dp)
                                                .background(Morandi.border.copy(alpha = 0.25f))
                                        )

                                        ReDropdownMenuItem(
                                            text = stringResource(R.string.layer_settings_title),
                                            icon = R.drawable.ic_sliders,
                                            onClick = {
                                                showQuickMenu = false
                                                vm.layers.firstOrNull { it.index == vm.currentLayerIndex }?.let {
                                                    onOpenLayerDetails(it.id)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // 居中极简拖动微指示胶囊
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Morandi.icon.copy(alpha = 0.3f))
                        )

                        // 右侧：优雅关闭按钮
                        PanelCloseButton(onClose = onClose)
                    }

                    // 顶部分割线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.6.dp)
                            .background(Morandi.border.copy(alpha = 0.35f))
                    )

                    // 2. 纵向图层列表 (每个图层高度 46dp, 独立左侧眼睛显隐区 + 右侧预览区与名称)
                    var revealedLayerId by remember { mutableStateOf<Long?>(null) }
                    LaunchedEffect(displayLayers) {
                        if (revealedLayerId != null && displayLayers.none { it.id == revealedLayerId }) {
                            revealedLayerId = null
                        }
                    }
                    val viewConfig = LocalViewConfiguration.current
                    val swipeScope = rememberCoroutineScope()
                    val listState = rememberLazyListState()
                    val rowHeightDp = 46.dp
                    val rowHeightPx = with(density) { rowHeightDp.toPx() }
                    var listBounds by remember { mutableStateOf(Rect.Zero) }
                    val rowBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
                    var draggingLayerId by remember { mutableStateOf<Long?>(null) }
                    var dragFingerX by remember { mutableFloatStateOf(0f) }
                    var dragFingerY by remember { mutableFloatStateOf(0f) }
                    var dragStartX by remember { mutableFloatStateOf(0f) }
                    var dragGrabOffsetY by remember { mutableFloatStateOf(0f) }
                    var currentDropTarget by remember { mutableStateOf<LayerDragTarget?>(null) }
                    var previewDropIdx by remember { mutableIntStateOf(-1) }

                    fun updateDrag(fingerX: Float, fingerY: Float) {
                        dragFingerX = fingerX
                        dragFingerY = fingerY
                        val draggedId = draggingLayerId ?: return
                        val fromIdx = vm.layers.firstOrNull { it.id == draggedId }?.index ?: return
                        val draggedDescendants = getAllDescendantIndices(listOf(fromIdx), vm.layers)
                        val draggedSet = (listOf(fromIdx) + draggedDescendants).toSet()
                        val remaining = displayLayers.filter { it.index !in draggedSet }
                        val clampedX = fingerX.coerceIn(listBounds.left, listBounds.right)
                        val target = resolveLayerDropTarget(
                            fingerX = clampedX,
                            fingerY = fingerY,
                            dragStartX = dragStartX.coerceIn(listBounds.left, listBounds.right),
                            listLeft = listBounds.left,
                            listTop = listBounds.top,
                            listWidth = listBounds.width,
                            rowHeightPx = rowHeightPx,
                            remainingRows = remaining,
                            rowBounds = rowBounds,
                            allLayers = vm.layers,
                            panelRootGroupIdx = vm.panelRootGroupIdx,
                            horizontalThresholdPx = with(density) { 28.dp.toPx() },
                        )
                        currentDropTarget = target
                        previewDropIdx = if (target == null || target.placement == LayerDropPlacement.INSIDE) {
                            -1
                        } else {
                            resolveDisplayDropSlot(target, displayLayers)
                        }
                    }

                    fun endDrag() {
                        val draggedId = draggingLayerId
                        val target = currentDropTarget
                        draggingLayerId = null
                        currentDropTarget = null
                        previewDropIdx = -1
                        if (draggedId == null || target == null) return
                        val fromIdx = vm.layers.firstOrNull { it.id == draggedId }?.index ?: return
                        if (fromIdx <= 0) return

                        val fromPid = vm.panelIdForGroupRow(fromIdx)
                        if (fromPid != null) {
                            val displaySlot = resolveDisplayDropSlot(target, displayLayers)
                            val curVisualIdx = displayLayers.indexOfFirst { it.index == fromIdx }
                            vm.movePanelZBy(fromPid, up = displaySlot < curVisualIdx)
                            return
                        }

                        when (target.placement) {
                            LayerDropPlacement.INSIDE -> {
                                vm.moveLayerToGroup(fromIdx, target.anchorIndex)
                                val groupLayer = vm.layers.firstOrNull { it.index == target.anchorIndex }
                                if (groupLayer != null) {
                                    val pid = vm.panelIdForGroupRow(target.anchorIndex)
                                    if (pid != null) {
                                        if (pid in vm.collapsedPanelIds) {
                                            vm.collapsedPanelIds = vm.collapsedPanelIds - pid
                                        }
                                    } else {
                                        vm.setGroupCollapsed(groupLayer.id, collapsed = false)
                                    }
                                }
                            }
                            LayerDropPlacement.ABOVE -> {
                                vm.moveLayersRelative(listOf(fromIdx), target.anchorIndex, placeAbove = true)
                            }
                            LayerDropPlacement.BELOW -> {
                                vm.moveLayersRelative(listOf(fromIdx), target.anchorIndex, placeAbove = false)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (windowHeight - 120.dp).coerceIn(46.dp, 280.dp))
                            .onGloballyPositioned { listBounds = it.boundsInRoot() },
                    ) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = draggingLayerId == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(displayLayers, key = { it.id }) { layer ->
                            val isSelected = layer.index == vm.currentLayerIndex
                            val isPanelMask = vm.panelCropStateFor(layer.index) != null
                            val canSwipe = !layer.isBackground && !isPanelMask
                            val canDrag = canSwipe && !LayerNodeType.isMask(layer.nodeType)
                            val isDraggingThis = draggingLayerId == layer.id
                            val isDropGroupTarget = currentDropTarget?.placement == LayerDropPlacement.INSIDE &&
                                currentDropTarget?.anchorIndex == layer.index
                            val revealed = canSwipe && revealedLayerId == layer.id
                            val drawerWidth = 46.dp
                            val drawerPx = with(density) { drawerWidth.roundToPx() }
                            val revealAnim = remember(layer.id) { Animatable(0f) }
                            var liveSwipeOffset by remember(layer.id) { mutableFloatStateOf(Float.NaN) }

                            LaunchedEffect(revealed, drawerPx) {
                                val animSpec = spring<Float>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                )
                                revealAnim.animateTo(if (revealed) -drawerPx.toFloat() else 0f, animSpec)
                            }

                            val rowSource = remember { MutableInteractionSource() }
                            val labelColor = if (layer.colorLabel > 0) layerLabelColor(layer.colorLabel) else Color.Transparent

                            // 平滑动效: 选中背景色动画 (对齐主图层面板全行微染与选中不透明度)
                            val rowBgColor by animateColorAsState(
                                targetValue =
                                    when {
                                        isDropGroupTarget -> Morandi.accent.copy(alpha = 0.28f)
                                        layer.colorLabel > 0 ->
                                            if (isSelected) labelColor.copy(alpha = 0.26f) else labelColor.copy(alpha = 0.12f)
                                        isSelected ->
                                            Morandi.accent.copy(alpha = 0.22f)
                                        else ->
                                            Color.Transparent
                                    },
                                animationSpec = Motion.enterSpring(),
                                label = "rowBgColor"
                            )
                            val thumbBorderColor = when {
                                isSelected -> if (layer.colorLabel > 0) labelColor.copy(alpha = 0.6f) else Morandi.accent.copy(alpha = 0.6f)
                                layer.colorLabel > 0 -> labelColor.copy(alpha = 0.35f)
                                else -> Morandi.border
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = Motion.enterSpring(),
                                        fadeOutSpec = Motion.exitTween(),
                                        placementSpec = Motion.enterSpring()
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .clipToBounds()
                                        .onGloballyPositioned { coords ->
                                            val b = coords.boundsInRoot()
                                            rowBounds[layer.index] = b.top to b.bottom
                                        }
                                        .graphicsLayer {
                                            alpha = if (isDraggingThis) 0.3f else 1f
                                        }
                                        .then(
                                            if (!canSwipe) {
                                                Modifier
                                            } else {
                                                Modifier.pointerInput(layer.id, canSwipe, canDrag, drawerPx) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        val startX = down.position.x
                                                        val startY = down.position.y
                                                        val startOffset = revealAnim.value
                                                        val touchSlop = viewConfig.touchSlop
                                                        var lastChange = down
                                                        var isHorizontalSwipe = false
                                                        var isVerticalScroll = false
                                                        val isDrawerOpen = revealed || startOffset < -touchSlop

                                                        val earlyExit: Int? = withTimeoutOrNull(300L) {
                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                lastChange = change
                                                                if (change.changedToUpIgnoreConsumed()) {
                                                                    return@withTimeoutOrNull 0
                                                                }
                                                                val dx = change.position.x - startX
                                                                val dy = change.position.y - startY
                                                                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                                                                    isVerticalScroll = true
                                                                    return@withTimeoutOrNull 2
                                                                }
                                                                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.25f) {
                                                                    if (dx < 0 || startOffset < -1f) {
                                                                        isHorizontalSwipe = true
                                                                        if (revealedLayerId != null && revealedLayerId != layer.id) {
                                                                            revealedLayerId = null
                                                                        }
                                                                        change.consume()
                                                                        return@withTimeoutOrNull 1
                                                                    } else {
                                                                        return@withTimeoutOrNull 3
                                                                    }
                                                                }
                                                            }
                                                            null
                                                        }

                                                        if (earlyExit == null && canDrag && !isDrawerOpen && !isVerticalScroll) {
                                                            // 长按 300ms 触发拖拽排序
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            revealedLayerId = null
                                                            val itemOffsetY = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == layer.id }
                                                                ?.offset?.toFloat()
                                                                ?: ((rowBounds[layer.index]?.first ?: listBounds.top) - listBounds.top)
                                                            val rowTop = listBounds.top + itemOffsetY
                                                            val initRootX = listBounds.left + startX
                                                            val initRootY = rowTop + startY
                                                            dragStartX = initRootX
                                                            dragGrabOffsetY = startY
                                                            draggingLayerId = layer.id
                                                            updateDrag(initRootX, initRootY)

                                                            try {
                                                                while (true) {
                                                                    val event = awaitPointerEvent()
                                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                    lastChange = change
                                                                    if (change.changedToUpIgnoreConsumed()) {
                                                                        change.consume()
                                                                        break
                                                                    }
                                                                    change.consume()
                                                                    updateDrag(
                                                                        listBounds.left + change.position.x,
                                                                        rowTop + change.position.y,
                                                                    )
                                                                }
                                                            } finally {
                                                                endDrag()
                                                            }
                                                        } else if (isHorizontalSwipe) {
                                                            liveSwipeOffset = (startOffset + (lastChange.position.x - startX))
                                                                .coerceIn(-drawerPx.toFloat(), 0f)
                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                lastChange = change
                                                                if (change.changedToUpIgnoreConsumed()) break
                                                                change.consume()
                                                                val dx = change.position.x - startX
                                                                liveSwipeOffset = (startOffset + dx).coerceIn(-drawerPx.toFloat(), 0f)
                                                            }
                                                            var velocityX = 0f
                                                            val dxTotal = lastChange.position.x - startX
                                                            val dtMs = (lastChange.uptimeMillis - down.uptimeMillis).coerceAtLeast(1L)
                                                            if (dtMs < 200L) {
                                                                velocityX = dxTotal / dtMs * 1000f
                                                            }
                                                            val currentOffset = if (liveSwipeOffset.isNaN()) revealAnim.value else liveSwipeOffset
                                                            val shouldReveal = currentOffset < -drawerPx * 0.4f || velocityX < -500f
                                                            val targetOffset = if (shouldReveal) -drawerPx.toFloat() else 0f
                                                            swipeScope.launch {
                                                                if (!liveSwipeOffset.isNaN()) {
                                                                    revealAnim.snapTo(currentOffset)
                                                                    liveSwipeOffset = Float.NaN
                                                                }
                                                                revealAnim.animateTo(
                                                                    targetOffset,
                                                                    spring(
                                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                                        stiffness = Spring.StiffnessMedium,
                                                                    ),
                                                                )
                                                            }
                                                            revealedLayerId = if (shouldReveal) layer.id else null
                                                        }
                                                    }
                                                }
                                            }
                                        ),
                                ) {
                                    val xOffset = if (liveSwipeOffset.isNaN()) revealAnim.value else liveSwipeOffset
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .offset { IntOffset(xOffset.roundToInt(), 0) },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(46.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                    // 2.1 左侧独立显隐控制列 (宽 28dp, 带显隐微动效)
                                    val eyeSource = remember { MutableInteractionSource() }
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .fillMaxHeight()
                                            .pressScale(eyeSource)
                                            .clickable(
                                                interactionSource = eyeSource,
                                                indication = null,
                                            ) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                vm.toggleLayerVisible(layer.index)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AnimatedContent(
                                            targetState = layer.visible,
                                            transitionSpec = {
                                                fadeIn(tween(120)) + scaleIn(initialScale = 0.8f) togetherWith
                                                        fadeOut(tween(80))
                                            },
                                            label = "layerVisibilityAnim"
                                        ) { visible ->
                                            Icon(
                                                painter = painterResource(
                                                    if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off
                                                ),
                                                contentDescription = "Visibility",
                                                tint = if (visible) Morandi.icon else Morandi.subText.copy(alpha = 0.3f),
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    }

                                    // 显隐与内容之间的纵向微细缝
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .fillMaxHeight()
                                            .background(Morandi.border.copy(alpha = 0.15f))
                                    )

                                    // 2.2 右侧图层内容区域 (对齐主图层面板全行微染底色，轻触微缩)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(rowBgColor)
                                            .pressScale(rowSource)
                                            .clickable(
                                                interactionSource = rowSource,
                                                indication = null,
                                            ) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                if (draggingLayerId != null) return@clickable
                                                if (revealedLayerId != null) {
                                                    revealedLayerId = null
                                                    return@clickable
                                                }
                                                // Resolve the stable ID again: indices can change after delete/undo.
                                                vm.layers.firstOrNull { it.id == layer.id }?.let { current ->
                                                    if (current.index == vm.currentLayerIndex) {
                                                        onOpenLayerDetails(current.id)
                                                    } else {
                                                        vm.setCurrentLayer(current.index)
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        // 选中高亮左侧垂直小胶囊指示条 (对齐主图层面板)
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .width(2.5.dp)
                                                    .height(20.dp)
                                                    .clip(RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp))
                                                    .background(if (layer.colorLabel > 0) labelColor else Morandi.accent),
                                            )
                                        }

                                        when {
                                            // ---- 背景图层 ----
                                            layer.isBackground -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp, 26.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color.White)
                                                            .border(
                                                                0.5.dp,
                                                                thumbBorderColor,
                                                                RoundedCornerShape(4.dp),
                                                            )
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.layer_default_background),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (isSelected) Morandi.text else Morandi.subText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }

                                            // ---- 文件夹 / 组图层 (旋转动效) ----
                                            layer.isGroup || layer.nodeType == 1 -> {
                                                val isGroupCollapsed = vm.isGroupCollapsed(layer.id)
                                                val chevronRot by animateFloatAsState(
                                                    targetValue = if (isGroupCollapsed) 0f else 90f,
                                                    animationSpec = Motion.enterSpring(),
                                                    label = "chevronRotation"
                                                )
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_chevron),
                                                        contentDescription = if (isGroupCollapsed) {
                                                            stringResource(R.string.layer_expand)
                                                        } else {
                                                            stringResource(R.string.layer_collapse)
                                                        },
                                                        tint = if (isSelected) (if (layer.colorLabel > 0) labelColor else Morandi.accent) else Morandi.subText,
                                                        modifier = Modifier
                                                            .size(11.dp)
                                                            .rotate(chevronRot)
                                                            .clickable {
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                vm.toggleGroupCollapsed(layer.id)
                                                            },
                                                    )
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_folder),
                                                        contentDescription = "Group",
                                                        tint = if (isSelected) (if (layer.colorLabel > 0) labelColor else Morandi.accent) else Morandi.icon,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Text(
                                                        text = layerDisplayName(layer.name),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = Morandi.text,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }

                                            // ---- 蒙版 (透明度蒙版 / 滤镜蒙版等) ----
                                            com.reverie.paint.model.LayerNodeType.isMask(layer.nodeType) -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_crop),
                                                        contentDescription = stringResource(R.string.layer_tag_mask_prefix),
                                                        tint = if (layer.colorLabel > 0) labelColor else Morandi.accent,
                                                        modifier = Modifier.size(12.dp),
                                                    )

                                                    // 蒙版缩略图
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp, 26.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .border(
                                                                0.5.dp,
                                                                thumbBorderColor,
                                                                RoundedCornerShape(4.dp),
                                                            ),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        LightCheckerboard(Modifier.fillMaxSize())
                                                        val thumb = vm.thumbFor(layer.index, layer.name)
                                                        if (thumb != null && !thumb.isRecycled) {
                                                            Image(
                                                                bitmap = thumb.asImageBitmap(),
                                                                contentDescription = layer.name,
                                                                modifier = Modifier.fillMaxSize(),
                                                            )
                                                        } else {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxSize()
                                                                    .background(Morandi.accent.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Icon(
                                                                    painterResource(R.drawable.ic_crop),
                                                                    contentDescription = null,
                                                                    tint = Morandi.accent,
                                                                    modifier = Modifier.size(16.dp),
                                                                )
                                                            }
                                                        }
                                                    }

                                                    // 蒙版名称
                                                    Text(
                                                        text = layerDisplayName(layer.name),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = Morandi.text,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }

                                            // ---- 剪贴蒙版图层 (统一使用图层面板 ic_clip / ic_alpha_inherit 图标) ----
                                            layer.clipped || layer.alphaInherited -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(
                                                            if (layer.alphaInherited) R.drawable.ic_alpha_inherit else R.drawable.ic_clip
                                                        ),
                                                        contentDescription = stringResource(
                                                            if (layer.alphaInherited) R.string.layer_op_alpha_inherit else R.string.layer_op_clip
                                                        ),
                                                        tint = if (layer.colorLabel > 0) labelColor else Morandi.accent,
                                                        modifier = Modifier.size(12.dp),
                                                    )

                                                    // 图层缩略图
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp, 26.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .border(
                                                                0.5.dp,
                                                                thumbBorderColor,
                                                                RoundedCornerShape(4.dp),
                                                            ),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        LightCheckerboard(Modifier.fillMaxSize())
                                                        val thumb = vm.thumbFor(layer.index, layer.name)
                                                        if (thumb != null && !thumb.isRecycled) {
                                                            Image(
                                                                bitmap = thumb.asImageBitmap(),
                                                                contentDescription = layer.name,
                                                                modifier = Modifier.fillMaxSize(),
                                                            )
                                                        }
                                                    }

                                                    // 图层名称
                                                    Text(
                                                        text = layerDisplayName(layer.name),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = Morandi.text,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }

                                            // ---- 普通图层 ----
                                            else -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp, 26.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .border(
                                                                0.5.dp,
                                                                thumbBorderColor,
                                                                RoundedCornerShape(4.dp),
                                                            ),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        LightCheckerboard(Modifier.fillMaxSize())
                                                        val thumb = vm.thumbFor(layer.index, layer.name)
                                                        if (thumb != null && !thumb.isRecycled) {
                                                            Image(
                                                                bitmap = thumb.asImageBitmap(),
                                                                contentDescription = layer.name,
                                                                modifier = Modifier.fillMaxSize(),
                                                            )
                                                        }

                                                        // 锁定角标指示
                                                        if (layer.locked) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.ic_lock),
                                                                contentDescription = "Locked",
                                                                tint = Morandi.subText,
                                                                modifier = Modifier
                                                                    .size(9.dp)
                                                                    .align(Alignment.TopEnd)
                                                                    .padding(1.dp),
                                                            )
                                                        } else if (layer.alphaLocked) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.ic_grid),
                                                                contentDescription = "Alpha Locked",
                                                                tint = if (layer.colorLabel > 0) labelColor else Morandi.accent,
                                                                modifier = Modifier
                                                                    .size(9.dp)
                                                                    .align(Alignment.TopEnd)
                                                                    .padding(1.dp),
                                                            )
                                                        }
                                                    }

                                                    // 图层名称
                                                    Text(
                                                        text = layerDisplayName(layer.name),
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = Morandi.text,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                        }
                                        if (canSwipe) {
                                            val isMask = LayerNodeType.isMask(layer.nodeType)
                                            val isClearAction = shouldSwipeClearInsteadOfDelete(vm.layers, layer)
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .offset { IntOffset(drawerPx, 0) }
                                                    .width(drawerWidth)
                                                    .fillMaxHeight(),
                                            ) {
                                                DrawerAction(
                                                    modifier = Modifier.fillMaxSize(),
                                                    color = Color(0xFFB05552),
                                                    resId = if (isClearAction) R.drawable.ic_erase else R.drawable.ic_trash,
                                                    desc = stringResource(
                                                        if (isClearAction) R.string.layer_drawer_clear else R.string.common_delete,
                                                    ),
                                                ) {
                                                    val currentIdx = vm.layers.firstOrNull { it.id == layer.id }?.index
                                                    if (currentIdx != null) {
                                                        if (isMask) {
                                                            vm.removeMask(currentIdx)
                                                        } else if (isClearAction) {
                                                            vm.clearLayer(currentIdx)
                                                        } else {
                                                            val pid = vm.panelIdForGroupRow(currentIdx)
                                                            if (pid != null) {
                                                                vm.deletePanel(pid)
                                                            } else {
                                                                vm.removeLayer(currentIdx)
                                                            }
                                                        }
                                                    }
                                                    revealedLayerId = null
                                                }
                                            }
                                        }
                                    }
                                }

                                // 行底部分割线
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(Morandi.border.copy(alpha = 0.22f))
                                )
                            }
                        }
                    }

                    // 拖拽插入指示线
                    if (draggingLayerId != null && previewDropIdx >= 0) {
                        val targetY = remember(previewDropIdx, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                            val vis = listState.layoutInfo.visibleItemsInfo
                            val atSlot = vis.firstOrNull { it.index == previewDropIdx }
                            when {
                                atSlot != null -> atSlot.offset.toFloat()
                                previewDropIdx == displayLayers.size && vis.isNotEmpty() -> {
                                    val last = vis.last()
                                    (last.offset + last.size).toFloat()
                                }
                                else -> {
                                    ((previewDropIdx - listState.firstVisibleItemIndex) * rowHeightPx - listState.firstVisibleItemScrollOffset)
                                }
                            }
                        }
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val y = targetY.coerceIn(2f, size.height - 2f)
                            val startX = 28.dp.toPx()
                            val endX = size.width - 8.dp.toPx()
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Morandi.accent,
                                        Morandi.accent.copy(alpha = 0.75f),
                                        Morandi.accent.copy(alpha = 0.2f),
                                    ),
                                    startX = startX,
                                    endX = endX,
                                ),
                                start = Offset(startX, y),
                                end = Offset(endX, y),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }

                    // 拖拽悬浮行预览
                    val draggingLayer = draggingLayerId?.let { id -> displayLayers.firstOrNull { it.id == id } }
                    if (draggingLayer != null) {
                        val floatY = (dragFingerY - listBounds.top - dragGrabOffsetY)
                            .coerceIn(0f, (listBounds.height - rowHeightPx).coerceAtLeast(0f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeightDp)
                                .offset { IntOffset(0, floatY.roundToInt()) }
                                .graphicsLayer {
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                    alpha = 0.94f
                                }
                                .shadow(6.dp)
                                .background(Morandi.panelHi)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val thumb = vm.thumbFor(draggingLayer.index, draggingLayer.name)
                            if (thumb != null && !thumb.isRecycled) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = draggingLayer.name,
                                    modifier = Modifier
                                        .size(32.dp, 26.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(
                                        if (draggingLayer.isGroup) R.drawable.ic_folder else R.drawable.ic_layers
                                    ),
                                    contentDescription = null,
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = layerDisplayName(draggingLayer.name),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Morandi.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

