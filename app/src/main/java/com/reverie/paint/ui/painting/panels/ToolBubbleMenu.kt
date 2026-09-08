/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlin.math.abs

enum class ArrowDirection {
    DOWN,
    UP,
}

/**
 * Speech-bubble shape with an arrow tip pointing up or down.
 */
class BubbleShape(
    private val cornerRadius: Dp = 12.dp,
    private val arrowWidth: Dp = 12.dp,
    private val arrowHeight: Dp = 6.dp,
    private val arrowOffsetXPx: Float? = null,
    private val direction: ArrowDirection = ArrowDirection.DOWN,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { cornerRadius.toPx() }
        val aw = with(density) { arrowWidth.toPx() }
        val ah = with(density) { arrowHeight.toPx() }
        val cx = arrowOffsetXPx ?: (size.width / 2f)

        val path = Path().apply {
            when (direction) {
                ArrowDirection.DOWN -> {
                    val bodyBottom = size.height - ah
                    val arrowLeft = (cx - aw / 2f).coerceIn(r, (size.width - r - aw).coerceAtLeast(r))
                    val arrowRight = arrowLeft + aw
                    val tipX = cx.coerceIn(arrowLeft, arrowRight)

                    moveTo(r, 0f)
                    lineTo(size.width - r, 0f)
                    quadraticTo(size.width, 0f, size.width, r)
                    lineTo(size.width, bodyBottom - r)
                    quadraticTo(size.width, bodyBottom, size.width - r, bodyBottom)
                    lineTo(arrowRight, bodyBottom)
                    lineTo(tipX, size.height)
                    lineTo(arrowLeft, bodyBottom)
                    lineTo(r, bodyBottom)
                    quadraticTo(0f, bodyBottom, 0f, bodyBottom - r)
                    lineTo(0f, r)
                    quadraticTo(0f, 0f, r, 0f)
                    close()
                }
                ArrowDirection.UP -> {
                    val bodyTop = ah
                    val arrowLeft = (cx - aw / 2f).coerceIn(r, (size.width - r - aw).coerceAtLeast(r))
                    val arrowRight = arrowLeft + aw
                    val tipX = cx.coerceIn(arrowLeft, arrowRight)

                    moveTo(r, bodyTop)
                    lineTo(arrowLeft, bodyTop)
                    lineTo(tipX, 0f)
                    lineTo(arrowRight, bodyTop)
                    lineTo(size.width - r, bodyTop)
                    quadraticTo(size.width, bodyTop, size.width, bodyTop + r)
                    lineTo(size.width, size.height - r)
                    quadraticTo(size.width, size.height, size.width - r, size.height)
                    lineTo(r, size.height)
                    quadraticTo(0f, size.height, 0f, size.height - r)
                    lineTo(0f, bodyTop + r)
                    quadraticTo(0f, bodyTop, r, bodyTop)
                    close()
                }
            }
        }
        return Outline.Generic(path)
    }
}

/**
 * Dropdown item definition.
 */
data class ToolDropdownItemData<T>(
    val value: T,
    val iconRes: Int,
    val label: String,
)

/**
 * Positions a bubble popup relative to the anchor with boundary clamping.
 */
internal class BubblePopupPositionProvider(
    private val verticalGapPx: Int = 8,
    private val marginPx: Int = 16,
    private val onCalculated: (Float, ArrowDirection) -> Unit = { _, _ -> },
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        var x = anchorCenterX - popupContentSize.width / 2
        val minX = marginPx
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        x = x.coerceIn(minX, maxX)

        val arrowX = (anchorCenterX - x).toFloat()

        val aboveY = anchorBounds.top - popupContentSize.height - verticalGapPx
        val (y, direction) = if (aboveY >= marginPx) {
            aboveY to ArrowDirection.DOWN
        } else {
            val belowY = (anchorBounds.bottom + verticalGapPx).coerceAtMost(windowSize.height - popupContentSize.height - marginPx)
            belowY to ArrowDirection.UP
        }

        onCalculated(arrowX, direction)
        return IntOffset(x, y)
    }
}

/**
 * Trigger button with mini bottom-right corner triangle indicator.
 */
@Composable
fun ToolDropdownTriggerButton(
    iconRes: Int,
    label: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "btn_scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (expanded || active) Morandi.accent.copy(alpha = 0.15f)
                else Morandi.panelHi
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = if (expanded || active) Morandi.accent else Morandi.icon,
                modifier = Modifier.size(18.dp),
            )
            Canvas(
                modifier = Modifier
                    .size(5.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
            ) {
                val path = Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    path = path,
                    color = if (expanded || active) Morandi.accent else Morandi.subText.copy(alpha = 0.7f),
                )
            }
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (expanded || active) FontWeight.Bold else FontWeight.Normal,
            color = if (expanded || active) Morandi.accent else Morandi.subText,
        )
    }
}

/**
 * Standard uniform action button with identical geometry and tactile response as ToolDropdownTriggerButton.
 */
@Composable
fun ToolActionButton(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "btn_scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary -> Morandi.accent
                    danger -> Color(0xFFFF4D4F).copy(alpha = 0.12f)
                    active -> Morandi.accent.copy(alpha = 0.15f)
                    else -> Morandi.panelHi
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = when {
                    primary -> Morandi.onAccent
                    danger -> Color(0xFFFF4D4F)
                    active -> Morandi.accent
                    else -> Morandi.icon
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (active || primary) FontWeight.Bold else FontWeight.Normal,
            color = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFFF4D4F)
                active -> Morandi.accent
                else -> Morandi.subText
            },
        )
    }
}

/**
 * Bubble popup menu showing icon, label, and checkmark for selected item with spring entry/exit animation.
 */
@Composable
fun <T> ToolBubbleMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<ToolDropdownItemData<T>>,
    selected: T? = null,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    showCheckmark: Boolean = (selected != null),
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = expanded

    if (!transitionState.currentState && !transitionState.targetState) {
        return
    }

    var arrowOffsetPx by remember { mutableStateOf<Float?>(null) }
    var arrowDirection by remember { mutableStateOf(ArrowDirection.DOWN) }

    val density = LocalDensity.current
    val verticalGapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    val positionProvider = remember(verticalGapPx, marginPx) {
        BubblePopupPositionProvider(
            verticalGapPx = verticalGapPx,
            marginPx = marginPx,
        ) { offset, dir ->
            if (arrowOffsetPx == null || abs((arrowOffsetPx ?: 0f) - offset) > 1f) {
                arrowOffsetPx = offset
            }
            if (arrowDirection != dir) {
                arrowDirection = dir
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val bubbleShape = remember(arrowOffsetPx, arrowDirection) {
            BubbleShape(
                cornerRadius = 12.dp,
                arrowWidth = 12.dp,
                arrowHeight = 6.dp,
                arrowOffsetXPx = arrowOffsetPx,
                direction = arrowDirection,
            )
        }

        var popupWidthPx by remember { mutableFloatStateOf(0f) }
        val originX = if (popupWidthPx > 0f && arrowOffsetPx != null) {
            (arrowOffsetPx!! / popupWidthPx).coerceIn(0.1f, 0.9f)
        } else {
            0.5f
        }
        val originY = if (arrowDirection == ArrowDirection.DOWN) 1f else 0f
        val transformOrigin = remember(originX, originY) {
            TransformOrigin(originX, originY)
        }

        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(tween(140)) + scaleIn(
                initialScale = 0.82f,
                transformOrigin = transformOrigin,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f),
            ),
            exit = fadeOut(tween(110)) + scaleOut(
                targetScale = 0.82f,
                transformOrigin = transformOrigin,
                animationSpec = tween(110),
            ),
        ) {
            Box(
                modifier = modifier
                    .onSizeChanged { popupWidthPx = it.width.toFloat() }
                    .shadow(12.dp, bubbleShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(bubbleShape)
                    .background(Morandi.panel.copy(alpha = 0.96f))
                    .glassBorder(bubbleShape)
                    .padding(
                        top = if (arrowDirection == ArrowDirection.UP) 6.dp + 6.dp else 6.dp,
                        bottom = if (arrowDirection == ArrowDirection.DOWN) 6.dp + 6.dp else 6.dp,
                        start = 6.dp,
                        end = 6.dp,
                    )
                    .widthIn(min = 128.dp, max = 180.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items.forEach { item ->
                        val isSel = showCheckmark && item.value == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onSelect(item.value)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = item.iconRes),
                                contentDescription = item.label,
                                tint = if (isSel) Morandi.accent else Morandi.text,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.label,
                                color = if (isSel) Morandi.accent else Morandi.text,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (showCheckmark) {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .widthIn(min = 12.dp),
                                )
                                if (isSel) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_check),
                                        contentDescription = "已选择",
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dropdown trigger button + bubble popup menu combination.
 */
@Composable
fun <T> ToolBubbleDropdown(
    items: List<ToolDropdownItemData<T>>,
    selected: T? = null,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    labelOverride: String? = null,
    iconOverride: Int? = null,
    showCheckmark: Boolean = (selected != null),
) {
    var expanded by remember { mutableStateOf(false) }
    val currentItem = if (selected != null) {
        items.firstOrNull { it.value == selected } ?: items.firstOrNull()
    } else {
        items.firstOrNull()
    }

    Box(modifier = modifier) {
        ToolDropdownTriggerButton(
            iconRes = iconOverride ?: currentItem?.iconRes ?: R.drawable.ic_check,
            label = labelOverride ?: currentItem?.label ?: "",
            expanded = expanded,
            active = active || expanded,
            onClick = { expanded = !expanded },
        )

        ToolBubbleMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = items,
            selected = selected,
            showCheckmark = showCheckmark,
            onSelect = { value ->
                onSelect(value)
                expanded = false
            },
        )
    }
}
