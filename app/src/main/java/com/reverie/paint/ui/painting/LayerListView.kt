package com.reverie.paint.ui.painting

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
private val rowHeight = 48.dp

@Composable
internal fun LayerListView(
    vm: PaintViewModel,
    onOpenDetail: (Int) -> Unit,
    onOpenFilters: (Int) -> Unit,
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
                "RELEASE pending=$pendingOrder real=${displayRows.map { it.name }}",
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
                onClick = { vm.addLayer() },
            )
            TopIcon(
                resId = R.drawable.ic_folder,
                desc = "添加图层组",
                onClick = { vm.addGroupLayer() },
            )
            Box {
                TopIcon(
                    resId = R.drawable.ic_layers,
                    desc = "更多图层类型",
                    active = showNewLayerMenu,
                    onClick = { showNewLayerMenu = true },
                )
                DropdownMenu(
                    expanded = showNewLayerMenu,
                    onDismissRequest = { showNewLayerMenu = false },
                    modifier = Modifier.background(Morandi.panel).border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
                ) {
                    DropdownMenuItem(
                        text = { Text("填充图层", color = Morandi.text, fontSize = 13.sp) },
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
                            vm.addFillLayer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("滤镜图层", color = Morandi.text, fontSize = 13.sp) },
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
                            vm.addFilterLayer(onOpenFilters)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("盖印可见图层", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_layers),
                                null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            vm.stampVisibleLayers()
                            showNewLayerMenu = false
                        },
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
                },
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
                },
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
                },
            )
            TopIcon(
                resId = R.drawable.ic_merge_down,
                desc = "向下合并",
                enabled = selectedIndex > 0 && !isBg,
                onClick = {
                    if (selectedIndex > 0 && !isBg) {
                        vm.mergeDown(selectedIndex)
                    }
                },
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
                                        if (settling && settleTo != null) {
                                            settleAnim.value
                                        } else {
                                            dragFingerY - listTop - rowPx / 2f
                                        }
                                    IntOffset(0, y.roundToInt())
                                }.fillMaxWidth()
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
