/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val drawerWidth = 132.dp

@Composable
internal fun LayerListView(
    vm: PaintViewModel,
    onOpenDetail: (Int) -> Unit,
    onOpenFilters: (Int) -> Unit,
    onOpenCreateFilter: () -> Unit = {},
) {
    val rowHeight = vm.layerRowHeightDp.dp
    // Local selection (synchronous, not the async JNI currentLayerIndex):
    // the async C++ sync would lag a fast double tap and block opening detail.
    var selectedIndex by remember { mutableStateOf(vm.currentLayerIndex) }
    LaunchedEffect(vm.currentLayerIndex) {
        if (vm.currentLayerIndex in vm.layers.indices) {
            selectedIndex = vm.currentLayerIndex
        }
    }
    // Only one row may have its swipe drawer open; swiping another row
    // closes this one (revealedIndex is the open row's layer index)
    var revealedIndex by remember { mutableStateOf<Int?>(null) }
    var collapsedGroupNames by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    var draggingFrom by remember { mutableIntStateOf(-1) }
    var activeDragLayer by remember { mutableStateOf<PaintViewModel.LayerUiState?>(null) }
    var dragOver by remember { mutableStateOf<Pair<Int, DropMode>?>(null) }
    var dragFingerX by remember { mutableFloatStateOf(0f) }
    var dragFingerY by remember { mutableFloatStateOf(0f) }
    var dragTargetIdx by remember { mutableIntStateOf(-1) }
    var listLeft by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    var listWidth by remember { mutableFloatStateOf(0f) }
    var listHeight by remember { mutableFloatStateOf(0f) }
    var settleTo by remember { mutableStateOf<Float?>(null) }
    var settleFrom by remember { mutableStateOf<Float?>(null) }
    var settling by remember { mutableStateOf(false) }
    val settleAnim = remember { Animatable(0f) }
    val rowBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    // Display order, top-first, keeping group blocks intact.
    // m_layers is a flat bottom-to-top tree walk, so a plain reverse breaks
    // group children (they would render above their group row). We rebuild a
    // display list recursively: siblings are reversed, group rows keep their
    // whole subtree below them (nested groups included).
    val displayRows =
        remember(vm.layers, collapsedGroupNames) {
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
                    if (c.isGroup && c.name !in collapsedGroupNames) {
                        val e = (j + 1 until hi).firstOrNull { vm.layers[it].depth <= c.depth } ?: hi
                        collectBlock(j + 1, e, c.depth, out)
                    }
                }
            }
            // C++ walk(root, 0) gives root children depth=0, so top-level
            // siblings match parentDepth -1
            val res = buildList { collectBlock(0, n, -1, this) }
            if (res.isEmpty() && n > 0) vm.layers.reversed() else res
        }

    val displayList = displayRows

    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val rowPx = with(density) { rowHeight.roundToPx() }

    fun updateDragPos(fingerX: Float, fingerY: Float) {
        if (displayList.isEmpty() || draggingFrom < 0) return
        dragFingerX = fingerX
        dragFingerY = fingerY

        val scrollOffset = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
        val contentY = (fingerY - listTop) + scrollOffset

        val isMulti = draggingFrom in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
        val draggedSet = if (isMulti) vm.selectedLayerIndices else setOf(draggingFrom)

        // 1. Group hover detection
        var over: Pair<Int, DropMode>? = null
        for (layer in vm.layers) {
            if (!layer.isGroup || layer.index in draggedSet) continue
            val fromLayer = vm.layers.firstOrNull { it.index == draggingFrom }
            if (fromLayer?.isGroup == true && layer.depth > fromLayer.depth) continue

            // Priority A: direct screen bounding box from onGloballyPositioned
            val b = rowBounds[layer.index]
            if (b != null && fingerY >= b.first && fingerY <= b.second) {
                val h = (b.second - b.first).coerceAtLeast(1f)
                val relY = (fingerY - b.first) / h
                if (relY in 0.18f..0.82f) {
                    over = layer.index to DropMode.OnGroup
                    break
                }
            } else {
                // Priority B: mathematical contentY range in displayRows
                val groupVisualIdx = displayRows.indexOfFirst { it.index == layer.index }
                if (groupVisualIdx >= 0) {
                    val gTop = groupVisualIdx * rowPx
                    val gBottom = gTop + rowPx
                    if (contentY >= gTop && contentY <= gBottom) {
                        val relY = (contentY - gTop) / rowPx.toFloat()
                        if (relY in 0.18f..0.82f) {
                            over = layer.index to DropMode.OnGroup
                            break
                        }
                    }
                }
            }
        }
        dragOver = over

        if (over != null) {
            val groupVisualIdx = displayRows.indexOfFirst { it.index == over.first }
            if (groupVisualIdx >= 0) {
                dragTargetIdx = groupVisualIdx
            }
            return
        }

        // 2. Math slot calculation (divider seam between rows)
        val rawSlot = ((contentY + rowPx * 0.5f) / rowPx).toInt()
        val bgVisual = displayRows.indexOfFirst { it.isBackground || it.index == 0 }
        val maxSlot = if (bgVisual >= 0) bgVisual else (displayRows.size - 1).coerceAtLeast(0)
        dragTargetIdx = rawSlot.coerceIn(0, maxSlot)
    }

    fun endDrag() {
        if (draggingFrom < 0) return
        val from = draggingFrom
        val insert = dragTargetIdx
        val over = dragOver
        val isMulti = from in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
        val batch = if (isMulti) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(from)

        if (from > 0 && (insert >= 0 || over != null)) {
            val groupDrop = over != null && over.second == DropMode.OnGroup
            if (groupDrop) {
                val groupIdx = over!!.first
                if (batch.size > 1) {
                    vm.moveLayersToGroup(batch, groupIdx)
                } else {
                    vm.moveLayerToGroup(from, groupIdx)
                }
                val groupLayer = vm.layers.firstOrNull { it.index == groupIdx }
                if (groupLayer != null && groupLayer.name in collapsedGroupNames) {
                    collapsedGroupNames = collapsedGroupNames - groupLayer.name
                }
            } else if (insert >= 0) {
                val draggedSet = batch.toSet()
                val nonDraggedPrev = displayRows.take(insert).lastOrNull { it.index !in draggedSet }
                val nonDraggedNext = displayRows.drop(insert).firstOrNull { it.index !in draggedSet }
                val indentThreshold = listLeft + with(density) { 48.dp.toPx() }
                val isIndented = dragFingerX >= indentThreshold

                when {
                    nonDraggedPrev == null && nonDraggedNext != null -> {
                        vm.moveLayersRelative(batch, nonDraggedNext.index, placeAbove = true)
                    }
                    nonDraggedNext == null && nonDraggedPrev != null -> {
                        vm.moveLayersRelative(batch, nonDraggedPrev.index, placeAbove = false)
                    }
                    nonDraggedPrev != null && nonDraggedNext != null -> {
                        if (nonDraggedPrev.depth > nonDraggedNext.depth) {
                            if (isIndented) {
                                vm.moveLayersRelative(batch, nonDraggedPrev.index, placeAbove = false)
                            } else {
                                vm.moveLayersRelative(batch, nonDraggedNext.index, placeAbove = true)
                            }
                        } else if (nonDraggedPrev.isGroup && (nonDraggedPrev.name !in collapsedGroupNames) && nonDraggedPrev.depth < nonDraggedNext.depth) {
                            if (isIndented) {
                                vm.moveLayersRelative(batch, nonDraggedNext.index, placeAbove = true)
                            } else {
                                vm.moveLayersRelative(batch, nonDraggedPrev.index, placeAbove = true)
                            }
                        } else {
                            vm.moveLayersRelative(batch, nonDraggedNext.index, placeAbove = true)
                        }
                    }
                }
            }

            // Settle animation into drop slot
            val targetInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == insert }
            if (targetInfo != null) {
                settleFrom = dragFingerY - listTop - rowPx / 2f
                settleTo = listTop + targetInfo.offset.toFloat()
                settling = true
            } else {
                activeDragLayer = null
            }
        } else {
            activeDragLayer = null
        }
        draggingFrom = -1
        dragTargetIdx = -1
        dragFingerX = 0f
        dragFingerY = 0f
        dragOver = null
    }

    LaunchedEffect(draggingFrom, dragFingerY) {
        if (draggingFrom < 0) return@LaunchedEffect
        val scrollZone = with(density) { 48.dp.toPx() }
        val maxScrollStep = with(density) { 14.dp.toPx() }
        while (isActive && draggingFrom >= 0) {
            val topDist = dragFingerY - listTop
            val bottomDist = (listTop + listHeight) - dragFingerY
            var scrollDelta = 0f
            if (topDist in 0f..scrollZone) {
                val ratio = 1f - (topDist / scrollZone).coerceIn(0f, 1f)
                scrollDelta = -maxScrollStep * ratio
            } else if (bottomDist in 0f..scrollZone) {
                val ratio = 1f - (bottomDist / scrollZone).coerceIn(0f, 1f)
                scrollDelta = maxScrollStep * ratio
            }
            if (scrollDelta != 0f) {
                listState.scrollBy(scrollDelta)
                updateDragPos(dragFingerX, dragFingerY)
            }
            delay(16L)
        }
    }

    var showNewLayerMenu by remember { mutableStateOf(false) }
    var lastLayerOpTime by remember { mutableLongStateOf(0L) }

    Column(modifier = Modifier.fillMaxWidth()) {
        val haptic = LocalHapticFeedback.current
        val selLayer = vm.layers.getOrNull(selectedIndex)
        val isBg = selLayer?.isBackground ?: true
        val isFilter = selLayer?.nodeType == 3 || selLayer?.name?.contains("滤镜") == true || selLayer?.name?.contains("Filter", ignoreCase = true) == true

        // Top actions: + new paint layer | folder group | more layers (menu) | lock layer | lock alpha | clip mask | merge down
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TopIcon(
                resId = R.drawable.ic_plus,
                desc = stringResource(R.string.layer_add_paint_layer),
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastLayerOpTime > 350L) {
                        lastLayerOpTime = now
                        vm.addLayer()
                    }
                },
            )
            TopIcon(
                resId = R.drawable.ic_folder,
                desc = stringResource(R.string.layer_add_group),
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastLayerOpTime > 350L) {
                        lastLayerOpTime = now
                        vm.addGroupLayer()
                    }
                },
            )
            Box {
                TopIcon(
                    resId = R.drawable.ic_layers,
                    desc = stringResource(R.string.layer_more_types),
                    active = showNewLayerMenu,
                    onClick = { showNewLayerMenu = true },
                )
                DropdownMenu(
                    expanded = showNewLayerMenu,
                    onDismissRequest = { showNewLayerMenu = false },
                    modifier = Modifier.background(Morandi.panel).glassBorder(RoundedCornerShape(8.dp)),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_type_fill), color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_fill),
                                null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            showNewLayerMenu = false
                            val now = System.currentTimeMillis()
                            if (now - lastLayerOpTime > 350L) {
                                lastLayerOpTime = now
                                vm.addFillLayer()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_type_filter), color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_image_adjust),
                                null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            showNewLayerMenu = false
                            onOpenCreateFilter()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_stamp_visible), color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_layers),
                                null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            showNewLayerMenu = false
                            val now = System.currentTimeMillis()
                            if (now - lastLayerOpTime > 350L) {
                                lastLayerOpTime = now
                                vm.stampVisibleLayers()
                            }
                        },
                    )
                }
            }

            TopIcon(
                resId = R.drawable.ic_merge_down,
                desc = stringResource(R.string.layer_op_merge_down),
                enabled = selectedIndex > 0 && !isBg && !isFilter,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastLayerOpTime > 350L && selectedIndex > 0 && !isBg && !isFilter) {
                        lastLayerOpTime = now
                        vm.mergeDown(selectedIndex)
                    }
                },
            )
            TopIcon(
                resId = R.drawable.ic_grid,
                desc = stringResource(R.string.layer_op_alpha_lock),
                active = selLayer?.alphaLocked == true,
                enabled = !isBg && !isFilter,
                onClick = {
                    if (selectedIndex >= 0 && !isBg && !isFilter) {
                        vm.setLayerAlphaLocked(selectedIndex, !(selLayer?.alphaLocked == true))
                    }
                },
            )
            TopIcon(
                resId = R.drawable.ic_clip,
                desc = stringResource(R.string.layer_op_clip),
                active = selLayer?.clipped == true,
                enabled = !isBg,
                onClick = {
                    if (selectedIndex >= 0 && !isBg) {
                        vm.setLayerClipped(selectedIndex, !(selLayer?.clipped == true))
                    }
                },
            )
            TopIcon(
                resId = R.drawable.ic_lock,
                desc = stringResource(R.string.layer_op_lock_layer),
                active = selLayer?.locked == true,
                enabled = !isBg,
                onClick = {
                    if (selectedIndex >= 0 && !isBg) {
                        vm.setLayerLocked(selectedIndex, !(selLayer?.locked == true))
                    }
                },
            )
        }

        Spacer(Modifier.height(4.dp))

        // Adaptive height: grows with the layer count, capped at
        // screen*3/4 minus the panel header (~56dp); scrolls beyond that
        val cfg = LocalConfiguration.current
        val maxListH = (cfg.screenHeightDp * 3 / 4 - 56).dp
        val targetListH = (rowHeight * vm.layers.size.toFloat()).coerceAtMost(maxListH)
        val listH by animateDpAsState(targetListH, tween(200), label = "listH")
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(listH)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        listLeft = b.left
                        listTop = b.top
                        listWidth = b.width
                        listHeight = b.height
                    }
                    .pointerInput(displayList) {
                        awaitPointerEventScope {
                            var pinchStartDist = 0f
                            var pinchRow1 = -1
                            var pinchRow2 = -1
                            var pinchTriggered = false
                            var lastMergeTime = 0L

                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val pressedChanges = event.changes.filter { it.pressed }
                                val now = System.currentTimeMillis()

                                if (pressedChanges.size == 2) {
                                    val p1 = pressedChanges[0]
                                    val p2 = pressedChanges[1]
                                    val dist = kotlin.math.abs(p1.position.y - p2.position.y)

                                    if (pinchStartDist == 0f) {
                                        if (displayList.isEmpty()) continue
                                        pinchStartDist = dist
                                        val maxIdx = (displayList.size - 1).coerceAtLeast(0)
                                        pinchRow1 = ((p1.position.y) / rowPx).toInt().coerceIn(0, maxIdx)
                                        pinchRow2 = ((p2.position.y) / rowPx).toInt().coerceIn(0, maxIdx)
                                    } else if (!pinchTriggered && (now - lastMergeTime > 1200L) && (pinchStartDist - dist > 48.dp.toPx())) {
                                        val topVisual = minOf(pinchRow1, pinchRow2)
                                        val bottomVisual = maxOf(pinchRow1, pinchRow2)
                                        if (topVisual < bottomVisual && topVisual in displayList.indices) {
                                            val upperLayer = displayList[topVisual]
                                            val isBg = upperLayer.index == 0 || upperLayer.name == "背景" || upperLayer.name.equals("Background", ignoreCase = true)
                                            val isFilter = upperLayer.nodeType == 3 || upperLayer.name.contains("滤镜") || upperLayer.name.contains("Filter", ignoreCase = true)
                                            if (!isBg) {
                                                if (isFilter) {
                                                    pinchTriggered = true
                                                    lastMergeTime = now
                                                    p1.consume()
                                                    p2.consume()
                                                    vm.showActionToast(context.getString(R.string.layer_toast_filter_cannot_merge), com.reverie.paint.R.drawable.ic_image_adjust)
                                                } else {
                                                    pinchTriggered = true
                                                    lastMergeTime = now
                                                    p1.consume()
                                                    p2.consume()
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    vm.mergeDown(upperLayer.index)
                                                    vm.showActionToast(context.getString(R.string.layer_toast_pinch_merged), com.reverie.paint.R.drawable.ic_merge_down)
                                                }
                                            }
                                        }
                                    }
                                } else if (pressedChanges.isEmpty()) {
                                    pinchStartDist = 0f
                                    pinchTriggered = false
                                }
                            }
                        }
                    }
                    .pointerInput(draggingFrom >= 0) {
                        if (draggingFrom < 0) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val pressed = event.changes.firstOrNull { it.pressed }
                                if (pressed == null || event.changes.size > 1) {
                                    endDrag()
                                    break
                                }
                                pressed.consume()
                                val rootX = listLeft + pressed.position.x
                                val rootY = listTop + pressed.position.y
                                updateDragPos(rootX, rootY)
                            }
                        }
                    },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Key by a unique combination of depth, name, and index to ensure
                // stability while preventing duplicate key crashes even with duplicate names
                items(displayList, key = { "${it.depth}:${it.name}:${it.index}" }) { layer ->
                    LayerRow(
                        vm = vm,
                        layer = layer,
                        selected = layer.index == selectedIndex,
                        collapsed = layer.name in collapsedGroupNames,
                        onToggleCollapse = {
                            revealedIndex = null
                            collapsedGroupNames =
                                if (layer.name in collapsedGroupNames) {
                                    collapsedGroupNames - layer.name
                                } else {
                                    collapsedGroupNames + layer.name
                                }
                        },
                        revealed = layer.index == revealedIndex,
                        onReveal = { revealedIndex = layer.index },
                        onRevealClose = { revealedIndex = null },
                        onBounds = { top, bottom -> rowBounds[layer.index] = top to bottom },
                        onDragStart = { startX, startY ->
                            revealedIndex = null
                            if (layer.index !in vm.selectedLayerIndices) {
                                vm.clearLayerSelection()
                            }
                            activeDragLayer = layer
                            draggingFrom = layer.index
                            updateDragPos(startX, startY)
                        },
                        onDragPosition = { x, y -> updateDragPos(x, y) },
                        onDragEnd = { endDrag() },
                        dragOnGroup = dragOver?.first == layer.index && dragOver?.second == DropMode.OnGroup,
                        isDragging = draggingFrom == layer.index ||
                            (draggingFrom in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1 && layer.index in vm.selectedLayerIndices),
                        dragFingerY = dragFingerY,
                        multiSelected = layer.index in vm.selectedLayerIndices,
                        onSelect = {
                            revealedIndex = null
                            vm.toggleLayerSelection(layer.index)
                        },
                        onClick = {
                            revealedIndex = null
                            if (layer.index !in vm.selectedLayerIndices) {
                                // Tapping an unselected row switches the
                                // target (standard behaviour) and clears the
                                // multi-selection
                                vm.clearLayerSelection()
                            }
                            // NOTE: tapping a row that IS part of the multi-
                            // selection keeps the set - the old unconditional
                            // clear silently nuked the whole selection after
                            // the user had swiped several rows
                            if (layer.index == selectedIndex) {
                                onOpenDetail(layer.index)
                            } else {
                                // 独显模式下选中其他图层时自动取消独显 (FolioLayers 行为)
                                vm.cancelSoloIfSwitchingLayer()
                                selectedIndex = layer.index
                                vm.setCurrentLayer(layer.index)
                            }
                        },
                        modifier =
                            Modifier.animateItem(
                                // Smooth non-bouncy parting animation
                                // (the drag flicker was the thumbnail
                                // index cache going empty after moves,
                                // not this animation)
                                placementSpec = tween(220),
                                fadeInSpec = tween(120),
                                fadeOutSpec = tween(120),
                            ),
                    )
                }
            }

            // Insertion indicator line with start dot and hierarchy indentation
            if (draggingFrom >= 0 && dragOver == null && dragTargetIdx >= 0) {
                val targetInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragTargetIdx }
                val lineY = if (targetInfo != null) {
                    targetInfo.offset.toFloat()
                } else {
                    val lastInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragTargetIdx - 1 }
                    lastInfo?.let { (it.offset + it.size).toFloat() }
                }

                if (lineY != null) {
                    val isMulti = draggingFrom in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
                    val batch = if (isMulti) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(draggingFrom)
                    val draggedSet = batch.toSet()

                    val nonDraggedPrev = displayRows.take(dragTargetIdx).lastOrNull { it.index !in draggedSet }
                    val nonDraggedNext = displayRows.drop(dragTargetIdx).firstOrNull { it.index !in draggedSet }
                    val indentThreshold = listLeft + with(density) { 48.dp.toPx() }
                    val isIndented = dragFingerX >= indentThreshold

                    val targetDepth = when {
                        nonDraggedPrev == null -> nonDraggedNext?.depth ?: 0
                        nonDraggedNext == null -> nonDraggedPrev.depth
                        nonDraggedPrev.depth > nonDraggedNext.depth -> {
                            if (isIndented) nonDraggedPrev.depth else nonDraggedNext.depth
                        }
                        nonDraggedPrev.isGroup && (nonDraggedPrev.name !in collapsedGroupNames) && nonDraggedPrev.depth < nonDraggedNext.depth -> {
                            if (isIndented) nonDraggedNext.depth else nonDraggedPrev.depth
                        }
                        else -> nonDraggedNext.depth
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(5f)
                    ) {
                        val y = lineY.coerceIn(0f, size.height)
                        val dotRadius = 4.dp.toPx()
                        val lineStroke = 2.5.dp.toPx()
                        val startX = (16 + targetDepth * 20).dp.toPx()
                        val endX = size.width - 16.dp.toPx()

                        drawLine(
                            color = Morandi.accent,
                            start = Offset(startX + dotRadius, y),
                            end = Offset(endX, y),
                            strokeWidth = lineStroke,
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = Morandi.accent,
                            radius = dotRadius,
                            center = Offset(startX, y),
                        )
                    }
                }
            }

            // After release the overlay glides into the drop slot (settleTo)
            LaunchedEffect(settling, settleTo, settleFrom) {
                if (settling && settleTo != null && settleFrom != null) {
                    settleAnim.snapTo(settleFrom ?: 0f)
                    val target = (settleTo ?: 0f) - listTop - rowPx / 2f
                    settleAnim.animateTo(target, tween(160))
                    settling = false
                    settleTo = null
                    settleFrom = null
                    activeDragLayer = null
                }
            }

            // Floating drag overlay: the dragged row rendered on top of the
            // list, following the finger, so it is never occluded by other rows.
            // After release it stays for 160ms, gliding into the drop slot
            // (settleTo) so the visual landing matches the real landing.
            if (draggingFrom >= 0 || settling) {
                val dragged = activeDragLayer
                if (dragged != null) {
                    val isMultiDrag = (dragged.index in vm.selectedLayerIndices) && vm.selectedLayerIndices.size > 1
                    val multiCount = if (isMultiDrag) vm.selectedLayerIndices.size else 1
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    val y =
                                        if (settling && settleTo != null) {
                                            settleAnim.value
                                        } else {
                                            dragFingerY - listTop - rowPx / 2f
                                        }
                                    IntOffset(0, y.roundToInt())
                                }.fillMaxWidth()
                                .height(rowHeight)
                                .zIndex(10f)
                                .graphicsLayer {
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = with(density) { 16.dp.toPx() }
                                },
                    ) {
                        // Stacked cards effect for multi-selection drag
                        if (isMultiDrag) {
                            if (multiCount >= 3) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .offset(x = 6.dp, y = (-6).dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                                            .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp)),
                                )
                            }
                            if (multiCount >= 2) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .offset(x = 3.dp, y = (-3).dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi.copy(alpha = 0.75f))
                                            .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp)),
                                )
                            }
                        }

                        // Main floating card
                        LayerRowContent(
                            vm = vm,
                            layer = dragged,
                            selected = dragged.index == selectedIndex,
                            collapsed = dragged.name in collapsedGroupNames,
                            index = dragged.index,
                            onToggleCollapse = {},
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp),
                        )

                        // Multi-selection count badge
                        if (isMultiDrag) {
                            Surface(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-6).dp)
                                        .zIndex(20f),
                                shape = CircleShape,
                                color = Morandi.accent,
                                shadowElevation = 6.dp,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "$multiCount",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
