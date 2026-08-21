package com.reverie.paint.ui.painting

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class PlacedReferenceImage(
    val bitmap: Bitmap,
    val bounds: Rect
)

@Composable
fun ReferenceWindow(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.96f,
) {
    val density = LocalDensity.current
    var showSettingsPopup by remember { mutableStateOf(false) }

    // Multi-image selection launcher
    val importImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            vm.importReferenceImagesFromUris(uris)
        }
    }

    val windowShape = RoundedCornerShape(16.dp)

    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    // Floating Window Container
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    vm.referenceWindowX.roundToInt(),
                    vm.referenceWindowY.roundToInt()
                )
            }
            .size(vm.referenceWindowWidth.dp, vm.referenceWindowHeight.dp)
            .shadow(14.dp, windowShape)
            .clip(windowShape)
            .then(
                if (vm.blurBackground && hazeState != null) {
                    Modifier.hazeChild(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.1f, 0.98f)),
                            tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.1f, 0.98f))),
                            blurRadius = 24.dp,
                            noiseFactor = 0.05f
                        )
                    )
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = opacity))
                }
            )
            .border(1.dp, Morandi.border, windowShape)
    ) {
        // 1. Full-bleed Viewport Content (Underneath navigation bars)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF161719))
                .onSizeChanged { viewportSize = it }
                .pointerInput(vm.referenceAllowRotation) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var localZoom = vm.referenceZoom
                        var localRotation = vm.referenceRotation
                        var localPanX = vm.referencePanX
                        var localPanY = vm.referencePanY

                        var transformStarted = false
                        var prevCentroid = Offset.Zero
                        var prevDistance = 1f
                        var prevAngle = 0f
                        var previousSinglePoint = down.position
                        val downTime = System.currentTimeMillis()
                        var maxMovement = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                val duration = System.currentTimeMillis() - downTime
                                if (!transformStarted && maxMovement < 12f && duration < 300L) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTimeMs < 300L) {
                                        vm.resetReferenceTransform()
                                        lastTapTimeMs = 0L
                                    } else {
                                        lastTapTimeMs = now
                                        vm.referenceBarsCollapsed = !vm.referenceBarsCollapsed
                                    }
                                }
                                break
                            }

                            if (pressed.size >= 2) {
                                val p1 = pressed[0].position
                                val p2 = pressed[1].position
                                val centroid = (p1 + p2) / 2f
                                val distance = kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y).coerceAtLeast(1f)
                                val angle = angleDegrees(p1, p2)

                                if (!transformStarted) {
                                    transformStarted = true
                                    prevCentroid = centroid
                                    prevDistance = distance
                                    prevAngle = angle
                                } else {
                                    val k = (distance / prevDistance).coerceIn(0.2f, 5f)
                                    val dRot = normalizeAngle(angle - prevAngle).coerceIn(-25f, 25f)

                                    val viewW = viewportSize.width.toFloat().coerceAtLeast(1f)
                                    val viewH = viewportSize.height.toFloat().coerceAtLeast(1f)
                                    val centerX = viewW / 2f + localPanX
                                    val centerY = viewH / 2f + localPanY
                                    val vx = prevCentroid.x - centerX
                                    val vy = prevCentroid.y - centerY
                                    val radians = Math.toRadians((if (vm.referenceAllowRotation) dRot else 0f).toDouble())
                                    val cosR = kotlin.math.cos(radians).toFloat()
                                    val sinR = kotlin.math.sin(radians).toFloat()
                                    val rx = vx * cosR - vy * sinR
                                    val ry = vx * sinR + vy * cosR

                                    localZoom = (localZoom * k).coerceIn(0.05f, 40f)
                                    if (vm.referenceAllowRotation) {
                                        localRotation = (localRotation + dRot) % 360f
                                    }
                                    localPanX = centroid.x - k * rx - viewW / 2f
                                    localPanY = centroid.y - k * ry - viewH / 2f

                                    vm.referenceZoom = localZoom
                                    vm.referenceRotation = localRotation
                                    vm.referencePanX = localPanX
                                    vm.referencePanY = localPanY

                                    prevCentroid = centroid
                                    prevDistance = distance
                                    prevAngle = angle
                                }
                                pressed.forEach { it.consume() }
                                continue
                            }

                            if (transformStarted) {
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            // Single finger drag
                            val point = pressed.first()
                            val delta = point.position - previousSinglePoint
                            previousSinglePoint = point.position
                            maxMovement += kotlin.math.hypot(delta.x, delta.y)

                            localPanX += delta.x
                            localPanY += delta.y
                            vm.referencePanX = localPanX
                            vm.referencePanY = localPanY
                            point.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (vm.referenceActiveTab == 0) {
                // Image Tab
                val images = vm.referenceImages
                if (images.isNotEmpty()) {
                    ReferenceImagesView(
                        images = images,
                        zoom = vm.referenceZoom,
                        rotation = vm.referenceRotation,
                        panX = vm.referencePanX,
                        panY = vm.referencePanY,
                        isGrayscale = vm.referenceIsGrayscale,
                        isFlipped = vm.referenceIsFlipped
                    )
                } else {
                    // Empty Placeholder with "添加图片" button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                            .clickable { importImagesLauncher.launch("image/*") }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "添加图片",
                            color = Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                // Canvas Tab (Main canvas projection bitmap)
                val canvasBmp = vm.displayBitmap
                val rev = vm.displayRevision
                val memoized = remember(canvasBmp, rev) { canvasBmp }
                if (memoized != null) {
                    ReferenceSingleBitmapView(
                        bitmap = memoized,
                        zoom = vm.referenceZoom,
                        rotation = vm.referenceRotation,
                        panX = vm.referencePanX,
                        panY = vm.referencePanY,
                        isGrayscale = vm.referenceIsGrayscale,
                        isFlipped = vm.referenceIsFlipped
                    )
                } else {
                    Text(
                        text = "画布暂无内容",
                        color = Morandi.subText,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 2. Persistent Top Drag Handle when bars are collapsed
        if (vm.referenceBarsCollapsed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            vm.referenceWindowX += dragAmount.x
                            vm.referenceWindowY += dragAmount.y
                        }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(36.dp, 3.5.dp)
                        .clip(CircleShape)
                        .background(Morandi.subText.copy(alpha = 0.45f))
                )
            }
        }

        // 3. Top Navigation Bar (Overlaid on top)
        AnimatedVisibility(
            visible = !vm.referenceBarsCollapsed,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReferenceTopBar(
                onDrag = { dx, dy ->
                    vm.referenceWindowX += dx
                    vm.referenceWindowY += dy
                },
                onToggleSettings = { showSettingsPopup = !showSettingsPopup },
                onClose = onClose
            )
        }

        // 4. Bottom Navigation Bar (Overlaid at bottom)
        AnimatedVisibility(
            visible = !vm.referenceBarsCollapsed,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReferenceBottomBar(
                activeTab = vm.referenceActiveTab,
                onTabSelect = { tab ->
                    vm.referenceActiveTab = tab
                    if (tab == 0 && vm.referenceImages.isEmpty()) {
                        importImagesLauncher.launch("image/*")
                    }
                },
                onResizeDrag = { dx, dy ->
                    val newW = (vm.referenceWindowWidth + dx / density.density).coerceIn(160f, 600f)
                    val newH = (vm.referenceWindowHeight + dy / density.density).coerceIn(160f, 700f)
                    vm.referenceWindowWidth = newW
                    vm.referenceWindowHeight = newH
                }
            )
        }

        // 5. Persistent Bottom-Right Resize Handle when bars are collapsed
        if (vm.referenceBarsCollapsed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newW = (vm.referenceWindowWidth + dragAmount.x / density.density).coerceIn(160f, 600f)
                            val newH = (vm.referenceWindowHeight + dragAmount.y / density.density).coerceIn(160f, 700f)
                            vm.referenceWindowWidth = newW
                            vm.referenceWindowHeight = newH
                        }
                    }
                    .padding(bottom = 3.dp, end = 3.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val strokeW = 1.6.dp.toPx()
                    val color = Morandi.subText.copy(alpha = 0.6f)
                    drawLine(
                        color = color,
                        start = Offset(size.width, size.height * 0.35f),
                        end = Offset(size.width * 0.35f, size.height),
                        strokeWidth = strokeW
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width, size.height * 0.72f),
                        end = Offset(size.width * 0.72f, size.height),
                        strokeWidth = strokeW
                    )
                }
            }
        }

        // 6. Settings Popup Dropdown (matching Image 2)
        if (showSettingsPopup) {
            ReferenceSettingsPopup(
                vm = vm,
                onDismiss = { showSettingsPopup = false },
                onAddImage = {
                    showSettingsPopup = false
                    vm.referenceActiveTab = 0
                    importImagesLauncher.launch("image/*")
                },
                onClearImage = {
                    showSettingsPopup = false
                    vm.clearReferenceImage()
                }
            )
        }
    }
}

@Composable
private fun ReferenceTopBar(
    onDrag: (Float, Float) -> Unit,
    onToggleSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Morandi.panel.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Drag Pill Handle in center top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .size(36.dp, 3.5.dp)
                .clip(CircleShape)
                .background(Morandi.subText.copy(alpha = 0.5f))
        )

        // Left Title
        Text(
            text = "参考",
            color = Morandi.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        // Right Action Icons
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings Gear Icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Close (X) Icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = "关闭",
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ReferenceBottomBar(
    activeTab: Int,
    onTabSelect: (Int) -> Unit,
    onResizeDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Morandi.panel.copy(alpha = 0.92f))
            .padding(start = 8.dp, end = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab Buttons: "图片" and "画布"
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab: 图片
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onTabSelect(0) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image_adjust),
                    contentDescription = "图片",
                    tint = if (activeTab == 0) Morandi.accent else Morandi.subText,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "图片",
                    color = if (activeTab == 0) Morandi.accent else Morandi.subText,
                    fontSize = 12.sp,
                    fontWeight = if (activeTab == 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            // Tab: 画布
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onTabSelect(1) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_rect),
                    contentDescription = "画布",
                    tint = if (activeTab == 1) Morandi.accent else Morandi.subText,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "画布",
                    color = if (activeTab == 1) Morandi.accent else Morandi.subText,
                    fontSize = 12.sp,
                    fontWeight = if (activeTab == 1) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        // Bottom-Right Corner Resize Handle
        Box(
            modifier = Modifier
                .size(26.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onResizeDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(bottom = 3.dp, end = 3.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                val strokeW = 1.6.dp.toPx()
                val color = Morandi.subText.copy(alpha = 0.6f)
                drawLine(
                    color = color,
                    start = Offset(size.width, size.height * 0.35f),
                    end = Offset(size.width * 0.35f, size.height),
                    strokeWidth = strokeW
                )
                drawLine(
                    color = color,
                    start = Offset(size.width, size.height * 0.72f),
                    end = Offset(size.width * 0.72f, size.height),
                    strokeWidth = strokeW
                )
            }
        }
    }
}

/**
 * Arranges N images naturally in rows/columns without stretching,
 * optimizing row partition so the overall layout aspect ratio is as close to 1:1 as possible.
 */
private fun computeOptimalLayout(
    images: List<Bitmap>,
    spacing: Float = 16f
): Pair<List<PlacedReferenceImage>, Size> {
    if (images.isEmpty()) return emptyList<PlacedReferenceImage>() to Size.Zero
    if (images.size == 1) {
        val bmp = images[0]
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        return listOf(PlacedReferenceImage(bmp, Rect(-w / 2f, -h / 2f, w / 2f, h / 2f))) to Size(w, h)
    }

    val n = images.size
    val stdH = 600f
    // Compute natural aspect ratios
    val aspectRatios = images.map {
        val w = it.width.toFloat().coerceAtLeast(1f)
        val h = it.height.toFloat().coerceAtLeast(1f)
        w / h
    }

    var bestRows = 1
    var bestDiff = Float.MAX_VALUE

    // Test row counts from 1 to n to find layout closest to 1:1
    for (r in 1..n) {
        val itemsPerRow = (n + r - 1) / r
        var maxRowW = 0f
        var totalH = 0f
        var curIdx = 0
        for (row in 0 until r) {
            val count = min(itemsPerRow, n - curIdx)
            if (count <= 0) break
            var rowW = 0f
            for (j in 0 until count) {
                rowW += aspectRatios[curIdx + j] * stdH
            }
            rowW += (count - 1) * spacing
            maxRowW = max(maxRowW, rowW)
            totalH += stdH + (if (row > 0) spacing else 0f)
            curIdx += count
        }
        val aspect = if (totalH > 0f) maxRowW / totalH else 1f
        val diff = abs(aspect - 1.0f)
        if (diff < bestDiff) {
            bestDiff = diff
            bestRows = r
        }
    }

    // Build placed items using bestRows
    val itemsPerRow = (n + bestRows - 1) / bestRows
    val rowPlacedLists = mutableListOf<List<PlacedReferenceImage>>()
    var curIdx = 0
    var maxRowWidth = 0f

    for (row in 0 until bestRows) {
        val count = min(itemsPerRow, n - curIdx)
        if (count <= 0) break
        val rowItems = mutableListOf<PlacedReferenceImage>()
        var curX = 0f
        for (j in 0 until count) {
            val bmp = images[curIdx + j]
            val w = aspectRatios[curIdx + j] * stdH
            rowItems.add(
                PlacedReferenceImage(
                    bitmap = bmp,
                    bounds = Rect(curX, 0f, curX + w, stdH)
                )
            )
            curX += w + spacing
        }
        val thisRowW = curX - spacing
        maxRowWidth = max(maxRowWidth, thisRowW)
        rowPlacedLists.add(rowItems)
        curIdx += count
    }

    val totalHeight = rowPlacedLists.size * stdH + (rowPlacedLists.size - 1) * spacing
    val finalPlaced = mutableListOf<PlacedReferenceImage>()

    // Center each row horizontally and stack vertically centered around (0, 0)
    var curY = -totalHeight / 2f
    for (rowItems in rowPlacedLists) {
        val rowW = if (rowItems.isNotEmpty()) rowItems.last().bounds.right else 0f
        val startX = -rowW / 2f
        for (item in rowItems) {
            finalPlaced.add(
                item.copy(
                    bounds = Rect(
                        left = startX + item.bounds.left,
                        top = curY,
                        right = startX + item.bounds.right,
                        bottom = curY + stdH
                    )
                )
            )
        }
        curY += stdH + spacing
    }

    return finalPlaced to Size(maxRowWidth, totalHeight)
}

@Composable
private fun ReferenceImagesView(
    images: List<Bitmap>,
    zoom: Float,
    rotation: Float,
    panX: Float,
    panY: Float,
    isGrayscale: Boolean,
    isFlipped: Boolean,
    modifier: Modifier = Modifier
) {
    val (placedImages, totalSize) = remember(images) {
        computeOptimalLayout(images)
    }

    val imageBitmaps = remember(images) {
        images.map { it to it.asImageBitmap() }.toMap()
    }

    val colorFilter = remember(isGrayscale) {
        if (isGrayscale) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val totalW = totalSize.width
        val totalH = totalSize.height
        if (totalW <= 0f || totalH <= 0f) return@Canvas

        val fitScale = min(size.width / totalW, size.height / totalH) * 0.92f
        val finalScale = fitScale * zoom
        val centerX = size.width / 2f + panX
        val centerY = size.height / 2f + panY

        withTransform({
            translate(centerX, centerY)
            rotate(rotation)
            scale(
                scaleX = if (isFlipped) -finalScale else finalScale,
                scaleY = finalScale,
                pivot = Offset.Zero
            )
        }) {
            for (placed in placedImages) {
                val imgBitmap = imageBitmaps[placed.bitmap] ?: continue
                val b = placed.bounds
                withTransform({
                    translate(b.left, b.top)
                    scale(
                        scaleX = b.width / placed.bitmap.width.toFloat(),
                        scaleY = b.height / placed.bitmap.height.toFloat(),
                        pivot = Offset.Zero
                    )
                }) {
                    drawImage(
                        image = imgBitmap,
                        colorFilter = colorFilter
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceSingleBitmapView(
    bitmap: Bitmap,
    zoom: Float,
    rotation: Float,
    panX: Float,
    panY: Float,
    isGrayscale: Boolean,
    isFlipped: Boolean,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val colorFilter = remember(isGrayscale) {
        if (isGrayscale) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return@Canvas

        val fitScale = min(size.width / bw, size.height / bh) * 0.95f
        val finalScale = fitScale * zoom
        val centerX = size.width / 2f + panX
        val centerY = size.height / 2f + panY

        withTransform({
            translate(centerX, centerY)
            rotate(rotation)
            scale(
                scaleX = if (isFlipped) -finalScale else finalScale,
                scaleY = finalScale,
                pivot = Offset.Zero
            )
            translate(-bw / 2f, -bh / 2f)
        }) {
            drawImage(
                image = imageBitmap,
                colorFilter = colorFilter
            )
        }
    }
}

@Composable
private fun ReferenceSettingsPopup(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
    onAddImage: () -> Unit,
    onClearImage: () -> Unit
) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 36),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier
                .width(180.dp)
                .shadow(16.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                .padding(vertical = 8.dp, horizontal = 12.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. 去色 (Grayscale Switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "去色",
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = vm.referenceIsGrayscale,
                        onCheckedChange = { vm.referenceIsGrayscale = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Morandi.accent,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Morandi.panel
                        ),
                        modifier = Modifier.scale(0.75f)
                    )
                }

                // 2. 允许旋转 (Allow Rotation Switch, auto-calibrates rotation when turned off)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "允许旋转",
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = vm.referenceAllowRotation,
                        onCheckedChange = { vm.updateReferenceAllowRotation(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Morandi.accent,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Morandi.panel
                        ),
                        modifier = Modifier.scale(0.75f)
                    )
                }

                // 3. 水平翻转 (Horizontal Flip)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.referenceIsFlipped = !vm.referenceIsFlipped }
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "水平翻转",
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_flip_horizontal),
                        contentDescription = "水平翻转",
                        tint = if (vm.referenceIsFlipped) Morandi.accent else Morandi.text,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Morandi.border.copy(alpha = 0.5f))
                )

                // 4. 添加图片 (Add Image)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onAddImage)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "添加图片",
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 5. 清除图片 (Clear Image)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearImage)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "清除图片",
                        color = Color(0xFFE55858),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
