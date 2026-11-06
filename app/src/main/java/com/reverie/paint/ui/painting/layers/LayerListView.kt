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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
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
import androidx.compose.runtime.snapshotFlow
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
    onRenameLayer: ((Int, String) -> Unit)? = null,
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
    val collapsedGroupKeys = vm.collapsedGroupKeys
    val collapsedPanelIds = vm.collapsedPanelIds

    // 组行折叠态: 分格组行按 panelId (collapsedPanelIds), 其余组按 id 键 (vm.isGroupCollapsed(l.id))
    fun groupRowCollapsed(l: PaintViewModel.LayerUiState): Boolean {
        if (!l.isGroup) return false
        val pid = vm.panelIdForGroupRow(l.index)
        return if (pid != null) pid in collapsedPanelIds else vm.isGroupCollapsed(l.id)
    }
    val listState = rememberLazyListState()
    // 惯性滚动中，以及滚动刚停后的 300ms，行内长按不进入拖图层。
    // 以 State 下发给行: 值只在手势回调里读, 滚动起止不会连带重组可见行 (实例恒定, 行可跳过)。
    val layerScrollGuard = remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                layerScrollGuard.longValue = Long.MAX_VALUE
            } else if (wasScrolling) {
                layerScrollGuard.longValue = android.os.SystemClock.uptimeMillis() + 300L
            }
            wasScrolling = scrolling
        }
    }
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
        remember(vm.layers, collapsedGroupKeys, collapsedPanelIds, vm.panels) {
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
                    // 分格裁剪蒙版必须留下: 浮窗已没有裁剪开关, 只有这一行的眼睛能 setPanelCrop。
                    // 填充/边框和跨格容器仍由 isPanelTreeHidden 收掉。
                    if (vm.isPanelTreeHidden(j)) continue
                    val c = vm.layers[j]
                    out.add(c)
                    // 分格组与普通组统一遵循折叠状态 (groupRowCollapsed)
                    val isCollapsedGroup = c.isGroup && groupRowCollapsed(c)
                    if (!isCollapsedGroup) {
                        val e = (j + 1 until hi).firstOrNull { vm.layers[it].depth <= c.depth } ?: hi
                        if (e > j + 1) {
                            collectBlock(j + 1, e, c.depth, out)
                        }
                    }
                }
            }
            // C++ walk(root, 0) gives root children depth=0, so top-level
            // siblings match parentDepth -1
            val res = buildList { collectBlock(0, n, -1, this) }
            if (res.isEmpty() && n > 0) vm.layers.reversed() else res
        }

    // Auto-scroll to newly added layer ONLY when it is outside the visible viewport
    var prevLayerCount by remember { mutableIntStateOf(vm.layers.size) }
    LaunchedEffect(vm.layers.size) {
        if (vm.layers.size > prevLayerCount) {
            val newLayer = vm.layers.getOrNull(vm.currentLayerIndex)
            if (newLayer != null && newLayer.depth > 0) {
                var currentDepth = newLayer.depth
                var idx = vm.currentLayerIndex - 1
                val toExpand = mutableSetOf<Long>()
                while (idx >= 0 && currentDepth > 0) {
                    val candidate = vm.layers.getOrNull(idx) ?: break
                    if (candidate.depth == currentDepth - 1 && candidate.isGroup) {
                        // 分格组行默认折叠 (collapsedPanelIds), 不因新层自动展开
                        if (vm.panelIdForGroupRow(candidate.index) == null &&
                            vm.isGroupCollapsed(candidate.id)
                        ) {
                            toExpand.add(candidate.id)
                        }
                        currentDepth = candidate.depth
                    }
                    idx--
                }
                for (id in toExpand) {
                    vm.setGroupCollapsed(id, collapsed = false)
                }
            }
            val visualIdx = displayRows.indexOfFirst { it.index == vm.currentLayerIndex }
            if (visualIdx >= 0) {
                val layout = listState.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull { it.index == visualIdx }
                val isFullyVisible = item != null &&
                    item.offset >= layout.viewportStartOffset &&
                    (item.offset + item.size) <= layout.viewportEndOffset
                if (!isFullyVisible) {
                    listState.animateScrollToItem(visualIdx)
                }
            }
        }
        prevLayerCount = vm.layers.size
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

    // Keep stable displayList during drag; drop target is indicated by the floating
    // card (LayerDragOverlay) and the animated insertion indicator line.
    val displayList = displayRows

    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val rowPx = with(density) { rowHeight.roundToPx() }

    fun updateDragPos(fingerX: Float, fingerY: Float) {
        if (displayList.isEmpty() || draggingFrom < 0) return
        dragFingerX = fingerX
        dragFingerY = fingerY
        vm.layerDragFingerX = fingerX
        vm.layerDragFingerY = fingerY

        val isOutsidePanel = fingerX < listLeft - with(density) { 80.dp.toPx() } ||
            fingerX > (listLeft + listWidth + with(density) { 80.dp.toPx() })
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
            val fromPid = vm.panelIdForGroupRow(from)
            if (fromPid != null) {
                // 拖动的是分格组整行：走 movePanelZBy，在分格根组内安全重排，严禁脱离根组
                val curVisualIdx = displayRows.indexOfFirst { it.index == from }
                val isUp = if (insert >= 0) insert < curVisualIdx else false
                vm.movePanelZBy(fromPid, up = isUp)
                draggingFrom = -1
                dragTargetIdx = -1
                dragOver = null
                previewDropIdx = -1
                vm.activeLayerDrag = null
                return
            }

            groupDrop = over != null && over.second == DropMode.OnGroup
            if (groupDrop) {
                val groupIdx = over!!.first
                if (groupIdx == vm.panelRootGroupIdx) {
                    // 严禁将图层直接 drop 进分格根组
                    draggingFrom = -1
                    dragTargetIdx = -1
                    dragOver = null
                    previewDropIdx = -1
                    vm.activeLayerDrag = null
                    return
                }
                if (batch.size > 1) {
                    vm.moveLayersToGroup(batch, groupIdx)
                } else {
                    vm.moveLayerToGroup(from, groupIdx)
                }
                val groupLayer = vm.layers.firstOrNull { it.index == groupIdx }
                if (groupLayer != null) {
                    val pid = vm.panelIdForGroupRow(groupIdx)
                    if (pid != null) {
                        if (pid in vm.collapsedPanelIds) {
                            vm.collapsedPanelIds = vm.collapsedPanelIds - pid
                        }
                    } else {
                        vm.setGroupCollapsed(groupLayer.id, collapsed = false)
                    }
                }
            } else if (insert >= 0) {
                val nonDraggedPrev = remaining.take(insert).lastOrNull()
                val nonDraggedNext = remaining.drop(insert).firstOrNull()
                val isIndented = isLayerDragIndented(dragStartX, dragFingerX, density)

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
                        } else if (nonDraggedPrev.isGroup && !groupRowCollapsed(nonDraggedPrev) && nonDraggedPrev.depth < nonDraggedNext.depth) {
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
        val scrollZone = with(density) { 56.dp.toPx() }
        val maxScrollStep = with(density) { 18.dp.toPx() }
        val horizontalTolerance = with(density) { 80.dp.toPx() }
        val maxOutsideDistance = with(density) { 180.dp.toPx() }
        val touchSlop = with(density) { 8.dp.toPx() }

        while (isActive && draggingFrom >= 0) {
            val inHorizontalRange = dragFingerX >= listLeft - horizontalTolerance &&
                dragFingerX <= listLeft + listWidth + horizontalTolerance
            val startY = if (dragStartY != 0f) dragStartY else (vm.activeLayerDrag?.startY ?: 0f)
            val hasMovedFromStart = startY > 0f && (
                kotlin.math.abs(dragFingerY - startY) > touchSlop ||
                dragFingerY < listTop ||
                dragFingerY > listTop + listHeight
            )

            if (inHorizontalRange && hasMovedFromStart && dragFingerY > 0f) {
                var scrollDelta = 0f

                // 向上自动滚动 (接近或拖出列表顶部)
                if (dragFingerY <= listTop + scrollZone && dragFingerY >= listTop - maxOutsideDistance) {
                    if (listState.canScrollBackward) {
                        val ratio = if (dragFingerY <= listTop) {
                            1.25f // 拖出顶部边缘时保持全速自动向上滚动
                        } else {
                            ((listTop + scrollZone - dragFingerY) / scrollZone).coerceIn(0.15f, 1f)
                        }
                        scrollDelta = -maxScrollStep * ratio
                    }
                }
                // 向下自动滚动 (接近或拖出列表底部)
                else if (dragFingerY >= listTop + listHeight - scrollZone && dragFingerY <= listTop + listHeight + maxOutsideDistance) {
                    if (listState.canScrollForward) {
                        val ratio = if (dragFingerY >= listTop + listHeight) {
                            1.25f // 拖出底部边缘时保持全速自动向下滚动
                        } else {
                            ((dragFingerY - (listTop + listHeight - scrollZone)) / scrollZone).coerceIn(0.15f, 1f)
                        }
                        scrollDelta = maxScrollStep * ratio
                    }
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
        val isFilter = selLayer?.nodeType == 3

        // Top actions: [Creation group] | Divider | [Properties/State group]
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左侧：新建与分组操作
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                    ReDropdownMenu(
                        expanded = showNewLayerMenu,
                        onDismissRequest = { showNewLayerMenu = false },
                        modifier = Modifier.width(190.dp),
                    ) {
                        // --- 1. 特殊图层类型板块 ---
                        Text(
                            text = stringResource(R.string.layer_menu_section_layers),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_type_fill),
                            icon = R.drawable.ic_fill,
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
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_type_filter),
                            icon = R.drawable.ic_image_adjust,
                            onClick = {
                                showNewLayerMenu = false
                                vm.clearLayerSelection()
                                onOpenCreateFilter()
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_type_stroke),
                            icon = R.drawable.ic_shape_stroke,
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
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_new_3d_reference),
                            icon = R.drawable.ic_eye,
                            onClick = {
                                showNewLayerMenu = false
                                vm.clearLayerSelection()
                                vm.show3DModelPicker = true
                            },
                        )

                        // 柔和分割线
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Morandi.border.copy(alpha = 0.25f))
                        )

                        // --- 2. 蒙版板块 ---
                        Text(
                            text = stringResource(R.string.layer_menu_section_masks),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
                        )
                        val curIdx = vm.currentLayerIndex
                        val curLayer = vm.layers.getOrNull(curIdx)
                        val isBg = curLayer?.isBackground == true
                        val isFilter = curLayer?.nodeType == 3

                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_op_add_mask),
                            icon = R.drawable.ic_crop,
                            onClick = {
                                showNewLayerMenu = false
                                val now = System.currentTimeMillis()
                                if (now - lastLayerOpTime > 350L) {
                                    lastLayerOpTime = now
                                    if (curIdx > 0 && curIdx < vm.layers.size && !isBg && !isFilter) {
                                        vm.addMaskToLayer(curIdx, com.reverie.paint.model.LayerMaskType.TRANSPARENCY)
                                    }
                                }
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_op_add_selection_mask),
                            icon = R.drawable.ic_select,
                            onClick = {
                                showNewLayerMenu = false
                                val now = System.currentTimeMillis()
                                if (now - lastLayerOpTime > 350L) {
                                    lastLayerOpTime = now
                                    if (!vm.hasSelection) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.layer_mask_toast_no_selection),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (curIdx > 0 && curIdx < vm.layers.size && !isBg && !isFilter) {
                                        vm.addMaskToLayer(curIdx, com.reverie.paint.model.LayerMaskType.SELECTION)
                                    }
                                }
                            },
                        )

                        // 柔和分割线
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Morandi.border.copy(alpha = 0.25f))
                        )

                        // --- 3. 快捷生成板块 ---
                        Text(
                            text = stringResource(R.string.layer_menu_section_actions),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_stamp_visible),
                            icon = R.drawable.ic_layers,
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
            }

            // 中间微弱分割线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Morandi.border.copy(alpha = 0.45f)),
            )

            // 右侧：图层操作与属性状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                val useAlphaInherit = vm.layerHeaderInheritAlpha
                TopIcon(
                    resId = if (useAlphaInherit) R.drawable.ic_alpha_inherit else R.drawable.ic_clip,
                    desc = stringResource(if (useAlphaInherit) R.string.layer_op_alpha_inherit else R.string.layer_op_clip),
                    active = if (useAlphaInherit) selLayer?.alphaInherited == true else selLayer?.clipped == true,
                    enabled = !isBg,
                    onClick = {
                        if (selectedIndex >= 0 && !isBg) {
                            if (useAlphaInherit) {
                                vm.setLayerAlphaInherited(selectedIndex, !(selLayer?.alphaInherited == true))
                            } else {
                                vm.setLayerClipped(selectedIndex, !(selLayer?.clipped == true))
                            }
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
        }

        Spacer(Modifier.height(4.dp))

        // Adaptive height: grows with the layer count, capped at
        // screen*3/4 minus the panel header (~56dp); scrolls beyond that
        val cfg = LocalConfiguration.current
        val maxListH = (cfg.screenHeightDp * 3 / 4 - 56).dp
        // 用**可见行数**而不是图层总数: 折叠的组子层不占高度, 用 vm.layers.size 会算多,
        // 下方留出一片空白 (用户反馈: 有收起的分格/图层组时列表下面空一截)
        val targetListH = (rowHeight * displayRows.size.toFloat()).coerceAtMost(maxListH)
        val listH by animateDpAsState(targetListH, tween(200), label = "listH")
        // 双指捏合合并 Toast 文案在组合期解析 (Lint: LocalContextGetResourceValueCall)
        val pinchMergedMsg = stringResource(R.string.layer_toast_pinch_merged)
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
                    .pointerInput(displayList, draggingFrom) {
                        awaitPointerEventScope {
                            var pinchStartDist = 0f
                            var pinchRow1 = -1
                            var pinchRow2 = -1
                            var pinchTriggered = false
                            var lastMergeTime = 0L

                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (draggingFrom >= 0) {
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        if (change.changedToUpIgnoreConsumed()) {
                                            endDrag()
                                        } else {
                                            change.consume()
                                            val screenX = listLeft + change.position.x
                                            val screenY = listTop + change.position.y
                                            updateDragPos(screenX, screenY)
                                        }
                                    }
                                    continue
                                }

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
                                            val isFilter = upperLayer.nodeType == 3
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
                                                    vm.showActionToast(pinchMergedMsg, com.reverie.paint.R.drawable.ic_merge_down)
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
                    itemsIndexed(displayList, key = { _, layer -> layer.id }) { displayIdx, layer ->
                        val hasNextSibling = if (layer.depth > 0) {
                            var found = false
                            for (k in (displayIdx + 1) until displayList.size) {
                                val next = displayList[k]
                                if (next.depth < layer.depth) break
                                if (next.depth == layer.depth) {
                                    found = true
                                    break
                                }
                            }
                            found
                        } else false

                        val openDepths = if (layer.depth > 1) {
                            val open = mutableSetOf<Int>()
                            for (d in 1 until layer.depth) {
                                for (k in (displayIdx + 1) until displayList.size) {
                                    val next = displayList[k]
                                    if (next.depth < d) break
                                    if (next.depth >= d) {
                                        open.add(d)
                                        break
                                    }
                                }
                            }
                            open
                        } else emptySet()

                        LayerRow(
                            vm = vm,
                            layer = layer,
                            selected = layer.index == selectedIndex || (
                                groupRowCollapsed(layer) && run {
                                    val pid = vm.panelIdForGroupRow(layer.index)
                                    pid != null && vm.panelContentLayerIndex(pid) == vm.currentLayerIndex
                                }
                            ),
                            collapsed = groupRowCollapsed(layer),
                            hasNextSibling = hasNextSibling,
                            openDepths = openDepths,
                            panelCrop = vm.panelCropStateFor(layer.index),
                            scrollGuardUntil = layerScrollGuard,
                            onToggleCollapse = {
                                revealedIndex = null
                                val pid = vm.panelIdForGroupRow(layer.index)
                                if (pid != null) {
                                    vm.togglePanelCollapsed(pid)
                                } else {
                                    vm.toggleGroupCollapsed(layer.id)
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
                            onRename = onRenameLayer,
                            onSelect = {
                                revealedIndex = null
                                vm.toggleLayerSelection(layer.index)
                            },
                            onClick = {
                                revealedIndex = null
                                val panelId = vm.panelIdForGroupRow(layer.index)
                                if (layer.index !in vm.selectedLayerIndices) {
                                    vm.clearLayerSelection()
                                }
                                if (panelId != null) {
                                    vm.cancelSoloIfSwitchingLayer()
                                    selectedIndex = layer.index
                                    vm.selectedPanelId = panelId
                                    vm.currentToolId = "panel"
                                    val content = vm.panelContentLayerIndex(panelId)
                                    if (content >= 0) vm.setCurrentLayer(content)
                                } else if (layer.index == vm.panelRootGroupIdx) {
                                    vm.cancelSoloIfSwitchingLayer()
                                    selectedIndex = layer.index
                                    vm.currentToolId = "panel"
                                } else if (layer.index == selectedIndex) {
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
            val rawLineY = when {
                targetInfo != null -> targetInfo.offset.toFloat()
                showIndicator -> {
                    val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    when {
                        firstVisible != null && dragTargetIdx <= firstVisible.index -> 0f
                        lastVisible != null && dragTargetIdx > lastVisible.index -> (lastVisible.offset + lastVisible.size).toFloat()
                        else -> {
                            val lastInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragTargetIdx - 1 }
                            lastInfo?.let { (it.offset + it.size).toFloat() }
                        }
                    }
                }
                else -> null
            }

            val isMultiDrag = (draggingFrom in vm.selectedLayerIndices) && vm.selectedLayerIndices.size > 1
            val batch = if (isMultiDrag) vm.selectedLayerIndices.filter { it > 0 }.sorted() else listOf(draggingFrom)
            val draggedSet = batch.toSet()
            val remaining = displayRows.filter { it.index !in draggedSet }

            val nonDraggedPrev = if (showIndicator) remaining.take(dragTargetIdx).lastOrNull() else null
            val nonDraggedNext = if (showIndicator) remaining.drop(dragTargetIdx).firstOrNull() else null
            val isIndented = isLayerDragIndented(dragStartX, dragFingerX, density)

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
                nonDraggedPrev.isGroup && !groupRowCollapsed(nonDraggedPrev) && nonDraggedPrev.depth < nonDraggedNext.depth -> {
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

/**
 * 图层拖拽时"是否保持原层级"(不脱离所在图层组)。
 *
 * 判据只有**横向手势意图**: 手指相对起手点向左移动满一格缩进 (14dp) 才算往外拖。
 * 这里曾经还有一条"距面板左边缘 >= 130dp"的绝对阈值 —— 它和真实缩进 (14dp/级)
 * 毫无关系, 面板一窄一宽、横竖屏切换都会改变判定, 表现就是用户报的
 * "拖动图层组里的图层时有概率跳到图层组外"。
 *
 * 起手点未知 (dragStartX == 0) 时按"保持原层级"处理: 宁可留在组里, 也不要把
 * 图层意外甩出去 (要移出组还有右键菜单的"移出图层组")。
 */
internal fun isLayerDragIndented(
    dragStartX: Float,
    dragFingerX: Float,
    density: androidx.compose.ui.unit.Density,
): Boolean {
    if (dragStartX == 0f) return true
    val dedentPx = with(density) { LAYER_INDENT_STEP_DP.dp.toPx() }
    return dragFingerX >= dragStartX - dedentPx
}