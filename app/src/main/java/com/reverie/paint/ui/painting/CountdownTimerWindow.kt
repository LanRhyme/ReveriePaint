/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.pauseCountdown
import com.reverie.paint.core.persistCountdownWindowState
import com.reverie.paint.core.resetCountdown
import com.reverie.paint.core.resumeCountdown
import com.reverie.paint.core.startCountdown
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.evadablePanelContent
import com.reverie.paint.ui.components.evadablePanelSurface
import com.reverie.paint.ui.components.reportUiSlot
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

@Composable
internal fun CountdownTimerWindow(
    vm: PaintViewModel,
    onClose: () -> Unit,
    opacity: Float,
) {
    var customMinutes by remember { mutableStateOf("") }
    var pinnedOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val expanded = hovered || pinnedOpen
    val shape = RoundedCornerShape(14.dp)
    val x = vm.countdownWindowX
    val y = vm.countdownWindowY
    val targetWidth = if (expanded) 208.dp else 150.dp
    val panelWidth by animateDpAsState(targetWidth, tween(180), label = "countdownWidth")

    Column(
        modifier = Modifier
            .zIndex(79f)
            .offset { IntOffset(x.roundToInt().coerceAtLeast(0), y.roundToInt().coerceAtLeast(0)) }
            .width(panelWidth)
            .reportUiSlot(vm, UiPanelSlot.COUNTDOWN_TIMER_WINDOW)
            .hoverable(interactionSource)
            .animateContentSize(tween(180))
            .onSizeChanged { size ->
                vm.countdownWindowWidth = size.width.toFloat()
                vm.countdownWindowHeight = size.height.toFloat()
                val maxX = (vm.floatingHostWidthPx - size.width).coerceAtLeast(0f)
                val maxY = (vm.floatingHostHeightPx - size.height).coerceAtLeast(0f)
                var wasClamped = false
                if (vm.countdownWindowX >= 0f) {
                    val nextX = vm.countdownWindowX.coerceIn(0f, maxX)
                    wasClamped = wasClamped || nextX != vm.countdownWindowX
                    vm.countdownWindowX = nextX
                }
                if (vm.countdownWindowY >= 0f) {
                    val nextY = vm.countdownWindowY.coerceIn(0f, maxY)
                    wasClamped = wasClamped || nextY != vm.countdownWindowY
                    vm.countdownWindowY = nextY
                }
                if (wasClamped) vm.persistCountdownWindowState()
            }
            .clip(shape)
            .evadablePanelSurface(
                vm = vm,
                slot = UiPanelSlot.COUNTDOWN_TIMER_WINDOW,
                shape = shape,
                baseOpacity = opacity,
                hazeState = null,
            )
            .evadablePanelContent(vm, UiPanelSlot.COUNTDOWN_TIMER_WINDOW)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { vm.persistCountdownWindowState() },
                        onDragCancel = { vm.persistCountdownWindowState() },
                    ) { change, drag ->
                        change.consume()
                        val maxX = (vm.floatingHostWidthPx - vm.countdownWindowWidth).coerceAtLeast(0f)
                        val maxY = (vm.floatingHostHeightPx - vm.countdownWindowHeight).coerceAtLeast(0f)
                        vm.countdownWindowX = (vm.countdownWindowX + drag.x).coerceIn(0f, maxX)
                        vm.countdownWindowY = (vm.countdownWindowY + drag.y).coerceIn(0f, maxY)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReIconButton(
                icon = R.drawable.ic_countdown,
                desc = stringResource(R.string.countdown_title),
                onTap = { pinnedOpen = !pinnedOpen },
                modifier = Modifier.size(22.dp),
                iconSize = 14.dp,
                tint = Morandi.accent,
            )
            if (expanded) {
                Text(
                    text = stringResource(R.string.countdown_title),
                    color = Morandi.text,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).padding(start = 2.dp),
                )
            } else {
                Text(
                    text = formatCountdown(vm.countdownRemainingMs),
                    color = Morandi.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f).padding(start = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { pinnedOpen = !pinnedOpen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = Morandi.subText,
                    modifier = Modifier.size(13.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            ReIconButton(
                icon = R.drawable.ic_x,
                desc = stringResource(R.string.common_close),
                onTap = onClose,
                modifier = Modifier.size(24.dp),
                iconSize = 14.dp,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            Column {
                Text(
                    text = formatCountdown(vm.countdownRemainingMs),
                    color = Morandi.accent,
                    fontSize = 27.sp,
                    lineHeight = 29.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(1L, 3L, 5L).forEach { minutes ->
                        TextButton(
                            onClick = { vm.startCountdown(minutes) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(stringResource(R.string.countdown_minutes, minutes), fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { customMinutes = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.countdown_custom_minutes), fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(color = Morandi.text, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canResume = vm.countdownRunning || vm.countdownRemainingMs > 0L
                    TextButton(
                        enabled = canResume,
                        onClick = { if (vm.countdownRunning) vm.pauseCountdown() else vm.resumeCountdown() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (vm.countdownRunning) R.string.countdown_pause else R.string.countdown_resume,
                            ),
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(
                        enabled = customMinutes.toLongOrNull()?.let { it in 1L..999L } == true,
                        onClick = { customMinutes.toLongOrNull()?.let(vm::startCountdown) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.countdown_start), fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = vm.countdownRunning || vm.countdownRemainingMs > 0L,
                        onClick = vm::resetCountdown,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.countdown_reset), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs + 999L) / 1000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
