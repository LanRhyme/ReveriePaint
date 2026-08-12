package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt

private val drawerWidth = 120.dp
private val rowHeight = 56.dp

private fun Boolean?.orFalse() = this ?: false

private enum class DropMode { Above, OnGroup }

/** Panel sub-view: the detail page replaces the list inside the same panel. */
private sealed interface LayerView {
    data object List : LayerView

    data class Detail(
        val index: Int,
    ) : LayerView

    data class BlendModes(
        val index: Int,
    ) : LayerView

    data class Filters(
        val index: Int,
    ) : LayerView
}

/**
 * Layer panel (画世界 Pro style)
 *
 * Sub-page navigation inside one panel:
 * - list page: layer rows, top actions, swipe drawer
 * - detail page (tap the selected layer): blend mode row, opacity slider,
 *   vertical operation list
 * - blend-modes / filters sub pages
 *
 * UI is driven by [PaintViewModel.layers] (Compose state mirrored from C++)
 * so every structure change is immediately visible.
 */
@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    var view by remember { mutableStateOf<LayerView>(LayerView.List) }
    var renameRequest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLayerThumbs(force = true)
    }

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
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi.copy(alpha = opacity))
                    .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        ) {
            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
                },
                label = "layerPages",
            ) { v ->
                when (v) {
                    is LayerView.List -> {
                        LayerListView(
                            vm = vm,
                            onOpenDetail = { view = LayerView.Detail(it) },
                        )
                    }

                    is LayerView.Detail -> {
                        LayerDetailPage(
                            vm = vm,
                            index = v.index,
                            onBack = { view = LayerView.List },
                            onOpenBlendModes = { view = LayerView.BlendModes(v.index) },
                            onOpenFilters = { view = LayerView.Filters(v.index) },
                            onRename = { renameRequest = it },
                        )
                    }

                    is LayerView.BlendModes -> {
                        BlendModesPage(
                            vm = vm,
                            index = v.index,
                            onBack = { view = LayerView.Detail(v.index) },
                        )
                    }

                    is LayerView.Filters -> {
                        FiltersPage(
                            vm = vm,
                            index = v.index,
                            onBack = { view = LayerView.Detail(v.index) },
                        )
                    }
                }
            }
        }
    }

    renameRequest?.let { name ->
        val target = (view as? LayerView.Detail)?.index ?: -1
        RenameDialog(
            initial = name,
            onConfirm = { newName ->
                if (target >= 0) vm.renameLayer(target, newName)
                renameRequest = null
            },
            onDismiss = { renameRequest = null },
        )
    }
}

// ---------------------------------------------------------------------------
// List page
// ---------------------------------------------------------------------------

@Composable
private fun LayerListView(
    vm: PaintViewModel,
    onOpenDetail: (Int) -> Unit,
) {
    // Local selection (synchronous, not the async JNI currentLayerIndex):
    // the async C++ sync would lag a fast double tap and block opening detail.
    var selectedIndex by remember { mutableStateOf(vm.currentLayerIndex) }
    var collapsedGroups by remember { mutableStateOf(setOf<Int>()) }
    var draggingFrom by remember { mutableStateOf(-1) }
    var dragOver by remember { mutableStateOf<Pair<Int, DropMode>?>(null) }
    var dragFingerY by remember { mutableStateOf(0f) }
    var dragTargetIdx by remember { mutableStateOf(-1) }
    val rowBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    // Display order, top-first, keeping group blocks intact.
    // m_layers is a flat bottom-to-top tree walk, so a plain reverse breaks
    // group children (they would render above their group row). We rebuild a
    // display list recursively: siblings are reversed, group rows keep their
    // whole subtree below them (nested groups included).
    val displayRows =
        remember(vm.layers, collapsedGroups) {
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
                    if (c.isGroup && c.index !in collapsedGroups) {
                        val e = (j + 1 until hi).firstOrNull { vm.layers[it].depth <= c.depth } ?: hi
                        collectBlock(j + 1, e, c.depth, out)
                    }
                }
            }
            // C++ walk(root, 0) gives root children depth=0, so top-level
            // siblings match parentDepth -1
            buildList { collectBlock(0, n, -1, this) }
        }

    // Freeze the dragged order on release so the row does not first animate
    // back to its original position: displayList stays at the drop order until
    // the native move has landed (vm.layers updates -> LaunchedEffect releases).
    // Frozen drop order, keyed by layer NAME (indices change after the native
    // move lands, so an index-keyed freeze remaps wrong and plays a phantom
    // animateItem shuffle after release)
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }

    // Display list priority: frozen drop order > dragging order > real order
    val displayList =
        remember(vm.layers, collapsedGroups, draggingFrom, dragTargetIdx, pendingOrder) {
            if (pendingOrder != null) {
                val byName = displayRows.associateBy { it.name }
                pendingOrder!!.mapNotNull { byName[it] }
            } else if (draggingFrom >= 0 && dragTargetIdx >= 0) {
                val l = displayRows.toMutableList()
                val fi = l.indexOfFirst { it.index == draggingFrom }
                if (fi >= 0) {
                    val item = l.removeAt(fi)
                    l.add(dragTargetIdx.coerceIn(0, l.size), item)
                }
                l
            } else {
                displayRows
            }
        }

    // Once the native layer list reflects the move, release the frozen order
    LaunchedEffect(vm.layers) {
        if (pendingOrder != null) pendingOrder = null
    }

    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.roundToPx() }
    var columnTop by remember { mutableStateOf(0f) }

    fun updateDragPos(fingerY: Float) {
        dragFingerY = fingerY
        // Math-mapped target: rows are fixed-height, so the insert index is
        // (fingerY - listTop) / rowHeight, ROUNDED to the nearest row boundary
        // (finger in the top half of a row = insert before it, bottom half =
        // insert after it). The parting animation and the drop use the same
        // index, so what the user sees is where it lands.
        val rowPos = (fingerY - columnTop) / rowPx
        var target = rowPos.roundToInt().coerceIn(0, displayList.size - 1)
        // Background protection: never below the background row (index 0)
        val bgVisual = displayList.indexOfFirst { it.index == 0 }
        if (bgVisual >= 0) target = target.coerceAtMost((bgVisual - 1).coerceAtLeast(0))
        // Only re-sort when the target slot actually changes: recomputing the
        // list for every in-row finger micro-move restarts the animateItem
        // animations over and over, which reads as jitter
        if (target != dragTargetIdx) dragTargetIdx = target
        // Group middle zone highlight (rowBounds only used for this hint)
        var over: Pair<Int, DropMode>? = null
        for (i in displayList.indices) {
            val idx = displayList[i].index
            if (idx == draggingFrom) continue
            val b = rowBounds[idx] ?: continue
            if (fingerY >= b.first && fingerY <= b.second) {
                val isGroup = vm.layers.firstOrNull { it.index == idx }?.isGroup == true
                if (isGroup) {
                    val mid0 = b.first + (b.second - b.first) * 0.3f
                    val mid1 = b.first + (b.second - b.first) * 0.7f
                    if (fingerY >= mid0 && fingerY <= mid1) {
                        over = idx to DropMode.OnGroup
                    }
                }
            }
        }
        dragOver = over
        android.util.Log.d("LayerPanel", "dragPos y=$fingerY target=$target over=${over?.first ?: -1} ${over?.second}")
    }

    fun endDrag() {
        val from = draggingFrom
        val insert = dragTargetIdx
        val over = dragOver
        if (from > 0 && insert >= 0) {
            // Freeze the drop order so the row does not animate back to its
            // original slot before the native move lands (keyed by name -
            // indices change once the native move lands)
            pendingOrder = displayList.map { it.name }
            // Dropped into a group's middle zone -> move into the group
            val groupDrop =
                over != null && over.second == DropMode.OnGroup &&
                    displayList.getOrNull(insert)?.index == over.first
            if (groupDrop) {
                vm.moveLayerToGroup(from, over.first)
            } else {
                // Exact target semantics verified against Krita's sources:
                // KisNode::add(newNode, aboveThis) inserts at
                // index(aboveThis)+1, so the node lands DIRECTLY ABOVE
                // aboveThis in the bottom-first tree (m_layers). With
                //   to = m_layers index where the layer lands (visual slot
                //        insert <-> m_layers size-1-insert):
                //   to > from (dragged up visually): aboveThis = m_layers[to]
                //   to < from (dragged down visually): aboveThis = m_layers[to-1]
                // Desktop harness: 9/9 scenarios land exactly at `insert`.
                val to = displayList.size - 1 - insert
                if (to > 0 && to != from) {
                    val aboveIdx = if (to > from) to else to - 1
                    if (aboveIdx != from) {
                        pendingOrder = displayList.map { it.name }
                        vm.moveLayerAbove(from, aboveIdx)
                        // The dragged layer now lives at m_layers index `to`;
                        // select it (not the layer that was pushed into its
                        // old slot)
                        selectedIndex = to
                        vm.setCurrentLayer(to)
                    }
                }
            }
        }
        draggingFrom = -1
        dragTargetIdx = -1
        dragFingerY = 0f
        dragOver = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top actions: + new layer | folder group | lock alpha | merge down | selection grid
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopIcon(R.drawable.ic_plus, "新建图层") { vm.addLayer() }
            Spacer(Modifier.weight(1f))
            TopIcon(R.drawable.ic_folder, "添加图层组") { vm.addGroupLayer() }
            TopIcon(R.drawable.ic_lock, "锁定透明度") {
                if (selectedIndex >= 0) vm.setLayerAlphaLocked(selectedIndex, !vm.layers.getOrNull(selectedIndex)?.alphaLocked.orFalse())
            }
            TopIcon(R.drawable.ic_merge_down, "向下合并") {
                if (selectedIndex >= 0) vm.mergeDown(selectedIndex)
            }
            TopIcon(R.drawable.ic_grid, "创建选区") {
                if (selectedIndex >= 0) vm.selectionFromLayer(selectedIndex)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        var listTop by remember { mutableStateOf(0f) }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .onGloballyPositioned { listTop = it.boundsInRoot().top },
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { columnTop = it.boundsInRoot().top }
                        // Panel-level drag handler: once a row's long press activated
                        // dragging (draggingFrom >= 0), this consumes the following
                        // moves for drop-position calculation. Consuming also stops
                        // the lazy scroll from hijacking the drag.
                        .pointerInput(draggingFrom) {
                            if (draggingFrom < 0) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.firstOrNull { it.pressed }
                                    if (pressed == null) {
                                        endDrag()
                                        break
                                    }
                                    pressed.consume()
                                    updateDragPos(columnTop + pressed.position.y)
                                }
                            }
                        },
            ) {
            // Key by a STABLE identity (depth+name): indices change after a
            // native move lands, so an index-keyed list makes animateItem
            // play a phantom swap animation even when the visible order is
            // already correct
            items(displayList, key = { "${it.depth}:${it.name}" }) { layer ->
                    LayerRow(
                        vm = vm,
                        layer = layer,
                        selected = layer.index == selectedIndex,
                        collapsed = layer.index in collapsedGroups,
                        onToggleCollapse = {
                            collapsedGroups =
                                if (layer.index in collapsedGroups) collapsedGroups - layer.index
                                else collapsedGroups + layer.index
                        },
                        onBounds = { top, bottom -> rowBounds[layer.index] = top to bottom },
                        onDragStart = {
                            pendingOrder = null
                            draggingFrom = layer.index
                        },
                        onDragPosition = { updateDragPos(it) },
                        onDragEnd = { endDrag() },
                        dragOnGroup = dragOver?.first == layer.index && dragOver?.second == DropMode.OnGroup,
                        isDragging = draggingFrom == layer.index,
                        dragFingerY = dragFingerY,
                        onClick = {
                            if (layer.index == selectedIndex) {
                                onOpenDetail(layer.index)
                            } else {
                                selectedIndex = layer.index
                                vm.setCurrentLayer(layer.index)
                            }
                        },
                            modifier =
                                Modifier.animateItem(
                                    // No-bouncy placement: the default spring
                                    // overshoots and looks like a jitter
                                    placementSpec =
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessHigh,
                                        ),
                                    fadeInSpec = tween(150),
                                    fadeOutSpec = tween(150),
                                ),
                        )
            }
        }

            // Floating drag overlay: the dragged row rendered on top of the
            // list, following the finger, so it is never occluded by other rows.
            if (draggingFrom >= 0) {
                val dragged = vm.layers.firstOrNull { it.index == draggingFrom }
                if (dragged != null) {
                    Box(
                        modifier =
                            Modifier
                                // Follows the finger (画世界 Pro style). The
                                // parting animation shows the drop slot; the
                                // rounded target in updateDragPos matches it
                                // exactly, so release lands at the slot.
                                .offset {
                                    IntOffset(0, (dragFingerY - listTop - rowPx / 2f).roundToInt())
                                }
                                .fillMaxWidth()
                                .height(rowHeight)
                                .graphicsLayer {
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = with(density) { 16.dp.toPx() }
                                },
                    ) {
                        LayerRowContent(
                            vm = vm,
                            layer = dragged,
                            selected = dragged.index == selectedIndex,
                            collapsed = dragged.index in collapsedGroups,
                            index = dragged.index,
                            onToggleCollapse = {},
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopIcon(
    resId: Int,
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
        Icon(
            painterResource(resId),
            contentDescription = desc,
            tint = Morandi.icon,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun LayerRow(
    vm: PaintViewModel,
    layer: PaintViewModel.LayerUiState,
    selected: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onBounds: (Float, Float) -> Unit,
    onDragStart: () -> Unit,
    onDragPosition: (Float) -> Unit,
    onDragEnd: () -> Unit,
    dragOnGroup: Boolean,
    isDragging: Boolean,
    dragFingerY: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = layer.index
    val isBg = layer.isBackground
    val visible = layer.visible
    var reveal by remember { mutableStateOf(false) }
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 8.dp.roundToPx() }
    val drawerPx = with(density) { drawerWidth.roundToPx() }
    var rowTop by remember { mutableStateOf(0f) }
    var rowBottom by remember { mutableStateOf(0f) }

    val revealFraction by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(220),
        label = "reveal",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(rowHeight)
                .onGloballyPositioned { c ->
                    rowTop = c.boundsInRoot().top
                    rowBottom = c.boundsInRoot().bottom
                    onBounds(rowTop, rowBottom)
                }
                // Swipe-left reveals the action drawer (full event loop,
                // requireUnconsumed=false so it always sees the down even if
                // combinedClickable consumed it; only consumes moves once a
                // horizontal swipe is detected, which cancels the clickable)
                .pointerInput(index) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val thr = dragThresholdPx.toFloat()
                        var prevX = startX
                        var swiping = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.changedToUpIgnoreConsumed()) break
                            val dx = change.position.x - startX
                            if (!swiping && (dx > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop)) {
                                swiping = true
                            }
                            if (swiping) {
                                change.consume()
                                reveal = dx < 0
                                android.util.Log.d("LayerPanel", "SWIPE reveal idx=$index dx=$dx")
                            }
                        }
                    }
                }
                .combinedClickable(
                    onClick = { onClick() },
                    onLongClick = {
                        // 画世界 Pro style: vibrate then drag
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDragStart()
                    },
                ),
    ) {
        // Action drawer: three actions (copy / solo / delete), composed only
        // while revealed so a closed row neither renders nor hits the buttons;
        // the row slides away (offset) and the drawer fades in.
        if (reveal) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .zIndex(2f)
                        .clip(RoundedCornerShape(8.dp)),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_copy, "复制") {
                    vm.copyLayer(index)
                    reveal = false
                }
                DrawerAction(Modifier.weight(1f), Morandi.accent, R.drawable.ic_eye, "独显") {
                    vm.soloLayer(index)
                    reveal = false
                }
                DrawerAction(Modifier.weight(1f), Color(0xFFB05552), R.drawable.ic_trash, "删除") {
                    if (!isBg) vm.removeLayer(index)
                    reveal = false
                }
            }
            }
        }

        // Row visuals (indent guides, collapse arrow, eye, thumbnail, name, status)
        LayerRowContent(
            vm = vm,
            layer = layer,
            selected = selected,
            collapsed = collapsed,
            index = index,
            onToggleCollapse = onToggleCollapse,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        animateColorAsState(
                            targetValue =
                                when {
                                    dragOnGroup -> Morandi.accent.copy(alpha = 0.3f)
                                    selected -> Morandi.accent
                                    else -> Morandi.panelHi
                                },
                            animationSpec = tween(180),
                            label = "rowBg",
                        ).value,
                    )
                    // slide left while the drawer is revealed (animated)
                    .offset { IntOffset(-(drawerPx * revealFraction).roundToInt(), 0) }
                    // while dragging: dim the in-list row (the floating copy in
                    // the overlay is the visible one)
                    .graphicsLayer { if (isDragging) alpha = 0.4f }
                    .padding(horizontal = 8.dp),
        )
    }
}

/**
 * Visual content of a layer row (indent guides, collapse arrow, eye, thumbnail,
 * name with sub-info, status icons). No gestures - shared by the in-list row
 * and the floating drag overlay.
 */
@Composable
private fun LayerRowContent(
    vm: PaintViewModel,
    layer: PaintViewModel.LayerUiState,
    selected: Boolean,
    collapsed: Boolean,
    index: Int,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBg = layer.isBackground
    val visible = layer.visible
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Group indent: whole row content shifts right for nested layers,
        // with VS Code style vertical guide lines (one per nesting level)
        if (layer.depth > 0) {
            Box(Modifier.width((layer.depth * 16).dp).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val step = 16.dp.toPx()
                    val lw = 1.dp.toPx()
                    for (d in 1..layer.depth) {
                        val x = step * (d - 0.5f)
                        drawLine(
                            color = Morandi.border.copy(alpha = 0.7f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = lw,
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(0.dp))
        }

        // Collapse arrow for groups (toggle, does not select)
        if (layer.isGroup) {
            Box(
                modifier =
                    Modifier
                        .size(22.dp)
                        .noRippleClickable(onToggleCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = if (collapsed) "展开" else "折叠",
                    tint = Morandi.subText,
                    modifier = Modifier.size(14.dp).rotate(if (collapsed) 0f else 90f),
                )
            }
        }
        // Visibility eye (left of the thumbnail)
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (visible) Color.Transparent else Morandi.panel.copy(alpha = 0.7f))
                    .noRippleClickable { vm.toggleLayerVisible(index) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                contentDescription = "可见性",
                tint = if (visible) Morandi.icon else Morandi.subText,
                modifier = Modifier.size(17.dp),
            )
        }
        // Thumbnail (light checkerboard behind)
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Morandi.border.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        ) {
            LightCheckerboard(Modifier.fillMaxSize())
            vm.layerThumbs[layer.index]?.let { thumb ->
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "图层缩略图",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = layer.name,
                color = if (selected) Morandi.onAccent else Morandi.text,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val blendName =
                vm.blendModes.firstOrNull { it.first == layer.blendMode }?.second
                    ?: layer.blendMode
            val modified = layer.opacity < 0.999f || layer.blendMode != "normal"
            if (modified) {
                Text(
                    text = "${(layer.opacity * 100).roundToInt()}% · $blendName",
                    color = if (selected) Morandi.onAccent.copy(alpha = 0.7f) else Morandi.subText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Right-side status icons
        if (layer.clipped) {
            Icon(
                painterResource(R.drawable.ic_clip),
                contentDescription = "剪贴蒙版",
                tint = if (selected) Morandi.onAccent.copy(alpha = 0.8f) else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
        }
        if (layer.alphaLocked && !isBg) {
            Icon(
                painterResource(R.drawable.ic_grid),
                contentDescription = "锁定透明度",
                tint = if (selected) Morandi.onAccent.copy(alpha = 0.8f) else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
        }
        if (layer.locked || isBg) {
            Icon(
                painterResource(R.drawable.ic_lock),
                contentDescription = "锁定",
                tint = if (selected) Morandi.onAccent.copy(alpha = 0.8f) else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    color: Color,
    resId: Int,
    desc: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(color)
                .noRippleClickable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(resId), contentDescription = desc, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(desc, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 1)
    }
}

/** Light checkerboard: white background with faint light-gray grid lines. */
@Composable
private fun LightCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(Color(0xFFF5F3EF))
        val cell = size.width / 4f
        val line = Color(0xFFDCD8D0)
        for (i in 0..4) {
            val x = i * cell
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            val y = i * cell
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
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

// ---------------------------------------------------------------------------
// Detail page (replaces the list inside the same panel)
// ---------------------------------------------------------------------------

@Composable
private fun LayerDetailPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
    onOpenBlendModes: () -> Unit,
    onOpenFilters: () -> Unit,
    onRename: (String) -> Unit,
) {
    val layer = vm.layers.firstOrNull { it.index == index }
    val isBg = layer?.isBackground ?: true
    val name = layer?.name ?: ""
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
    ) {
        // Header: < 图层设置
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
                "图层设置",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Blend mode row button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .noRippleClickable(onOpenBlendModes)
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
                vm.blendModes.firstOrNull { it.first == layer?.blendMode }?.second ?: layer?.blendMode ?: "正常",
                color = Morandi.subText,
                fontSize = 13.sp,
            )
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border.copy(alpha = 0.5f)),
        )

        // Opacity slider
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("不透明度", color = Morandi.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${((layer?.opacity ?: 1.0) * 100).roundToInt()}%", color = Morandi.subText, fontSize = 13.sp)
        }
        var localOpacity by remember(index) { mutableFloatStateOf((layer?.opacity ?: 1.0).toFloat()) }
        var lastOpacityNs by remember(index) { mutableLongStateOf(0L) }
        ReSlider(
            value = localOpacity,
            onValue = {
                localOpacity = it
                val now = System.nanoTime()
                if (now - lastOpacityNs > 50_000_000L) {
                    lastOpacityNs = now
                    vm.setLayerOpacity(index, it.toDouble())
                }
            },
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border),
        )

        if (layer?.isGroup == true) {
            // Group-specific page
            Column {
                OpItem(R.drawable.ic_rename, "重命名") { onRename(name) }
                OpItem(R.drawable.ic_trash, "删除图层组", enabled = !isBg) { vm.removeLayer(index) }
            }
        } else {
            // Vertical operation list
            Column {
                OpItem(R.drawable.ic_copy, "复制图层") { vm.copyLayer(index) }
                OpItem(R.drawable.ic_erase, "清除图层") { vm.clearLayer(index) }
                OpItem(R.drawable.ic_rename, "重命名") { onRename(name) }
                OpItem(R.drawable.ic_trash, "删除图层", enabled = !isBg) { vm.removeLayer(index) }
                OpItem(R.drawable.ic_flip_h, "水平翻转") { vm.flipLayerHorizontal(index) }
                OpItem(R.drawable.ic_flip_v, "垂直翻转") { vm.flipLayerVertical(index) }
                OpItem(R.drawable.ic_reference, "参考", enabled = false) {}
                OpItem(R.drawable.ic_merge_down, "向下合并图层", enabled = !isBg) { vm.mergeDown(index) }
                OpItem(R.drawable.ic_refresh, "统一图层可见性", enabled = false) {}
                OpItem(R.drawable.ic_clip, "添加图层蒙版", enabled = false) {}
                OpItem(R.drawable.ic_select, "创建选区") { vm.selectionFromLayer(index) }
                OpToggle(R.drawable.ic_lock, "锁定透明度", layer?.alphaLocked == true, enabled = !isBg) {
                    vm.setLayerAlphaLocked(index, !(layer?.alphaLocked == true))
                }
                OpToggle(R.drawable.ic_clip, "设为剪贴蒙版", layer?.clipped == true, enabled = !isBg) {
                    vm.setLayerClipped(index, !(layer?.clipped == true))
                }
                OpItem(R.drawable.ic_sliders, "滤镜") { onOpenFilters() }
            }
        }
    }
}

@Composable
private fun OpItem(
    resId: Int,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable { if (enabled) onClick() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(resId),
            contentDescription = null,
            tint = if (enabled) Morandi.icon else Morandi.subText.copy(alpha = 0.4f),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text,
            color = if (enabled) Morandi.text else Morandi.subText.copy(alpha = 0.5f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        if (!enabled) {
            Text("未实现", color = Morandi.subText.copy(alpha = 0.4f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun OpToggle(
    resId: Int,
    text: String,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable { if (enabled) onClick() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(resId),
            contentDescription = null,
            tint =
                if (enabled && on) {
                    Morandi.accent
                } else if (enabled) {
                    Morandi.icon
                } else {
                    Morandi.subText.copy(alpha = 0.4f)
                },
            modifier = Modifier.size(17.dp),
        )
        Text(
            text,
            color = if (enabled) Morandi.text else Morandi.subText.copy(alpha = 0.5f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(34.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on && enabled) Morandi.accent else Morandi.panel),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on && enabled) Morandi.onAccent else Morandi.subText)
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Blend modes sub page
// ---------------------------------------------------------------------------

@Composable
private fun BlendModesPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
) {
    val current = vm.layers.firstOrNull { it.index == index }?.blendMode
    Column(modifier = Modifier.fillMaxWidth()) {
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
            Text("混合模式", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        Column(
            modifier =
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            for ((opId, name) in vm.blendModes) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .noRippleClickable {
                                vm.setLayerBlendMode(index, opId)
                                onBack()
                            }.background(if (opId == current) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        color = if (opId == current) Morandi.accent else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = if (opId == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    if (opId == current) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filters sub page
// ---------------------------------------------------------------------------

@Composable
private fun FiltersPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
            Text("滤镜", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        Column {
            listOf(
                "灰度" to 0,
                "反色" to 1,
                "模糊" to 2,
                "锐化" to 3,
            ).forEach { (name, id) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .noRippleClickable {
                                vm.applyFilter(index, id)
                                onBack()
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = Morandi.text, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

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
