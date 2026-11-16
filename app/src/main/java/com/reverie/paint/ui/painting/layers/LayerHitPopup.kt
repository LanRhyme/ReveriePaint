/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.thumbFor
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * Lightweight floating bubble panel displaying layers present under the touch point.
 */
@Composable
fun LayerHitPopup(
    vm: PaintViewModel,
    hitIndices: List<Int>,
    anchor: Offset?,
    onDismiss: () -> Unit,
    onSelectLayer: (Int) -> Unit,
) {
    val layers = remember(vm.layers, hitIndices) {
        hitIndices.mapNotNull { idx -> vm.layers.find { it.index == idx } }
    }

    if (layers.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthPx = windowInfo.containerSize.width
    val screenHeightPx = windowInfo.containerSize.height

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val animScale by animateFloatAsState(
        targetValue = if (entered) 1.0f else 0.6f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "layerHitPopupScale",
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (entered) 1.0f else 0.0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "layerHitPopupAlpha",
    )

    val popupWidth = 240.dp
    val popupWidthPx = with(density) { popupWidth.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    // Estimate popup height based on item count
    val estimatedHeightDp = 44.dp + (layers.size.coerceAtMost(6) * 44).dp
    val estimatedHeightPx = with(density) { estimatedHeightDp.roundToPx() }

    val popupOffset = remember(anchor, screenWidthPx, screenHeightPx, popupWidthPx, estimatedHeightPx) {
        if (anchor != null) {
            val anchorX = anchor.x.toInt()
            val anchorY = anchor.y.toInt()

            val targetX = (anchorX - popupWidthPx / 2).coerceIn(
                marginPx,
                (screenWidthPx - popupWidthPx - marginPx).coerceAtLeast(marginPx),
            )

            // Prefer placing slightly above touch point, fallback to below, else clamp
            val targetY = when {
                anchorY - estimatedHeightPx - with(density) { 24.dp.roundToPx() } >= marginPx -> {
                    anchorY - estimatedHeightPx - with(density) { 24.dp.roundToPx() }
                }
                anchorY + with(density) { 24.dp.roundToPx() } + estimatedHeightPx <= screenHeightPx - marginPx -> {
                    anchorY + with(density) { 24.dp.roundToPx() }
                }
                else -> {
                    (anchorY - estimatedHeightPx / 2).coerceIn(
                        marginPx,
                        (screenHeightPx - estimatedHeightPx - marginPx).coerceAtLeast(marginPx),
                    )
                }
            }

            IntOffset(targetX, targetY)
        } else {
            IntOffset(
                ((screenWidthPx - popupWidthPx) / 2).coerceAtLeast(marginPx),
                ((screenHeightPx - estimatedHeightPx) / 2).coerceAtLeast(marginPx),
            )
        }
    }

    Popup(
        offset = popupOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = animScale
                    scaleY = animScale
                    alpha = animAlpha
                }
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Morandi.panel.copy(alpha = 0.96f))
                .border(BorderStroke(1.dp, Morandi.border), RoundedCornerShape(16.dp))
                .width(popupWidth)
                .padding(vertical = 8.dp, horizontal = 10.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: Title and count badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.layer_select_popup_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Morandi.subText,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${layers.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Morandi.accent,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.common_close),
                            tint = Morandi.subText,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Scrollable layer items
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    layers.forEach { layer ->
                        val isSelected = layer.index == vm.currentLayerIndex
                        val rowBg = if (isSelected) {
                            Morandi.accent.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(rowBg)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            BorderStroke(1.dp, Morandi.accent.copy(alpha = 0.4f)),
                                            RoundedCornerShape(8.dp),
                                        )
                                    } else Modifier
                                )
                                .clickable {
                                    onSelectLayer(layer.index)
                                }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Left accent indicator for current active layer
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.dp, height = 18.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(Morandi.accent),
                                )
                                Spacer(Modifier.width(6.dp))
                            }

                            // Layer Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .border(
                                        BorderStroke(0.5.dp, Morandi.border),
                                        RoundedCornerShape(5.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightCheckerboard(Modifier.fillMaxSize())
                                val thumb = vm.thumbFor(layer.index, layer.name)
                                if (thumb != null && !thumb.isRecycled) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = layer.name,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_layers),
                                        contentDescription = null,
                                        tint = Morandi.subText,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            // Layer Name
                            Text(
                                text = layerDisplayName(layer.name),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) Morandi.text else Morandi.subText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            // Clipped icon
                            if (layer.clipped) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_clip),
                                    contentDescription = null,
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(12.dp),
                                )
                            }

                            // Locked Icon
                            if (layer.locked) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = stringResource(R.string.layer_locked),
                                    tint = Morandi.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
