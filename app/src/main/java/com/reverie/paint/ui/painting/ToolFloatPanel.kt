package com.reverie.paint.ui.painting

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
import kotlin.math.roundToInt

/**
 * Premium glassmorphism-style floating capsule with entry animations and drag support.
 */
@Composable
fun ToolFloatPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

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
                .shadow(16.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panelHi.copy(alpha = 0.85f))
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
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
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> Morandi.accent
                    danger -> Color(0x33B05552)
                    else -> Morandi.panel.copy(alpha = 0.6f)
                }
            )
            .border(
                1.dp, 
                if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.3f), 
                RoundedCornerShape(10.dp)
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Morandi.onAccent else Morandi.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        com.reverie.paint.ui.components.ReSlider(
            value = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
            onValue = { frac -> onValue(range.start + frac * (range.endInclusive - range.start)) },
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
