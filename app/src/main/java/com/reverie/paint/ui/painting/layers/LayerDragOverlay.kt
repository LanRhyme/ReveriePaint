/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Root-level overlay for layer drag & drop.
 *
 * Rendered at the top level of PaintingPage to bypass all panel clipping boundaries
 * (fixed 300dp panel width, rounded corner clips, and list height caps).
 * The floating card can move freely across the entire screen, casting full-depth
 * drop shadows onto the canvas and panels, with realistic paper tilt physics and
 * multi-card fanning.
 */
@Composable
fun LayerDragOverlay(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val activeDrag = vm.activeLayerDrag ?: return
    val settling = vm.isLayerDragSettling
    val groupSettle = vm.isLayerDragGroupSettle
    val density = LocalDensity.current

    val initialPos = remember(activeDrag) {
        Offset(
            activeDrag.startX - activeDrag.grabOffsetX,
            activeDrag.startY - activeDrag.grabOffsetY,
        )
    }
    val settleAnim = remember(activeDrag) { Animatable(initialPos, androidx.compose.ui.geometry.Offset.VectorConverter) }
    var isSettleRunning by remember { mutableStateOf(false) }

    LaunchedEffect(settling, vm.layerDragSettleTo, vm.layerDragSettleFrom) {
        if (settling && vm.layerDragSettleTo != null && vm.layerDragSettleFrom != null) {
            val from = vm.layerDragSettleFrom!!
            val to = vm.layerDragSettleTo!!
            settleAnim.snapTo(from)
            isSettleRunning = true
            settleAnim.animateTo(
                to,
                spring(dampingRatio = 0.82f, stiffness = 480f),
            )
            vm.activeLayerDrag = null
            vm.isLayerDragSettling = false
            vm.layerDragSettleTo = null
            vm.layerDragSettleFrom = null
            vm.isLayerDragGroupSettle = false
            isSettleRunning = false
        }
    }

    // Dynamic tilt reacting to horizontal drag velocity / delta (paper physics)
    var lastFingerX by remember { mutableFloatStateOf(vm.layerDragFingerX) }
    var tiltTarget by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(vm.layerDragFingerX) {
        if (!settling) {
            val dx = vm.layerDragFingerX - lastFingerX
            lastFingerX = vm.layerDragFingerX
            tiltTarget = (dx * 0.18f).coerceIn(-4.0f, 4.0f)
            delay(55)
            tiltTarget = 0f
        }
    }

    val cardTilt by animateFloatAsState(
        targetValue = if (settling) 0f else tiltTarget,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "cardTilt",
    )

    val cardScale by animateFloatAsState(
        targetValue = when {
            settling && groupSettle -> 0.45f
            settling -> 1.0f
            else -> 1.05f
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
        label = "cardScale",
    )

    val cardAlpha by animateFloatAsState(
        targetValue = if (settling && groupSettle) 0f else 1f,
        animationSpec = if (settling && groupSettle) tween(durationMillis = 180, delayMillis = 40) else tween(100),
        label = "cardAlpha",
    )

    val cardElevation by animateDpAsState(
        targetValue = if (!settling) 20.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
        label = "cardElevation",
    )

    val fanOffset by animateFloatAsState(
        targetValue = if (!settling) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "fanOffset",
    )

    val cardWidthDp = with(density) { activeDrag.cardWidthPx.toDp() }
    val cardHeightDp = with(density) { activeDrag.cardHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(950f),
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val pos = when {
                        isSettleRunning -> settleAnim.value
                        settling && vm.layerDragSettleFrom != null -> vm.layerDragSettleFrom!!
                        else -> Offset(
                            vm.layerDragFingerX - activeDrag.grabOffsetX,
                            vm.layerDragFingerY - activeDrag.grabOffsetY,
                        )
                    }
                    IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                }
                .width(cardWidthDp)
                .height(cardHeightDp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    rotationZ = cardTilt
                    alpha = cardAlpha
                    shadowElevation = with(density) { cardElevation.toPx() }
                    shape = RoundedCornerShape(8.dp)
                    clip = false
                },
        ) {
            // Stacked cards effect for multi-selection drag
            if (activeDrag.isMulti) {
                if (activeDrag.multiCount >= 3) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(x = 6.dp * fanOffset, y = (-6).dp * fanOffset)
                            .graphicsLayer { rotationZ = -2.5f * fanOffset }
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.6f))
                            .border(1.dp, Morandi.accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    )
                }
                if (activeDrag.multiCount >= 2) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(x = 3.dp * fanOffset, y = (-3).dp * fanOffset)
                            .graphicsLayer { rotationZ = -1.2f * fanOffset }
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.8f))
                            .border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                    )
                }
            }

            // Main floating card
            LayerRowContent(
                vm = vm,
                layer = activeDrag.layer,
                selected = true,
                collapsed = false,
                index = activeDrag.layer.index,
                onToggleCollapse = {},
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
            )

            // Multi-selection count badge
            if (activeDrag.isMulti) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp * fanOffset, y = (-6).dp * fanOffset)
                        .zIndex(20f),
                    shape = CircleShape,
                    color = Morandi.accent,
                    shadowElevation = 6.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${activeDrag.multiCount}",
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
