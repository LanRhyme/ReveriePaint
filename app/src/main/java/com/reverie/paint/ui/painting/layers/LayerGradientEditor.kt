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
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Dialog
import com.reverie.paint.ui.theme.glassBorder
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
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt

class CustomGradStop(
    val id: Long,
    initialPos: Float,
    initialColor: Color
) {
    var pos by mutableFloatStateOf(initialPos)
    var color by mutableStateOf(initialColor)
}

private val GRADIENT_PRESETS = listOf(
    "日落暖金" to listOf(
        0.0f to Color(0xFF2C0B38),
        0.35f to Color(0xFFB82E55),
        0.7f to Color(0xFFE88A35),
        1.0f to Color(0xFFFFF6A5)
    ),
    "赛博霓虹" to listOf(
        0.0f to Color(0xFF0F052A),
        0.4f to Color(0xFF8A148D),
        0.8f to Color(0xFF00E5FF),
        1.0f to Color(0xFFFFFFFF)
    ),
    "深海幽蓝" to listOf(
        0.0f to Color(0xFF061426),
        0.45f to Color(0xFF0A4F6B),
        0.8f to Color(0xFF26A69A),
        1.0f to Color(0xFFE0F7FA)
    ),
    "复古怀旧" to listOf(
        0.0f to Color(0xFF2E1C0C),
        0.4f to Color(0xFF704E2E),
        0.75f to Color(0xFFC4A47C),
        1.0f to Color(0xFFFBF4E8)
    ),
    "烈焰熔岩" to listOf(
        0.0f to Color(0xFF100000),
        0.3f to Color(0xFF800000),
        0.65f to Color(0xFFFF4500),
        1.0f to Color(0xFFFFFF80)
    ),
    "梦幻粉紫" to listOf(
        0.0f to Color(0xFF2D1436),
        0.45f to Color(0xFF8B5E83),
        0.8f to Color(0xFFE8B4B8),
        1.0f to Color(0xFFFFF0F5)
    ),
    "森系翠绿" to listOf(
        0.0f to Color(0xFF0A2218),
        0.4f to Color(0xFF1B5E3C),
        0.75f to Color(0xFF7CB342),
        1.0f to Color(0xFFF1F8E9)
    ),
    "黑白胶片" to listOf(
        0.0f to Color(0xFF000000),
        0.5f to Color(0xFF808080),
        1.0f to Color(0xFFFFFFFF)
    ),
)

@Composable
fun CompactColorPickerDialog(
    title: String = "选取颜色",
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (initialColor.alpha * 255).toInt(),
                (initialColor.red * 255).toInt(),
                (initialColor.green * 255).toInt(),
                (initialColor.blue * 255).toInt()
            ),
            arr
        )
        arr
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var valB by remember { mutableFloatStateOf(hsv[2]) }

    val currentColor = remember(hue, sat, valB) {
        val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valB))
        Color(colorInt)
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.5f))
                .clip(RoundedCornerShape(16.dp))
                .background(Morandi.panel)
                .glassBorder(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(currentColor)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    )
                }

                // 2D Saturation-Value Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(hue) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    sat = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    valB = (1f - (offset.y / size.height.toFloat())).coerceIn(0f, 1f)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    sat = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    valB = (1f - (change.position.y / size.height.toFloat())).coerceIn(0f, 1f)
                                }
                            )
                        }
                ) {
                    val pureHueColor = remember(hue) {
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(listOf(Color.White, pureHueColor))
                        )
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        )
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val w = maxWidth
                        val h = maxHeight
                        val handleX = w * sat - 8.dp
                        val handleY = h * (1f - valB) - 8.dp
                        Box(
                            modifier = Modifier
                                .offset(x = handleX, y = handleY)
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (valB > 0.5f && sat < 0.5f) Color.Black else Color.White, CircleShape)
                        )
                    }
                }

                // Hue Spectrum Slider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green,
                                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                )
                            )
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    hue = (offset.x / size.width.toFloat()).coerceIn(0f, 1f) * 360f
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    hue = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f) * 360f
                                }
                            )
                        }
                )

                // Fast Swatch Palette
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(
                        Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFFFB8C00),
                        Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA)
                    ).forEach { sw ->
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(sw)
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                .clickable {
                                    val arr = FloatArray(3)
                                    android.graphics.Color.colorToHSV(
                                        android.graphics.Color.argb(255, (sw.red * 255).toInt(), (sw.green * 255).toInt(), (sw.blue * 255).toInt()),
                                        arr
                                    )
                                    hue = arr[0]
                                    sat = arr[1]
                                    valB = arr[2]
                                }
                        )
                    }
                }

                // Hex readout and dialog actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hex = String.format("#%02X%02X%02X", (currentColor.red * 255).toInt(), (currentColor.green * 255).toInt(), (currentColor.blue * 255).toInt())
                    Text(hex, color = Morandi.subText, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReTextButton("取消", onDismiss, textColor = Morandi.subText, fontSize = 12.sp)
                        ReTextButton(
                            "确定",
                            {
                                onColorSelected(currentColor)
                                onDismiss()
                            },
                            primary = true,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

internal fun generateGradientLUTFromStops(stops: List<CustomGradStop>, reverse: Boolean): IntArray {
    val sorted = stops.sortedBy { it.pos }
    val lut = IntArray(256)
    if (sorted.isEmpty()) {
        for (i in 0..255) lut[i] = (0xFF shl 24) or (i shl 16) or (i shl 8) or i
        return lut
    }
    for (i in 0..255) {
        val t = if (reverse) (255 - i) / 255f else i / 255f
        val col = when {
            t <= sorted.first().pos -> sorted.first().color
            t >= sorted.last().pos -> sorted.last().color
            else -> {
                val idx = sorted.indexOfFirst { it.pos >= t }.coerceAtLeast(1)
                val s0 = sorted[idx - 1]
                val s1 = sorted[idx]
                val span = s1.pos - s0.pos
                val factor = if (span > 0f) (t - s0.pos) / span else 0f
                Color(
                    red = s0.color.red + factor * (s1.color.red - s0.color.red),
                    green = s0.color.green + factor * (s1.color.green - s0.color.green),
                    blue = s0.color.blue + factor * (s1.color.blue - s0.color.blue),
                    alpha = s0.color.alpha + factor * (s1.color.alpha - s0.color.alpha),
                )
            }
        }
        val a = (col.alpha * 255).toInt().coerceIn(0, 255)
        val r = (col.red * 255).toInt().coerceIn(0, 255)
        val g = (col.green * 255).toInt().coerceIn(0, 255)
        val b = (col.blue * 255).toInt().coerceIn(0, 255)
        lut[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return lut
}

@Composable
internal fun CustomGradientEditor(
    stops: MutableList<CustomGradStop>,
    reverse: Boolean,
    onReverseToggle: () -> Unit,
    onGradientChanged: () -> Unit
) {
    var selectedStopId by remember { mutableLongStateOf(stops.firstOrNull()?.id ?: 0L) }
    var showColorPicker by remember { mutableStateOf(false) }
    val currentOnGradientChanged by rememberUpdatedState(onGradientChanged)

    val sortedStops = remember(stops.size, stops.map { it.pos to it.color.value }) {
        stops.sortedBy { it.pos }
    }

    val gradientBrush = remember(sortedStops, reverse) {
        val list = if (reverse) sortedStops.reversed().map { (1f - it.pos) to it.color } else sortedStops.map { it.pos to it.color }
        Brush.horizontalGradient(list.map { it.second })
    }

    val activeStop = stops.find { it.id == selectedStopId } ?: stops.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Gradient Track with Drag Handles
        Column(modifier = Modifier.fillMaxWidth()) {
            // Interactive Gradient Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(gradientBrush)
                    .pointerInput(Unit) {
                        detectTapGestures { tapOffset ->
                            val pos = (tapOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val sorted = stops.sortedBy { it.pos }
                            val col = when {
                                sorted.isEmpty() -> Color.White
                                pos <= sorted.first().pos -> sorted.first().color
                                pos >= sorted.last().pos -> sorted.last().color
                                else -> {
                                    val idx = sorted.indexOfFirst { it.pos >= pos }.coerceAtLeast(1)
                                    val s0 = sorted[idx - 1]
                                    val s1 = sorted[idx]
                                    val span = s1.pos - s0.pos
                                    val factor = if (span > 0f) (pos - s0.pos) / span else 0f
                                    Color(
                                        red = s0.color.red + factor * (s1.color.red - s0.color.red),
                                        green = s0.color.green + factor * (s1.color.green - s0.color.green),
                                        blue = s0.color.blue + factor * (s1.color.blue - s0.color.blue),
                                        alpha = 1f
                                    )
                                }
                            }
                            val newStop = CustomGradStop(System.currentTimeMillis(), pos, col)
                            stops.add(newStop)
                            selectedStopId = newStop.id
                            currentOnGradientChanged()
                        }
                    }
            )

            // Stop handles row
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val totalWidthPx = constraints.maxWidth.toFloat()
                val density = LocalDensity.current
                val handleSizeDp = 20.dp
                val handleSizePx = with(density) { handleSizeDp.toPx() }
                val travelPx = (totalWidthPx - handleSizePx).coerceAtLeast(1f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val touchX = down.position.x

                                val hitRadius = 28f * density.density
                                val nearest = stops.minByOrNull {
                                    val hCenter = it.pos * travelPx + handleSizePx * 0.5f
                                    kotlin.math.abs(hCenter - touchX)
                                }
                                if (nearest != null) {
                                    val hCenter = nearest.pos * travelPx + handleSizePx * 0.5f
                                    if (kotlin.math.abs(hCenter - touchX) <= hitRadius) {
                                        selectedStopId = nearest.id
                                        val targetStop = nearest

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            change.consume()

                                            val newPos = ((change.position.x - handleSizePx * 0.5f) / travelPx).coerceIn(0f, 1f)
                                            targetStop.pos = newPos
                                            currentOnGradientChanged()
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    stops.forEach { stop ->
                        val isSel = (stop.id == selectedStopId)
                        val offsetPx = (stop.pos * travelPx).roundToInt()
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset { androidx.compose.ui.unit.IntOffset(offsetPx, 0) }
                                .size(handleSizeDp)
                                .clip(CircleShape)
                                .background(Morandi.bg)
                                .border(2.dp, if (isSel) Morandi.accent else Color.White, CircleShape)
                                .clickable {
                                    selectedStopId = stop.id
                                    showColorPicker = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(stop.color)
                            )
                        }
                    }
                }
            }
        }

        // Active Stop Color & Position Controls
        if (activeStop != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .noRippleClickable { showColorPicker = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(activeStop.color)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    )
                    Column {
                        Text(
                            "当前色标: ${(activeStop.pos * 100).roundToInt()}%",
                            color = Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text("点击打开取色面板", color = Morandi.accent, fontSize = 11.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (stops.size > 2) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panel)
                                .noRippleClickable {
                                    stops.remove(activeStop)
                                    selectedStopId = stops.first().id
                                    currentOnGradientChanged()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("删除色标", color = Color(0xFFFF5252), fontSize = 11.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (reverse) Morandi.accent else Morandi.panel)
                            .noRippleClickable(onReverseToggle)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (reverse) "已反转" else "反转", color = if (reverse) Color.White else Morandi.subText, fontSize = 11.sp)
                    }
                }
            }

            if (showColorPicker) {
                CompactColorPickerDialog(
                    title = "设置色标颜色",
                    initialColor = activeStop.color,
                    onColorSelected = { newCol ->
                        activeStop.color = newCol
                        currentOnGradientChanged()
                    },
                    onDismiss = { showColorPicker = false }
                )
            }
        }

        Text("载入经典预设", color = Morandi.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))

        // Preset Palettes Grid (compact 2-column layout)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GRADIENT_PRESETS.chunked(2).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowPresets.forEach { (name, presetStops) ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panelHi)
                                .noRippleClickable {
                                    stops.clear()
                                    presetStops.forEachIndexed { i, p ->
                                        stops.add(CustomGradStop(System.currentTimeMillis() + i, p.first, p.second))
                                    }
                                    selectedStopId = stops.first().id
                                    currentOnGradientChanged()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp, 12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Brush.horizontalGradient(presetStops.map { it.second }))
                            )
                            Text(
                                name,
                                color = Morandi.text,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filter Adjust Page (All Filter Controls)
// ---------------------------------------------------------------------------

