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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.compositeOver
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val drawerWidth = 132.dp

@Composable
internal fun LayerRow(
    vm: PaintViewModel,
    layer: PaintViewModel.LayerUiState,
    selected: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    revealed: Boolean,
    onReveal: () -> Unit,
    onRevealClose: () -> Unit,
    onBounds: (Float, Float) -> Unit,
    onDragStart: (Float, Float) -> Unit,
    onDragPosition: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    dragOnGroup: Boolean,
    isDragging: Boolean,
    dragFingerY: Float,
    onClick: () -> Unit,
    onSelect: () -> Unit = {},
    multiSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rowHeight = vm.layerRowHeightDp.dp
    val index = layer.index
    val isBg = layer.isBackground
    val visible = layer.visible
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // A swipe must travel this far to reveal the drawer
    val revealThresholdPx = with(density) { 20.dp.roundToPx() }
    val drawerPx = with(density) { drawerWidth.roundToPx() }
    // Right-swipe (multi-select) follow distance cap before the row springs back
    val selectMaxPx = with(density) { 64.dp.roundToPx() }
    var rowTop by remember { mutableFloatStateOf(0f) }
    var rowBottom by remember { mutableFloatStateOf(0f) }
    var rowLeft by remember { mutableFloatStateOf(0f) }
    val rowInteraction = remember { MutableInteractionSource() }

    // Coroutine scope used to drive revealAnim directly from gesture callbacks —
    // no LaunchedEffect follow-loop, zero scheduling delay, 1:1 finger tracking
    val scope = rememberCoroutineScope()

    // Row slide offset in px. snapTo() is called directly in the gesture handler
    // (via scope.launch) for instant follow; on release animateTo() springs back
    // or fully opens. One Animatable covers both phases, so there is no value
    // jump at finger lift.
    val revealAnim = remember { Animatable(0f) }

    // When the external revealed flag flips (e.g. another row opens and this one
    // should close, or a tap closes it), snap-animate to the correct position.
    LaunchedEffect(revealed) {
        val animSpec = if (revealed) {
            spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        }
        revealAnim.animateTo(if (revealed) -drawerPx.toFloat() else 0f, animSpec)
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(rowHeight)
                // Clip overflow so buttons are hidden at rest
                .clipToBounds()
                .pressScale(rowInteraction, pressedScale = 0.97f)
                .onGloballyPositioned { c ->
                    val bounds = c.boundsInRoot()
                    rowTop = bounds.top
                    rowBottom = bounds.bottom
                    rowLeft = bounds.left
                    onBounds(rowTop, rowBottom)
                }
                .pointerInput(index, isBg) {
                    if (isBg) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        val startOffset = revealAnim.value
                        val touchSlop = viewConfiguration.touchSlop
                        val isDrawerOpen = revealed || startOffset < -touchSlop
                        var gestureMode = 0 // 0: undecided, 1: swiping drawer, 2: dragging layer, 3: scrolling
                        var velocityX = 0f
                        var prevX = startX
                        var prevTimeNs = down.uptimeMillis * 1_000_000L
                        var selectTriggered = false
                        val pressTime = down.uptimeMillis
                        val longPressTimeoutMs = 300L
                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
                        if (!isDrawerOpen) {
                            scope.launch { rowInteraction.emit(press) }
                        }

                        var lastChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            lastChange = change
                            if (change.changedToUpIgnoreConsumed()) break
                            val currentX = change.position.x
                            val currentY = change.position.y
                            val dx = currentX - startX
                            val dy = currentY - startY
                            val elapsed = change.uptimeMillis - pressTime

                            if (gestureMode == 0) {
                                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.7f) {
                                    gestureMode = 1
                                    if (!isDrawerOpen) {
                                        scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                                    }
                                } else if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                                    gestureMode = 3
                                    if (!isDrawerOpen) {
                                        scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                                    }
                                } else if (!isDrawerOpen && elapsed >= longPressTimeoutMs && abs(dx) <= touchSlop * 1.5f && abs(dy) <= touchSlop * 1.5f) {
                                    gestureMode = 2
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                                    onDragStart(rowLeft + currentX, rowTop + currentY)
                                }
                            }

                            if (gestureMode == 1) {
                                change.consume()
                                val nowNs = change.uptimeMillis * 1_000_000L
                                val dt = ((nowNs - prevTimeNs) / 1_000_000f).coerceAtLeast(1f)
                                velocityX = ((currentX - prevX) / dt * 1000f).coerceIn(-5000f, 5000f)
                                prevX = currentX
                                prevTimeNs = nowNs
                                if (dx > 0 && startOffset >= -revealThresholdPx) {
                                    // Right-swipe from closed: multi-select
                                    scope.launch { revealAnim.snapTo(dx.coerceIn(0f, selectMaxPx.toFloat())) }
                                    if (dx > revealThresholdPx && !selectTriggered) {
                                        selectTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onSelect()
                                    }
                                } else {
                                    // Left-swipe to open or right-swipe to close open drawer
                                    scope.launch { revealAnim.snapTo((startOffset + dx).coerceIn(-drawerPx.toFloat(), 0f)) }
                                }
                            } else if (gestureMode == 2) {
                                change.consume()
                                val rootX = rowLeft + currentX
                                val rootY = rowTop + currentY
                                onDragPosition(rootX, rootY)
                            }
                        }

                        if (gestureMode == 2) {
                            onDragEnd()
                        } else if (gestureMode == 1) {
                            val currentOffset = revealAnim.value
                            val shouldReveal = currentOffset < -drawerPx * 0.4f || velocityX < -500f
                            val targetOffset = if (shouldReveal) -drawerPx.toFloat() else 0f
                            val animSpec = if (shouldReveal) {
                                spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                            } else {
                                spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                            }
                            scope.launch { revealAnim.animateTo(targetOffset, animSpec) }
                            if (shouldReveal) onReveal() else onRevealClose()
                        } else if (gestureMode == 0) {
                            if (isDrawerOpen) {
                                // Drawer was open: do NOT trigger onClick / layer properties
                                if (startX < size.width - drawerPx) {
                                    scope.launch {
                                        revealAnim.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                                    }
                                    onRevealClose()
                                }
                            } else if (lastChange?.isConsumed == true) {
                                // Child clickable (eye toggle, chevron) handled the event
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                            } else {
                                scope.launch { rowInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press)) }
                                onClick()
                            }
                        }
                    }
                },
    ) {
        // Inner unit: row content + buttons slide as one piece.
        val groupScale by animateFloatAsState(
            targetValue = if (dragOnGroup) 1.025f else 1.0f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
            label = "groupScale",
        )
        val selectionBg by animateColorAsState(
            targetValue =
                when {
                    dragOnGroup -> Morandi.accent.copy(alpha = 0.22f)
                    isDragging -> Color.Transparent
                    selected -> Morandi.accent.copy(alpha = 0.28f)
                    multiSelected -> Morandi.accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            animationSpec = spring(dampingRatio = 0.90f, stiffness = 500f),
            label = "selectionBg",
        )
        val groupBorderColor by animateColorAsState(
            targetValue = if (dragOnGroup) Morandi.accent else Color.Transparent,
            animationSpec = tween(180),
            label = "groupBorderColor",
        )
        val groupBorderWidth by animateDpAsState(
            targetValue = if (dragOnGroup) 2.dp else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
            label = "groupBorderWidth",
        )
        val groupBorder = if (groupBorderWidth > 0.dp) BorderStroke(groupBorderWidth, groupBorderColor) else null
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .then(if (groupBorder != null) Modifier.border(groupBorder, RoundedCornerShape(8.dp)) else Modifier)
                    .background(selectionBg, shape = RoundedCornerShape(8.dp))
                    .offset { IntOffset(revealAnim.value.roundToInt(), 0) }
                    .graphicsLayer {
                        alpha = if (isDragging) 0f else 1f
                        scaleX = groupScale
                        scaleY = groupScale
                    },
        ) {
            LayerRowContent(
                vm = vm,
                layer = layer,
                selected = selected,
                collapsed = collapsed,
                index = index,
                onToggleCollapse = onToggleCollapse,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = 4.dp),
            )
            Row(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .offset { IntOffset(drawerPx, 0) }
                        .width(drawerWidth)
                        .fillMaxHeight(),
            ) {
                DrawerAction(Modifier.weight(1f), Morandi.panelHi, R.drawable.ic_copy, stringResource(R.string.common_copy)) {
                    vm.copyLayer(index)
                    onRevealClose()
                }
                DrawerAction(Modifier.weight(1f), Morandi.accent, R.drawable.ic_eye, stringResource(R.string.layer_drawer_solo)) {
                    vm.soloLayer(index)
                    onRevealClose()
                }
                DrawerAction(Modifier.weight(1f), Color(0xFFB05552), R.drawable.ic_trash, stringResource(R.string.common_delete)) {
                    if (!isBg) vm.removeLayer(index)
                    onRevealClose()
                }
            }
        }
    }
}

/**
 * Visual content of a layer row (indent guides, collapse arrow, eye, thumbnail,
 * name with sub-info, status icons). No gestures - shared by the in-list row
 * and the floating drag overlay.
 */
@Composable
internal fun LayerRowContent(
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
                    contentDescription = if (collapsed) stringResource(R.string.layer_expand) else stringResource(R.string.layer_collapse),
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
                contentDescription = stringResource(R.string.layer_visibility),
                tint = if (visible) Morandi.icon else Morandi.subText,
                modifier = Modifier.size(17.dp),
            )
        }
        // Thumbnail (light checkerboard behind)
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (layer.colorLabel > 0) {
                        Modifier.border(
                            width = 2.dp,
                            color = layerLabelColor(layer.colorLabel),
                            shape = RoundedCornerShape(6.dp),
                        )
                    } else Modifier
                ),
        ) {
            LightCheckerboard(Modifier.fillMaxSize())
            val isFilter = layer.nodeType == 3 || layer.name.contains("滤镜")
            if (isFilter) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Morandi.accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_image_adjust),
                        contentDescription = stringResource(R.string.layer_filter_layer),
                        tint = Morandi.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                vm.thumbFor(layer.index, layer.name)?.let { thumb ->
                    if (!thumb.isRecycled) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = stringResource(R.string.layer_thumbnail),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (layer.nodeType == 2 || layer.name.contains("填充")) {
                    Icon(
                        painterResource(R.drawable.ic_fill),
                        contentDescription = stringResource(R.string.layer_fill_layer),
                        tint = if (selected) Morandi.onAccent else Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                } else if (layer.nodeType == 3 || layer.name.contains("滤镜") || layer.name.contains("Filter", ignoreCase = true)) {
                    Icon(
                        painterResource(R.drawable.ic_image_adjust),
                        contentDescription = stringResource(R.string.layer_filter_layer),
                        tint = if (selected) Morandi.onAccent else Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Text(
                    text = layerDisplayName(layer.name),
                    color = if (selected) Morandi.onAccent else Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val blendName = stringResource(blendModeResId(layer.blendMode))
            val isSpecial = layer.nodeType == 2 || layer.nodeType == 3 || layer.name.contains("填充") || layer.name.contains("Fill", ignoreCase = true) || layer.name.contains("滤镜") || layer.name.contains("Filter", ignoreCase = true)
            val modified = layer.opacity < 0.999f || layer.blendMode != "normal" || isSpecial
            if (modified) {
                val tag = when {
                    layer.nodeType == 2 || layer.name.contains("填充") -> stringResource(R.string.layer_tag_fill_prefix)
                    layer.nodeType == 3 || layer.name.contains("滤镜") -> stringResource(R.string.layer_tag_filter_prefix)
                    else -> ""
                }
                Text(
                    text = "$tag${(layer.opacity * 100).roundToInt()}% · $blendName",
                    color = if (selected) Morandi.onAccent.copy(alpha = 0.7f) else Morandi.subText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Right-side status icons
        if (vm.layerSoloed(index)) {
            Icon(
                painterResource(R.drawable.ic_eye),
                contentDescription = stringResource(R.string.layer_solo),
                tint = Morandi.accent,
                modifier = Modifier.size(13.dp),
            )
        }
        if (layer.clipped) {
            Icon(
                painterResource(R.drawable.ic_clip),
                contentDescription = stringResource(R.string.layer_alpha_inherit),
                tint = if (selected) Morandi.onAccent.copy(alpha = 0.8f) else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
        }
        if (layer.alphaLocked && !isBg) {
            Icon(
                painterResource(R.drawable.ic_grid),
                contentDescription = stringResource(R.string.layer_alpha_lock),
                tint = if (selected) Morandi.onAccent.copy(alpha = 0.8f) else Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
        }
        if (layer.locked || isBg) {
            Icon(
                painterResource(R.drawable.ic_lock),
                contentDescription = stringResource(R.string.layer_locked),
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
