/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationAddBlankKeyframeAt
import com.reverie.paint.core.animationCopyCurrentFrameTo
import com.reverie.paint.core.animationGenerateInbetween
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationSetKeyframeTag
import com.reverie.paint.core.clearLayer
import com.reverie.paint.core.copyLayer
import com.reverie.paint.core.frameThumbKey
import com.reverie.paint.core.removeLayer
import com.reverie.paint.core.setCurrentLayer
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/** 长按菜单命中态: 命中的图层索引 + 块起始帧 (空白格则该格帧号) + 弹出锚点 (Canvas 局部 px) */
internal class FrameMenuState(
    val layer: Int,
    val time: Int,
    val anchorX: Float,
    val anchorY: Float,
    // true = 长按命中已有帧块 (全量菜单+可拖拽); false = 长按空白格 (仅新建帧/粘贴)
    val onBlock: Boolean,
)

/** 轨道头长按菜单态: 图层索引 + 图层名称 + 锚点坐标 */
internal class TrackMenuState(
    val layerIndex: Int,
    val layerName: String,
    val anchorX: Float,
    val anchorY: Float,
)

/** 帧剪贴板 (Kotlin 层记录) */
internal var frameClipboard: Pair<Int, Int>? = null

/** 长按上下文菜单里的单行 (支持按下动效与徽标) */
@Composable
internal fun FrameMenuItem(
    text: String,
    textColor: Color = Morandi.text,
    isDestructive: Boolean = false,
    badge: String? = null,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val bgAnim by animateColorAsState(
        targetValue = if (isPressed) {
            if (isDestructive) Color(0xFFE55D42).copy(alpha = 0.16f)
            else Morandi.accent.copy(alpha = 0.16f)
        } else Color.Transparent,
        animationSpec = spring(stiffness = 600f),
        label = "menuItemBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgAnim)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (isDestructive) Color(0xFFE55D42) else textColor,
            fontSize = 12.5.sp,
            fontWeight = if (isDestructive || badge != null) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (badge != null) {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.accent.copy(alpha = 0.20f))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
            ) {
                Text(
                    text = badge,
                    color = Morandi.accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 关键帧长按上下文菜单 */
@Composable
internal fun FrameMenuPopup(
    menu: FrameMenuState,
    vm: PaintViewModel,
    canvasWpx: Float,
    trackAreaTopInWindow: Float = 0f,
    rowPx: Float,
    haptics: HapticFeedback,
    onDismiss: () -> Unit,
) {
    val menuShape = RoundedCornerShape(14.dp)
    val menuW = 160.dp

    val canPaste = frameClipboard?.let { (cl, ct) ->
        vm.anim.keyframeCache[cl].orEmpty().contains(ct)
    } == true

    fun nextFreeSlot(layer: Int, from: Int): Int {
        val times = vm.anim.keyframeCache[layer].orEmpty()
        var t = from
        var guard = 0
        while (times.contains(t) && guard < 512) {
            t++
            guard++
        }
        return t
    }

    val items: List<Pair<String, () -> Unit>> = buildList {
        if (menu.onBlock) {
            val times = vm.anim.keyframeCache[menu.layer].orEmpty()
            val curIdx = times.indexOf(menu.time)
            val hasNextKey = curIdx in times.indices && curIdx + 1 < times.size
            if (hasNextKey) {
                add("自动中割" to {
                    vm.animationGenerateInbetween(menu.layer, menu.time)
                })
            }
            add("新建帧" to {
                vm.animationAddBlankKeyframeAt(menu.layer, menu.time + 1)
            })
            add("复制帧" to {
                val t2 = nextFreeSlot(menu.layer, menu.time + 1)
                vm.animationCopyCurrentFrameTo(
                    t2,
                    menu.layer,
                    share = true,
                    fromTime = menu.time,
                )
                vm.animationSeek(t2)
            })
            add("拷贝" to {
                frameClipboard = menu.layer to menu.time
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            })
            add("剪切" to {
                frameClipboard = menu.layer to menu.time
                vm.animationRemoveKeyframe(menu.layer, menu.time)
            })
        }
        if (canPaste) {
            val clip = frameClipboard
            if (clip != null) {
                val clipTime = clip.second
                val target = if (menu.onBlock) nextFreeSlot(menu.layer, menu.time + 1) else menu.time
                add("粘贴" to {
                    vm.animationCopyCurrentFrameTo(
                        target,
                        menu.layer,
                        share = false,
                        fromTime = clipTime,
                    )
                    vm.animationSeek(target)
                })
            }
        }
        if (menu.onBlock) {
            add("删除帧" to {
                vm.animationRemoveKeyframe(menu.layer, menu.time)
            })
        }
    }

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val menuWpx = with(density) { menuW.toPx() }
    val tagBarH = if (menu.onBlock) 36 else 0
    val menuHpx = with(density) { (items.size * 32 + 14 + tagBarH).dp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }

    val x = (menu.anchorX - menuWpx / 2f)
        .coerceIn(padPx, (canvasWpx - menuWpx - padPx).coerceAtLeast(padPx))

    val idealY = menu.anchorY - menuHpx - padPx
    val idealScreenY = trackAreaTopInWindow + idealY
    val clampedScreenY = idealScreenY.coerceIn(padPx, (screenHeightPx - menuHpx - padPx).coerceAtLeast(padPx))
    val y = clampedScreenY - trackAreaTopInWindow

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f),
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x.roundToInt(), y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    val p = animProgress.value
                    scaleX = 0.85f + 0.15f * p
                    scaleY = 0.85f + 0.15f * p
                    alpha = p
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .shadow(16.dp, menuShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(menuShape)
                .background(Morandi.panelHi.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.05f),
                        ),
                    ),
                    shape = menuShape,
                )
                .width(menuW)
                .padding(horizontal = 2.dp, vertical = 5.dp),
        ) {
            if (menu.onBlock) {
                val curKey = frameThumbKey(menu.layer, menu.time)
                val curTag = vm.anim.keyframeTags[curKey] ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "色标",
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tags = listOf(
                            0 to Morandi.subText.copy(alpha = 0.35f),
                            1 to Color(0xFFE55D42), // 原画 Key (橙红)
                            2 to Color(0xFF3F77B0), // 中割 Breakdown (群青)
                            3 to Color(0xFF4FA06B), // 草稿 Guide (青绿)
                        )
                        tags.forEach { (tagVal, color) ->
                            val isSel = curTag == tagVal
                            val scale by animateFloatAsState(
                                targetValue = if (isSel) 1.15f else 1.0f,
                                label = "tagScale",
                            )
                            Box(
                                modifier = Modifier
                                    .size(17.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        onDismiss()
                                        vm.setCurrentLayer(menu.layer)
                                        vm.animationSetKeyframeTag(menu.layer, menu.time, tagVal)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSel) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.8.dp)
                        .background(Morandi.border.copy(alpha = 0.5f))
                        .padding(bottom = 2.dp),
                )
            }
            items.forEach { (label, action) ->
                val isDelete = label == "删除帧"
                val isAi = label == "自动中割"
                FrameMenuItem(
                    text = label,
                    isDestructive = isDelete,
                    badge = if (isAi) "AI" else null,
                ) {
                    onDismiss()
                    vm.setCurrentLayer(menu.layer)
                    action()
                }
            }
        }
    }
}

/** 轨道头长按上下文菜单 (图层管理) */
@Composable
internal fun TrackMenuPopup(
    menu: TrackMenuState,
    vm: PaintViewModel,
    canvasWpx: Float,
    trackAreaTopInWindow: Float = 0f,
    rowPx: Float,
    onDismiss: () -> Unit,
) {
    val menuShape = RoundedCornerShape(14.dp)
    val menuW = 146.dp
    val canDelete = vm.layers.size > 1

    val items = listOf(
        "复制图层" to { vm.copyLayer(menu.layerIndex) },
        "清空图层" to { vm.clearLayer(menu.layerIndex) },
        "删除图层" to {
            if (canDelete) {
                vm.removeLayer(menu.layerIndex)
            } else {
                vm.showActionToast("无法删除唯一的图层", R.drawable.ic_x)
            }
        },
    )

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val menuWpx = with(density) { menuW.toPx() }
    val menuHpx = with(density) { (items.size * 32 + 12).dp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }

    val x = (menu.anchorX - menuWpx / 2f).coerceIn(padPx, (canvasWpx - menuWpx - padPx).coerceAtLeast(padPx))
    val idealY = menu.anchorY - menuHpx - padPx
    val idealScreenY = trackAreaTopInWindow + idealY
    val clampedScreenY = idealScreenY.coerceIn(padPx, (screenHeightPx - menuHpx - padPx).coerceAtLeast(padPx))
    val y = clampedScreenY - trackAreaTopInWindow

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f),
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x.roundToInt(), y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    val p = animProgress.value
                    scaleX = 0.85f + 0.15f * p
                    scaleY = 0.85f + 0.15f * p
                    alpha = p
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .shadow(16.dp, menuShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(menuShape)
                .background(Morandi.panelHi.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.05f),
                        ),
                    ),
                    shape = menuShape,
                )
                .width(menuW)
                .padding(horizontal = 2.dp, vertical = 5.dp),
        ) {
            items.forEach { (label, action) ->
                val isDelete = label == "删除图层"
                FrameMenuItem(
                    text = label,
                    isDestructive = isDelete,
                ) {
                    onDismiss()
                    action()
                }
            }
        }
    }
}
