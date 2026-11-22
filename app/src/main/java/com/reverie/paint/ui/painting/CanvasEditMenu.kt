/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.CanvasEditAction
import com.reverie.paint.model.QuickMenuLayout
import com.reverie.paint.model.QuickMenuSlotOffset
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/** Floating capsules around the editor entry with smooth burst-in physics animation. */
@Composable
internal fun CanvasEditMenu(vm: PaintViewModel, onDismiss: () -> Unit, onCustomize: () -> Unit) {
    val pages = vm.canvasEditMenuActions.chunked(6)
    var requestedPage by rememberSaveable { mutableIntStateOf(0) }
    val page = requestedPage.coerceIn(0, pages.lastIndex)
    val hasClipboard = pages[page].any { it is CanvasEditAction.Clipboard }
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val windowHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val customizeLabel = stringResource(R.string.canvas_edit_customize)

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val animScale by animateFloatAsState(
        targetValue = if (entered) 1.0f else 0.5f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "quickMenuScale",
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (entered) 1.0f else 0.0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "quickMenuAlpha",
    )

    val anchor = vm.quickMenuAnchor
    val screenWidthPx = windowInfo.containerSize.width
    val screenHeightPx = windowInfo.containerSize.height
    val menuWidth = 360.dp
    val menuWidthPx = with(density) { menuWidth.roundToPx() }
    val hasPager = pages.size > 1
    val menuHeight = when {
        hasClipboard && hasPager -> 280.dp
        hasClipboard -> 240.dp
        hasPager -> 220.dp
        else -> 180.dp
    }
    val menuHeightPx = with(density) { menuHeight.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    // Center of the radial capsules (middle of 360dp width and 82dp height)
    val centerOffsetX = with(density) { (menuWidth / 2).roundToPx() }
    val centerOffsetY = with(density) { 82.dp.roundToPx() }

    val popupOffset = if (anchor != null) {
        val targetX = (anchor.x.toInt() - centerOffsetX).coerceIn(
            marginPx,
            (screenWidthPx - menuWidthPx - marginPx).coerceAtLeast(marginPx),
        )
        val targetY = (anchor.y.toInt() - centerOffsetY).coerceIn(
            marginPx,
            (screenHeightPx - menuHeightPx - marginPx).coerceAtLeast(marginPx),
        )
        IntOffset(targetX, targetY)
    } else {
        IntOffset.Zero
    }

    Popup(
        alignment = if (anchor != null) Alignment.TopStart else Alignment.Center,
        offset = popupOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(menuWidth)
                .graphicsLayer {
                    scaleX = animScale
                    scaleY = animScale
                    alpha = animAlpha
                    transformOrigin = if (anchor != null) {
                        TransformOrigin(
                            pivotFractionX = (centerOffsetX.toFloat() / menuWidthPx.toFloat()).coerceIn(0f, 1f),
                            pivotFractionY = (centerOffsetY.toFloat() / menuHeightPx.toFloat()).coerceIn(0f, 1f),
                        )
                    } else {
                        TransformOrigin.Center
                    }
                }
                .heightIn(max = (windowHeight - 32.dp).coerceAtLeast(120.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CanvasEditCapsules(
                actions = pages[page],
                hoverIndex = vm.quickMenuHoverIndex,
                actionContent = { action, isHovered ->
                    CanvasEditActionButton(vm, action, isHovered, onDismiss)
                },
                centerContent = {
                    FilledTonalButton(
                        onClick = onCustomize,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = customizeLabel },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Morandi.text, contentColor = Morandi.panel,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.canvas_edit_title), fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
            )
            if (hasPager) {
                Surface(shape = RoundedCornerShape(50), color = Morandi.panel.copy(alpha = 0.96f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Morandi.text, disabledContentColor = Morandi.subText.copy(alpha = 0.5f),
                        )
                        IconButton(onClick = { requestedPage = page - 1 }, enabled = page > 0, colors = colors) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_left),
                                stringResource(R.string.canvas_edit_previous_page),
                            )
                        }
                        Text(
                            stringResource(R.string.canvas_edit_page, page + 1, pages.size),
                            color = Morandi.text, fontSize = 12.sp,
                        )
                        IconButton(
                            onClick = { requestedPage = page + 1 }, enabled = page < pages.lastIndex, colors = colors,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_left),
                                stringResource(R.string.canvas_edit_next_page), Modifier.rotate(180f),
                            )
                        }
                    }
                }
            }
            if (hasClipboard) {
                Surface(shape = RoundedCornerShape(16.dp), color = Morandi.panel.copy(alpha = 0.96f)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(
                                when {
                                    vm.canvasEditBusy -> R.string.canvas_edit_loading
                                    !vm.canvasClipboardAvailable -> R.string.canvas_edit_unavailable
                                    vm.canvasEditCapabilities and 1 == 0 -> R.string.canvas_edit_no_pixels
                                    vm.hasSelection -> R.string.canvas_edit_selection_hint
                                    else -> R.string.canvas_edit_layer_hint
                                },
                            ),
                            color = Morandi.text, fontSize = 12.sp,
                        )
                        Text(
                            stringResource(R.string.canvas_edit_clipboard_hint),
                            color = Morandi.text.copy(alpha = 0.75f), fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Measure labels first so translations and enlarged fonts cannot overlap adjacent capsules. */
@Composable
private fun CanvasEditCapsules(
    actions: List<CanvasEditAction>,
    hoverIndex: Int = -1,
    actionContent: @Composable (CanvasEditAction, Boolean) -> Unit,
    centerContent: @Composable () -> Unit,
) {
    val offsets = remember(actions.size) { QuickMenuLayout.getSlotOffsets(actions.size) }
    Layout(
        content = {
            actions.forEachIndexed { index, action ->
                actionContent(action, hoverIndex == index)
            }
            centerContent()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = ((constraints.maxWidth - 2 * gap) / 3).coerceAtLeast(1)
        val children = measurables.map { it.measure(Constraints.fixedWidth(width)) }
        val height = children.maxOf { it.height }
        val step = height + 10.dp.roundToPx()

        val centerX = constraints.maxWidth / 2f
        val centerY = step + height / 2f
        val dx = (width + gap).toFloat()
        val dy = step.toFloat()

        layout(constraints.maxWidth, height + 2 * step) {
            children.dropLast(1).forEachIndexed { index, child ->
                val offset = offsets.getOrElse(index) { QuickMenuSlotOffset(0f, 0f) }
                val targetCenterX = centerX + offset.xRatio * dx
                val targetCenterY = centerY + offset.yRatio * dy
                child.placeRelative(
                    (targetCenterX - child.width / 2f).roundToInt(),
                    (targetCenterY - child.height / 2f).roundToInt(),
                )
            }
            val center = children.last()
            center.placeRelative(
                (centerX - center.width / 2f).roundToInt(),
                (centerY - center.height / 2f).roundToInt(),
            )
        }
    }
}

@Composable
private fun CanvasEditActionButton(
    vm: PaintViewModel,
    action: CanvasEditAction,
    isHovered: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = action as? CanvasEditAction.Clipboard
    val hoverScale by animateFloatAsState(
        targetValue = if (isHovered) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "capsuleHoverScale",
    )

    FilledTonalButton(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = hoverScale
                scaleY = hoverScale
            },
        shape = RoundedCornerShape(50),
        border = if (isHovered) BorderStroke(2.dp, Morandi.accent) else null,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isHovered) Morandi.accent else Morandi.panelHi.copy(alpha = 0.96f),
            contentColor = if (isHovered) Color.White else Morandi.text,
            disabledContainerColor = Morandi.panelHi.copy(alpha = 0.9f),
            disabledContentColor = Morandi.subText.copy(alpha = 0.6f),
        ),
        enabled = clipboard == null ||
            (!vm.canvasEditBusy && vm.canvasEditCapabilities and clipboard.capability != 0),
        onClick = {
            vm.executeCanvasEditAction(action)
            onDismiss()
        },
    ) {
        Text(stringResource(action.titleRes), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
