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
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
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
    LaunchedEffect(vm.currentLayerIndex, vm.layerRevision) {
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
    var dragOver by remember { mutableStateOf<Pair<Int, DropMode>?>(null) }
    var dragFingerX by remember { mutableFloatStateOf(0f) }
    var dragFingerY by remember { mutableFloatStateOf(0f) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragTargetIdx by remember { mutableIntStateOf(-1) }
    var previewDropIdx by remember { mutableIntStateOf(-1) }
    var listLeft by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    var listWidth by remember { mutableFloatStateOf(0f) }
    var listHeight by remember { mutableFloatStateOf(0f) }
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

    val activeDrag = vm.activeLayerDrag
    val isDraggingActive = draggingFrom >= 0 || activeDrag != null
    val targetSlot = when {
        draggingFrom >= 0 -> if (dragOver != null) -1 else dragTargetIdx
        activeDrag != null -> previewDropIdx
        else -> -1
    }

    LaunchedEffect(vm.activeLayerDrag) {
        if (vm.activeLayerDrag == null) {
            previewDropIdx = -1
        }
    }

    // Dynamic preview displayList: parts remaining rows to open a vacant slot
    // at the drop target seam so other layers smoothly spring into position
    val displayList = remember(displayRows, isDraggingActive, targetSlot, dragOver, activeDrag) {
        val draggedIds = activeDrag?.draggedIds ?: emptySet()
        val isMulti = draggingFrom in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
        val batchIndices = if (isMulti) vm.selectedLayerIndices else if (draggingFrom >= 0) setOf(draggingFrom) else emptySet()

        if (!isDraggingActive || dragOver != null || targetSlot < 0 || displayRows.isEmpty() || (draggedIds.isEmpty() && batchIndices.isEmpty())) {
            displayRows
        } else {
            val isDragged: (PaintViewModel.LayerUiState) -> Boolean = { it.id in draggedIds || it.index in batchIndices }
            val draggedItems = displayRows.filter(isDragged)
            val remaining = displayRows.filterNot(isDragged)
            val insertAt = targetSlot.coerceIn(0, remaining.size)
            val result = ArrayList<PaintViewModel.LayerUiState>(displayRows.size)
            result.addAll(remaining)
            result.addAll(insertAt, draggedItems)
            result
        }
    }

    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val rowPx = with(density) { rowHeight.roundToPx() }

    fun updateDragPos(fingerX: Float, fingerY: Float) {
        if (displayList.isEmpty() || draggingFrom < 0) return
        dragFingerX = fingerX
        dragFingerY = fingerY
        vm.layerDragFingerX = fingerX
        vm.layerDragFingerY = fingerY

        val isOutsidePanel = fingerX < listLeft - with(density) { 60.dp.toPx() } ||
            fingerX > (listLeft + listWidth + with(density) { 60.dp.toPx() })
        if (isOutsidePanel) {
            dragOver = null
            dragTargetIdx = -1
            return
        }

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

            // If the layer being dragged already belongs to this group, do not hover-drop into it
            val isAlreadyInsideThisGroup = fromLayer != null && fromLayer.depth > layer.depth &&
                run {
                    val pg = vm.layers.take(fromLayer.index).lastOrNull { it.depth == layer.depth && it.isGroup }
                    pg?.index == layer.index
                }
            if (isAlreadyInsideThisGroup) continue

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

        // 2. Math slot calculation (divider seam between rows of remaining)
        val remaining = displayRows.filter { it.index !in draggedSet }
        val bgVisual = remaining.indexOfFirst { it.isBackground || it.index == 0 }
        val maxSlot = if (bgVisual >= 0) bgVisual else remaining.size
        val rawSlot = ((contentY + rowPx * 0.4f) / rowPx).toInt().coerceIn(0, maxSlot)
        dragTargetIdx = rawSlot
        previewDropIdx = rawSlot
    }

    fun endDrag() {
        if (draggingFrom < 0) return
        val from = draggingFrom
        val insert = dragTargetIdx
        val over = dragOver
        val isMulti = from in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
        val batch = if (isMulti) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(from)
        val draggedSet = batch.toSet()
        val remaining = displayRows.filter { it.index !in draggedSet }

        val grabOffsetX = vm.activeLayerDrag?.grabOffsetX ?: 0f
        val grabOffsetY = vm.activeLayerDrag?.grabOffsetY ?: 0f
        val currentFingerX = when {
            dragFingerX != 0f -> dragFingerX
            vm.layerDragFingerX != 0f -> vm.layerDragFingerX
            else -> vm.activeLayerDrag?.startX ?: listLeft
        }
        val currentFingerY = when {
            dragFingerY != 0f -> dragFingerY
            vm.layerDragFingerY != 0f -> vm.layerDragFingerY
            else -> vm.activeLayerDrag?.startY ?: listTop
        }
        val settleFromOffset = Offset(
            x = currentFingerX - grabOffsetX,
            y = currentFingerY - grabOffsetY,
        )

        var groupDrop = false
        if (from > 0 && (insert >= 0 || over != null)) {
            groupDrop = over != null && over.second == DropMode.OnGroup
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
                val nonDraggedPrev = remaining.take(insert).lastOrNull()
                val nonDraggedNext = remaining.drop(insert).firstOrNull()
                val panelX = dragFingerX - listLeft
                val isIndented = (dragStartX == 0f || dragFingerX >= dragStartX - with(density) { 24.dp.toPx() }) &&
                    panelX >= with(density) { 130.dp.toPx() }

                val fromLayer = vm.layers.firstOrNull { it.index == from }
                val fromIsNested = (fromLayer?.depth ?: 0) > 0
                val parentGroup = if (fromIsNested && fromLayer != null) {
                    vm.layers.take(fromLayer.index).lastOrNull { it.depth == fromLayer.depth - 1 && it.isGroup }
                } else null

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
                        } else if (!isIndented && fromIsNested && parentGroup != null) {
                            val grpVisualIdx = remaining.indexOfFirst { it.index == parentGroup.index }
                            if (grpVisualIdx >= 0 && insert <= grpVisualIdx) {
                                vm.moveLayersRelative(batch, parentGroup.index, placeAbove = true)
                            } else {
                                vm.moveLayersRelative(batch, parentGroup.index, placeAbove = false)
                            }
                        } else if (nonDraggedPrev.isGroup && nonDraggedPrev.depth == nonDraggedNext.depth && parentGroup?.index == nonDraggedPrev.index) {
                            val grpVisualIdx = remaining.indexOfFirst { it.index == nonDraggedPrev.index }
                            if (grpVisualIdx >= 0 && insert <= grpVisualIdx) {
                                vm.moveLayersRelative(batch, nonDraggedPrev.index, placeAbove = true)
                            } else {
                                vm.moveLayersRelative(batch, nonDraggedPrev.index, placeAbove = false)
                            }
                        } else {
                            vm.moveLayersRelative(batch, nonDraggedNext.index, placeAbove = true)
                        }
                    }
                }
            }

            // Settle animation into drop slot or group folder
            val targetInfo = if (groupDrop) {
                val grpVisualIdx = displayRows.indexOfFirst { r -> r.index == over!!.first }
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == grpVisualIdx }
            } else {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == insert }
            }
            val targetY = if (targetInfo != null) {
                listTop + targetInfo.offset
            } else if (groupDrop) {
                rowBounds[over!!.first]?.first ?: listTop
            } else {
                listTop + insert * rowPx
            }
            val settleTargetX = if (listLeft > 0f) listLeft else (vm.activeLayerDrag?.startX ?: 0f)
            vm.layerDragSettleFrom = settleFromOffset
            vm.layerDragSettleTo = Offset(settleTargetX, targetY.toFloat())
            vm.isLayerDragGroupSettle = groupDrop
            vm.isLayerDragSettling = true
            previewDropIdx = if (groupDrop) -1 else insert
        } else {
            // Cancelled or dropped outside panel: spring back to origin
            val b = rowBounds[from]
            val originY = b?.first ?: (listTop + displayRows.indexOfFirst { it.index == from }.coerceAtLeast(0) * rowPx)
            val settleTargetX = if (listLeft > 0f) listLeft else (vm.activeLayerDrag?.startX ?: 0f)
            vm.layerDragSettleFrom = settleFromOffset
            vm.layerDragSettleTo = Offset(settleTargetX, originY)
            vm.isLayerDragGroupSettle = false
            vm.isLayerDragSettling = true
            previewDropIdx = -1
        }
        draggingFrom = -1
        dragTargetIdx = -1
        dragStartX = 0f
        dragStartY = 0f
        dragFingerX = 0f
        dragFingerY = 0f
        dragOver = null
    }

    LaunchedEffect(draggingFrom) {
        if (draggingFrom < 0) return@LaunchedEffect
        val scrollZone = with(density) { 48.dp.toPx() }
        val maxScrollStep = with(density) { 14.dp.toPx() }
        val minDragDistanceToScroll = with(density) { 32.dp.toPx() }
        while (isActive && draggingFrom >= 0) {
            val inHorizontalRange = dragFingerX >= listLeft - with(density) { 40.dp.toPx() } &&
                dragFingerX <= listLeft + listWidth + with(density) { 40.dp.toPx() }
            val startY = if (dragStartY != 0f) dragStartY else (vm.activeLayerDrag?.startY ?: 0f)
            val hasMovedFromStart = startY > 0f && abs(dragFingerY - startY) > minDragDistanceToScroll
            if (inHorizontalRange && hasMovedFromStart && dragFingerY > 0f) {
                val topDist = dragFingerY - listTop
                val bottomDist = (listTop + listHeight) - dragFingerY
                var scrollDelta = 0f
                if (topDist in 0f..scrollZone && listState.canScrollBackward) {
                    val ratio = 1f - (topDist / scrollZone).coerceIn(0f, 1f)
                    scrollDelta = -maxScrollStep * ratio
                } else if (bottomDist in 0f..scrollZone && listState.canScrollForward) {
                    val ratio = 1f - (bottomDist / scrollZone).coerceIn(0f, 1f)
                    scrollDelta = maxScrollStep * ratio
                }
                if (scrollDelta != 0f) {
                    listState.scrollBy(scrollDelta)
                    updateDragPos(dragFingerX, dragFingerY)
                }
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
                        vm.clearLayerSelection()
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
                        vm.clearLayerSelection()
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
                                vm.clearLayerSelection()
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
                            vm.clearLayerSelection()
                            onOpenCreateFilter()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_type_stroke), color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_shape_stroke),
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
                                vm.clearLayerSelection()
                                vm.addStrokeLayer()
                            }
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
                                vm.clearLayerSelection()
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
                    },
        ) {
            CompositionLocalProvider(
                LocalOverscrollFactory provides null,
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = draggingFrom < 0,
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Key by unique stable layer id so Compose animateItem correctly animates reordered rows
                    items(displayList, key = { it.id }) { layer ->
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
                                draggingFrom = layer.index
                                dragStartX = startX
                                dragStartY = startY

                                val isMulti = layer.index in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1
                                val batch = if (isMulti) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(layer.index)
                                val draggedLayers = vm.layers.filter { it.index in batch }
                                val draggedIds = draggedLayers.map { it.id }.toSet()

                                val b = rowBounds[layer.index]
                                val rowScreenTop = b?.first ?: (listTop + displayRows.indexOfFirst { it.index == layer.index }.coerceAtLeast(0) * rowPx)
                                val grabOffsetX = (startX - listLeft).coerceIn(0f, listWidth.coerceAtLeast(1f))
                                val grabOffsetY = (startY - rowScreenTop).coerceIn(0f, rowPx.toFloat())

                                vm.layerDragFingerX = startX
                                vm.layerDragFingerY = startY
                                vm.isLayerDragSettling = false
                                vm.isLayerDragGroupSettle = false
                                vm.layerDragSettleTo = null
                                vm.layerDragSettleFrom = null

                                vm.activeLayerDrag = PaintViewModel.LayerDragState(
                                    layer = layer,
                                    draggedIds = draggedIds,
                                    isMulti = isMulti,
                                    multiCount = batch.size,
                                    startX = startX,
                                    startY = startY,
                                    grabOffsetX = grabOffsetX,
                                    grabOffsetY = grabOffsetY,
                                    cardWidthPx = if (listWidth > 0f) listWidth else with(density) { 280.dp.toPx() },
                                    cardHeightPx = rowPx.toFloat(),
                                )

                                updateDragPos(startX, startY)
                            },
                            onDragPosition = { x, y -> updateDragPos(x, y) },
                            onDragEnd = { endDrag() },
                            dragOnGroup = dragOver?.first == layer.index && dragOver?.second == DropMode.OnGroup,
                            isDragging = layer.id in (vm.activeLayerDrag?.draggedIds ?: emptySet()) ||
                                draggingFrom == layer.index ||
                                (draggingFrom in vm.selectedLayerIndices && vm.selectedLayerIndices.size > 1 && layer.index in vm.selectedLayerIndices),
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
                                    placementSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = 500f,
                                    ),
                                    fadeInSpec = tween(150),
                                    fadeOutSpec = tween(150),
                                ),
                        )
                    }
                }
            }

            // Insertion indicator line with start dot and hierarchy indentation
            val showIndicator = draggingFrom >= 0 && dragOver == null && dragTargetIdx >= 0
            val targetInfo = if (showIndicator) listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragTargetIdx } else null
            val rawLineY = if (targetInfo != null) {
                targetInfo.offset.toFloat()
            } else if (showIndicator) {
                val lastInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragTargetIdx - 1 }
                lastInfo?.let { (it.offset + it.size).toFloat() }
            } else {
                null
            }

            val isMultiDrag = (draggingFrom in vm.selectedLayerIndices) && vm.selectedLayerIndices.size > 1
            val batch = if (isMultiDrag) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(draggingFrom)
            val draggedSet = batch.toSet()
            val remaining = displayRows.filter { it.index !in draggedSet }

            val nonDraggedPrev = if (showIndicator) remaining.take(dragTargetIdx).lastOrNull() else null
            val nonDraggedNext = if (showIndicator) remaining.drop(dragTargetIdx).firstOrNull() else null
            val panelX = dragFingerX - listLeft
            val isIndented = (dragStartX == 0f || dragFingerX >= dragStartX - with(density) { 24.dp.toPx() }) &&
                panelX >= with(density) { 130.dp.toPx() }

            val fromLayer = vm.layers.firstOrNull { it.index == draggingFrom }
            val fromIsNested = (fromLayer?.depth ?: 0) > 0
            val parentGroup = if (fromIsNested && fromLayer != null) {
                vm.layers.take(fromLayer.index).lastOrNull { it.depth == fromLayer.depth - 1 && it.isGroup }
            } else null

            val targetDepth = when {
                nonDraggedPrev == null -> nonDraggedNext?.depth ?: 0
                nonDraggedNext == null -> nonDraggedPrev.depth
                nonDraggedPrev.depth > nonDraggedNext.depth -> {
                    if (isIndented) nonDraggedPrev.depth else nonDraggedNext.depth
                }
                nonDraggedPrev.isGroup && (nonDraggedPrev.name !in collapsedGroupNames) && nonDraggedPrev.depth < nonDraggedNext.depth -> {
                    if (isIndented) nonDraggedNext.depth else nonDraggedPrev.depth
                }
                !isIndented && fromIsNested -> 0
                nonDraggedPrev.isGroup && nonDraggedPrev.depth == nonDraggedNext.depth && parentGroup?.index == nonDraggedPrev.index -> 0
                else -> nonDraggedNext?.depth ?: 0
            }

            var lastValidLineY by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(rawLineY) {
                if (rawLineY != null) {
                    lastValidLineY = rawLineY
                }
            }

            var lastHapticSlot by remember { mutableIntStateOf(-1) }
            LaunchedEffect(dragTargetIdx, dragOver) {
                if (draggingFrom >= 0 && dragOver == null && dragTargetIdx >= 0 && dragTargetIdx != lastHapticSlot) {
                    lastHapticSlot = dragTargetIdx
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                } else if (dragOver != null && lastHapticSlot != -999) {
                    lastHapticSlot = -999
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }

            val animatedLineY by animateFloatAsState(
                targetValue = rawLineY ?: lastValidLineY,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = 650f,
                ),
                label = "animatedLineY",
            )

            val animatedDepth by animateFloatAsState(
                targetValue = targetDepth.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = 550f,
                ),
                label = "animatedDepth",
            )

            val lineAlpha by animateFloatAsState(
                targetValue = if (showIndicator && rawLineY != null) 1f else 0f,
                animationSpec = tween(120),
                label = "lineAlpha",
            )

            if (lineAlpha > 0.01f) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(5f)
                ) {
                    val y = animatedLineY.coerceIn(0f, size.height)
                    val dotRadius = 4.5.dp.toPx()
                    val haloRadius = 7.5.dp.toPx()
                    val lineStroke = 2.5.dp.toPx()
                    val startX = (16 + animatedDepth * 20).dp.toPx()
                    val endX = size.width - 16.dp.toPx()

                    // 1. Soft glowing outer beam
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Morandi.accent.copy(alpha = lineAlpha * 0.35f),
                                Morandi.accent.copy(alpha = lineAlpha * 0.15f),
                                Color.Transparent,
                            ),
                            startX = startX,
                            endX = endX,
                        ),
                        start = Offset(startX, y),
                        end = Offset(endX, y),
                        strokeWidth = lineStroke * 2.2f,
                        cap = StrokeCap.Round,
                    )

                    // 2. Crisp foreground beam
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Morandi.accent.copy(alpha = lineAlpha),
                                Morandi.accent.copy(alpha = lineAlpha * 0.85f),
                                Morandi.accent.copy(alpha = lineAlpha * 0.45f),
                            ),
                            startX = startX,
                            endX = endX,
                        ),
                        start = Offset(startX + dotRadius, y),
                        end = Offset(endX, y),
                        strokeWidth = lineStroke,
                        cap = StrokeCap.Round,
                    )

                    // 3. Glowing dot outer halo
                    drawCircle(
                        color = Morandi.accent.copy(alpha = lineAlpha * 0.35f),
                        radius = haloRadius,
                        center = Offset(startX, y),
                    )

                    // 4. Glowing dot inner core
                    drawCircle(
                        color = Morandi.accent.copy(alpha = lineAlpha),
                        radius = dotRadius,
                        center = Offset(startX, y),
                    )
                }
            }
        }
    }
}
