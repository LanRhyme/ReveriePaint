package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * 点按或拖拽二合一手势, 坐标统一以根视图 (root) 空间上报:
 * - 位移未超过 touchSlop 视为点按 → [onTap]
 * - 超过 touchSlop 进入拖拽 → [onDragStart]/[onDragMove]/[onDragEnd]
 * - 拖拽中指针流被系统中断时回调 [onDragCancel], 保证外部状态可复位
 *
 * 回调经 rememberUpdatedState 包装, pointerInput 以 Unit 为 key,
 * 手势进行中发生重组不会打断进行中的手势流。
 */
fun Modifier.tapOrDragGesture(
    onTap: () -> Unit,
    onDragStart: ((Offset) -> Unit)? = null,
    onDragMove: ((Offset) -> Unit)? = null,
    onDragEnd: ((Offset) -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
): Modifier = composed {
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    this
        .onGloballyPositioned { coords ->
            rootPos = coords.positionInRoot()
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var isDragging = false
                var endDelivered = false
                val touchSlop = viewConfiguration.touchSlop
                val startPos = down.position
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val currentPos = change.position
                            if (!isDragging) {
                                if ((currentPos - startPos).getDistance() > touchSlop) {
                                    isDragging = true
                                    currentOnDragStart?.invoke(rootPos + currentPos)
                                    change.consume()
                                }
                            } else {
                                currentOnDragMove?.invoke(rootPos + change.position)
                                change.consume()
                            }
                        } else {
                            if (isDragging) {
                                currentOnDragEnd?.invoke(rootPos + change.position)
                                endDelivered = true
                                change.consume()
                            } else {
                                currentOnTap()
                            }
                            break
                        }
                    }
                } finally {
                    if (isDragging && !endDelivered) {
                        currentOnDragCancel?.invoke()
                    }
                }
            }
        }
}
