package com.reverie.paint.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import com.reverie.paint.ui.theme.Theme
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.roundToInt

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}

/**
 * Shared component library (画世界 Pro / Procreate style).
 *
 * Rules for every component:
 *  - read ALL colors from Theme.current - never hardcode
 *  - min 44dp touch targets
 *  - reusable: panels/buttons/sliders are built here, pages only compose
 */

// ---------- design tokens ----------
object Dimens {
    val touch = 44.dp
    val radius = 12.dp
    val radiusSm = 9.dp
    val icon = 20.dp
    val iconLg = 24.dp
    val barHeight = 56.dp
}

// ---------- icon button (top bar, rails) ----------
@Composable
fun ReIconButton(
    @DrawableRes icon: Int,
    desc: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val colors = Theme.current
    val tintColor by animateColorAsState(if (selected) colors.accent else colors.icon, tween(200))

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = tintColor,
            modifier = Modifier.size(Dimens.icon),
        )
    }
}

// ---------- primary / secondary text button ----------
@Composable
fun ReButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    val colors = Theme.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(Dimens.radius))
                .background(if (primary) colors.accent else colors.panelHi)
                .clickable { onClick() }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) colors.onAccent else colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------- vertical capsule slider (brush size / opacity) ----------
@Composable
fun ReVerticalSlider(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: Int = 18,
    trackHeight: Int = 100,
    tooltipText: String? = null,
) {
    val colors = Theme.current
    var localFraction by remember(fraction) { mutableFloatStateOf(fraction) }
    var trackPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height((trackHeight + if (label.isNotEmpty()) 16 else 0).dp),
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = colors.subText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
        }
        Box(
            modifier =
                Modifier
                    .width(trackWidth.dp)
                    .height(trackHeight.dp)
                    .onSizeChanged { trackPx = it.height }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false }
                        ) { change, _ ->
                            val value = 1f - (change.position.y / trackPx.toFloat()).coerceIn(0f, 1f)
                            localFraction = value
                            onFraction(value)
                            change.consume()
                        }
                    },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Value tooltip
            if (isDragging) {
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.CenterStart,
                    offset = androidx.compose.ui.unit.IntOffset(100, 0)
                ) {
                    Box(
                        modifier = Modifier
                            .shadow(6.dp, RoundedCornerShape(8.dp), spotColor = colors.accent.copy(alpha = 0.25f))
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.panel)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            tooltipText ?: "${(localFraction * 100).toInt()}",
                            color = colors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            val capsuleRadius = (trackWidth / 2).dp

            // Track background & Outline Border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(capsuleRadius))
                    .background(colors.panel.copy(alpha = 0.5f))
                    .border(1.5.dp, colors.border, RoundedCornerShape(capsuleRadius))
            ) {
                // Active progress fill (from bottom up)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(localFraction.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(capsuleRadius))
                        .background(colors.accent.copy(alpha = 0.25f))
                )
            }

            // Refined Thumb knob with clean border and center dot
            val thumbSize = (trackWidth - 2).coerceAtLeast(14).dp
            val thumbPadding = ((trackWidth.dp - thumbSize) / 2)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (localFraction * (trackHeight - trackWidth)).dp + thumbPadding)
                    .size(thumbSize)
                    .shadow(2.dp, CircleShape, spotColor = colors.accent.copy(alpha = 0.35f))
                    .clip(CircleShape)
                    .background(colors.panel)
                    .border(2.dp, colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
            }
        }
    }
}

// ---------- horizontal slider (panels) ----------
@Composable
fun ReSlider(
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 20,
    onRelease: (() -> Unit)? = null,
) {
    val colors = Theme.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape((height / 2).dp))
                .background(colors.panelHi)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        if (w > 0f) {
                            onValue((offset.x / w).coerceIn(0f, 1f))
                            onRelease?.invoke()
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = { onRelease?.invoke() },
                        onDragCancel = { onRelease?.invoke() },
                    ) { change, _ ->
                        val w = size.width.toFloat()
                        if (w > 0f) {
                            onValue((change.position.x / w).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = colors.accent,
                size = androidx.compose.ui.geometry.Size(size.width * value.coerceIn(0f, 1f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
            )
        }
    }
}

// ---------- toggle switch ----------
@Composable
fun ReSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    Box(
        modifier =
            modifier
                .size(48.dp, 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) colors.accent else colors.panelHi)
                .clickable { onChecked(!checked) }
                .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(colors.onAccent),
        )
    }
}

// ---------- color dot / swatch ----------
@Composable
fun ReColorDot(
    color: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Int = 40,
) {
    val colors = Theme.current
    Box(
        modifier =
            modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size / 4).dp))
                .background(if (selected) colors.accentHi else Color.Transparent)
                .clickable { onTap() }
                .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(((size - 6) / 4).dp)).background(color),
        )
    }
}

// ---------- section title inside a panel ----------
@Composable
fun ReSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = Theme.current.subText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

// ---------- bottom-sheet panel with full-screen scrim ----------
@Composable
fun RePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
    content: @Composable () -> Unit,
) {
    val colors = Theme.current
    // Note: In a real app, you'd manage the visibility state outside to animate out before removing from composition.
    // For this MVP, we animate in when composed.
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(onClick = onClose),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(250)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = colors.panel.copy(alpha = opacity),
                            shape = RoundedCornerShape(topStart = Dimens.radius * 2, topEnd = Dimens.radius * 2),
                        ).padding(bottom = 12.dp)
                        .clickable(enabled = false) {}, // consume clicks inside panel
            ) {
                // drag handle
                Box(
                    Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.border),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    ReIconButton(
                        icon = com.reverie.paint.R.drawable.ic_x,
                        desc = "关闭",
                        onTap = onClose,
                    )
                }
                content()
            }
        }
    }
}

// ---------- small labeled value row (settings style) ----------
@Composable
fun ReSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.text, fontSize = 14.sp)
        trailing()
    }
}

// ---------- generic chip (preset selection) ----------
@Composable
fun ReChip(
    text: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = Theme.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(if (selected) colors.accent else colors.panelHi)
                .clickable { onTap() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.onAccent else colors.text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------- modal text field (replaces the ad-hoc text dialog) ----------
@Composable
fun ReTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = colors.subText) },
        colors =
            androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.panel,
                unfocusedContainerColor = colors.panel,
                cursorColor = colors.accent,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
            ),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------- menu item (dropdown style panels) ----------
@Composable
fun ReMenuItem(
    @DrawableRes icon: Int,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = Theme.current.icon,
    textColor: Color = Theme.current.text,
) {
    val colors = Theme.current
    var isPressed by remember { mutableStateOf(false) }
    val cardBg = if (isPressed) colors.accent.copy(alpha = 0.15f) else colors.panelHi.copy(alpha = 0.6f)
    val cardBorder = if (isPressed) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .clickable { onTap() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (isPressed) colors.accent else iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (isPressed) colors.accent else textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

