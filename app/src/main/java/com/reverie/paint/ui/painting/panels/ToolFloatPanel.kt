package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import com.reverie.paint.core.*

/**
 * Premium glassmorphism-style floating capsule with entry animations and drag support.
 */
@Composable
fun ToolFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel? = null,
    hazeState: HazeState? = null,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val capsuleShape = RoundedCornerShape(18.dp)

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)),
        exit = fadeOut() + scaleOut(targetScale = 0.95f)
    ) {
        Row(
            modifier = modifier
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(16.dp, capsuleShape, spotColor = Color.Black.copy(alpha = 0.2f))
                .systemHoverIcon(context)
                .clip(capsuleShape)
                .then(
                    if (vm?.blurBackground == true && hazeState != null) {
                        val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = popupAlpha.coerceIn(0.05f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = popupAlpha.coerceIn(0.05f, 0.98f))),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f
                            )
                        )
                    } else {
                        val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
                        Modifier.background(Morandi.panel.copy(alpha = popupAlpha))
                    }
                )
                .border(1.dp, Morandi.border, capsuleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
fun ToolFloatChip(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "scale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Morandi.accent
                    danger -> Color(0x33B05552)
                    else -> Morandi.panelHi
                }
            )
            .border(
                1.dp, 
                if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.3f), 
                RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Morandi.onAccent else if (danger) Color(0xFFE57373) else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun <T> ToolFloatSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (key, label) ->
            val isSel = selected == key
            var pressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "segScale")

            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) Morandi.accent else Color.Transparent)
                    .pointerInput(key) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onSelect(key) }
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSel) Morandi.onAccent else Morandi.subText,
                )
            }
        }
    }
}

@Composable
fun ToolFloatSlider(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, fontWeight = FontWeight.Normal)
        com.reverie.paint.ui.components.ReSlider(
            value = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
            onValue = { frac -> onValue(range.start + frac * (range.endInclusive - range.start)) },
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
