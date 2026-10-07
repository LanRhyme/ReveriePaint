/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

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
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.res.stringResource
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
import android.widget.Toast
import com.reverie.paint.ui.components.DragPillHandle
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.PinButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import kotlin.math.abs
import kotlin.math.roundToInt


private fun Boolean?.orFalse() = this ?: false

internal enum class DropMode { Above, OnGroup }

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
        val indices: kotlin.collections.List<Int>,
    ) : LayerView {
        constructor(index: Int) : this(kotlin.collections.listOf(index))
        val index: Int get() = indices.firstOrNull() ?: 0
    }

    data class FilterAdjust(
        val indices: kotlin.collections.List<Int>,
        val filterId: Int,
        val filterName: String,
    ) : LayerView {
        constructor(index: Int, filterId: Int, filterName: String) : this(kotlin.collections.listOf(index), filterId, filterName)
        val index: Int get() = indices.firstOrNull() ?: 0
    }

    /** 创建流: 先选滤镜再建层 (避免全零参数建层即污染画布)。 */
    data object FiltersCreate : LayerView
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
    initialTargetFilters: List<Int>? = null,
    initialFilterCategoryId: String? = null,
    onStartFilterSession: ((FilterSession) -> Unit)? = null,
) {
    var view by remember(initialTargetFilters) {
        mutableStateOf<LayerView>(
            if (initialTargetFilters != null && initialTargetFilters.isNotEmpty()) {
                LayerView.Filters(initialTargetFilters)
            } else {
                LayerView.List
            }
        )
    }
    var renameTarget by remember { mutableStateOf<LayerRenameTarget?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val panelShape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current
    val baseEndOffsetPx = remember(density) { with(density) { (-8).dp.roundToPx() } }
    val baseTopOffsetPx = remember(density) { with(density) { 44.dp.roundToPx() } }

    val isFilterAdjust = view is LayerView.FilterAdjust

    val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
        Column(
            modifier =
                cardModifier
                    .systemHoverIcon(context)
                    .width(300.dp)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 3 / 4).dp)
                    .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(panelShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.barStyle(if (opacity >= 0.99f) 0.92f else opacity),
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = opacity))
                        }
                    )
                    .glassBorder(panelShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        ) {
            // Header Bar: Drag Handle Pill in center, Pin Button & Close Button on right (only when enabled)
            if (vm.panelPinningEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                ) {
                    DragPillHandle(
                        onDrag = { dragAmount -> vm.layerPanelOffset += dragAmount },
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PinButton(
                            isPinned = vm.isLayerPanelPinned,
                            onClick = {
                                vm.isLayerPanelPinned = !vm.isLayerPanelPinned
                                if (!vm.isLayerPanelPinned) {
                                    vm.layerPanelOffset = androidx.compose.ui.geometry.Offset.Zero
                                }
                                Toast.makeText(
                                    context,
                                    if (vm.isLayerPanelPinned) context.getString(R.string.layer_pin_hint) else context.getString(R.string.layer_unpin_hint),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        if (vm.isLayerPanelPinned) {
                            PanelCloseButton(onClose = onClose)
                        }
                    }
                }
            }

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
                            onOpenFilters = { view = LayerView.Filters(it) },
                            onOpenCreateFilter = { view = LayerView.FiltersCreate },
                            onRenameLayer = { idx, name -> renameTarget = LayerRenameTarget(idx, name) },
                        )
                    }

                    is LayerView.Detail -> {
                        LayerDetailPage(
                            vm = vm,
                            index = v.index,
                            onBack = { view = LayerView.List },
                            onOpenBlendModes = { view = LayerView.BlendModes(v.index) },
                            onOpenFilters = { view = LayerView.Filters(v.index) },
                            onOpenFilterAdjust = { filterId, filterName ->
                                if (onStartFilterSession != null) {
                                    onStartFilterSession(FilterSession(listOf(v.index), filterId, filterName))
                                    onClose()
                                } else {
                                    view = LayerView.FilterAdjust(listOf(v.index), filterId, filterName)
                                }
                            },
                            onRename = { renameTarget = LayerRenameTarget(v.index, it) },
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
                            indices = v.indices,
                            initialCategoryId = initialFilterCategoryId,
                            onBack = {
                                if (v.indices.size == 1) {
                                    view = LayerView.Detail(v.index)
                                } else {
                                    view = LayerView.List
                                }
                            },
                            onSelectFilter = { filterId, filterName ->
                                if (onStartFilterSession != null) {
                                    onStartFilterSession(FilterSession(v.indices, filterId, filterName))
                                    onClose()
                                } else {
                                    view = LayerView.FilterAdjust(v.indices, filterId, filterName)
                                }
                            }
                        )
                    }

                    is LayerView.FilterAdjust -> {
                        FilterAdjustPage(
                            vm = vm,
                            indices = v.indices,
                            filterId = v.filterId,
                            filterName = v.filterName,
                            onBack = { view = LayerView.Filters(v.indices) },
                            onDone = { view = LayerView.List }
                        )
                    }

                    is LayerView.FiltersCreate -> {
                        FiltersPage(
                            vm = vm,
                            indices = emptyList(),
                            initialCategoryId = initialFilterCategoryId,
                            onBack = { view = LayerView.List },
                            onSelectFilter = { filterId, filterName ->
                                val st = FilterAdjustState()
                                val ap = adjustParamsOf(st, filterId)
                                val lut: ByteArray? = when (filterId) {
                                    13 -> st.buildAdjustmentCurvesLut()
                                    30 -> st.buildAdjustmentGradientLut()
                                    else -> null
                                }
                                vm.addAdjustmentLayer(
                                    name = "${context.getString(R.string.layer_tag_filter_prefix)}$filterName",
                                    filterType = filterId,
                                    p1 = ap?.p1 ?: 0.0,
                                    p2 = ap?.p2 ?: 0.0,
                                    p3 = ap?.p3 ?: 0.0,
                                    p4 = ap?.p4 ?: 0.0,
                                    lut = lut,
                                ) { newIdx ->
                                    if (onStartFilterSession != null) {
                                        onStartFilterSession(FilterSession(listOf(newIdx), filterId, filterName))
                                        onClose()
                                    } else {
                                        view = LayerView.FilterAdjust(listOf(newIdx), filterId, filterName)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (vm.panelPinningEnabled && vm.isLayerPanelPinned) {
        cardContent(modifier)
    } else {
        Box(
            modifier =
                if (isFilterAdjust) {
                    modifier.fillMaxSize().background(Color.Transparent)
                } else {
                    modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .noRippleClickable(onClose)
                        .systemHoverIcon(context)
                },
        ) {
            val offsetX = if (vm.panelPinningEnabled) vm.layerPanelOffset.x else 0f
            val offsetY = if (vm.panelPinningEnabled) vm.layerPanelOffset.y else 0f
            cardContent(
                Modifier
                    .align(if (vm.leftHandMode) Alignment.TopStart else Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            ((if (vm.leftHandMode) -baseEndOffsetPx else baseEndOffsetPx) + offsetX).roundToInt(),
                            (baseTopOffsetPx + offsetY).roundToInt(),
                        )
                    }
            )
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            vm = vm,
            initial = target.name,
            onConfirm = { newName ->
                if (target.index >= 0) vm.renameLayer(target.index, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

// ---------------------------------------------------------------------------
// List page
// ---------------------------------------------------------------------------


@Composable
internal fun TopIcon(
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
                .background(if (active) Morandi.accent.copy(alpha = 0.20f) else Color.Transparent)
                .then(
                    if (active) Modifier.border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    else Modifier
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


/** Light checkerboard: white background with faint light-gray grid lines. */
@Composable
internal fun LightCheckerboard(modifier: Modifier = Modifier) {
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
internal fun layerLabelColor(label: Int): Color =
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


private data class LayerRenameTarget(val index: Int, val name: String)

@Composable
private fun RenameDialog(
    vm: PaintViewModel,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    TextInputGuard(vm)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial,
                selection = TextRange(0, initial.length)
            )
        )
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val trimmed = textFieldValue.text.trim()
    val isValid = trimmed.isNotBlank()
    val submit = {
        if (isValid) {
            onConfirm(trimmed)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
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
                        .width(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                        .noRippleClickable { /* block click pass-through */ }
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.layer_rename_dialog_title),
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${textFieldValue.text.length}/40",
                        color = if (textFieldValue.text.length >= 40) Morandi.accent else Morandi.subText,
                        fontSize = 12.sp,
                    )
                }

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        if (newValue.text.length <= 40) {
                            textFieldValue = newValue
                        }
                    },
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.layer_rename_dialog_hint),
                            color = Morandi.subText,
                            fontSize = 14.sp,
                        )
                    },
                    textStyle = TextStyle(fontSize = 14.sp, color = Morandi.text),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panelHi,
                            unfocusedContainerColor = Morandi.panelHi,
                            cursorColor = Morandi.accent,
                        ),
                    trailingIcon = {
                        if (textFieldValue.text.isNotEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Morandi.subText.copy(alpha = 0.15f))
                                        .clickable {
                                            textFieldValue = TextFieldValue("", TextRange.Zero)
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = stringResource(R.string.common_cancel),
                                    tint = Morandi.text,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.Enter, Key.NumPadEnter -> {
                                            if (isValid) {
                                                submit()
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        Key.Escape -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.panelHi)
                                .noRippleClickable(onDismiss)
                                .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = Morandi.subText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isValid) Morandi.accent else Morandi.accent.copy(alpha = 0.35f))
                                .then(if (isValid) Modifier.noRippleClickable(submit) else Modifier)
                                .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.common_confirm),
                            color = if (isValid) Morandi.onAccent else Morandi.onAccent.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
