/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

data class ScratchPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val rotation: Float = 0f,
)

/**
 * 将试画板内容生成为 256x256 的预设缩略图
 * 优先裁剪试画板上的真实位图笔迹；若试画板为空，则绘制经典的优雅弧线笔触
 */
internal fun captureScratchpadAsThumbnail(
    context: Context,
    vm: PaintViewModel,
    scratchBitmap: Bitmap?,
    strokes: List<List<ScratchPoint>>,
) {
    val size = 256
    val outBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(outBitmap)
    // 雅致的浅色卡片底色
    canvas.drawColor(android.graphics.Color.rgb(243, 242, 240))

    val brushColorInt = runCatching { android.graphics.Color.parseColor(vm.brushColor) }.getOrDefault(android.graphics.Color.DKGRAY)

    if (scratchBitmap != null) {
        val bounds = findNonTransparentBounds(scratchBitmap)
        if (bounds != null && bounds.width() > 6 && bounds.height() > 6) {
            val pad = 32f
            val targetBox = size - pad * 2f
            val scale = minOf(targetBox / bounds.width(), targetBox / bounds.height()).coerceIn(0.1f, 4.0f)
            val dstW = bounds.width() * scale
            val dstH = bounds.height() * scale
            val left = pad + (targetBox - dstW) / 2f
            val top = pad + (targetBox - dstH) / 2f
            val srcRect = Rect(bounds)
            val dstRect = RectF(left, top, left + dstW, top + dstH)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(scratchBitmap, srcRect, dstRect, paint)
            vm.capturePresetThumbnail(outBitmap)
            return
        }
    }

    // 兜底：若试画板无有效笔迹，绘制经典平滑笔画
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColorInt
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = (vm.brushSize.toFloat()).coerceIn(12f, 36f)
        alpha = (vm.brushOpacity * 255).toInt().coerceIn(40, 255)
    }

    val path = android.graphics.Path()
    path.moveTo(40f, 216f)
    path.cubicTo(80f, 160f, 176f, 100f, 216f, 40f)
    canvas.drawPath(path, paint)

    vm.capturePresetThumbnail(outBitmap)
}

/** 查找非透明像素的外接包围盒 */
private fun findNonTransparentBounds(bitmap: Bitmap): Rect? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null

    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1

    val pixels = IntArray(w)
    for (y in 0 until h) {
        bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val alpha = (pixels[x] ushr 24) and 0xFF
            if (alpha > 12) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }

    return if (maxX >= minX && maxY >= minY) {
        Rect(minX, minY, maxX + 1, maxY + 1)
    } else {
        null
    }
}

/**
 * 试画台核心画布：直接呈现 C++ 引擎真实渲染的离线位图
 */
@Composable
internal fun ScratchpadCanvas(
    vm: PaintViewModel,
    scratchBitmap: Bitmap?,
    renderTick: Int,
    onStrokeStart: (ScratchPoint) -> Unit,
    onStrokeAddPoints: (List<ScratchPoint>) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val initP = if (down.pressure > 0f) down.pressure.coerceIn(0.01f, 1.0f) else 1.0f
                vm.scratchpadLiveInput = initP
                onStrokeStart(ScratchPoint(down.position.x, down.position.y, initP))

                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        vm.scratchpadLiveInput = -1f
                        vm.scratchpadLiveOutput = -1f
                        onStrokeEnd()
                        break
                    }
                    if (change.position != change.previousPosition) {
                        change.consume()
                        val p = if (change.pressure > 0f) change.pressure.coerceIn(0.01f, 1.0f) else initP
                        vm.scratchpadLiveInput = p
                        val historical = change.historical
                        if (historical.isNotEmpty()) {
                            val batch = ArrayList<ScratchPoint>(historical.size + 1)
                            for (h in historical) {
                                batch.add(ScratchPoint(h.position.x, h.position.y, p))
                            }
                            batch.add(ScratchPoint(change.position.x, change.position.y, p))
                            onStrokeAddPoints(batch)
                        } else {
                            onStrokeAddPoints(listOf(ScratchPoint(change.position.x, change.position.y, p)))
                        }
                    }
                }
            }
        },
    ) {
        // 读取 renderTick 触发重绘
        @Suppress("UNUSED_VARIABLE")
        val tick = renderTick

        if (scratchBitmap != null && !scratchBitmap.isRecycled) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(scratchBitmap, 0f, 0f, null)
            }
        }
    }
}

/**
 * 完整高内聚的试画板面板：内置离线 C++ 渲染调度、手势下发、撤销、清空与缩略图提取
 */
@Composable
internal fun BrushStudioScratchpadPanel(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scratchBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val strokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    val currentStroke = remember { mutableListOf<ScratchPoint>() }
    var renderTick by remember { mutableIntStateOf(0) }
    var solidBg by remember { mutableStateOf(false) }

    val thumbnailUpdatedToast = stringResource(R.string.brush_studio_toast_thumbnail_updated)

    DisposableEffect(Unit) {
        onDispose {
            ReverieCoreBridge.scratchpadEnd()
            scratchBitmap?.recycle()
            scratchBitmap = null
        }
    }

    // 重放已有笔画至 C++ 试画板
    fun replayStrokesOnEngine(bmp: Bitmap) {
        ReverieCoreBridge.scratchpadClear()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val p0 = stroke[0]
            ReverieCoreBridge.scratchpadStrokeStart(
                p0.x.toDouble(), p0.y.toDouble(), p0.pressure.toDouble(),
                p0.tiltX.toDouble(), p0.tiltY.toDouble(), p0.rotation.toDouble()
            )
            for (i in 1 until stroke.size) {
                val pi = stroke[i]
                ReverieCoreBridge.scratchpadStrokeMove(
                    pi.x.toDouble(), pi.y.toDouble(), pi.pressure.toDouble(),
                    pi.tiltX.toDouble(), pi.tiltY.toDouble(), pi.rotation.toDouble()
                )
            }
            ReverieCoreBridge.scratchpadStrokeEnd()
        }
        bmp.eraseColor(0)
        ReverieCoreBridge.scratchpadRender(bmp)
        renderTick++
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .border(0.6.dp, Morandi.border.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        // ---- 顶部控制栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.brush_studio_scratchpad_title),
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.brush_studio_scratchpad_sub),
                    color = Morandi.subText,
                    fontSize = 10.sp,
                )
            }

            // 撤销动作
            ReIconButton(
                R.drawable.ic_undo,
                stringResource(R.string.brush_studio_scratchpad_undo),
                onTap = {
                    if (strokes.isNotEmpty()) {
                        strokes.removeAt(strokes.lastIndex)
                        val bmp = scratchBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            replayStrokesOnEngine(bmp)
                        }
                    }
                },
                tint = if (strokes.isNotEmpty()) Morandi.text else Morandi.subText.copy(alpha = 0.35f),
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 清空动作
            ReIconButton(
                R.drawable.ic_trash,
                stringResource(R.string.brush_studio_scratchpad_clear),
                onTap = {
                    strokes.clear()
                    currentStroke.clear()
                    ReverieCoreBridge.scratchpadClear()
                    scratchBitmap?.eraseColor(0)
                    renderTick++
                },
                tint = if (strokes.isNotEmpty()) Morandi.text else Morandi.subText.copy(alpha = 0.35f),
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 背景棋盘格 / 纯色切换
            ReIconButton(
                if (solidBg) R.drawable.ic_layers else R.drawable.ic_circle,
                stringResource(R.string.brush_studio_tex_enable),
                onTap = { solidBg = !solidBg },
                tint = Morandi.subText,
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 设为预设图标
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panel.copy(alpha = 0.85f))
                    .clickable {
                        captureScratchpadAsThumbnail(context, vm, scratchBitmap, strokes)
                        Toast.makeText(context, thumbnailUpdatedToast, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_pencil),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        stringResource(R.string.brush_studio_scratchpad_set_icon),
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (isCollapsible) {
                Spacer(Modifier.width(4.dp))
                ReIconButton(
                    R.drawable.ic_x,
                    stringResource(R.string.common_close),
                    onTap = onClose,
                    tint = Morandi.subText,
                    iconSize = 15.dp,
                    size = 30.dp,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ---- 画布区域 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (solidBg) Morandi.panelHi else Morandi.panel)
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0 &&
                        (size.width != canvasSize.width || size.height != canvasSize.height)
                    ) {
                        canvasSize = size
                        val newBmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        scratchBitmap?.recycle()
                        scratchBitmap = newBmp
                        ReverieCoreBridge.scratchpadStart(size.width, size.height)
                        replayStrokesOnEngine(newBmp)
                    }
                },
        ) {
            if (!solidBg) {
                CheckerboardBackground(modifier = Modifier.fillMaxSize())
            }

            ScratchpadCanvas(
                vm = vm,
                scratchBitmap = scratchBitmap,
                renderTick = renderTick,
                onStrokeStart = { p ->
                    currentStroke.clear()
                    currentStroke.add(p)
                    ReverieCoreBridge.scratchpadStrokeStart(
                        p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                        p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble()
                    )
                    val bmp = scratchBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        ReverieCoreBridge.scratchpadRender(bmp)
                    }
                    renderTick++
                },
                onStrokeAddPoints = { pts ->
                    currentStroke.addAll(pts)
                    for (p in pts) {
                        ReverieCoreBridge.scratchpadStrokeMove(
                            p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                            p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble()
                        )
                    }
                    val bmp = scratchBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        ReverieCoreBridge.scratchpadRender(bmp)
                    }
                    renderTick++
                },
                onStrokeEnd = {
                    ReverieCoreBridge.scratchpadStrokeEnd()
                    val bmp = scratchBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        ReverieCoreBridge.scratchpadRender(bmp)
                    }
                    if (currentStroke.isNotEmpty()) {
                        strokes.add(currentStroke.toList())
                        currentStroke.clear()
                    }
                    renderTick++
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 空白提示文字
            if (strokes.isEmpty() && currentStroke.isEmpty()) {
                Text(
                    stringResource(R.string.brush_studio_scratchpad_hint),
                    color = Morandi.subText.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

// ==========================================
// Dialogs
// ==========================================

@Composable
internal fun StudioNewBrushDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    val defaultGroup = stringResource(R.string.brush_preset_custom_tag)
    var name by remember { mutableStateOf("") }
    var group by remember(defaultGroup) { mutableStateOf(defaultGroup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_new_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_new_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_create),
                onClick = { onCreate(name.trim(), group) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}

@Composable
internal fun StudioRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_rename_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_rename_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_save),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}

internal const val TIP_THUMB_MAX = 192

@Composable
internal fun CheckerboardBackground(modifier: Modifier = Modifier) {
    val color1 = Morandi.panel
    val color2 = Morandi.panelHi
    Canvas(modifier = modifier) {
        val checkSize = 12.dp.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val c = if ((i + j) % 2 == 0) color1 else color2
                drawRect(
                    color = c,
                    topLeft = Offset(i * checkSize, j * checkSize),
                    size = Size(checkSize, checkSize),
                )
            }
        }
    }
}
