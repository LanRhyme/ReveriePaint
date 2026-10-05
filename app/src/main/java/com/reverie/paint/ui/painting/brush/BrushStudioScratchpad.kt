/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal fun captureScratchpadAsThumbnail(
    context: Context,
    vm: PaintViewModel,
    strokes: List<List<ScratchPoint>>,
) {
    val size = 200
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(240, 239, 238))

    val brushColorInt = runCatching { android.graphics.Color.parseColor(vm.brushColor) }.getOrDefault(android.graphics.Color.DKGRAY)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColorInt
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    if (strokes.isNotEmpty()) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        strokes.forEach { stroke ->
            stroke.forEach { p ->
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        val strokeW = (maxX - minX).coerceAtLeast(10f)
        val strokeH = (maxY - minY).coerceAtLeast(10f)
        val padding = 28f
        val targetBox = size - padding * 2f
        val scale = minOf(targetBox / strokeW, targetBox / strokeH).coerceIn(0.1f, 5.0f)
        val offsetX = padding + (targetBox - strokeW * scale) / 2f - minX * scale
        val offsetY = padding + (targetBox - strokeH * scale) / 2f - minY * scale

        val baseWidth = (vm.brushSize.toFloat() * scale).coerceIn(4f, 48f)

        strokes.forEach { stroke ->
            if (stroke.size >= 2) {
                val path = android.graphics.Path()
                for (i in stroke.indices) {
                    val p = stroke[i]
                    val sx = p.x * scale + offsetX
                    val sy = p.y * scale + offsetY
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                paint.strokeWidth = baseWidth
                paint.alpha = (vm.brushOpacity * 255).toInt().coerceIn(10, 255)
                canvas.drawPath(path, paint)
            } else if (stroke.size == 1) {
                val p = stroke[0]
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(p.x * scale + offsetX, p.y * scale + offsetY, baseWidth / 2f, paint)
                paint.style = android.graphics.Paint.Style.STROKE
            }
        }
    } else {
        val path = android.graphics.Path()
        path.moveTo(35f, 165f)
        path.cubicTo(65f, 120f, 135f, 80f, 165f, 35f)
        paint.strokeWidth = (vm.brushSize.toFloat()).coerceIn(12f, 38f)
        paint.alpha = (vm.brushOpacity * 255).toInt().coerceIn(10, 255)
        canvas.drawPath(path, paint)
    }

    vm.capturePresetThumbnail(bitmap)
}

@Composable
internal fun ScratchpadCanvas(
    vm: PaintViewModel,
    strokes: List<List<ScratchPoint>>,
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
    // 与引擎口径一致：笔尖羽化来自 Fade（MaskGenerator 的 hfade/vfade），
    // 柔度 Softness 是在其之上叠加的乘数（1.0 = 不改动），两者相乘才是最终边缘柔和程度。
    val softness = (vm.brushFade.toFloat().coerceIn(0f, 1f) * vm.brushSoftness.toFloat().coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(2f, 80f) / 2f)
    val isSquare = vm.brushTipShape == 1
    val angle = (vm.brushAngle + vm.brushRotation).toFloat()
    // 试画板要真实笔尖形状, 不能缩采样; 但整幅解码仍属重活 (单个 .gih 可达 19MB),
    // 所以只在缓存命中时同步取, 否则后台解码再回来。
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
        fun DrawScope.drawDab(curPos: Offset, pressure: Float) {
            val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pressure * vm.brushPressureSize.toFloat()) else 1f)
            val baseAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.25f + 0.75f * pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)
            val texMod = if (vm.brushTextureEnabled) {
                0.8f + 0.4f * (((curPos.x.toInt() * 73 + curPos.y.toInt() * 37) and 0xFF) / 255f) * vm.brushTextureStrength.toFloat()
            } else 1f
            val dabAlpha = (baseAlpha * texMod).coerceIn(0.01f, 1f)

            // Secondary color mix preview
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
                            dstOffset = IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                            dstSize = IntSize(dabW.toInt(), dabH.toInt()),
                            alpha = dabAlpha,
                            colorFilter = ColorFilter.tint(effectiveColor, BlendMode.SrcIn),
                        )
                    }
                } else {
                    drawImage(
                        image = tipImageBitmap,
                        dstOffset = IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                        dstSize = IntSize(dabW.toInt(), dabH.toInt()),
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
                // Circle tip: when softness is close to 0, render razor sharp solid disc (no feathering)
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

        fun DrawScope.drawScratch(pts: List<ScratchPoint>) {
            if (pts.isEmpty()) return
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.01f, 2.5f) * (baseRadius * 2f)).coerceAtLeast(1.0f)

            if (pts.size == 1) {
                drawDab(Offset(pts[0].x, pts[0].y), pts[0].pressure)
                return
            }

            drawDab(Offset(pts[0].x, pts[0].y), pts[0].pressure)
            var distToNextDab = spacing

            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
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

        strokes.forEach { drawScratch(it) }
        drawScratch(currentStroke)
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
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 14.sp),
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
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 14.sp),
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
