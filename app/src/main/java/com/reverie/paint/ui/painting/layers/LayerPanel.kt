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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
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
        val index: Int,
    ) : LayerView

    data class FilterAdjust(
        val index: Int,
        val filterId: Int,
        val filterName: String,
    ) : LayerView

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
) {
    var view by remember { mutableStateOf<LayerView>(LayerView.List) }
    var renameRequest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLayerThumbs(force = true)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val panelShape = RoundedCornerShape(14.dp)

    val isFilterAdjust = view is LayerView.FilterAdjust

    Box(
        modifier =
            if (isFilterAdjust) {
                // Filter-adjust mode must not intercept ANY touch: the
                // full-screen transparent box used to carry .systemHoverIcon,
                // which made it the top pointer-input target and swallowed
                // canvas pinch/pan/two-finger-undo on CanvasTouchView.
                modifier.fillMaxSize().background(Color.Transparent)
            } else {
                modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .noRippleClickable(onClose)
                    .systemHoverIcon(context)
            },
    ) {
        Column(
            modifier =
                Modifier
                    .systemHoverIcon(context)
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
                            onOpenFilters = { view = LayerView.Filters(it) },
                            onOpenCreateFilter = { view = LayerView.FiltersCreate },
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

                    is LayerView.FiltersCreate -> {
                        // 滤镜图层功能暂时下线 (回滚), 路由保留兜底
                        view = LayerView.List
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
