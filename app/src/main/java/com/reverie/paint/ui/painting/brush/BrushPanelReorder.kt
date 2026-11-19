package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.BrushPresetInfo
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 拖动排序专属手势修饰符:
 * 1. 轻点抬起 -> [onTap]
 * 2. 滑动且未长按 -> 放行正常列表滚动
 * 3. 长按未拖动抬起 -> [onLongPressOnly] (弹出原有操作菜单)
 * 4. 长按并拖动 -> [onDragStart] / [onDrag] / [onDragEnd] (拖拽重排)
 */
internal fun Modifier.reorderDragGesture(
    key: Any?,
    enabled: Boolean = true,
    onTap: () -> Unit,
    onLongPressOnly: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPressOnly by rememberUpdatedState(onLongPressOnly)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    this.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var isDragging = false
            var endDispatched = false
            val touchSlop = viewConfiguration.touchSlop
            val startPos = down.position
            var totalMoveDist = 0f

            try {
                // 1. 等待长按超时
                val longPressResult = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "cancel"
                        if (change.isConsumed) return@withTimeoutOrNull "cancel"
                        if (!change.pressed) {
                            // 在长按超时前抬起 -> 普通点击
                            currentOnTap()
                            return@withTimeoutOrNull "tap"
                        }
                        if ((change.position - startPos).getDistance() > touchSlop) {
                            // 超时前移动 -> 滚动列表
                            return@withTimeoutOrNull "scroll"
                        }
                    }
                }

                if (longPressResult == null) {
                    // 长按触发
                    isDragging = true
                    down.consume()
                    currentOnDragStart(down.position)

                    // 2. 拖拽循环
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val dragAmount = change.positionChange()
                            change.consume()
                            totalMoveDist += dragAmount.getDistance()
                            currentOnDrag(change, dragAmount)
                        } else {
                            change.consume()
                            endDispatched = true
                            if (totalMoveDist < touchSlop) {
                                currentOnDragCancel()
                                currentOnLongPressOnly()
                            } else {
                                currentOnDragEnd()
                            }
                            break
                        }
                    }
                }
            } finally {
                if (isDragging && !endDispatched) {
                    currentOnDragCancel()
                }
            }
        }
    }
}

/**
 * 拖动笔刷组时的悬浮标签卡片
 */
@Composable
internal fun CategoryDragOverlay(
    category: String,
    offsetYProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val displayName = brushCategoryDisplayName(category)
    Box(
        modifier = modifier
            .graphicsLayer {
                translationY = offsetYProvider()
            }
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .height(38.dp)
            .graphicsLayer {
                scaleX = 1.05f
                scaleY = 1.05f
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.5.dp, Morandi.accent.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = Morandi.accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = displayName,
                color = Morandi.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 拖动笔刷列表条目时的悬浮行卡片
 */
@Composable
internal fun PresetListDragOverlay(
    preset: BrushPresetInfo,
    offsetYProvider: () -> Float,
    isFav: Boolean,
    isModified: Boolean,
    viewMode: com.reverie.paint.model.BrushPanelViewMode = com.reverie.paint.model.BrushPanelViewMode.LIST,
    strokePreview: android.graphics.Bitmap? = null,
    modifier: Modifier = Modifier,
) {
    val showStroke = viewMode.showsStrokePreview
    val showIcon = viewMode.showsPresetIcon
    val rowHeight = if (showStroke) 50.dp else 40.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .graphicsLayer {
                translationY = offsetYProvider()
                scaleX = 1.03f
                scaleY = 1.03f
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.5.dp, Morandi.accent.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(
                start = if (showIcon) 5.dp else 8.dp,
                end = 6.dp,
                top = if (showStroke) 4.dp else 3.dp,
                bottom = if (showStroke) 4.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon) {
            val iconSize = if (showStroke) 40.dp else 34.dp
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center
            ) {
                val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = preset.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Morandi.panelHi)
                    )
                }
                if (isModified) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Morandi.accent)
                    )
                }
            }
            Spacer(Modifier.width(if (showStroke) 7.dp else 8.dp))
        }

        if (showStroke) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preset.name,
                        color = Morandi.text,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFav) {
                        Icon(
                            painter = painterResource(R.drawable.ic_star_filled),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_menu),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Morandi.panel.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (strokePreview != null) {
                        Image(
                            bitmap = strokePreview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Morandi.text),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = preset.name,
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isFav) {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = null,
                    tint = Color(0xFFD4A373),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
            }

            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = Morandi.accent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 拖动笔刷网格卡片时的悬浮卡片
 */
@Composable
internal fun PresetGridDragOverlay(
    preset: BrushPresetInfo,
    offsetXProvider: () -> Float,
    offsetYProvider: () -> Float,
    cardWidthDp: Dp,
    cardHeightDp: Dp,
    isModified: Boolean,
    isTwoColumn: Boolean = false,
    strokePreview: android.graphics.Bitmap? = null,
    modifier: Modifier = Modifier,
) {
    if (isTwoColumn) {
        Column(
            modifier = modifier
                .size(width = cardWidthDp, height = cardHeightDp)
                .graphicsLayer {
                    translationX = offsetXProvider()
                    translationY = offsetYProvider()
                    scaleX = 1.05f
                    scaleY = 1.05f
                }
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.5.dp, Morandi.accent.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = preset.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Morandi.panel.copy(alpha = 0.5f))
                        )
                    }
                    if (isModified) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(1.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent)
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    preset.name,
                    color = Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.panel.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                if (strokePreview != null) {
                    Image(
                        bitmap = strokePreview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Morandi.text),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 1.dp)
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .size(width = cardWidthDp, height = cardHeightDp)
            .graphicsLayer {
                translationX = offsetXProvider()
                translationY = offsetYProvider()
                scaleX = 1.08f
                scaleY = 1.08f
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.5.dp, Morandi.accent.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Morandi.panel.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = preset.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                )
            }
            if (isModified) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Morandi.accent)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            preset.name,
            color = Morandi.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 1.dp)
        )
    }
}
