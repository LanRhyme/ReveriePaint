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
    onDragStart: () -> Unit,
    onDragPosition: (Float) -> Unit,
    onDragEnd: () -> Unit,
    dragOnGroup: Boolean,
    isDragging: Boolean,
    dragFingerY: Float,
    onClick: () -> Unit,
    onSelect: () -> Unit = {},
    multiSelected: Boolean = false,
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
    // Right-swipe (multi-select) follow distance cap before the row springs back
    val selectMaxPx = with(density) { 64.dp.roundToPx() }
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
            // Spring with a light bounce so a right-swipe select (and the
            // drawer close) springs back instead of sliding flat
            revealAnim.animateTo(
                if (revealed) -drawerPx.toFloat() else 0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
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
                        var selectTriggered = false
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
                                if (dx > 0) {
                                    // Right-swipe: follow the finger, then
                                    // toggle multi-select once past the
                                    // threshold; on release the row springs
                                    // back (bounce) to the closed position
                                    fingerDx = dx.coerceIn(0f, selectMaxPx.toFloat())
                                    if (dx > revealThresholdPx && !selectTriggered) {
                                        selectTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onSelect()
                                    }
                                } else {
                                    lastDx = dx
                                    // follow the finger, clamped to the drawer width
                                    fingerDx = dx.coerceIn(-drawerPx.toFloat(), 0f)
                                }
                            }
                        }
                        if (swiping) {
                            // reveal only on a deliberate swipe past the
                            // threshold; otherwise the row animates back
                            if (lastDx < -revealThresholdPx) {
                                onReveal()
                            } else {
                                onRevealClose()
                            }
                            swiping = false
                        }
                    }
                }.combinedClickable(
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

                                    multiSelected -> Morandi.accent.copy(alpha = 0.25f)

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
                    shape = RoundedCornerShape(6.dp),
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
        if (vm.layerSoloed(index)) {
            Icon(
                painterResource(R.drawable.ic_eye),
                contentDescription = "独显中",
                tint = Morandi.accent,
                modifier = Modifier.size(13.dp),
            )
        }
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
