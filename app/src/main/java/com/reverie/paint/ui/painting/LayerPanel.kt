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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateListOf
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

    val isFilterAdjust = view is LayerView.FilterAdjust

    Box(
        modifier =
            if (isFilterAdjust) {
                modifier.fillMaxSize().background(Color.Transparent)
            } else {
                modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .noRippleClickable(onClose)
            },
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
    var collapsedGroupNames by remember { mutableStateOf(setOf<String>()) }
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
        remember(vm.layers, collapsedGroupNames, draggingFrom, dragTargetIdx, pendingOrder) {
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
            val frozen = displayRows.toMutableList()
            val fi = frozen.indexOfFirst { it.index == from }
            if (fi >= 0) {
                val item = frozen.removeAt(fi)
                frozen.add(insert.coerceIn(0, frozen.size), item)
            }
            pendingOrder = frozen.map { it.name }

            val groupDrop = over != null && over.second == DropMode.OnGroup
            if (groupDrop) {
                vm.moveLayerToGroup(from, over.first)
            } else {
                val listWithoutFrom = displayRows.filter { it.index != from }
                if (listWithoutFrom.isNotEmpty()) {
                    if (insert == 0) {
                        val target = listWithoutFrom.first().index
                        vm.moveLayerRelative(from, target, placeAbove = true)
                    } else if (insert >= listWithoutFrom.size) {
                        val target = listWithoutFrom.last().index
                        vm.moveLayerRelative(from, target, placeAbove = false)
                    } else {
                        val prevItem = listWithoutFrom[insert - 1]
                        val nextItem = listWithoutFrom[insert]
                        if (prevItem.depth > nextItem.depth) {
                            vm.moveLayerRelative(from, prevItem.index, placeAbove = false)
                        } else {
                            vm.moveLayerRelative(from, nextItem.index, placeAbove = true)
                        }
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
                        collapsed = layer.name in collapsedGroupNames,
                        onToggleCollapse = {
                            revealedIndex = null
                            collapsedGroupNames =
                                if (layer.name in collapsedGroupNames) collapsedGroupNames - layer.name
                                else collapsedGroupNames + layer.name
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
                            collapsed = dragged.name in collapsedGroupNames,
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
                .pointerInput(index, isBg) {
                    if (isBg) return@pointerInput
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
    if (layer?.isBackground == true) {
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
                Text(
                    "背景图层设置",
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("背景颜色", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)

                val bgColors = listOf(
                    0xFFFFFFFF.toInt() to "纯白",
                    0xFFFFFDF5.toInt() to "暖白",
                    0xFFF5F3EF.toInt() to "羊皮纸",
                    0xFFEAE6E1.toInt() to "暖灰",
                    0xFFDCE2E6.toInt() to "冷灰",
                    0xFF2B2D30.toInt() to "炭黑",
                    0xFF000000.toInt() to "纯黑",
                    0xFFFDF0ED.toInt() to "樱粉",
                    0xFFEFF5EC.toInt() to "灰绿",
                    0xFFEDF3F8.toInt() to "天青",
                    0xFFF7F2E7.toInt() to "杏仁",
                    0xFFECEAF2.toInt() to "淡紫",
                )

                var currentColor by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
                var hVal by remember { mutableFloatStateOf(0f) }
                var sVal by remember { mutableFloatStateOf(0f) }
                var vVal by remember { mutableFloatStateOf(1f) }
                var lastUpdateNs by remember { mutableLongStateOf(0L) }

                fun updateHsv(h: Float, s: Float, v: Float, commit: Boolean = false) {
                    hVal = h.coerceIn(0f, 360f)
                    sVal = s.coerceIn(0f, 1f)
                    vVal = v.coerceIn(0f, 1f)
                    val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(hVal, sVal, vVal))
                    currentColor = colorInt
                    if (commit) {
                        vm.setBackgroundColor(colorInt, commit = true)
                    } else {
                        val now = System.nanoTime()
                        if (now - lastUpdateNs > 20_000_000L) {
                            lastUpdateNs = now
                            vm.setBackgroundColor(colorInt, commit = false)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bgColors.chunked(6).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            rowColors.forEach { (cInt, _) ->
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(cInt))
                                        .border(
                                            width = if (currentColor == cInt) 2.5.dp else 1.dp,
                                            color = if (currentColor == cInt) Morandi.accent else Morandi.border,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            val hsv = FloatArray(3)
                                            android.graphics.Color.colorToHSV(cInt, hsv)
                                            updateHsv(hsv[0], hsv[1], hsv[2], commit = true)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentColor == cInt) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (cInt == 0xFFFFFFFF.toInt() || cInt == 0xFFFFFDF5.toInt()) Morandi.accent else Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live preview and hex info bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(currentColor))
                                .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                        )
                        Text(
                            String.format("#%06X", 0xFFFFFF and currentColor),
                            color = Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        "H:${hVal.roundToInt()}° S:${(sVal * 100).roundToInt()}% V:${(vVal * 100).roundToInt()}%",
                        color = Morandi.subText,
                        fontSize = 11.sp
                    )
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))

                Text("自定义 HSV 色彩调节", color = Morandi.subText, fontSize = 12.sp)

                // H (色相)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("H", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    ReSlider(
                        value = (hVal / 360f).coerceIn(0f, 1f),
                        onValue = {
                            updateHsv(it * 360f, sVal, vVal, commit = false)
                        },
                        onRelease = {
                            updateHsv(hVal, sVal, vVal, commit = true)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text("${hVal.roundToInt()}°", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                }

                // S (饱和度)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("S", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    ReSlider(
                        value = sVal.coerceIn(0f, 1f),
                        onValue = {
                            updateHsv(hVal, it, vVal, commit = false)
                        },
                        onRelease = {
                            updateHsv(hVal, sVal, vVal, commit = true)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(sVal * 100).roundToInt()}%", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                }

                // V (明度)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("V", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    ReSlider(
                        value = vVal.coerceIn(0f, 1f),
                        onValue = {
                            updateHsv(hVal, sVal, it, commit = false)
                        },
                        onRelease = {
                            updateHsv(hVal, sVal, vVal, commit = true)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(vVal * 100).roundToInt()}%", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                }
            }
        }
        return
    }

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
    val hasSliders: Boolean,
    val desc: String = ""
)

data class FilterCategoryDef(
    val id: String,
    val name: String,
    val iconRes: Int,
    val filters: List<FilterItemDef>
)

@Composable
private fun FiltersPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
    onSelectFilter: (Int, String) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<FilterCategoryDef?>(null) }

    val categories = remember {
        listOf(
            FilterCategoryDef(
                id = "color",
                name = "调整图像/颜色",
                iconRes = R.drawable.ic_image_adjust,
                filters = listOf(
                    FilterItemDef(13, "曲线 (颜色调整)", true, "交互式多通道RGB调色曲线"),
                    FilterItemDef(14, "色阶", true, "黑场、白场与中间调伽马调整"),
                    FilterItemDef(0, "HSV 色相/饱和度/明度/对比度", true, "色相偏移与明暗饱和度"),
                    FilterItemDef(1, "色彩平衡", true, "青红、洋绿、黄蓝平衡"),
                    FilterItemDef(15, "色温与色调", true, "冷暖色温与绿-洋红色调"),
                    FilterItemDef(24, "曝光度与伽马", true, "线性曝光值与伽马曲线"),
                    FilterItemDef(16, "阈值 (黑白二值化)", true, "明度门限黑白分割"),
                    FilterItemDef(12, "去色 (灰度化)", false, "转为黑白灰度图"),
                    FilterItemDef(6, "反相 (底片效果)", false, "反转通道颜色"),
                )
            ),
            FilterCategoryDef(
                id = "artistic",
                name = "艺术效果",
                iconRes = R.drawable.ic_brush,
                filters = listOf(
                    FilterItemDef(21, "油画效果 (Kuwahara)", true, "基于局部方差的写生油画质感"),
                    FilterItemDef(5, "马赛克 / 像素化", true, "网格块状像素化"),
                    FilterItemDef(17, "色调分离", true, "色彩阶数离散量化"),
                    FilterItemDef(10, "杂色 / 噪点", true, "胶片颗粒感噪点添加"),
                    FilterItemDef(23, "半色调网点", true, "印刷漫画网点风格"),
                )
            ),
            FilterCategoryDef(
                id = "blur",
                name = "模糊",
                iconRes = R.drawable.ic_smudge,
                filters = listOf(
                    FilterItemDef(2, "高斯模糊", true, "Alpha加权多核高斯平滑"),
                    FilterItemDef(3, "动感模糊", true, "任意角度线性积分模糊"),
                    FilterItemDef(22, "径向/缩放模糊", true, "中心辐射聚焦模糊"),
                    FilterItemDef(26, "散焦模糊 (镜头光圈)", true, "圆形弥散斑镜头虚化"),
                )
            ),
            FilterCategoryDef(
                id = "enhance",
                name = "图像增强",
                iconRes = R.drawable.ic_magicwand,
                filters = listOf(
                    FilterItemDef(4, "锐化", true, "拉普拉斯边缘对比度锐化"),
                    FilterItemDef(18, "泛光 / 辉光 (Bloom)", true, "高光溢出扩散光晕"),
                    FilterItemDef(19, "投影效果 (Drop Shadow)", true, "自定义角度与模糊阴影"),
                    FilterItemDef(8, "查找边缘 (Sobel)", false, "轮廓边缘检测提取"),
                    FilterItemDef(25, "边缘霓虹发光", true, "边缘高亮荧光发光"),
                    FilterItemDef(9, "浮雕效果", false, "立体凹凸光影浮雕"),
                )
            ),
            FilterCategoryDef(
                id = "map",
                name = "映射与通道",
                iconRes = R.drawable.ic_gradient,
                filters = listOf(
                    FilterItemDef(30, "渐变映射", true, "灰度映射至多色阶调调色板"),
                    FilterItemDef(20, "亮度转不透明度", true, "明度保留色彩并调制Alpha通道"),
                    FilterItemDef(7, "亮度转透明度 (提取线稿)", false, "纯黑线稿透明化提取"),
                    FilterItemDef(11, "色散错位 (Glitch)", true, "红蓝RGB通道错位色散"),
                )
            ),
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top Header
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
                        .noRippleClickable {
                            if (selectedCategory != null) {
                                selectedCategory = null
                            } else {
                                onBack()
                            }
                        },
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
                text = selectedCategory?.name ?: "滤镜库",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        AnimatedContent(
            targetState = selectedCategory,
            label = "FilterNav"
        ) { category ->
            if (category == null) {
                // Category List (Level 1)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable { selectedCategory = cat }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.accent.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(cat.iconRes),
                                        contentDescription = null,
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column {
                                    Text(cat.name, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${cat.filters.size} 个滤镜", color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
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
            } else {
                // Category Filters (Level 2)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    category.filters.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable {
                                    onSelectFilter(item.id, item.name)
                                }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (item.desc.isNotEmpty()) {
                                    Text(item.desc, color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
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
    }
}

// ---------------------------------------------------------------------------
// Real Interactive 2D Curves Graph Component
// ---------------------------------------------------------------------------

private fun calculateMonotoneCubicSplineLUT(points: List<Offset>): ByteArray {
    val sorted = points.sortedBy { it.x }.distinctBy { it.x.toInt() }
    val lut = ByteArray(256)
    if (sorted.isEmpty()) {
        for (i in 0..255) lut[i] = i.toByte()
        return lut
    }
    if (sorted.size == 1) {
        val y = sorted[0].y.coerceIn(0f, 255f).toInt().toByte()
        for (i in 0..255) lut[i] = y
        return lut
    }
    val n = sorted.size
    val x = sorted.map { it.x.coerceIn(0f, 255f) }
    val y = sorted.map { it.y.coerceIn(0f, 255f) }
    val d = FloatArray(n - 1)
    val m = FloatArray(n)
    for (i in 0 until n - 1) {
        val dx = x[i + 1] - x[i]
        d[i] = if (dx != 0f) (y[i + 1] - y[i]) / dx else 0f
    }
    m[0] = d[0]
    for (i in 1 until n - 1) {
        m[i] = (d[i - 1] + d[i]) * 0.5f
    }
    m[n - 1] = d[n - 2]
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / kotlin.math.sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
    }
    var seg = 0
    for (i in 0..255) {
        val curX = i.toFloat()
        if (curX <= x[0]) {
            lut[i] = y[0].toInt().coerceIn(0, 255).toByte()
            continue
        }
        if (curX >= x[n - 1]) {
            lut[i] = y[n - 1].toInt().coerceIn(0, 255).toByte()
            continue
        }
        while (seg < n - 2 && curX > x[seg + 1]) {
            seg++
        }
        val h = x[seg + 1] - x[seg]
        val t = if (h != 0f) (curX - x[seg]) / h else 0f
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val curY = h00 * y[seg] + h10 * h * m[seg] + h01 * y[seg + 1] + h11 * h * m[seg + 1]
        lut[i] = curY.toInt().coerceIn(0, 255).toByte()
    }
    return lut
}

@Composable
private fun RealCurvesGraph(
    channelPoints: SnapshotStateMap<Int, MutableList<Offset>>,
    activeChannel: Int,
    onCurveChanged: () -> Unit
) {
    val points = channelPoints.getOrPut(activeChannel) {
        mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f))
    }
    var selectedIndex by remember { mutableIntStateOf(-1) }

    val channelColor = when (activeChannel) {
        1 -> Color(0xFFFF5252) // Red
        2 -> Color(0xFF4CAF50) // Green
        3 -> Color(0xFF448AFF) // Blue
        else -> Morandi.accent // RGB / Master
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                .pointerInput(activeChannel) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val px = (tapOffset.x / w) * 255f
                            val py = (1f - tapOffset.y / h) * 255f
                            val closeIdx = points.indexOfFirst {
                                val dx = (it.x / 255f) * w - tapOffset.x
                                val dy = ((1f - it.y / 255f) * h) - tapOffset.y
                                (dx * dx + dy * dy) <= 32f * 32f
                            }
                            if (closeIdx > 0 && closeIdx < points.size - 1) {
                                points.removeAt(closeIdx)
                                selectedIndex = -1
                                onCurveChanged()
                            }
                        },
                        onTap = { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val px = ((tapOffset.x / w) * 255f).coerceIn(0f, 255f)
                            val py = ((1f - tapOffset.y / h) * 255f).coerceIn(0f, 255f)
                            val closeIdx = points.indexOfFirst {
                                val dx = (it.x / 255f) * w - tapOffset.x
                                val dy = ((1f - it.y / 255f) * h) - tapOffset.y
                                (dx * dx + dy * dy) <= 24f * 24f
                            }
                            if (closeIdx >= 0) {
                                selectedIndex = closeIdx
                            } else {
                                points.add(Offset(px, py))
                                points.sortBy { it.x }
                                selectedIndex = points.indexOfFirst { it.x == px && it.y == py }
                                onCurveChanged()
                            }
                        }
                    )
                }
                .pointerInput(activeChannel) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val closeIdx = points.indexOfFirst {
                                val dx = (it.x / 255f) * w - startOffset.x
                                val dy = ((1f - it.y / 255f) * h) - startOffset.y
                                (dx * dx + dy * dy) <= 30f * 30f
                            }
                            selectedIndex = closeIdx
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (selectedIndex >= 0 && selectedIndex < points.size) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val cur = points[selectedIndex]
                                val newX = if (selectedIndex == 0) 0f
                                           else if (selectedIndex == points.size - 1) 255f
                                           else (cur.x + (dragAmount.x / w) * 255f).coerceIn(0f, 255f)
                                val newY = (cur.y - (dragAmount.y / h) * 255f).coerceIn(0f, 255f)
                                points[selectedIndex] = Offset(newX, newY)
                                onCurveChanged()
                            }
                        },
                        onDragEnd = {
                            points.sortBy { it.x }
                            onCurveChanged()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // 4x4 Grid
                for (i in 1..3) {
                    val gx = (w / 4f) * i
                    val gy = (h / 4f) * i
                    drawLine(Morandi.border.copy(alpha = 0.6f), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                    drawLine(Morandi.border.copy(alpha = 0.6f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }

                // Reference Diagonal Line (y = x)
                drawLine(
                    color = Morandi.subText.copy(alpha = 0.3f),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )

                // Render Monotone Spline Curve
                val lut = calculateMonotoneCubicSplineLUT(points)
                val curvePath = Path()
                val fillPath = Path()
                fillPath.moveTo(0f, h)

                for (i in 0..255) {
                    val px = (i / 255f) * w
                    val py = (1f - (lut[i].toInt() and 0xFF) / 255f) * h
                    if (i == 0) {
                        curvePath.moveTo(px, py)
                        fillPath.lineTo(px, py)
                    } else {
                        curvePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                // Area gradient fill
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(channelColor.copy(alpha = 0.25f), channelColor.copy(alpha = 0.02f)),
                        startY = 0f,
                        endY = h
                    )
                )

                // Curve stroke line
                drawPath(
                    path = curvePath,
                    color = channelColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Control Points
                points.forEachIndexed { idx, pt ->
                    val cx = (pt.x / 255f) * w
                    val cy = (1f - pt.y / 255f) * h
                    val isSel = (idx == selectedIndex)

                    // Point Shadow/Outer Ring
                    drawCircle(
                        color = if (isSel) channelColor else Morandi.bg,
                        radius = if (isSel) 7.dp.toPx() else 5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = if (isSel) Color.White else channelColor,
                        radius = if (isSel) 4.5.dp.toPx() else 3.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Coordinate Readout & Point Info
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selPt = if (selectedIndex in points.indices) points[selectedIndex] else null
            if (selPt != null) {
                Text(
                    "输入: ${selPt.x.roundToInt()}  输出: ${selPt.y.roundToInt()}",
                    color = Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text("点击添加控制点，双击控制点删除", color = Morandi.subText, fontSize = 11.sp)
            }

            Text(
                "重置此通道",
                color = Morandi.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.noRippleClickable {
                    points.clear()
                    points.addAll(listOf(Offset(0f, 0f), Offset(255f, 255f)))
                    selectedIndex = -1
                    onCurveChanged()
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Gradient Map Presets & Component
// ---------------------------------------------------------------------------

private val GRADIENT_PRESETS = listOf(
    "日落暖金" to listOf(
        0.0f to Color(0xFF2C0B38),
        0.35f to Color(0xFFB82E55),
        0.7f to Color(0xFFE88A35),
        1.0f to Color(0xFFFFF6A5)
    ),
    "赛博霓虹" to listOf(
        0.0f to Color(0xFF0F052A),
        0.4f to Color(0xFF8A148D),
        0.8f to Color(0xFF00E5FF),
        1.0f to Color(0xFFFFFFFF)
    ),
    "深海幽蓝" to listOf(
        0.0f to Color(0xFF061426),
        0.45f to Color(0xFF0A4F6B),
        0.8f to Color(0xFF26A69A),
        1.0f to Color(0xFFE0F7FA)
    ),
    "复古怀旧" to listOf(
        0.0f to Color(0xFF2E1C0C),
        0.4f to Color(0xFF704E2E),
        0.75f to Color(0xFFC4A47C),
        1.0f to Color(0xFFFBF4E8)
    ),
    "烈焰熔岩" to listOf(
        0.0f to Color(0xFF100000),
        0.3f to Color(0xFF800000),
        0.65f to Color(0xFFFF4500),
        1.0f to Color(0xFFFFFF80)
    ),
    "梦幻粉紫" to listOf(
        0.0f to Color(0xFF2D1436),
        0.45f to Color(0xFF8B5E83),
        0.8f to Color(0xFFE8B4B8),
        1.0f to Color(0xFFFFF0F5)
    ),
    "森系翠绿" to listOf(
        0.0f to Color(0xFF0A2218),
        0.4f to Color(0xFF1B5E3C),
        0.75f to Color(0xFF7CB342),
        1.0f to Color(0xFFF1F8E9)
    ),
    "黑白胶片" to listOf(
        0.0f to Color(0xFF000000),
        0.5f to Color(0xFF808080),
        1.0f to Color(0xFFFFFFFF)
    ),
)

private fun generateGradientLUT(stops: List<Pair<Float, Color>>, reverse: Boolean): IntArray {
    val sorted = stops.sortedBy { it.first }
    val lut = IntArray(256)
    if (sorted.isEmpty()) {
        for (i in 0..255) lut[i] = (0xFF shl 24) or (i shl 16) or (i shl 8) or i
        return lut
    }
    for (i in 0..255) {
        val t = if (reverse) (255 - i) / 255f else i / 255f
        val col = when {
            t <= sorted.first().first -> sorted.first().second
            t >= sorted.last().first -> sorted.last().second
            else -> {
                val idx = sorted.indexOfFirst { it.first >= t }.coerceAtLeast(1)
                val s0 = sorted[idx - 1]
                val s1 = sorted[idx]
                val span = s1.first - s0.first
                val factor = if (span > 0f) (t - s0.first) / span else 0f
                Color(
                    red = s0.second.red + factor * (s1.second.red - s0.second.red),
                    green = s0.second.green + factor * (s1.second.green - s0.second.green),
                    blue = s0.second.blue + factor * (s1.second.blue - s0.second.blue),
                    alpha = s0.second.alpha + factor * (s1.second.alpha - s0.second.alpha),
                )
            }
        }
        val a = (col.alpha * 255).toInt().coerceIn(0, 255)
        val r = (col.red * 255).toInt().coerceIn(0, 255)
        val g = (col.green * 255).toInt().coerceIn(0, 255)
        val b = (col.blue * 255).toInt().coerceIn(0, 255)
        lut[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return lut
}

// ---------------------------------------------------------------------------
// Filter Adjust Page (All Filter Controls)
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

    // Curves state: channel -> list of control points
    val curveChannels = remember {
        mutableStateMapOf<Int, MutableList<Offset>>(
            0 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Master RGB
            1 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Red
            2 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Green
            3 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f))  // Blue
        )
    }
    var activeCurveChannel by remember { mutableIntStateOf(0) }

    // Gradient Map state
    var selectedGradientPreset by remember { mutableIntStateOf(0) }
    var reverseGradient by remember { mutableStateOf(false) }

    // Standard Sliders
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

    var levelBlack by remember { mutableFloatStateOf(0f) }
    var levelWhite by remember { mutableFloatStateOf(255f) }
    var levelGamma by remember { mutableFloatStateOf(1.0f) }

    var tempVal by remember { mutableFloatStateOf(0f) }
    var tintVal by remember { mutableFloatStateOf(0f) }
    var thresholdVal by remember { mutableFloatStateOf(128f) }
    var posterizeLevels by remember { mutableFloatStateOf(4f) }

    var bloomThresh by remember { mutableFloatStateOf(180f) }
    var bloomRadius by remember { mutableFloatStateOf(15f) }
    var bloomIntensity by remember { mutableFloatStateOf(1.2f) }

    var shadowAngle by remember { mutableFloatStateOf(45f) }
    var shadowDist by remember { mutableFloatStateOf(12f) }
    var shadowRadius by remember { mutableFloatStateOf(10f) }
    var shadowOpacity by remember { mutableFloatStateOf(0.6f) }

    var oilRadius by remember { mutableFloatStateOf(3f) }
    var radialBlurAmt by remember { mutableFloatStateOf(15f) }
    var halftoneDotSize by remember { mutableFloatStateOf(10f) }
    var exposureVal by remember { mutableFloatStateOf(0f) }
    var exposureGamma by remember { mutableFloatStateOf(1.0f) }
    var edgeGlowStrength by remember { mutableFloatStateOf(2.0f) }
    var defocusRadius by remember { mutableFloatStateOf(8f) }
    var lumOpacityInvert by remember { mutableStateOf(false) }

    fun sendCurvesPreview() {
        if (!isPreview) return
        val lutMaster = calculateMonotoneCubicSplineLUT(curveChannels[0] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutR = calculateMonotoneCubicSplineLUT(curveChannels[1] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutG = calculateMonotoneCubicSplineLUT(curveChannels[2] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutB = calculateMonotoneCubicSplineLUT(curveChannels[3] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))

        val finalR = ByteArray(256)
        val finalG = ByteArray(256)
        val finalB = ByteArray(256)
        for (i in 0..255) {
            val mVal = lutMaster[i].toInt() and 0xFF
            finalR[i] = lutR[mVal]
            finalG[i] = lutG[mVal]
            finalB[i] = lutB[mVal]
        }
        vm.applyCurvesLUTPreview(index, finalR, finalG, finalB)
    }

    fun sendGradientMapPreview() {
        if (!isPreview) return
        val stops = GRADIENT_PRESETS[selectedGradientPreset.coerceIn(0, GRADIENT_PRESETS.size - 1)].second
        val lut = generateGradientLUT(stops, reverseGradient)
        vm.applyGradientMapPreview(index, lut)
    }

    fun sendPreview() {
        if (!isPreview) return
        when (filterId) {
            13 -> sendCurvesPreview()
            30 -> sendGradientMapPreview()
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
            14 -> vm.applyFilterPreview(index, 14, levelBlack.toDouble(), levelWhite.toDouble(), levelGamma.toDouble(), 0.0)
            15 -> vm.applyFilterPreview(index, 15, tempVal.toDouble(), tintVal.toDouble(), 0.0, 0.0)
            16 -> vm.applyFilterPreview(index, 16, thresholdVal.toDouble(), 0.0, 0.0, 0.0)
            17 -> vm.applyFilterPreview(index, 17, posterizeLevels.toDouble(), 0.0, 0.0, 0.0)
            18 -> vm.applyFilterPreview(index, 18, bloomThresh.toDouble(), bloomRadius.toDouble(), bloomIntensity.toDouble(), 0.0)
            19 -> vm.applyFilterPreview(index, 19, shadowAngle.toDouble(), shadowDist.toDouble(), shadowRadius.toDouble(), shadowOpacity.toDouble())
            20 -> vm.applyFilterPreview(index, 20, if (lumOpacityInvert) 1.0 else 0.0, 0.0, 0.0, 0.0)
            21 -> vm.applyFilterPreview(index, 21, oilRadius.toDouble(), 0.0, 0.0, 0.0)
            22 -> vm.applyFilterPreview(index, 22, radialBlurAmt.toDouble(), 0.5, 0.5, 0.0)
            23 -> vm.applyFilterPreview(index, 23, halftoneDotSize.toDouble(), 0.0, 0.0, 0.0)
            24 -> vm.applyFilterPreview(index, 24, exposureVal.toDouble(), exposureGamma.toDouble(), 0.0, 0.0)
            25 -> vm.applyFilterPreview(index, 25, edgeGlowStrength.toDouble(), 0.0, 0.0, 0.0)
            26 -> vm.applyFilterPreview(index, 26, defocusRadius.toDouble(), 0.0, 0.0, 0.0)
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
                            curveChannels.forEach { (_, list) ->
                                list.clear()
                                list.addAll(listOf(Offset(0f, 0f), Offset(255f, 255f)))
                            }
                            selectedGradientPreset = 0
                            reverseGradient = false
                            hue = 0f; sat = 1f; bright = 1f; contrast = 1f
                            cr = 0f; mg = 0f; yb = 0f
                            blurRadius = 8f; motionAngle = 0f; motionDist = 12f
                            sharpenAmt = 1.0f; mosaicSize = 10f; noiseAmt = 20f; glitchOffset = 8f
                            levelBlack = 0f; levelWhite = 255f; levelGamma = 1.0f
                            tempVal = 0f; tintVal = 0f; thresholdVal = 128f; posterizeLevels = 4f
                            bloomThresh = 180f; bloomRadius = 15f; bloomIntensity = 1.2f
                            shadowAngle = 45f; shadowDist = 12f; shadowRadius = 10f; shadowOpacity = 0.6f
                            oilRadius = 3f; radialBlurAmt = 15f; halftoneDotSize = 10f; exposureVal = 0f; exposureGamma = 1.0f
                            edgeGlowStrength = 2.0f; defocusRadius = 8f; lumOpacityInvert = false
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

        // Filter Content Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (filterId) {
                13 -> { // Real 2D Curves Graph
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("RGB" to 0, "红 (R)" to 1, "绿 (G)" to 2, "蓝 (B)" to 3).forEach { (name, ch) ->
                            val isSel = (activeCurveChannel == ch)
                            val chColor = when (ch) {
                                1 -> Color(0xFFFF5252)
                                2 -> Color(0xFF4CAF50)
                                3 -> Color(0xFF448AFF)
                                else -> Morandi.accent
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) chColor.copy(alpha = 0.25f) else Morandi.panelHi)
                                    .border(
                                        1.dp,
                                        if (isSel) chColor else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .noRippleClickable {
                                        activeCurveChannel = ch
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name,
                                    color = if (isSel) chColor else Morandi.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    RealCurvesGraph(
                        channelPoints = curveChannels,
                        activeChannel = activeCurveChannel,
                        onCurveChanged = { sendCurvesPreview() }
                    )
                }
                30 -> { // Gradient Map
                    val curStops = GRADIENT_PRESETS[selectedGradientPreset.coerceIn(0, GRADIENT_PRESETS.size - 1)].second
                    val gradientBrush = remember(selectedGradientPreset, reverseGradient) {
                        val activeStops = if (reverseGradient) curStops.reversed().map { (1f - it.first) to it.second } else curStops
                        Brush.horizontalGradient(activeStops.map { it.second })
                    }

                    // Continuous Gradient Strip
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(gradientBrush)
                            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("反向渐变", color = Morandi.text, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (reverseGradient) Morandi.accent else Morandi.panelHi)
                                .noRippleClickable {
                                    reverseGradient = !reverseGradient
                                    sendGradientMapPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (reverseGradient) "已反转" else "正常",
                                color = if (reverseGradient) Color.White else Morandi.subText,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text("渐变预设", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

                    // Preset Palettes Grid
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GRADIENT_PRESETS.chunked(2).forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowPresets.forEach { (name, stops) ->
                                    val idx = GRADIENT_PRESETS.indexOfFirst { it.first == name }
                                    val isSel = (selectedGradientPreset == idx)
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi)
                                            .border(
                                                1.5.dp,
                                                if (isSel) Morandi.accent else Morandi.border,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .noRippleClickable {
                                                selectedGradientPreset = idx
                                                sendGradientMapPreview()
                                            }
                                            .padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp, 16.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Brush.horizontalGradient(stops.map { it.second }))
                                        )
                                        Text(
                                            name,
                                            color = if (isSel) Morandi.accent else Morandi.text,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
                        valueRange = 1f..100f,
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
                        valueRange = 1f..100f,
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
                14 -> { // Levels
                    FilterSliderRow(
                        label = "输入黑场",
                        value = levelBlack,
                        valueRange = 0f..254f,
                        valueText = "${levelBlack.roundToInt()}",
                        onValue = { levelBlack = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "输入白场",
                        value = levelWhite,
                        valueRange = (levelBlack + 1f)..255f,
                        valueText = "${levelWhite.roundToInt()}",
                        onValue = { levelWhite = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "中间调灰度 (Gamma)",
                        value = levelGamma,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.2f", levelGamma),
                        onValue = { levelGamma = it; sendPreview() }
                    )
                }
                15 -> { // Temperature & Tint
                    FilterSliderRow(
                        label = "色温 (冷 - 暖)",
                        value = tempVal,
                        valueRange = -100f..100f,
                        valueText = "${tempVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF4A90E2), Color(0xFFF5A623))),
                        onValue = { tempVal = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "色调 (绿 - 洋红)",
                        value = tintVal,
                        valueRange = -100f..100f,
                        valueText = "${tintVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF50E3C2), Color(0xFFBD10E0))),
                        onValue = { tintVal = it; sendPreview() }
                    )
                }
                16 -> { // Threshold
                    FilterSliderRow(
                        label = "黑白阈值",
                        value = thresholdVal,
                        valueRange = 1f..255f,
                        valueText = "${thresholdVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                        onValue = { thresholdVal = it; sendPreview() }
                    )
                }
                17 -> { // Posterize
                    FilterSliderRow(
                        label = "色阶分离层数",
                        value = posterizeLevels,
                        valueRange = 2f..32f,
                        valueText = "${posterizeLevels.roundToInt()} 层",
                        onValue = { posterizeLevels = it; sendPreview() }
                    )
                }
                18 -> { // Bloom
                    FilterSliderRow(
                        label = "辉光亮度门限",
                        value = bloomThresh,
                        valueRange = 0f..255f,
                        valueText = "${bloomThresh.roundToInt()}",
                        onValue = { bloomThresh = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "泛光扩散半径",
                        value = bloomRadius,
                        valueRange = 1f..50f,
                        valueText = "${bloomRadius.roundToInt()} px",
                        onValue = { bloomRadius = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "辉光强度",
                        value = bloomIntensity,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", bloomIntensity),
                        onValue = { bloomIntensity = it; sendPreview() }
                    )
                }
                19 -> { // Drop Shadow
                    FilterSliderRow(
                        label = "投影角度",
                        value = shadowAngle,
                        valueRange = 0f..360f,
                        valueText = "${shadowAngle.roundToInt()}°",
                        onValue = { shadowAngle = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "投影距离",
                        value = shadowDist,
                        valueRange = 0f..50f,
                        valueText = "${shadowDist.roundToInt()} px",
                        onValue = { shadowDist = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "阴影模糊半径",
                        value = shadowRadius,
                        valueRange = 1f..40f,
                        valueText = "${shadowRadius.roundToInt()} px",
                        onValue = { shadowRadius = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "阴影不透明度",
                        value = shadowOpacity,
                        valueRange = 0f..1f,
                        valueText = "${(shadowOpacity * 100).roundToInt()}%",
                        onValue = { shadowOpacity = it; sendPreview() }
                    )
                }
                20 -> { // Luminance to Opacity
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("反转亮度关系 (暗部不透明)", color = Morandi.text, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (lumOpacityInvert) Morandi.accent else Morandi.panelHi)
                                .noRippleClickable {
                                    lumOpacityInvert = !lumOpacityInvert
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (lumOpacityInvert) "反转" else "默认",
                                color = if (lumOpacityInvert) Color.White else Morandi.subText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                21 -> { // Oil Paint
                    FilterSliderRow(
                        label = "写生油画半径",
                        value = oilRadius,
                        valueRange = 1f..8f,
                        valueText = "${oilRadius.roundToInt()} px",
                        onValue = { oilRadius = it; sendPreview() }
                    )
                }
                22 -> { // Radial / Zoom Blur
                    FilterSliderRow(
                        label = "聚焦辐射强度",
                        value = radialBlurAmt,
                        valueRange = 1f..50f,
                        valueText = "${radialBlurAmt.roundToInt()}",
                        onValue = { radialBlurAmt = it; sendPreview() }
                    )
                }
                23 -> { // Halftone
                    FilterSliderRow(
                        label = "网点单元大小",
                        value = halftoneDotSize,
                        valueRange = 4f..24f,
                        valueText = "${halftoneDotSize.roundToInt()} px",
                        onValue = { halftoneDotSize = it; sendPreview() }
                    )
                }
                24 -> { // Exposure & Gamma
                    FilterSliderRow(
                        label = "曝光值 (EV)",
                        value = exposureVal,
                        valueRange = -3.0f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%+.1f EV", exposureVal),
                        onValue = { exposureVal = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "伽马校正",
                        value = exposureGamma,
                        valueRange = 0.2f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.2f", exposureGamma),
                        onValue = { exposureGamma = it; sendPreview() }
                    )
                }
                25 -> { // Edge Glow
                    FilterSliderRow(
                        label = "荧光发光强度",
                        value = edgeGlowStrength,
                        valueRange = 1.0f..5.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", edgeGlowStrength),
                        onValue = { edgeGlowStrength = it; sendPreview() }
                    )
                }
                26 -> { // Defocus Blur
                    FilterSliderRow(
                        label = "光圈散焦半径",
                        value = defocusRadius,
                        valueRange = 1f..30f,
                        valueText = "${defocusRadius.roundToInt()} px",
                        onValue = { defocusRadius = it; sendPreview() }
                    )
                }
                else -> {
                    Text(
                        text = "此滤镜已实时应用至图层预览，点击右上角应用按钮确认",
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
