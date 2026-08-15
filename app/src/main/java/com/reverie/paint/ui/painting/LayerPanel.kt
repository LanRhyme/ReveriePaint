package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.util.Locale
import kotlin.math.roundToInt
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalConfiguration
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

private val drawerWidth = 132.dp
private val rowHeight = 48.dp

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

    data class FilterAdjust(
        val index: Int,
        val filterId: Int,
        val filterName: String,
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
    hazeState: HazeState? = null,
) {
    var view by remember { mutableStateOf<LayerView>(LayerView.List) }
    var renameRequest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLayerThumbs(force = true)
    }

    val panelShape = RoundedCornerShape(14.dp)

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
                    .pointerHoverIcon(PointerIcon.Default)
                    .align(Alignment.TopEnd)
                    .padding(top = 44.dp, end = 8.dp)
                    .width(300.dp)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 3 / 4).dp)
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        ) {
            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    if (targetState is LayerView.Detail && initialState is LayerView.List) {
                        (slideInHorizontally { it } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
                    } else if (targetState is LayerView.List && initialState is LayerView.Detail) {
                        (slideInHorizontally { -it / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { it } + fadeOut(tween(120)))
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
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
                            onSelectFilter = { filterId, filterName ->
                                view = LayerView.FilterAdjust(v.index, filterId, filterName)
                            }
                        )
                    }

                    is LayerView.FilterAdjust -> {
                        FilterAdjustPage(
                            vm = vm,
                            index = v.index,
                            filterId = v.filterId,
                            filterName = v.filterName,
                            onBack = { view = LayerView.Filters(v.index) },
                            onDone = { view = LayerView.List }
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
    LaunchedEffect(vm.currentLayerIndex) {
        if (vm.currentLayerIndex in vm.layers.indices) {
            selectedIndex = vm.currentLayerIndex
        }
    }
    // Only one row may have its swipe drawer open; swiping another row
    // closes this one (revealedIndex is the open row's layer index)
    var revealedIndex by remember { mutableStateOf<Int?>(null) }
    var collapsedGroups by remember { mutableStateOf(setOf<Int>()) }
    var draggingFrom by remember { mutableStateOf(-1) }
    var dragOver by remember { mutableStateOf<Pair<Int, DropMode>?>(null) }
    var dragFingerY by remember { mutableStateOf(0f) }
    var dragTargetIdx by remember { mutableStateOf(-1) }
    // After release the floating overlay glides into the drop slot before
    // fading out, so what the user sees (finger position) matches where the
    // layer lands (slot center). settleTo = absolute y of the slot center;
    // settleFrom = the overlay offset at release.
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
        if (pendingOrder != null) {
            android.util.Log.d(
                "LayerPanel",
                "RELEASE pending=${pendingOrder} real=${displayRows.map { it.name }}",
            )
            pendingOrder = null
        }
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
        // 0.5 counts as THIS row (roundToInt's Math.round bumps 0.5 up, so a
        // finger at the row center snapped the placeholder a full row lower -
        // the "offset by a bit" feel when dragging). (x+0.4999).toInt() keeps
        // the placeholder centered on the finger's row.
        var target = (rowPos + 0.4999f).toInt().coerceIn(0, displayList.size - 1)
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
        android.util.Log.d(
            "LayerPanel",
            "dragPos y=$fingerY colTop=$columnTop rowPx=$rowPx rowPos=$rowPos target=$target size=${displayList.size}",
        )
    }

    fun endDrag() {
        val from = draggingFrom
        val insert = dragTargetIdx
        val over = dragOver
        if (from > 0 && insert >= 0) {
            // Reconstruct the frozen drop order DIRECTLY from displayRows.
            // Reading the remember()d displayList here can return a stale
            // cached order built for an older dragTargetIdx (the last move
            // event's state update may not have recomposed yet), which then
            // differs from the real post-move order and plays a phantom
            // shuffle after release (the "moves again after drop" bug)
            val frozen = displayRows.toMutableList()
            val fi = frozen.indexOfFirst { it.index == from }
            if (fi >= 0) {
                val item = frozen.removeAt(fi)
                frozen.add(insert.coerceIn(0, frozen.size), item)
            }
            pendingOrder = frozen.map { it.name }
            // Dropped into a group's middle zone -> move into the group
            val groupDrop = over != null && over.second == DropMode.OnGroup
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
                val to = frozen.size - 1 - insert
                if (to > 0 && to != from) {
                    val aboveIdx = if (to > from) to else to - 1
                    if (aboveIdx != from) {
                        android.util.Log.d(
                            "LayerPanel",
                            "ENDDRAG from=$from insert=$insert to=$to above=$aboveIdx size=${frozen.size}",
                        )
                        vm.moveLayerAbove(from, aboveIdx)
                        // The dragged layer now lives at m_layers index `to`;
                        // select it (not the layer that was pushed into its
                        // old slot)
                        selectedIndex = to
                        vm.setCurrentLayer(to)
                        // glide the floating overlay into the drop slot so the
                        // visual landing equals the real landing
                        settleFrom = dragFingerY - columnTop - rowPx / 2f
                        settleTo = columnTop + insert * rowPx + rowPx / 2f
                        settling = true
                    }
                }
            }
        }
        draggingFrom = -1
        dragTargetIdx = -1
        dragFingerY = 0f
        dragOver = null
    }

    var showNewLayerMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        val selLayer = vm.layers.getOrNull(selectedIndex)
        val isBg = selLayer?.isBackground ?: true

        // Top actions: + new paint layer | folder group | more layers (menu) | lock layer | lock alpha | clip mask | merge down
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopIcon(
                resId = R.drawable.ic_plus,
                desc = "添加颜料图层",
                onClick = { vm.addLayer() }
            )
            TopIcon(
                resId = R.drawable.ic_folder,
                desc = "添加图层组",
                onClick = { vm.addGroupLayer() }
            )
            Box {
                TopIcon(
                    resId = R.drawable.ic_layers,
                    desc = "更多图层类型",
                    active = showNewLayerMenu,
                    onClick = { showNewLayerMenu = true }
                )
                DropdownMenu(
                    expanded = showNewLayerMenu,
                    onDismissRequest = { showNewLayerMenu = false },
                    modifier = Modifier.background(Morandi.panel).border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("填充图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_fill), null, tint = Morandi.icon, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            vm.addLayerWithType("", 2, vm.brushColor.toInt())
                            showNewLayerMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("调整图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sliders), null, tint = Morandi.icon, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            vm.addLayerWithType("", 3)
                            showNewLayerMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("矢量图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_polyline), null, tint = Morandi.icon, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            vm.addLayerWithType("", 4)
                            showNewLayerMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("克隆图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null, tint = Morandi.icon, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            vm.addLayerWithType("", 5)
                            showNewLayerMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("盖印可见图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_layers), null, tint = Morandi.icon, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            vm.stampVisibleLayers()
                            showNewLayerMenu = false
                        }
                    )
                }
            }

            TopIcon(
                resId = R.drawable.ic_lock,
                desc = "锁定图层",
                active = selLayer?.locked == true,
                enabled = !isBg,
                onClick = {
                    if (selectedIndex >= 0 && !isBg) {
                        vm.setLayerLocked(selectedIndex, !(selLayer?.locked == true))
                    }
                }
            )
            TopIcon(
                resId = R.drawable.ic_grid,
                desc = "锁定透明度",
                active = selLayer?.alphaLocked == true,
                enabled = !isBg,
                onClick = {
                    if (selectedIndex >= 0 && !isBg) {
                        vm.setLayerAlphaLocked(selectedIndex, !(selLayer?.alphaLocked == true))
                    }
                }
            )
            TopIcon(
                resId = R.drawable.ic_clip,
                desc = "继承透明度",
                active = selLayer?.clipped == true,
                enabled = !isBg,
                onClick = {
                    if (selectedIndex >= 0 && !isBg) {
                        vm.setLayerClipped(selectedIndex, !(selLayer?.clipped == true))
                    }
                }
            )
            TopIcon(
                resId = R.drawable.ic_merge_down,
                desc = "向下合并",
                enabled = selectedIndex > 0 && !isBg,
                onClick = {
                    if (selectedIndex > 0 && !isBg) {
                        vm.mergeDown(selectedIndex)
                    }
                }
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        var listTop by remember { mutableStateOf(0f) }
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
                            revealedIndex = null
                            collapsedGroups =
                                if (layer.index in collapsedGroups) collapsedGroups - layer.index
                                else collapsedGroups + layer.index
                        },
                        revealed = layer.index == revealedIndex,
                        onReveal = { revealedIndex = layer.index },
                        onRevealClose = { revealedIndex = null },
                        onBounds = { top, bottom -> rowBounds[layer.index] = top to bottom },
                        onDragStart = {
                            revealedIndex = null
                            pendingOrder = null
                            draggingFrom = layer.index
                        },
                        onDragPosition = { updateDragPos(it) },
                        onDragEnd = { endDrag() },
                        dragOnGroup = dragOver?.first == layer.index && dragOver?.second == DropMode.OnGroup,
                        isDragging = draggingFrom == layer.index,
                        dragFingerY = dragFingerY,
                        onClick = {
                            revealedIndex = null
                            if (layer.index == selectedIndex) {
                                onOpenDetail(layer.index)
                            } else {
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

            // After release the overlay glides into the drop slot (settleTo)
            LaunchedEffect(settling, settleTo, settleFrom) {
                if (settling && settleTo != null && settleFrom != null) {
                    settleAnim.snapTo(settleFrom ?: 0f)
                    val target = (settleTo ?: 0f) - listTop - rowPx / 2f
                    settleAnim.animateTo(target, tween(160))
                    settling = false
                    settleTo = null
                    settleFrom = null
                }
            }
            // Floating drag overlay: the dragged row rendered on top of the
            // list, following the finger, so it is never occluded by other rows.
            // After release it stays for 160ms, gliding into the drop slot
            // (settleTo) so the visual landing matches the real landing.
            if (draggingFrom >= 0 || settleTo != null) {
                val dragged = vm.layers.firstOrNull { it.index == draggingFrom }
                if (dragged != null) {
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    val y =
                                        if (settling && settleTo != null) settleAnim.value
                                        else dragFingerY - listTop - rowPx / 2f
                                    IntOffset(0, y.roundToInt())
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
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) Morandi.accent.copy(alpha = 0.2f) else Color.Transparent)
                .border(
                    width = if (active) 1.dp else 0.dp,
                    color = if (active) Morandi.accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .noRippleClickable { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(resId),
            contentDescription = desc,
            tint = if (!enabled) Morandi.subText.copy(alpha = 0.35f)
                   else if (active) Morandi.accent
                   else Morandi.icon,
            modifier = Modifier.size(17.dp),
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
    revealed: Boolean,
    onReveal: () -> Unit,
    onRevealClose: () -> Unit,
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
    var swiping by remember { mutableStateOf(false) }
    var fingerDx by remember { mutableStateOf(0f) }
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 8.dp.roundToPx() }
    // A swipe must travel this far to reveal the drawer (prevents accidental
    // triggers from small horizontal wiggles)
    val revealThresholdPx = with(density) { 20.dp.roundToPx() }
    val drawerPx = with(density) { drawerWidth.roundToPx() }
    var rowTop by remember { mutableStateOf(0f) }
    var rowBottom by remember { mutableStateOf(0f) }

    // Row slide offset in px. While swiping it snapTo()s the finger (instant
    // follow, no lag); on release it animates to the revealed/closed position.
    // A single Animatable for both phases means no value jump on release (the
    // "bounces back then slides out" feel came from switching between the raw
    // finger offset and a separately-animated fraction starting at 0).
    val revealAnim = remember { Animatable(0f) }

    LaunchedEffect(swiping, revealed) {
        if (swiping) {
            // follow the finger frame by frame (instant, no lag)
            while (true) {
                revealAnim.snapTo(fingerDx)
                withFrameNanos {}
            }
        } else {
            revealAnim.animateTo(
                if (revealed) -drawerPx.toFloat() else 0f,
                tween(220),
            )
        }
    }

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
                        val startY = down.position.y
                        var swiping = false
                        var lastDx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.changedToUpIgnoreConsumed()) break
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            if (!swiping) {
                                // Only treat it as a swipe when the movement is
                                // clearly horizontal (prevents vertical list
                                // scrolling and small wiggles from revealing)
                                if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.2f) {
                                    swiping = true
                                }
                            }
                            if (swiping) {
                                change.consume()
                                lastDx = dx
                                // follow the finger, clamped to the drawer width
                                fingerDx = dx.coerceIn(-drawerPx.toFloat(), 0f)
                            }
                        }
                        if (swiping) {
                            // reveal only on a deliberate swipe past the
                            // threshold; otherwise the row animates back
                            if (lastDx < -revealThresholdPx) onReveal()
                            else onRevealClose()
                            swiping = false
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
        // Drawer with real enter AND exit animations: slides in from the
        // right edge while fading on reveal, and slides back out while fading
        // when closed (synchronized with the row slide via tween(220))
        AnimatedVisibility(
            visible = revealed,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(160)),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(160)),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .zIndex(2f),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_copy, "复制") {
                    vm.copyLayer(index)
                    onRevealClose()
                }
                DrawerAction(Modifier.weight(1f), Morandi.accent, R.drawable.ic_eye, "独显") {
                    vm.soloLayer(index)
                    onRevealClose()
                }
                DrawerAction(Modifier.weight(1f), Color(0xFFB05552), R.drawable.ic_trash, "删除") {
                    if (!isBg) vm.removeLayer(index)
                    onRevealClose()
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
                    .background(
                        animateColorAsState(
                            targetValue =
                                when {
                                    dragOnGroup -> Morandi.accent.copy(alpha = 0.3f)
                                    selected -> Morandi.accent
                                    // rows are transparent by default; the
                                    // panel itself is translucent
                                    else -> Color.Transparent
                                },
                            animationSpec = tween(180),
                            label = "rowBg",
                        ).value,
                    )
                    // follow the finger while swiping; animate to the
                    // revealed/closed position on release (revealAnim covers
                    // both, so there is no value jump between the phases)
                    .offset { IntOffset(revealAnim.value.roundToInt(), 0) }
                    // while dragging: dim the in-list row (the floating copy in
                    // the overlay is the visible one)
                    .graphicsLayer { if (isDragging) alpha = 0.4f }
                    .padding(horizontal = 4.dp),
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            color = Morandi.subText.copy(alpha = 0.35f),
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
                    .size(24.dp)
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
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = if (layer.colorLabel > 0) 2.dp else 1.dp,
                    color = if (layer.colorLabel > 0) layerLabelColor(layer.colorLabel) else Morandi.border.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp)
                ),
        ) {
            LightCheckerboard(Modifier.fillMaxSize())
            vm.thumbFor(layer.index, layer.name)?.let { thumb ->
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
                contentDescription = "继承透明度",
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

        // Krita 8-color label picker
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val currentLabel = layer?.colorLabel ?: 0
            for (label in 0..8) {
                val color = layerLabelColor(label)
                val isSelected = currentLabel == label
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (label == 0) Morandi.panelHi else color)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Morandi.accent else Morandi.border.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                        .clickable { vm.setLayerColorLabel(index, label) },
                    contentAlignment = Alignment.Center
                ) {
                    if (label == 0) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Morandi.subText.copy(alpha = 0.5f)))
                    } else if (isSelected) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border),
        )

        var showGroupPicker by remember { mutableStateOf(false) }
        val availableGroups = remember(vm.layers) {
            vm.layers.filter { it.isGroup && it.index != index }
        }

        if (showGroupPicker) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showGroupPicker = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text("移入图层组", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (availableGroups.isEmpty()) {
                                Text("当前画布中暂无其他图层组", color = Morandi.subText, fontSize = 13.sp)
                            } else {
                                availableGroups.forEach { grp ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .noRippleClickable {
                                                vm.moveLayerToGroup(index, grp.index)
                                                showGroupPicker = false
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(painterResource(R.drawable.ic_folder), null, tint = Morandi.accent, modifier = Modifier.size(18.dp))
                                        Text(grp.name, color = Morandi.text, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .clickable { showGroupPicker = false }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("取消", color = Morandi.text, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (layer?.isGroup == true) {
            // Group-specific page
            Column {
                OpItem(R.drawable.ic_rename, "重命名") { onRename(name) }
                OpItem(R.drawable.ic_trash, "删除图层组", enabled = !isBg) { vm.removeLayer(index); onBack() }
                OpItem(R.drawable.ic_merge_down, "合并图层组", enabled = !isBg) { vm.flattenGroup(index); onBack() }
                OpItem(R.drawable.ic_arrow_up, "上移一层", enabled = index < vm.layers.size - 1) { vm.moveLayerUp(index) }
                OpItem(R.drawable.ic_arrow_down, "下移一层", enabled = index > 1) { vm.moveLayerDown(index) }
                OpItem(R.drawable.ic_eye, "独显/隔离此图层组") { vm.soloLayer(index) }
                OpToggle(R.drawable.ic_lock, "锁定图层组", layer?.locked == true || isBg, enabled = !isBg) {
                    vm.setLayerLocked(index, !(layer?.locked == true))
                }
                OpToggle(R.drawable.ic_clip, "继承透明度", layer?.clipped == true, enabled = !isBg) {
                    vm.setLayerClipped(index, !(layer?.clipped == true))
                }
                OpToggle(R.drawable.ic_sliders, "穿透混合模式 (Pass-through)", vm.groupPassThrough(index)) {
                    vm.setGroupPassThrough(index, !vm.groupPassThrough(index))
                }
            }
        } else {
            // Vertical operation list
            Column {
                OpItem(R.drawable.ic_copy, "复制图层") { vm.copyLayer(index) }
                OpItem(R.drawable.ic_erase, "清除图层") { vm.clearLayer(index) }
                OpItem(R.drawable.ic_rename, "重命名") { onRename(name) }
                OpItem(R.drawable.ic_trash, "删除图层", enabled = !isBg) { vm.removeLayer(index); onBack() }
                OpItem(R.drawable.ic_arrow_up, "上移一层", enabled = index < vm.layers.size - 1) { vm.moveLayerUp(index) }
                OpItem(R.drawable.ic_arrow_down, "下移一层", enabled = index > 1) { vm.moveLayerDown(index) }
                if ((layer?.depth ?: 0) > 0) {
                    OpItem(R.drawable.ic_folder, "移出图层组") { vm.moveLayerOut(index) }
                }
                if (availableGroups.isNotEmpty()) {
                    OpItem(R.drawable.ic_folder, "移入图层组") { showGroupPicker = true }
                }
                OpItem(R.drawable.ic_flip_h, "水平翻转") { vm.flipLayerHorizontal(index) }
                OpItem(R.drawable.ic_flip_v, "垂直翻转") { vm.flipLayerVertical(index) }
                OpItem(R.drawable.ic_merge_down, "向下合并图层", enabled = !isBg && index > 0) { vm.mergeDown(index); onBack() }
                OpItem(R.drawable.ic_eye, "独显此图层") { vm.soloLayer(index) }
                OpItem(R.drawable.ic_select, "从图层创建选区") { vm.selectionFromLayer(index) }
                OpToggle(R.drawable.ic_lock, "锁定图层", layer?.locked == true || isBg, enabled = !isBg) {
                    vm.setLayerLocked(index, !(layer?.locked == true))
                }
                OpToggle(R.drawable.ic_grid, "锁定透明度", layer?.alphaLocked == true, enabled = !isBg) {
                    vm.setLayerAlphaLocked(index, !(layer?.alphaLocked == true))
                }
                OpToggle(R.drawable.ic_clip, "继承透明度", layer?.clipped == true, enabled = !isBg) {
                    vm.setLayerClipped(index, !(layer?.clipped == true))
                }
                OpItem(R.drawable.ic_clip, "添加透明度蒙版") { vm.addMaskToLayer(index, 0) }
                OpItem(R.drawable.ic_sliders, "添加滤镜蒙版") { vm.addMaskToLayer(index, 1) }
                OpItem(R.drawable.ic_fill, "栅格化为普通图层") { vm.rasterizeLayer(index) }
                OpItem(R.drawable.ic_sliders, "滤镜与颜色调整") { onOpenFilters() }
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
            tint = if (enabled) Morandi.icon else Morandi.subText.copy(alpha = 0.35f),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text,
            color = if (enabled) Morandi.text else Morandi.subText.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
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
// Filters sub page (HuaShijie Pro style list matching user screenshot)
// ---------------------------------------------------------------------------

data class FilterItemDef(
    val id: Int,
    val name: String,
    val hasSliders: Boolean
)

@Composable
private fun FiltersPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
    onSelectFilter: (Int, String) -> Unit,
) {
    val filters = remember {
        listOf(
            FilterItemDef(0, "色相/饱和度/明度/对比度", true),
            FilterItemDef(1, "色彩平衡", true),
            FilterItemDef(2, "高斯模糊", true),
            FilterItemDef(3, "动感模糊", true),
            FilterItemDef(4, "锐化", true),
            FilterItemDef(5, "马赛克", true),
            FilterItemDef(6, "反相", false),
            FilterItemDef(7, "亮度转透明度", false),
            FilterItemDef(8, "查找边缘", false),
            FilterItemDef(9, "浮雕", false),
            FilterItemDef(10, "杂色", true),
            FilterItemDef(11, "色散", true),
            FilterItemDef(12, "灰度", false),
        )
    }

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
        Column(
            modifier = Modifier
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState())
        ) {
            filters.forEach { item ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .noRippleClickable {
                                onSelectFilter(item.id, item.name)
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.name, color = Morandi.text, fontSize = 13.sp)
                    Icon(
                        painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = Morandi.subText.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(Morandi.border.copy(alpha = 0.4f)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filter Adjust Page (HuaShijie Pro slider controls matching user screenshot)
// ---------------------------------------------------------------------------

@Composable
private fun FilterAdjustPage(
    vm: PaintViewModel,
    index: Int,
    filterId: Int,
    filterName: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var isPreview by remember { mutableStateOf(true) }

    // Filter params
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var bright by remember { mutableFloatStateOf(1f) }
    var contrast by remember { mutableFloatStateOf(1f) }

    var cr by remember { mutableFloatStateOf(0f) }
    var mg by remember { mutableFloatStateOf(0f) }
    var yb by remember { mutableFloatStateOf(0f) }

    var blurRadius by remember { mutableFloatStateOf(8f) }
    var motionAngle by remember { mutableFloatStateOf(0f) }
    var motionDist by remember { mutableFloatStateOf(12f) }
    var sharpenAmt by remember { mutableFloatStateOf(1.0f) }
    var mosaicSize by remember { mutableFloatStateOf(10f) }
    var noiseAmt by remember { mutableFloatStateOf(20f) }
    var glitchOffset by remember { mutableFloatStateOf(8f) }

    fun sendPreview() {
        if (!isPreview) return
        when (filterId) {
            0 -> vm.applyFilterPreview(index, 0, hue.toDouble(), sat.toDouble(), bright.toDouble(), contrast.toDouble())
            1 -> vm.applyFilterPreview(index, 1, cr.toDouble(), mg.toDouble(), yb.toDouble(), 0.0)
            2 -> vm.applyFilterPreview(index, 2, blurRadius.toDouble(), 0.0, 0.0, 0.0)
            3 -> vm.applyFilterPreview(index, 3, motionAngle.toDouble(), motionDist.toDouble(), 0.0, 0.0)
            4 -> vm.applyFilterPreview(index, 4, sharpenAmt.toDouble(), 0.0, 0.0, 0.0)
            5 -> vm.applyFilterPreview(index, 5, mosaicSize.toDouble(), 0.0, 0.0, 0.0)
            6 -> vm.applyFilterPreview(index, 6, 0.0, 0.0, 0.0, 0.0)
            7 -> vm.applyFilterPreview(index, 7, 0.0, 0.0, 0.0, 0.0)
            8 -> vm.applyFilterPreview(index, 8, 0.0, 0.0, 0.0, 0.0)
            9 -> vm.applyFilterPreview(index, 9, 0.0, 0.0, 0.0, 0.0)
            10 -> vm.applyFilterPreview(index, 10, noiseAmt.toDouble(), 0.0, 0.0, 0.0)
            11 -> vm.applyFilterPreview(index, 11, glitchOffset.toDouble(), 0.0, 0.0, 0.0)
            12 -> vm.applyFilter(index, 0)
        }
    }

    LaunchedEffect(Unit) {
        vm.beginFilterPreview(index)
        sendPreview()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // Header with Filter Title & Action Icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = filterName,
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Eye (Preview Toggle)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isPreview) Morandi.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .noRippleClickable {
                            isPreview = !isPreview
                            if (isPreview) sendPreview()
                            else vm.cancelFilter(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(if (isPreview) R.drawable.ic_eye else R.drawable.ic_eye_off),
                        contentDescription = "预览",
                        tint = if (isPreview) Morandi.accent else Morandi.subText,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Reset (↺)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noRippleClickable {
                            hue = 0f; sat = 1f; bright = 1f; contrast = 1f
                            cr = 0f; mg = 0f; yb = 0f
                            blurRadius = 8f; motionAngle = 0f; motionDist = 12f
                            sharpenAmt = 1.0f; mosaicSize = 10f; noiseAmt = 20f; glitchOffset = 8f
                            sendPreview()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = "重置",
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Cancel (✕)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noRippleClickable {
                            vm.cancelFilter(index)
                            onBack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_x),
                        contentDescription = "取消",
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Confirm (✓)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent)
                        .noRippleClickable {
                            vm.commitFilter(index, filterName)
                            onDone()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = "应用",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Sliders content based on filter type
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (filterId) {
                0 -> { // HSBC
                    FilterSliderRow(
                        label = "色相",
                        value = hue,
                        valueRange = -180f..180f,
                        valueText = "${hue.roundToInt()}",
                        gradient = Brush.horizontalGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                        ),
                        onValue = { hue = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "饱和度",
                        value = sat,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", sat),
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF888888), Color(0xFFFF4444))),
                        onValue = { sat = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "明度",
                        value = bright,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", bright),
                        gradient = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                        onValue = { bright = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "对比度",
                        value = contrast,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", contrast),
                        onValue = { contrast = it; sendPreview() }
                    )
                }
                1 -> { // Color Balance
                    FilterSliderRow(
                        label = "青 - 红",
                        value = cr,
                        valueRange = -100f..100f,
                        valueText = "${cr.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Cyan, Color.Red)),
                        onValue = { cr = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "洋红 - 绿",
                        value = mg,
                        valueRange = -100f..100f,
                        valueText = "${mg.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Magenta, Color.Green)),
                        onValue = { mg = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "黄 - 蓝",
                        value = yb,
                        valueRange = -100f..100f,
                        valueText = "${yb.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Yellow, Color.Blue)),
                        onValue = { yb = it; sendPreview() }
                    )
                }
                2 -> { // Gaussian Blur
                    FilterSliderRow(
                        label = "模糊半径",
                        value = blurRadius,
                        valueRange = 1f..50f,
                        valueText = "${blurRadius.roundToInt()} px",
                        onValue = { blurRadius = it; sendPreview() }
                    )
                }
                3 -> { // Motion Blur
                    FilterSliderRow(
                        label = "模糊角度",
                        value = motionAngle,
                        valueRange = 0f..360f,
                        valueText = "${motionAngle.roundToInt()}°",
                        onValue = { motionAngle = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "模糊距离",
                        value = motionDist,
                        valueRange = 1f..50f,
                        valueText = "${motionDist.roundToInt()} px",
                        onValue = { motionDist = it; sendPreview() }
                    )
                }
                4 -> { // Sharpen
                    FilterSliderRow(
                        label = "锐化强度",
                        value = sharpenAmt,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", sharpenAmt),
                        onValue = { sharpenAmt = it; sendPreview() }
                    )
                }
                5 -> { // Mosaic
                    FilterSliderRow(
                        label = "像素大小",
                        value = mosaicSize,
                        valueRange = 2f..64f,
                        valueText = "${mosaicSize.roundToInt()} px",
                        onValue = { mosaicSize = it; sendPreview() }
                    )
                }
                10 -> { // Noise
                    FilterSliderRow(
                        label = "杂色数量",
                        value = noiseAmt,
                        valueRange = 1f..100f,
                        valueText = "${noiseAmt.roundToInt()}",
                        onValue = { noiseAmt = it; sendPreview() }
                    )
                }
                11 -> { // Glitch
                    FilterSliderRow(
                        label = "色散偏移",
                        value = glitchOffset,
                        valueRange = 1f..40f,
                        valueText = "${glitchOffset.roundToInt()} px",
                        onValue = { glitchOffset = it; sendPreview() }
                    )
                }
                else -> {
                    Text(
                        text = "此滤镜已实时应用至图层预览，点击右上角 ✓ 确认应用",
                        color = Morandi.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    gradient: Brush? = null,
    onValue: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Morandi.text, fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.panelHi)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(valueText, color = Morandi.subText, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (gradient != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(gradient)
                )
            }
            val normVal = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            ReSlider(
                value = normVal.coerceIn(0f, 1f),
                onValue = {
                    val realVal = valueRange.start + it * (valueRange.endInclusive - valueRange.start)
                    onValue(realVal)
                },
                modifier = Modifier.fillMaxWidth()
            )
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
