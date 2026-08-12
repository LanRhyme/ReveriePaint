package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
            buildList { collectBlock(0, n, 0, this) }
        }

    fun endDrag() {
        val from = draggingFrom
        val target = dragOver
        draggingFrom = -1
        dragOver = null
        if (from > 0 && target != null && target.first != from) {
            when (target.second) {
                DropMode.Above -> vm.moveLayer(from, target.first)
                DropMode.OnGroup -> vm.moveLayerToGroup(from, target.first)
            }
        }
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

        Column(
            modifier =
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            for (i in displayRows.indices) {
                val layer = displayRows[i]
                key(layer.index) {
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
                        onDragStart = { draggingFrom = layer.index },
                        onDragPosition = { fingerY ->
                            var over: Pair<Int, DropMode>? = null
                            for ((idx, b) in rowBounds) {
                                if (idx == draggingFrom) continue
                                if (fingerY >= b.first && fingerY <= b.second) {
                                    val isGroup = vm.layers.firstOrNull { it.index == idx }?.isGroup == true
                                    val mid0 = b.first + (b.second - b.first) * 0.3f
                                    val mid1 = b.first + (b.second - b.first) * 0.7f
                                    over =
                                        if (isGroup && fingerY >= mid0 && fingerY <= mid1) {
                                            idx to DropMode.OnGroup
                                        } else {
                                            idx to DropMode.Above
                                        }
                                }
                            }
                            dragOver = over
                        },
                        onDragEnd = { endDrag() },
                        dragLineAbove = dragOver?.first == layer.index && dragOver?.second == DropMode.Above,
                        dragOnGroup = dragOver?.first == layer.index && dragOver?.second == DropMode.OnGroup,
                        onClick = {
                            if (layer.index == selectedIndex) {
                                onOpenDetail(layer.index)
                            } else {
                                selectedIndex = layer.index
                                vm.setCurrentLayer(layer.index)
                            }
                        },
                    )
                }
                if (i < displayRows.size - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp)
                            .height(1.dp)
                            .background(Morandi.border.copy(alpha = 0.5f)),
                    )
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
    onDragStart: () -> Unit,
    onDragPosition: (Float) -> Unit,
    onDragEnd: () -> Unit,
    dragLineAbove: Boolean,
    dragOnGroup: Boolean,
    onClick: () -> Unit,
) {
    val index = layer.index
    val isBg = layer.isBackground
    val visible = layer.visible
    var reveal by remember { mutableStateOf(false) }
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 10.dp.roundToPx() }
    val rowHeight = 56.dp
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
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .onGloballyPositioned { c ->
                    rowTop = c.boundsInRoot().top
                    rowBottom = c.boundsInRoot().bottom
                },
    ) {
        // Action drawer: two actions (copy gray / delete red), composed only
        // while revealed so it can never show through a closed row.
        AnimatedVisibility(
            visible = reveal,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(drawerWidth)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_copy, "复制") {
                    vm.copyLayer(index)
                    reveal = false
                }
                DrawerAction(Modifier.weight(1f), Color(0xFFB05552), R.drawable.ic_trash, "删除") {
                    if (!isBg) vm.removeLayer(index)
                    reveal = false
                }
            }
        }

        Row(
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
                    .offset { IntOffset(-(drawerPx * revealFraction).roundToInt(), 0) }
                    .pointerInput(index) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            android.util.Log.d("LayerPanel", "gesture down idx=$index at $startX")
                            val lp = awaitLongPressOrCancellation(down.id)
                            if (lp != null) {
                                android.util.Log.d("LayerPanel", "gesture LONGPRESS idx=$index")
                                onDragStart()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || change.changedToUpIgnoreConsumed()) break
                                    change.consume()
                                    onDragPosition(rowTop + change.position.y)
                                }
                                onDragEnd()
                            } else {
                                var swiping = false
                                var total = 0f
                                var prevX = startX
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || change.changedToUpIgnoreConsumed()) {
                                        val tap = !swiping || (total > -dragThresholdPx.toFloat() && total < dragThresholdPx.toFloat())
                                        android.util.Log.d("LayerPanel", "gesture UP idx=$index swiping=$swiping total=$total tap=$tap")
                                        if (tap) onClick()
                                        break
                                    }
                                    val dx = change.position.x - startX
                                    if (!swiping && (dx > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop)) {
                                        swiping = true
                                    }
                                    if (swiping) {
                                        change.consume()
                                        total += change.position.x - prevX
                                        prevX = change.position.x
                                        if (total < -dragThresholdPx.toFloat()) {
                                            reveal = true
                                        } else if (total > dragThresholdPx.toFloat()) {
                                            reveal = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            // Group indent
            Spacer(Modifier.width((layer.depth * 14).dp))
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
        // Drag insert indicator (above the target row)
        if (dragLineAbove) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(2.dp)
                    .background(Morandi.accent),
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
