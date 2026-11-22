/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
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
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

@Composable
internal fun CountdownTimerWindow(
    vm: PaintViewModel,
    onClose: () -> Unit,
    opacity: Float,
) {
    var customMinutes by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(16.dp)
    val x = vm.countdownWindowX
    val y = vm.countdownWindowY

    Column(
        modifier = Modifier
            .zIndex(79f)
            .offset { IntOffset(x.roundToInt().coerceAtLeast(0), y.roundToInt().coerceAtLeast(0)) }
            .width(248.dp)
            .onSizeChanged { size ->
                vm.countdownWindowWidth = size.width.toFloat()
                vm.countdownWindowHeight = size.height.toFloat()
                val maxX = (vm.floatingHostWidthPx - size.width).coerceAtLeast(0f)
                val maxY = (vm.floatingHostHeightPx - size.height).coerceAtLeast(0f)
                if (vm.countdownWindowX >= 0f) {
                    vm.countdownWindowX = vm.countdownWindowX.coerceIn(0f, maxX)
                }
                if (vm.countdownWindowY >= 0f) {
                    vm.countdownWindowY = vm.countdownWindowY.coerceIn(0f, maxY)
                }
            }
            .clip(shape)
            .background(Morandi.panel.copy(alpha = opacity))
            .border(1.dp, Morandi.border.copy(alpha = 0.8f), shape)
            .padding(12.dp),
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
                icon = R.drawable.ic_clock,
                desc = stringResource(R.string.countdown_title),
                onTap = {},
                modifier = Modifier.size(28.dp),
                iconSize = 17.dp,
                tint = Morandi.accent,
            )
            Text(
                text = stringResource(R.string.countdown_title),
                color = Morandi.text,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            ReIconButton(
                icon = R.drawable.ic_x,
                desc = stringResource(R.string.common_close),
                onTap = onClose,
                modifier = Modifier.size(28.dp),
                iconSize = 16.dp,
            )
        }

        Spacer(Modifier.size(8.dp))
        Text(
            text = formatCountdown(vm.countdownRemainingMs),
            color = Morandi.accent,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(1L, 3L, 5L).forEach { minutes ->
                TextButton(onClick = { vm.startCountdown(minutes) }) {
                    Text(stringResource(R.string.countdown_minutes, minutes))
                }
            }
        }

        OutlinedTextField(
            value = customMinutes,
            onValueChange = { customMinutes = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.countdown_custom_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.countdownRunning) {
                TextButton(onClick = vm::pauseCountdown) {
                    Text(stringResource(R.string.countdown_pause))
                }
            } else if (vm.countdownRemainingMs > 0L) {
                TextButton(onClick = vm::resumeCountdown) {
                    Text(stringResource(R.string.countdown_resume))
                }
            }
            TextButton(
                enabled = customMinutes.toLongOrNull()?.let { it in 1L..999L } == true,
                onClick = { customMinutes.toLongOrNull()?.let(vm::startCountdown) },
            ) {
                Text(stringResource(R.string.countdown_start_custom))
            }
            TextButton(
                enabled = vm.countdownRunning || vm.countdownRemainingMs > 0L,
                onClick = vm::resetCountdown,
            ) {
                Text(stringResource(R.string.countdown_reset))
            }
        }
    }
}

private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs + 999L) / 1000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
