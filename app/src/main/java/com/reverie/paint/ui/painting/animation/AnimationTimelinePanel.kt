/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationAddKeyframe
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationTogglePlay
import com.reverie.paint.core.setCurrentLayer
import com.reverie.paint.core.toggleLayerVisible
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

private val TRACK_HEADER_W = 104.dp
private val ROW_H = 36.dp
private val RULER_H = 22.dp
private val HANDLE_H = 20.dp
private val CONTROL_H = 42.dp
private const val MIN_PANEL_H = 140f
private const val MAX_PANEL_H = 460f
private const val MIN_FRAME_W = 8f
private const val MAX_FRAME_W = 160f

/**
 * 时间轴面板 (动画画布专用)。
 *
 * 顶部把手上下拖拽调高度, 拖到底自动收起; 轨道区支持双指缩放帧宽与单指平移。
 * 每条轨道 = 一个图层, 轨道上的块 = 关键帧, 块宽 = 该帧的曝光长度
 * (Krita 的 hold 语义: 一个关键帧持续到下一个关键帧)。
 *
 * 数据全部取自 vm.anim 的状态镜像; 引擎真身在 Krita 的
 * KisRasterKeyframeChannel, 这里不持有任何像素数据。
 */
@Composable
internal fun AnimationTimelinePanel(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    var panelHeightDp by remember { mutableFloatStateOf(200f) }
    var scrollY by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val rowPx = with(density) { ROW_H.toPx() }
    val maxScrollY = (vm.layers.size * rowPx - rowPx * 2f).coerceAtLeast(0f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeightDp.dp)
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.barStyle(vm.popupPanelOpacity))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
                },
            ),
    ) {
        TimelineHandle(
            onDrag = { delta ->
                if (delta < 0 && panelHeightDp <= MIN_PANEL_H + 6f) {
                    vm.anim.panelOpen = false
                } else {
                    panelHeightDp = (panelHeightDp + delta).coerceIn(MIN_PANEL_H, MAX_PANEL_H)
                }
            },
            onDoubleTap = {
                panelHeightDp = if (panelHeightDp > 260f) MIN_PANEL_H + 40f else MAX_PANEL_H - 60f
            },
            modifier = Modifier.fillMaxWidth().height(HANDLE_H),
        )

        // 刻度尺固定在顶部, 不随轨道垂直滚动
        Row(modifier = Modifier.fillMaxWidth().height(RULER_H)) {
            Spacer(modifier = Modifier.width(TRACK_HEADER_W))
            TimelineRuler(vm = vm, modifier = Modifier.weight(1f).fillMaxSize())
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TrackHeaders(
                vm = vm,
                scrollY = scrollY,
                rowHeight = ROW_H,
                modifier = Modifier.width(TRACK_HEADER_W).fillMaxSize(),
            )
            TimelineTrackArea(
                vm = vm,
                rowPx = rowPx,
                scrollY = scrollY,
                onScrollYChange = { scrollY = it.coerceIn(0f, maxScrollY) },
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }

        TimelineControls(vm = vm, modifier = Modifier.fillMaxWidth().height(CONTROL_H))
    }
}

// ============================================================
// 收放把手
// ============================================================

@Composable
private fun TimelineHandle(
    onDrag: (delta: Float) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .pointerInput(Unit) {
                var prev = 0f
                detectVerticalDragGestures(
                    onDragStart = { prev = it.y },
                    onDragEnd = {},
                    onDragCancel = {},
                ) { change, _ ->
                    val delta = change.position.y - prev
                    prev = change.position.y
                    onDrag(delta)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Morandi.subText.copy(alpha = 0.5f)),
        )
    }
}

// ============================================================
// 刻度尺
// ============================================================

@Composable
private fun TimelineRuler(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val frameW = vm.anim.frameWidthPx
    val scroll = vm.anim.scrollPx
    val count = vm.anim.length
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .background(Morandi.panelHi.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    vm.animationSeek(((scroll + off.x) / frameW).toInt())
                }
            },
    ) {
        translate(left = -scroll) {
            val first = (scroll / frameW).toInt() - 1
            val last = ((scroll + size.width) / frameW).toInt() + 1
            for (f in first..last) {
                if (f < 0 || f >= count) continue
                val x = f * frameW
                if (f % 5 == 0 || frameW > 26f) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = f.toString(),
                        topLeft = Offset(x + 3f, 4f),
                        style = TextStyle(color = Morandi.subText, fontSize = 9.sp),
                    )
                }
                drawLine(
                    color = if (f % 5 == 0) Morandi.subText.copy(alpha = 0.7f) else Morandi.border,
                    start = Offset(x, size.height - 5f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

// ============================================================
// 轨道头 (左侧固定列)
// ============================================================

@Composable
private fun TrackHeaders(
    vm: PaintViewModel,
    scrollY: Float,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val layers = vm.layers.reversed()
    Column(modifier = modifier.background(Morandi.panelHi.copy(alpha = 0.35f))) {
        Column(modifier = Modifier.offset { IntOffset(0, -scrollY.roundToInt()) }) {
            layers.forEach { layer ->
                Row(
                    modifier = Modifier
                        .height(rowHeight)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { vm.toggleLayerVisible(layer.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val c = if (layer.visible) Morandi.text else Morandi.subText.copy(alpha = 0.4f)
                            drawCircle(color = c, radius = 5.dp.toPx())
                            if (!layer.visible) {
                                drawLine(
                                    color = Morandi.panel,
                                    start = Offset(center.x - 6.dp.toPx(), center.y - 6.dp.toPx()),
                                    end = Offset(center.x + 6.dp.toPx(), center.y + 6.dp.toPx()),
                                    strokeWidth = 2f,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = layer.name,
                        color = if (layer.index == vm.currentLayerIndex) Morandi.accent else Morandi.text,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { vm.setCurrentLayer(layer.index) },
                    )
                }
            }
        }
    }
}

// ============================================================
// 轨道区 (帧块 + 播放头, 可缩放平移)
// ============================================================

@Composable
private fun TimelineTrackArea(
    vm: PaintViewModel,
    rowPx: Float,
    scrollY: Float,
    onScrollYChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layers = vm.layers.reversed()
    val frameW = vm.anim.frameWidthPx
    val scrollX = vm.anim.scrollPx
    val currentTime = vm.anim.currentTime
    val cache = vm.anim.keyframeCache

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldW = vm.anim.frameWidthPx
                    val newW = (oldW * zoom).coerceIn(MIN_FRAME_W, MAX_FRAME_W)
                    // 缩放锚定在双指中心: 保持该点下的帧号不变
                    val anchorFrame = (vm.anim.scrollPx + centroid.x) / oldW
                    vm.anim.frameWidthPx = newW
                    vm.anim.scrollPx = (anchorFrame * newW - centroid.x - pan.x).coerceAtLeast(0f)
                    onScrollYChange(scrollY - pan.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frame = ((vm.anim.scrollPx + offset.x) / vm.anim.frameWidthPx).toInt()
                    val row = ((offset.y + scrollY) / rowPx).toInt()
                    if (row in layers.indices) {
                        vm.setCurrentLayer(layers[row].index)
                        vm.anim.selectedTrack = layers[row].index
                    }
                    vm.animationSeek(frame)
                }
            },
    ) {
        translate(left = -scrollX, top = -scrollY) {
            layers.forEachIndexed { i, layer ->
                val top = i * rowPx
                drawRect(
                    color = if (i % 2 == 0) Morandi.panelHi.copy(alpha = 0.18f) else Color.Transparent,
                    topLeft = Offset(scrollX, top),
                    size = Size(size.width, rowPx),
                )

                val times = cache[layer.index]
                if (!times.isNullOrEmpty()) {
                    for (idx in times.indices) {
                        val t = times[idx]
                        // hold 语义: 块宽 = 到下一个关键帧的跨度, 末帧按 1 帧显示
                        val next = if (idx + 1 < times.size) times[idx + 1] else t + 1
                        val span = (next - t).coerceAtLeast(1)
                        val active = currentTime >= t && currentTime < next
                        drawRoundRectCompat(
                            color = if (active) Morandi.accent else Morandi.panelHi,
                            topLeft = Offset(t * frameW + 1f, top + 3f),
                            size = Size((span * frameW - 2f).coerceAtLeast(2f), rowPx - 6f),
                        )
                    }
                }
            }

            val headX = currentTime * frameW + frameW / 2f
            drawLine(
                color = Morandi.accent,
                start = Offset(headX, 0f),
                end = Offset(headX, (layers.size * rowPx).coerceAtLeast(size.height)),
                strokeWidth = 2f,
            )
        }
    }
}

// ============================================================
// 底部控制条
// ============================================================

@Composable
private fun TimelineControls(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val playing = vm.anim.isPlaying
    Row(
        modifier = modifier
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(onClick = { vm.animationSeek((vm.anim.currentTime - 1).coerceAtLeast(0)) }) { drawGlyphPrev() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationTogglePlay() }) {
            if (playing) drawGlyphPause() else drawGlyphPlay()
        }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationSeek(vm.anim.currentTime + 1) }) { drawGlyphNext() }
        Spacer(modifier = Modifier.width(14.dp))
        GlyphButton(onClick = { vm.animationAddKeyframe(duplicate = false) }) { drawGlyphPlus() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationAddKeyframe(duplicate = true) }) { drawGlyphDuplicate() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationRemoveKeyframe() }) { drawGlyphTrash() }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${vm.anim.framerate}fps · ${vm.anim.length}帧 · 第 ${vm.anim.currentTime + 1} 帧",
            color = Morandi.subText,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun GlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp), onDraw = draw)
    }
}

// ============================================================
// 图标 (自绘, 避免引入图标库依赖)
// ============================================================

private fun DrawScope.drawGlyphPlay() {
    val p = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.12f)
        lineTo(size.width * 0.86f, size.height * 0.5f)
        lineTo(size.width * 0.22f, size.height * 0.88f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphPause() {
    val w = size.width * 0.18f
    drawRect(Morandi.text, Offset(size.width * 0.26f, size.height * 0.16f), Size(w, size.height * 0.68f))
    drawRect(Morandi.text, Offset(size.width * 0.56f, size.height * 0.16f), Size(w, size.height * 0.68f))
}

private fun DrawScope.drawGlyphPrev() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.18f, h * 0.2f), Offset(size.width * 0.18f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.82f, h * 0.16f)
        lineTo(size.width * 0.34f, h * 0.5f)
        lineTo(size.width * 0.82f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphNext() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.82f, h * 0.2f), Offset(size.width * 0.82f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.18f, h * 0.16f)
        lineTo(size.width * 0.66f, h * 0.5f)
        lineTo(size.width * 0.18f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphPlus() {
    drawLine(Morandi.text, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth = 2f)
}

private fun DrawScope.drawGlyphDuplicate() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.08f, size.height * 0.22f), Size(size.width * 0.58f, size.height * 0.56f))
    drawRoundRectCompat(
        Morandi.text.copy(alpha = 0.45f),
        Offset(size.width * 0.34f, size.height * 0.22f),
        Size(size.width * 0.58f, size.height * 0.56f),
    )
}

private fun DrawScope.drawGlyphTrash() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.16f, size.height * 0.62f), Size(size.width * 0.68f, size.height * 0.24f))
    drawLine(Morandi.text, Offset(size.width * 0.1f, size.height * 0.28f), Offset(size.width * 0.9f, size.height * 0.28f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.36f, size.height * 0.12f), Offset(size.width * 0.64f, size.height * 0.12f), strokeWidth = 2f)
}

private fun DrawScope.drawRoundRectCompat(
    color: Color,
    topLeft: Offset,
    size: Size,
) {
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
    )
}
