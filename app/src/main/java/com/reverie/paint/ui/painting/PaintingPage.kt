/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Motion
import kotlin.math.min
import kotlin.math.roundToInt
import com.reverie.paint.ui.painting.brush.BrushPanel
import com.reverie.paint.ui.painting.brush.BrushStudioPage
import com.reverie.paint.ui.painting.canvas.CanvasView
import com.reverie.paint.ui.painting.canvas.TransformMode
import com.reverie.paint.ui.painting.canvas.TransformState
import com.reverie.paint.ui.painting.canvas.widgetToImage
import com.reverie.paint.ui.theme.parseColor
import com.reverie.paint.ui.painting.layers.LayerPanel
import com.reverie.paint.ui.painting.panels.AllToolsPanel
import com.reverie.paint.ui.painting.panels.ColorPanel
import com.reverie.paint.ui.painting.panels.CropPanel
import com.reverie.paint.ui.painting.panels.FillPanel
import com.reverie.paint.ui.painting.panels.GradientPanel
import com.reverie.paint.ui.painting.panels.LiquifyPanel
import com.reverie.paint.ui.painting.panels.PickerLayerSourceBar
import com.reverie.paint.ui.painting.panels.SelectionFloatPanel
import com.reverie.paint.ui.painting.panels.SelectionMenuItem
import com.reverie.paint.ui.painting.panels.SettingsPanel
import com.reverie.paint.ui.painting.panels.ShapeToolPanel
import com.reverie.paint.ui.painting.panels.ToolFloatChip
import com.reverie.paint.ui.painting.panels.ToolFloatPanel
import com.reverie.paint.ui.painting.panels.ToolRail
import com.reverie.paint.ui.painting.panels.TransformPanel
import com.reverie.paint.ui.painting.panels.*

private class FillDiffusionWave(
    val id: Long,
    val origin: Offset,
    val color: Color,
    val anim: Animatable<Float, AnimationVector1D>,
)

/**
 * Painting page: full-bleed canvas with touch painting + gestures,
 * overlaid by the top bar, left tool rail and popup panels.
 *
 * 画世界 Pro style: left tool rail with vertical sliders, top operation
 * bar, dark grid workspace with a centered white canvas.
 */
@Composable
fun PaintingPage(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }
    // Gesture-driven transforms: exposed as State objects so CanvasView/
    // CanvasOverlay read them inside draw lambdas — pinch writes then only
    // invalidate canvas redraw instead of recomposing this whole page.
    val zoomState = remember { mutableFloatStateOf(1f) }
    var zoom by zoomState
    val rotationState = remember { mutableFloatStateOf(0f) }
    var rotation by rotationState
    val panXState = remember { mutableFloatStateOf(0f) }
    var panX by panXState
    val panYState = remember { mutableFloatStateOf(0f) }
    var panY by panYState
    var fitScale by remember { mutableFloatStateOf(1f) }

    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }

    // Popup panels
    var showIndicator by remember { mutableStateOf(false) }
    var indicatorTick by remember { mutableStateOf(0) }

    // Plain holder (no snapshot writes) gating indicator ticks during pinch:
    // each tick restarts the auto-hide LaunchedEffect below.
    val indicatorGate = remember { LongArray(1) }

    fun flashIndicator() {
        showIndicator = true
        indicatorTick++
    }

    // Auto-hide the transform indicator 1.2s after the last flash
    LaunchedEffect(indicatorTick) {
        if (indicatorTick > 0) {
            kotlinx.coroutines.delay(1200)
            showIndicator = false
        }
    }

    // Auto-hide action toast 1.0s after trigger
    LaunchedEffect(vm.actionToastRevision) {
        if (vm.actionToastRevision > 0L && vm.actionToastMessage != null) {
            kotlinx.coroutines.delay(1000)
            vm.clearActionToast()
        }
    }

    var textDialogPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var brushPanelOpen by remember { mutableStateOf(false) }
    var layerPanelOpen by remember { mutableStateOf(false) }
    var targetFilterLayers by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(layerPanelOpen) {
        vm.layerPanelOpen = layerPanelOpen
        if (layerPanelOpen) {
            vm.refreshLayerThumbs(force = true)
        } else {
            targetFilterLayers = null
        }
    }
    var settingsPanelOpen by remember { mutableStateOf(false) }
    var colorPanelOpen by remember { mutableStateOf(false) }
    var drawingGuidePanelOpen by remember { mutableStateOf(false) }
    // 快捷键打开滤镜页时预选的滤镜分类 id (LayerPanel → FiltersPage)
    var filterCategoryHint by remember { mutableStateOf<String?>(null) }

    // 快捷键触发的视口/面板命令 (VM 不持有视口状态, 只发一次性命令 token)
    LaunchedEffect(vm.uiCommandTick) {
        val cmd = vm.pendingUiCommand ?: return@LaunchedEffect
        when (cmd) {
            "zoom_in" -> {
                zoom = (zoom * 1.2f).coerceAtMost(16f)
                flashIndicator()
            }
            "zoom_out" -> {
                zoom = (zoom / 1.2f).coerceAtLeast(0.1f)
                flashIndicator()
            }
            "rotate_cw" -> {
                rotation = (rotation + 90f) % 360f
                flashIndicator()
            }
            "open_color" -> colorPanelOpen = true
            else -> {
                if (cmd.startsWith("open_filter:")) {
                    settingsPanelOpen = false
                    targetFilterLayers = vm.editTargetLayers()
                    filterCategoryHint = cmd.removePrefix("open_filter:")
                    layerPanelOpen = true
                }
            }
        }
        vm.consumeUiCommand()
    }

    // 当前工具: 直接从 ViewModel 派生, 保证重启恢复与引擎内自动切换
    // (如选橡皮擦分组预设反向触发 applyTool) 时 UI 高亮始终一致
    val tool = Tool.fromId(vm.currentToolId)
    var moreToolsOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var selectionPanelOpen by remember { mutableStateOf(false) }
    var selectionPropsOpen by remember { mutableStateOf(false) }
    var selectionPanelOffsetX by remember { mutableFloatStateOf(0f) }
    var selectionPanelOffsetY by remember { mutableFloatStateOf(0f) }
    val selectionTools =
        listOf(
            Tool.SELECT_RECT,
            Tool.SELECT_ELLIPSE,
            Tool.SELECT_POLYGON,
            Tool.SELECT_MAGNETIC,
            Tool.LASSO,
            Tool.MAGICWAND,
            Tool.SELECT_SIMILAR,
        )

    val tfState = remember { TransformState() }
    var cropRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var gradientType by remember { mutableStateOf(0) }
    var liquifyStrength by remember { mutableStateOf(0.9f) }
    var liquifyMode by remember { mutableStateOf(0) }
    var liquifyBrushSize by remember { mutableStateOf(60f) }
    // Point-click shape tools share the canvas vertex list
    var polyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(polyPoints.isNotEmpty(), vm.lassoMultiPoints.isNotEmpty()) {
        if (polyPoints.isNotEmpty()) {
            vm.customUndoHook = {
                if (polyPoints.isNotEmpty()) {
                    polyPoints = polyPoints.dropLast(1)
                    vm.showActionToast("撤销顶点", R.drawable.ic_undo)
                    true
                } else {
                    false
                }
            }
        } else if (vm.lassoMultiPoints.isNotEmpty()) {
            vm.customUndoHook = {
                if (vm.lassoMultiPoints.isNotEmpty()) {
                    val undone = vm.undoLassoPoint()
                    if (undone) {
                        vm.showActionToast("撤销套索点", R.drawable.ic_undo)
                    }
                    undone
                } else {
                    false
                }
            }
        } else {
            vm.customUndoHook = null
        }
    }
    var isColorDropping by remember { mutableStateOf(false) }
    var colorDropPos by remember { mutableStateOf(Offset.Zero) }
    var colorDropHex by remember { mutableStateOf(vm.brushColor) }

    val diffusionWaves = remember { mutableStateListOf<FillDiffusionWave>() }
    val coroutineScope = rememberCoroutineScope()

    val triggerFillDiffusion: (Offset, Color) -> Unit = { screenOrigin, waveColor ->
        val waveId = System.currentTimeMillis()
        val anim = Animatable(0f)
        val wave = FillDiffusionWave(waveId, screenOrigin, waveColor, anim)
        diffusionWaves.add(wave)
        coroutineScope.launch {
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 480,
                    easing = FastOutSlowInEasing,
                ),
            )
            diffusionWaves.remove(wave)
        }
    }

    val handleColorDrop: (Offset) -> Unit = { dropScreenPos ->
        val activeLayer = vm.layers.firstOrNull { it.index == vm.currentLayerIndex }
        when {
            activeLayer?.isGroup == true ->
                vm.showActionToast("图层组不可直接绘制，请选择组内图层", R.drawable.ic_folder)

            activeLayer?.name?.contains("滤镜") == true ->
                vm.showActionToast("滤镜图层不可直接绘制，请在普通图层绘制或栅格化", R.drawable.ic_image_adjust)

            activeLayer?.locked == true ->
                vm.showActionToast("图层已锁定，无法编辑", R.drawable.ic_lock)

            else -> {
                val bmpW = vm.displayBitmap?.width ?: vm.docWidth
                val bmpH = vm.displayBitmap?.height ?: vm.docHeight
                val docPos = widgetToImage(
                    dropScreenPos,
                    canvasW,
                    canvasH,
                    panX,
                    panY,
                    zoom,
                    fitScale,
                    rotation,
                    bmpW,
                    bmpH,
                    vm.docWidth,
                    vm.docHeight,
                )
                if (docPos.x in 0f..vm.docWidth.toFloat() && docPos.y in 0f..vm.docHeight.toFloat()) {
                    vm.floodFill(docPos.x, docPos.y)
                    triggerFillDiffusion(dropScreenPos, parseColor(colorDropHex))
                } else {
                    vm.showActionToast("请在画布范围内填色", R.drawable.ic_fill)
                }
            }
        }
        isColorDropping = false
    }
    // Clear transient tool state when switching tools, and activate tool states
    androidx.compose.runtime.LaunchedEffect(tool) {
        if (tool == Tool.TRANSFORM || tool == Tool.MOVE) {
            val b = vm.contentBounds()
            if (b != null && b[2] > 0 && b[3] > 0) {
                tfState.reset(
                    androidx.compose.ui.geometry.Rect(
                        b[0].toFloat(),
                        b[1].toFloat(),
                        (b[0] + b[2]).toFloat(),
                        (b[1] + b[3]).toFloat(),
                    ),
                )
            } else {
                tfState.reset(
                    androidx.compose.ui.geometry.Rect(
                        0f,
                        0f,
                        vm.docWidth.toFloat(),
                        vm.docHeight.toFloat(),
                    ),
                )
            }
            vm.startTransformPreview()
        } else {
            if (tfState.active) {
                // Real-time auto-commit on tool switch
                when (tfState.mode) {
                    TransformMode.PERSPECTIVE -> {
                        val corners = tfState.quadCorners
                        if (corners.size == 4) {
                            vm.applyPerspectiveTransform(
                                corners[0].x.toDouble(),
                                corners[0].y.toDouble(),
                                corners[1].x.toDouble(),
                                corners[1].y.toDouble(),
                                corners[2].x.toDouble(),
                                corners[2].y.toDouble(),
                                corners[3].x.toDouble(),
                                corners[3].y.toDouble(),
                                tfState.bounds.left.toDouble(),
                                tfState.bounds.top.toDouble(),
                                tfState.bounds.width.toDouble(),
                                tfState.bounds.height.toDouble(),
                            )
                        }
                    }

                    TransformMode.DISTORT -> {
                        vm.applyWarpMeshTransform(
                            tfState.origMeshPoints,
                            tfState.meshPoints,
                            tfState.bounds.left.toDouble(),
                            tfState.bounds.top.toDouble(),
                            tfState.bounds.width.toDouble(),
                            tfState.bounds.height.toDouble(),
                        )
                    }

                    else -> {
                        val rad = Math.toRadians(tfState.rotation.toDouble())
                        val c = tfState.bounds.center
                        if (tfState.rotation != 0f || tfState.scaleX != 1f || tfState.scaleY != 1f || tfState.tx != 0f ||
                            tfState.ty != 0f
                        ) {
                            vm.applyTransform(
                                tfState.scaleX.toDouble(),
                                tfState.scaleY.toDouble(),
                                0.0,
                                0.0,
                                rad,
                                tfState.tx.toDouble(),
                                tfState.ty.toDouble(),
                                c.x.toDouble(),
                                c.y.toDouble(),
                            )
                        } else {
                            vm.cancelTransformPreview()
                        }
                    }
                }
            }
            tfState.active = false
        }
        if (tool != Tool.CROP) cropRect = null
        if (tool != Tool.POLYGON && tool != Tool.POLYLINE && tool != Tool.PATH && tool != Tool.SELECT_POLYGON) {
            polyPoints = emptyList()
        }
        if (tool != Tool.LASSO && vm.lassoMultiPoints.isNotEmpty()) {
            vm.cancelLassoMulti()
        }
    }
    var shapeStrokeWidth by remember { mutableStateOf(4f) }
    var shapeFilled by remember { mutableStateOf(false) }
    val shapeTools =
        listOf(
            Tool.LINE,
            Tool.RECT,
            Tool.ELLIPSE,
            Tool.POLYGON,
            Tool.POLYLINE,
            Tool.PATH,
        )

    val hazeState = remember { HazeState() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Morandi.canvasBg)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { vm.handleKeyEvent(it) }
    ) {
        // ---- Canvas workspace
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .then(
                        if (vm.blurBackground) Modifier.haze(hazeState) else Modifier,
                    ),
        ) {
            CanvasView(
                vm = vm,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            canvasW = it.width
                            canvasH = it.height
                        },
                zoom = zoomState,
                rotation = rotationState,
                panX = panXState,
                panY = panYState,
                fitScale = fitScale,
                onFitScale = {
                    fitScale = it
                },
                onTransform = { z, r, px, py ->
                    zoom = z
                    rotation = r
                    panX = px
                    panY = py
                    if (!showIndicator) {
                        showIndicator = true
                    }
                    // Throttle tick writes: each one restarts the indicator
                    // LaunchedEffect, and pinch fires hundreds of events.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - indicatorGate[0] >= 120) {
                        indicatorGate[0] = now
                        indicatorTick++
                    }
                },
                onTextRequested = { x, y ->
                    textDialogPos = x to y
                },
                tool = tool,
                tfState = tfState,
                polyPoints = polyPoints,
                onPolyPoint = { polyPoints = polyPoints + it },
                cropRect = cropRect,
                onCropRect = { cropRect = it },
                fillTolerance = vm.fillTolerance,
                gradientType = gradientType,
                liquifyStrength = liquifyStrength,
                liquifyMode = liquifyMode,
                liquifyBrushSize = liquifyBrushSize,
                overlayPanelsOpen = brushPanelOpen || layerPanelOpen ||
                    colorPanelOpen || settingsPanelOpen || moreToolsOpen ||
                    drawingGuidePanelOpen,
            )

            // Diffusion Animation Canvas Overlay
            if (diffusionWaves.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (wave in diffusionWaves) {
                        val t = wave.anim.value
                        val maxR = maxOf(size.width, size.height) * 0.45f
                        val curR = maxR * t
                        val alpha = (1f - t).coerceIn(0f, 1f)

                        // 1. Soft radial glowing fill expanding and dissolving
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    wave.color.copy(alpha = alpha * 0.45f),
                                    wave.color.copy(alpha = alpha * 0.15f),
                                    wave.color.copy(alpha = 0f),
                                ),
                                center = wave.origin,
                                radius = maxOf(1f, curR),
                            ),
                            radius = curR,
                            center = wave.origin,
                        )

                        // 2. Main expanding outer wave ring
                        drawCircle(
                            color = wave.color.copy(alpha = alpha * 0.85f),
                            radius = curR,
                            center = wave.origin,
                            style = Stroke(
                                width = (5.dp.toPx() * (1f - t * 0.6f)).coerceAtLeast(1.5f),
                            ),
                        )

                        // 3. Secondary concentric ripple
                        if (t > 0.12f) {
                            val t2 = ((t - 0.12f) / 0.88f).coerceIn(0f, 1f)
                            val r2 = curR * 0.68f
                            val alpha2 = (1f - t2).coerceIn(0f, 1f)
                            drawCircle(
                                color = wave.color.copy(alpha = alpha2 * 0.55f),
                                radius = r2,
                                center = wave.origin,
                                style = Stroke(
                                    width = 2.5.dp.toPx(),
                                ),
                            )
                        }

                        // 4. Center splash flash
                        if (t < 0.35f) {
                            val splashAlpha = ((0.35f - t) / 0.35f).coerceIn(0f, 1f)
                            val splashR = 18.dp.toPx() * (1f + t * 2f)
                            drawCircle(
                                color = Color.White.copy(alpha = splashAlpha * 0.7f),
                                radius = splashR,
                                center = wave.origin,
                            )
                        }
                    }
                }
            }
        }

        var showExitSaveDialog by remember { mutableStateOf(false) }
        var showDiscardConfirmDialog by remember { mutableStateOf(false) }

        val requestExit: () -> Unit = {
            if (vm.hasUnsavedChanges()) {
                showExitSaveDialog = true
            } else {
                vm.discardAndExit()
            }
        }

        // Primary Exit Save Confirmation Dialog
        if (showExitSaveDialog) {
            val exitContext = androidx.compose.ui.platform.LocalContext.current
            ExitSaveDialog(
                vm = vm,
                onDiscard = {
                    showExitSaveDialog = false
                    showDiscardConfirmDialog = true
                },
                onSaveAndExit = {
                    showExitSaveDialog = false
                    vm.saveProject(vm.docName) {
                        android.widget.Toast
                            .makeText(exitContext, "工程已保存", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        vm.goHome()
                    }
                },
                onDismiss = { showExitSaveDialog = false },
            )
        }

        if (showDiscardConfirmDialog) {
            DiscardConfirmDialog(
                onDiscard = {
                    showDiscardConfirmDialog = false
                    vm.discardAndExit()
                },
                onDismiss = { showDiscardConfirmDialog = false },
            )
        }

        // BackHandler for Android system back button/gesture: close active panels first, then request exit
        androidx.activity.compose.BackHandler {
            when {
                showDiscardConfirmDialog -> showDiscardConfirmDialog = false
                showExitSaveDialog -> showExitSaveDialog = false
                brushPanelOpen -> brushPanelOpen = false
                layerPanelOpen -> layerPanelOpen = false
                colorPanelOpen -> colorPanelOpen = false
                settingsPanelOpen -> settingsPanelOpen = false
                moreToolsOpen -> moreToolsOpen = false
                selectionMenuOpen -> selectionMenuOpen = false
                selectionPanelOpen -> selectionPanelOpen = false
                selectionPropsOpen -> selectionPropsOpen = false
                vm.currentToolId != "brush" -> vm.applyTool("brush")
                else -> requestExit()
            }
        }

        val currentDensity = androidx.compose.ui.platform.LocalDensity.current
        val scaledDensity = remember(currentDensity, vm.paintingUiScale) {
            androidx.compose.ui.unit.Density(
                density = currentDensity.density * vm.paintingUiScale,
                fontScale = currentDensity.fontScale * vm.paintingUiScale
            )
        }

        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides scaledDensity
        ) {
            // ---- Top bar ----
            TopBar(
            modifier = Modifier.align(Alignment.TopEnd),
            vm = vm,
            opacity = vm.uiOpacity,
            hazeState = hazeState,
            onBack = requestExit,
            onRotateCw = {
                rotation = (rotation + 90) % 360
                flashIndicator()
            },
            onRotateCcw = {
                rotation = (rotation - 90 + 360) % 360
                flashIndicator()
            },
            onZoomIn = {
                zoom = (zoom * 1.2f).coerceAtMost(16f)
                flashIndicator()
            },
            onZoomOut = {
                zoom = (zoom / 1.2f).coerceAtLeast(0.1f)
                flashIndicator()
            },
            onLayers = {
                layerPanelOpen = true
                brushPanelOpen = false
                settingsPanelOpen = false
                colorPanelOpen = false
                moreToolsOpen = false
            },
            onSettings = {
                settingsPanelOpen = true
                layerPanelOpen = false
                brushPanelOpen = false
                colorPanelOpen = false
                moreToolsOpen = false
                drawingGuidePanelOpen = false
            },
        )

        // ---- Selection operations menu (全选 / 反选 / 清除选区) ----
        if (selectionMenuOpen) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset =
                    androidx.compose.ui.unit
                        .IntOffset(0, 180),
            ) {
                val popupContext = androidx.compose.ui.platform.LocalContext.current
                Box(
                    modifier =
                        Modifier
                            .systemHoverIcon(popupContext)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panel)
                            .border(
                                1.dp,
                                Morandi.border,
                                RoundedCornerShape(10.dp),
                            ),
                ) {
                    Column {
                        SelectionMenuItem("选中图层") { vm.selectAllAction() }
                        SelectionMenuItem("反选") { vm.invertSelectionAction() }
                        SelectionMenuItem("清除选区", danger = true) { vm.clearSelectionAction() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Morandi.border),
                        )
                        SelectionMenuItem("关闭") { selectionMenuOpen = false }
                    }
                }
            }
        }

        // ---- Left tool rail ----
        ToolRail(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp) // Gap from top bar
                    .fillMaxHeight(),
            vm = vm,
            hazeState = hazeState,
            opacity = vm.uiOpacity.toDouble(),
            tool = tool,
            onTool = {
                when (it) {
                    Tool.REFERENCE -> {
                        vm.referenceWindowOpen = !vm.referenceWindowOpen
                        moreToolsOpen = false
                    }
                    Tool.SYMMETRY -> {
                        vm.drawingGuide = vm.drawingGuide.copy(mode = com.reverie.paint.model.GuideMode.SYMMETRY, assistedDrawing = true)
                        drawingGuidePanelOpen = true
                        vm.applyTool(Tool.BRUSH.id)
                        moreToolsOpen = false
                    }
                    Tool.PERSPECTIVE -> {
                        vm.drawingGuide = vm.drawingGuide.copy(mode = com.reverie.paint.model.GuideMode.PERSPECTIVE, assistedDrawing = true)
                        drawingGuidePanelOpen = true
                        vm.applyTool(Tool.BRUSH.id)
                        moreToolsOpen = false
                    }
                    else -> {
                        vm.applyTool(it.id)
                        if (it in selectionTools) {
                            selectionPanelOpen = true
                        }
                        moreToolsOpen = false
                    }
                }
            },
            moreToolsOpen = moreToolsOpen,
            onToggleMoreTools = {
                brushPanelOpen = false
                colorPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = !moreToolsOpen
            },
            brushSize = vm.brushSize,
            onBrushSize = { size, commit -> vm.updateBrushSize(size, commit) },
            popupOpacity = vm.popupPanelOpacity,
            brushOpacity = vm.brushOpacity,
            onOpacity = { op, commit -> vm.updateBrushOpacity(op, commit) },
            brushColor = vm.brushColor,
            onOpenBrush = {
                brushPanelOpen = true
                colorPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = false
            },
            onOpenColor = {
                colorPanelOpen = true
                brushPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = false
            },
            onColorDropStart = { pos ->
                colorDropHex = vm.brushColor
                colorDropPos = pos
                isColorDropping = true
            },
            onColorDropMove = { pos ->
                colorDropPos = pos
            },
            onColorDropEnd = { pos ->
                handleColorDrop(pos)
            },
            onColorDropCancel = {
                isColorDropping = false
            },
        )

        // ---- Transform tool options panel ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.TRANSFORM && tfState.active,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            TransformPanel(
                vm = vm,
                tfState = tfState,
                hazeState = hazeState,
                onReset = {
                    vm.cancelTransformPreview()
                    val b = vm.contentBounds()
                    if (b != null && b[2] > 0 && b[3] > 0) {
                        tfState.reset(
                            androidx.compose.ui.geometry.Rect(
                                b[0].toFloat(),
                                b[1].toFloat(),
                                (b[0] + b[2]).toFloat(),
                                (b[1] + b[3]).toFloat(),
                            ),
                        )
                    }
                    vm.startTransformPreview()
                },
            )
        }

        // ---- Shape tools options panel (Krita tool-options style) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool in shapeTools,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            ShapeToolPanel(
                vm = vm,
                tool = tool,
                vertexCount = polyPoints.size,
                strokeWidth = vm.shapeStrokeWidth.toFloat(),
                filled = vm.shapeFillMode == 1,
                keepAspect = vm.shapeKeepAspect,
                hazeState = hazeState,
                onStrokeWidth = {
                    vm.updateShapeStrokeWidth(it.toDouble())
                },
                onFilled = {
                    vm.updateShapeFillMode(if (it) 1 else 0)
                },
                onKeepAspect = {
                    vm.updateShapeKeepAspect(it)
                },
                onFinish = {
                    if (polyPoints.isNotEmpty()) {
                        val pts = polyPoints.map { it.x.toInt() to it.y.toInt() }
                        when (tool) {
                            Tool.POLYGON -> {
                                vm.drawPolygon(pts, closed = true)
                            }

                            Tool.POLYLINE -> {
                                vm.drawPolygon(pts, closed = false)
                            }

                            Tool.SELECT_POLYGON -> {
                                vm.selectPolygon(pts)
                            }

                            Tool.PATH -> {
                                // Bézier path: smooth through the anchors with
                                // a Catmull-Rom spline, commit as a selection
                                // (Krita's path tool can convert to a selection)
                                val smooth = smoothPathPoints(pts)
                                if (smooth.size >= 3) vm.selectPolygon(smooth)
                            }

                            else -> {
                                Unit
                            }
                        }
                        polyPoints = emptyList()
                    }
                },
                onUndo = {
                    if (polyPoints.isNotEmpty()) {
                        polyPoints = polyPoints.dropLast(1)
                        vm.showActionToast("撤销顶点", R.drawable.ic_undo)
                    }
                },
                onCancel = { polyPoints = emptyList() },
            )
        }

        // ---- Crop tool options panel ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.CROP && cropRect != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            cropRect?.let { cr ->
                CropPanel(
                    rect = cr,
                    vm = vm,
                    hazeState = hazeState,
                    onApply = {
                        vm.cropCanvas(
                            cr.left.toInt(),
                            cr.top.toInt(),
                            maxOf(1, cr.width.toInt()),
                            maxOf(1, cr.height.toInt()),
                        )
                        cropRect = null
                    },
                    onCancel = { cropRect = null },
                )
            }
        }

        // ---- Gradient / Fill / Liquify tool options ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.GRADIENT,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            GradientPanel(
                vm = vm,
                type = vm.gradientType,
                onType = { vm.updateGradientType(it) },
                repeat = vm.gradientRepeat,
                onRepeat = { vm.updateGradientRepeat(it) },
                reverse = vm.gradientReverse,
                onReverse = { vm.updateGradientReverse(it) },
                hazeState = hazeState,
            )
        }
        // Solo-mode floating panel (bottom center, same style as fill/gradient
        // tool panels): 常规 keeps the layer's own effects, 取消所有效果
        // switches to pure color (100% opacity + Normal + no inherit alpha).
        // Closing solo restores every layer's original state exactly.
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.soloActive,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()) +
                    androidx.compose.animation.scaleIn(
                        Motion.enterSpring(),
                        initialScale = 0.95f,
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.scaleOut(
                        androidx.compose.animation.core
                            .tween(200),
                        targetScale = 0.95f,
                    ),
        ) {
            ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement
                            .spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolFloatChip(
                        label = "常规",
                        selected = !vm.soloRawMode,
                        onClick = {
                            if (vm.soloRawMode) vm.toggleSoloRawMode()
                        },
                    )
                    ToolFloatChip(
                        label = "取消所有效果",
                        selected = vm.soloRawMode,
                        onClick = {
                            if (!vm.soloRawMode) vm.toggleSoloRawMode()
                        },
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.FILL,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            FillPanel(
                vm = vm,
                tolerance = vm.fillTolerance,
                onTolerance = { vm.updateFillTolerance(it) },
                sampleLayers = vm.fillSampleLayers,
                onSampleLayers = { vm.updateFillSampleLayers(it) },
                hazeState = hazeState,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.LIQUIFY,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            LiquifyPanel(
                vm = vm,
                strength = liquifyStrength,
                onStrength = { liquifyStrength = it },
                mode = liquifyMode,
                onMode = { liquifyMode = it },
                brushSize = liquifyBrushSize,
                hazeState = hazeState,
                onBrushSize = {
                    liquifyBrushSize = it
                    vm.setLiquifyBrushSize(it.toDouble())
                },
            )
        }

        // ---- Floating selection panel (Krita tool-options style) ----
        // Context-sensitive: shown while a selection tool is active, sliding
        // in from the canvas edge; draggable so it never blocks the work
        androidx.compose.animation.AnimatedVisibility(
            visible = tool in selectionTools,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(selectionPanelOffsetX.roundToInt(), selectionPanelOffsetY.roundToInt()) }
                    .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(Motion.enterSpring()) +
                    androidx.compose.animation.slideInVertically(
                        Motion.enterSpring(),
                        initialOffsetY = { it },
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(200),
                        targetOffsetY = { it },
                    ),
        ) {
            SelectionFloatPanel(
                vm = vm,
                tool = tool,
                propsOpen = selectionPropsOpen,
                hazeState = hazeState,
                polyPoints = polyPoints,
                onPolyFinish = {
                    if (polyPoints.isNotEmpty()) {
                        val pts = polyPoints.map { it.x.toInt() to it.y.toInt() }
                        vm.selectPolygon(pts)
                        polyPoints = emptyList()
                    }
                },
                onPolyUndo = {
                    if (polyPoints.isNotEmpty()) {
                        polyPoints = polyPoints.dropLast(1)
                        vm.showActionToast("撤销顶点", R.drawable.ic_undo)
                    }
                },
                onPolyCancel = { polyPoints = emptyList() },
                onToggleProps = { selectionPropsOpen = !selectionPropsOpen },
                onDrag = { dx, dy ->
                    selectionPanelOffsetX += dx
                    selectionPanelOffsetY += dy
                },
            )
        }

        // ---- Global Active Selection Pill (when outside selection tools) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.hasSelection && tool !in selectionTools,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            enter = androidx.compose.animation.fadeIn(Motion.enterSpring()) +
                androidx.compose.animation.slideInVertically(Motion.enterSpring()) { -it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(150)) { -it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Morandi.accent)
                    )
                    Text(
                        "选区生效中",
                        color = Morandi.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Morandi.panel)
                            .border(0.5.dp, Morandi.border, RoundedCornerShape(6.dp))
                            .clickable { vm.clearSelectionAction() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "取消选区",
                            color = Morandi.subText,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        // ---- Floating Color Picker layer-source bar (PaintWorld style) ----
        PickerLayerSourceBar(
            tool = tool,
            vm = vm,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // ---- Action Toast (Undo/Redo, top-center, animated pill) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.undoToastEnabled && vm.actionToastMessage != null,
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core
                            .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    ) {
                        -it
                    },
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(200),
                    ) { -it },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .zIndex(25f),
        ) {
            val msg = vm.actionToastMessage ?: ""
            val iconRes = vm.actionToastIcon
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.94f))
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = msg,
                            tint = Morandi.text,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        msg,
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }
        }

        // ---- Transform indicator (top-center, animated pill) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = showIndicator && !vm.isFilterAdjustActive && (vm.actionToastMessage == null),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core
                            .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    ) {
                        -it
                    },
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(250),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(250),
                    ) { -it },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .zIndex(20f),
        ) {
            val zoomPct = (zoom * fitScale * 100).toInt()
            val rotDeg = ((rotation % 360 + 360) % 360).toInt()
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.94f))
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "缩放 $zoomPct%",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Box(
                        Modifier
                            .size(3.dp)
                            .background(Morandi.border, CircleShape),
                    )
                    Text(
                        "旋转 $rotDeg°",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }
        }

        // ---- Popup panels (topmost) ----
        AnimatedVisibility(
            visible = brushPanelOpen,
            enter = fadeIn(Motion.enterSpring()) + slideInVertically(Motion.enterSpring()) { 40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { 40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            BrushPanel(
                vm = vm,
                onClose = { brushPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }
        AnimatedVisibility(
            visible = layerPanelOpen,
            enter = fadeIn(Motion.enterSpring()) + slideInVertically(Motion.enterSpring()) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            LayerPanel(
                vm = vm,
                onClose = {
                    layerPanelOpen = false
                    targetFilterLayers = null
                },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
                initialTargetFilters = targetFilterLayers,
                initialFilterCategoryId = filterCategoryHint,
            )
        }
        AnimatedVisibility(
            visible = settingsPanelOpen,
            enter = fadeIn(Motion.enterSpring()) + slideInVertically(Motion.enterSpring()) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            SettingsPanel(
                vm = vm,
                onClose = { settingsPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
                onOpenFilters = { targetIndices ->
                    settingsPanelOpen = false
                    targetFilterLayers = targetIndices
                    layerPanelOpen = true
                },
            )
        }
        AnimatedVisibility(
            visible = colorPanelOpen,
            enter = fadeIn(Motion.enterSpring()) + slideInVertically(Motion.enterSpring()) { 40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { 40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            ColorPanel(
                vm = vm,
                onClose = { colorPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
                onColorDropStart = { pos ->
                    colorDropHex = vm.brushColor
                    colorDropPos = pos
                    isColorDropping = true
                },
                onColorDropMove = { pos ->
                    colorDropPos = pos
                },
                onColorDropEnd = { pos ->
                    handleColorDrop(pos)
                },
                onColorDropCancel = {
                    isColorDropping = false
                },
            )
        }
        AnimatedVisibility(
            visible = moreToolsOpen,
            enter =
                fadeIn(Motion.enterSpring()) +
                    slideInHorizontally(Motion.enterSpring()) { -40 },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            AllToolsPanel(
                vm = vm,
                tool = tool,
                onTool = {
                    when (it) {
                        Tool.REFERENCE -> {
                            vm.referenceWindowOpen = !vm.referenceWindowOpen
                            moreToolsOpen = false
                        }
                        Tool.SYMMETRY -> {
                            vm.drawingGuide = vm.drawingGuide.copy(mode = com.reverie.paint.model.GuideMode.SYMMETRY, assistedDrawing = true)
                            drawingGuidePanelOpen = true
                            vm.applyTool(Tool.BRUSH.id)
                            moreToolsOpen = false
                        }
                        Tool.PERSPECTIVE -> {
                            vm.drawingGuide = vm.drawingGuide.copy(mode = com.reverie.paint.model.GuideMode.PERSPECTIVE, assistedDrawing = true)
                            drawingGuidePanelOpen = true
                            vm.applyTool(Tool.BRUSH.id)
                            moreToolsOpen = false
                        }
                        else -> {
                            vm.applyTool(it.id)
                            if (it in selectionTools) {
                                selectionPanelOpen = true
                            }
                            moreToolsOpen = false
                        }
                    }
                },
                onOpenBrush = {
                    brushPanelOpen = true
                    moreToolsOpen = false
                },
                onClose = { moreToolsOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }

        // ---- Persistent Floating Reference Window (常态固定显示参考窗口) ----
        AnimatedVisibility(
            visible = vm.referenceWindowOpen,
            enter = fadeIn(Motion.enterSpring()) + androidx.compose.animation.scaleIn(Motion.enterSpring(), initialScale = 0.92f),
            exit = fadeOut(tween(150)) + androidx.compose.animation.scaleOut(tween(150), targetScale = 0.92f),
            modifier = Modifier.zIndex(8f),
        ) {
            ReferenceWindow(
                vm = vm,
                onClose = { vm.referenceWindowOpen = false },
                hazeState = hazeState,
                opacity = vm.popupPanelOpacity,
            )
        }

        // Brush Studio full-screen dedicated page
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.brushStudioOpen,
            enter =
                fadeIn(Motion.enterSpring()) +
                    slideInHorizontally(Motion.enterSpring()) { it / 4 },
            exit =
                fadeOut(tween(180)) +
                    slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 4 },
            modifier = Modifier.zIndex(600f),
        ) {
            BrushStudioPage(
                vm = vm,
                presetIndex = vm.brushPresetIndex,
                onBack = { vm.brushStudioOpen = false },
                hazeState = hazeState,
            )
        }

        // More Settings full-screen overlay (stays inside painting page, back returns to canvas)
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.moreSettingsOpen,
            enter =
                fadeIn(Motion.enterSpring()) +
                    slideInHorizontally(Motion.enterSpring()) { it / 5 },
            exit =
                fadeOut(tween(180)) +
                    slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 4 },
            modifier = Modifier.zIndex(500f),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Morandi.canvasBg),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar with back button (no bottom navigation bar here)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(Morandi.panel)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReIconButton(R.drawable.ic_arrow_left, "返回画布", { vm.closeMoreSettings() }, tint = Morandi.text)
                        Text(
                            "更多设置",
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        com.reverie.paint.ui.home.SettingsPageContent(
                            vm = vm,
                            onExit = { vm.closeMoreSettings() },
                        )
                    }
                }
            }
        }



        // Drawing Guides & Assist panel
        if (drawingGuidePanelOpen) {
            DrawingGuidePanel(
                vm = vm,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp),
                onDismiss = { drawingGuidePanelOpen = false },
                hazeState = hazeState,
            )
        }

        // Text tool (Krita style modal dialog)
        textDialogPos?.let { (tx, ty) ->
            com.reverie.paint.ui.dialog.KritaTextToolDialog(
                brushColorHex = vm.brushColor,
                onConfirm = { txt, fontSize ->
                    vm.drawText(tx, ty, txt, fontSize)
                    textDialogPos = null
                },
                onDismiss = { textDialogPos = null },
            )
        }

        // Blocking Loading & Saving Modal Overlay (prevents any clicks/interactions)
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.isBlockingLoading,
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(150),
                ) +
                    androidx.compose.animation.scaleIn(
                        androidx.compose.animation.core
                            .tween(150),
                        initialScale = 0.94f,
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(150),
                ) +
                    androidx.compose.animation.scaleOut(
                        androidx.compose.animation.core
                            .tween(150),
                        targetScale = 0.94f,
                    ),
            modifier = Modifier.zIndex(999f),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Color.Black
                                .copy(alpha = 0.45f),
                        ).clickable(
                            interactionSource =
                                remember {
                                    androidx.compose.foundation.interaction
                                        .MutableInteractionSource()
                                },
                            indication = null,
                            onClick = {},
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Morandi.panelHi)
                            .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Morandi.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = vm.blockingLoadingMessage.ifBlank { "请稍候..." },
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // ---- Floating Color Droplet Overlay (ColorDrop) ----
        if (isColorDropping) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1000f)
            ) {
                val dropColor = parseColor(colorDropHex)
                val density = LocalDensity.current
                val xDp = with(density) { colorDropPos.x.toDp() }
                val yDp = with(density) { colorDropPos.y.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = xDp - 20.dp, y = yDp - 20.dp)
                        .size(40.dp)
                        .shadow(elevation = 12.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(dropColor)
                        .border(2.5.dp, Color.White, CircleShape)
                ) {
                    // Inner contrast ring for light / white colors
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .align(Alignment.Center)
                            .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                    )
                    // Center crosshair / precision dot
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(if (dropColor.luminance() > 0.5f) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.8f))
                    )
                }
            }
        }
    }
}
}

/** Text input dialog for the text tool (MVP). */
@Composable
fun TextInputDialog(
    onConfirm: (String, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(48f) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入文字", color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("在这里输入...", color = Morandi.subText) },
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panel,
                            unfocusedContainerColor = Morandi.panel,
                            cursorColor = Morandi.accent,
                        ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("字号", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    ReSlider(
                        value = ((fontSize - 8f) / 192f).coerceIn(0f, 1f),
                        onValue = { frac -> fontSize = 8f + frac * 192f },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${fontSize.roundToInt()}", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                }
            }
        },
        confirmButton = {
            ReTextButton("确定", { onConfirm(text, fontSize.toDouble()) }, textColor = Morandi.accentHi)
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

private fun smoothPathPoints(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    if (points.size < 3) return points
    val result = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until points.size - 1) {
        val p0 = points[maxOf(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(points.size - 1, i + 2)]
        for (step in 0 until 16) {
            val u = step / 16f
            val u2 = u * u
            val u3 = u2 * u
            val x =
                0.5f * (
                    (2 * p1.first) +
                        (-p0.first + p2.first) * u +
                        (2 * p0.first - 5 * p1.first + 4 * p2.first - p3.first) * u2 +
                        (-p0.first + 3 * p1.first - 3 * p2.first + p3.first) * u3
                )
            val y =
                0.5f * (
                    (2 * p1.second) +
                        (-p0.second + p2.second) * u +
                        (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * u2 +
                        (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * u3
                )
            result += x.toInt() to y.toInt()
        }
    }
    result += points.last()
    return result.distinct()
}
