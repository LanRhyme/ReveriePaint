/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)

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
 * 试画台核心画布：采用双缓冲位图渲染
 * 历史完成的笔画离屏渲染至 Bitmap，当前活跃笔画增量绘制，彻底消除多笔画卡顿
 */
@Composable
internal fun ScratchpadCanvas(
    vm: PaintViewModel,
    scratchBitmap: Bitmap?,
    currentStroke: List<ScratchPoint>,
    onStrokeStart: (ScratchPoint) -> Unit,
    onStrokeAddPoints: (List<ScratchPoint>) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val brushColor = remember(vm.brushColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.White)
    }
    val secColor = remember(vm.brushSecondaryColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) }.getOrDefault(Color.White)
    }
    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    val softness = (vm.brushFade.toFloat().coerceIn(0f, 1f) * vm.brushSoftness.toFloat().coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(2f, 80f) / 2f)
    val isSquare = vm.brushTipShape == 1
    val angle = (vm.brushAngle + vm.brushRotation).toFloat()

    var tipBitmap by remember(context, vm.brushTipAsset) {
        mutableStateOf(
            if (vm.brushTipAsset.isNotBlank()) {
                BrushTipDecoder.loadTip(context, vm.brushTipAsset)
            } else null
        )
    }
    LaunchedEffect(context, vm.brushTipAsset) {
        if (vm.brushTipAsset.isNotBlank() && tipBitmap == null) {
            tipBitmap = withContext(Dispatchers.IO) {
                BrushTipDecoder.loadTip(context, vm.brushTipAsset)
            }
        }
    }
    val tipImageBitmap = remember(tipBitmap) { tipBitmap?.asImageBitmap() }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val initP = if (down.pressure > 0f) down.pressure.coerceIn(0.1f, 1.0f) else 1.0f
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
                        val p = if (change.pressure > 0f) change.pressure.coerceIn(0.1f, 1.0f) else initP
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
        // 1. 绘制已烘焙的历史位图 (1 次 drawImage，极速 0 分配)
        if (scratchBitmap != null) {
            drawImage(image = scratchBitmap.asImageBitmap())
        }

        // 2. 绘制当前正在进行的活跃笔画 (只算当前这一笔)
        fun DrawScope.drawDab(curPos: Offset, pressure: Float) {
            val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pressure * vm.brushPressureSize.toFloat()) else 1f)
            val baseAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.25f + 0.75f * pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)
            val texMod = if (vm.brushTextureEnabled) {
                0.8f + 0.4f * (((curPos.x.toInt() * 73 + curPos.y.toInt() * 37) and 0xFF) / 255f) * vm.brushTextureStrength.toFloat()
            } else 1f
            val dabAlpha = (baseAlpha * texMod).coerceIn(0.01f, 1f)

            val effectiveColor = if (vm.brushSecondaryMix > 0.001) {
                val mixFactor = vm.brushSecondaryMix.toFloat().coerceIn(0f, 1f)
                Color(
                    red = brushColor.red * (1f - mixFactor) + secColor.red * mixFactor,
                    green = brushColor.green * (1f - mixFactor) + secColor.green * mixFactor,
                    blue = brushColor.blue * (1f - mixFactor) + secColor.blue * mixFactor,
                    alpha = brushColor.alpha,
                )
            } else brushColor

            if (tipImageBitmap != null) {
                val dabW = (rad * 2f).coerceAtLeast(2f)
                val dabH = (rad * 2f * ratio).coerceAtLeast(2f)
                if (angle != 0f) {
                    withTransform({
                        rotate(angle, curPos)
                    }) {
                        drawImage(
                            image = tipImageBitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(dabW.toInt(), dabH.toInt()),
                            alpha = dabAlpha,
                            colorFilter = ColorFilter.tint(effectiveColor, BlendMode.SrcIn),
                        )
                    }
                } else {
                    drawImage(
                        image = tipImageBitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(dabW.toInt(), dabH.toInt()),
                        alpha = dabAlpha,
                        colorFilter = ColorFilter.tint(effectiveColor, BlendMode.SrcIn),
                    )
                }
            } else if (isSquare) {
                if (angle != 0f) {
                    withTransform({
                        rotate(angle, curPos)
                    }) {
                        drawRect(
                            color = effectiveColor.copy(alpha = dabAlpha),
                            topLeft = Offset(curPos.x - rad, curPos.y - rad * ratio),
                            size = Size(rad * 2f, rad * 2f * ratio),
                        )
                    }
                } else {
                    drawRect(
                        color = effectiveColor.copy(alpha = dabAlpha),
                        topLeft = Offset(curPos.x - rad, curPos.y - rad * ratio),
                        size = Size(rad * 2f, rad * 2f * ratio),
                    )
                }
            } else {
                if (ratio < 0.99f || angle != 0f) {
                    withTransform({
                        if (angle != 0f) rotate(angle, curPos)
                        scale(scaleX = 1f, scaleY = ratio, pivot = curPos)
                    }) {
                        if (softness <= 0.02f) {
                            drawCircle(
                                color = effectiveColor.copy(alpha = dabAlpha),
                                radius = rad.coerceAtLeast(1.5f),
                                center = curPos,
                            )
                        } else {
                            val solidStop = (1f - softness).coerceIn(0f, 0.98f)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to effectiveColor.copy(alpha = dabAlpha),
                                        solidStop to effectiveColor.copy(alpha = dabAlpha),
                                        1f to effectiveColor.copy(alpha = 0f),
                                    ),
                                    center = curPos,
                                    radius = rad.coerceAtLeast(1.5f),
                                ),
                                radius = rad.coerceAtLeast(1.5f),
                                center = curPos,
                            )
                        }
                    }
                } else {
                    if (softness <= 0.02f) {
                        drawCircle(
                            color = effectiveColor.copy(alpha = dabAlpha),
                            radius = rad.coerceAtLeast(1.5f),
                            center = curPos,
                        )
                    } else {
                        val solidStop = (1f - softness).coerceIn(0f, 0.98f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to effectiveColor.copy(alpha = dabAlpha),
                                    solidStop to effectiveColor.copy(alpha = dabAlpha),
                                    1f to effectiveColor.copy(alpha = 0f),
                                ),
                                center = curPos,
                                radius = rad.coerceAtLeast(1.5f),
                            ),
                            radius = rad.coerceAtLeast(1.5f),
                            center = curPos,
                        )
                    }
                }
            }
        }

        if (currentStroke.isNotEmpty()) {
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.01f, 2.5f) * (baseRadius * 2f)).coerceAtLeast(1.0f)
            drawDab(Offset(currentStroke[0].x, currentStroke[0].y), currentStroke[0].pressure)

            if (currentStroke.size > 1) {
                var distToNextDab = spacing
                for (i in 1 until currentStroke.size) {
                    val p0 = currentStroke[i - 1]
                    val p1 = currentStroke[i]
                    val dx = p1.x - p0.x
                    val dy = p1.y - p0.y
                    val segDist = kotlin.math.hypot(dx, dy)
                    if (segDist <= 0.0001f) continue

                    var traveled = 0f
                    while (traveled + distToNextDab <= segDist) {
                        traveled += distToNextDab
                        val t = traveled / segDist
                        val cx = p0.x + dx * t
                        val cy = p0.y + dy * t
                        val cp = p0.pressure + (p1.pressure - p0.pressure) * t
                        drawDab(Offset(cx, cy), cp)
                        distToNextDab = spacing
                    }
                    distToNextDab -= (segDist - traveled)
                }
            }
        }
    }
}

/**
 * 将完成的笔画一次性烘焙至离屏位图
 */
internal fun bakeStrokeToBitmap(
    bitmap: Bitmap,
    stroke: List<ScratchPoint>,
    vm: PaintViewModel,
    tipBitmap: Bitmap?,
) {
    if (stroke.isEmpty()) return
    val canvas = android.graphics.Canvas(bitmap)

    val brushColorInt = runCatching { android.graphics.Color.parseColor(vm.brushColor) }.getOrDefault(android.graphics.Color.WHITE)
    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(2f, 80f) / 2f)
    val isSquare = vm.brushTipShape == 1
    val angle = (vm.brushAngle + vm.brushRotation).toFloat()
    val spacing = (vm.brushSpacing.toFloat().coerceIn(0.01f, 2.5f) * (baseRadius * 2f)).coerceAtLeast(1.0f)

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    fun drawDab(cx: Float, cy: Float, pressure: Float) {
        val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pressure * vm.brushPressureSize.toFloat()) else 1f)
        val baseAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.25f + 0.75f * pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)
        val alphaInt = (baseAlpha * 255).toInt().coerceIn(1, 255)

        if (tipBitmap != null) {
            val dabW = (rad * 2f).coerceAtLeast(2f)
            val dabH = (rad * 2f * ratio).coerceAtLeast(2f)
            val matrix = android.graphics.Matrix()
            matrix.postScale(dabW / tipBitmap.width, dabH / tipBitmap.height)
            if (angle != 0f) {
                matrix.postRotate(angle, dabW / 2f, dabH / 2f)
            }
            matrix.postTranslate(cx - dabW / 2f, cy - dabH / 2f)

            paint.colorFilter = PorterDuffColorFilter(brushColorInt, PorterDuff.Mode.SRC_IN)
            paint.alpha = alphaInt
            canvas.drawBitmap(tipBitmap, matrix, paint)
        } else if (isSquare) {
            paint.colorFilter = null
            paint.color = brushColorInt
            paint.alpha = alphaInt
            paint.style = android.graphics.Paint.Style.FILL

            canvas.save()
            if (angle != 0f) canvas.rotate(angle, cx, cy)
            canvas.drawRect(cx - rad, cy - rad * ratio, cx + rad, cy + rad * ratio, paint)
            canvas.restore()
        } else {
            paint.colorFilter = null
            paint.color = brushColorInt
            paint.alpha = alphaInt
            paint.style = android.graphics.Paint.Style.FILL

            canvas.save()
            if (angle != 0f) canvas.rotate(angle, cx, cy)
            if (ratio < 0.99f) canvas.scale(1f, ratio, cx, cy)
            canvas.drawCircle(cx, cy, rad, paint)
            canvas.restore()
        }
    }

    drawDab(stroke[0].x, stroke[0].y, stroke[0].pressure)
    if (stroke.size > 1) {
        var distToNextDab = spacing
        for (i in 1 until stroke.size) {
            val p0 = stroke[i - 1]
            val p1 = stroke[i]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val segDist = kotlin.math.hypot(dx, dy)
            if (segDist <= 0.0001f) continue

            var traveled = 0f
            while (traveled + distToNextDab <= segDist) {
                traveled += distToNextDab
                val t = traveled / segDist
                val cx = p0.x + dx * t
                val cy = p0.y + dy * t
                val cp = p0.pressure + (p1.pressure - p0.pressure) * t
                drawDab(cx, cy, cp)
                distToNextDab = spacing
            }
            distToNextDab -= (segDist - traveled)
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
