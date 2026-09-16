/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationAddBlankKeyframeAt
import com.reverie.paint.core.animationCopyCurrentFrameTo
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.clearLayer
import com.reverie.paint.core.copyLayer
import com.reverie.paint.core.removeLayer
import com.reverie.paint.core.setCurrentLayer
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
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

/** 长按上下文菜单里的单行 (纯文字, 整行可点) */
@Composable
internal fun FrameMenuItem(
    text: String,
    textColor: Color = Morandi.text,
    onTap: () -> Unit,
) {
    Text(
        text = text,
        color = textColor,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** 关键帧长按上下文菜单 */
@Composable
internal fun FrameMenuPopup(
    menu: FrameMenuState,
    vm: PaintViewModel,
    canvasWpx: Float,
    rowPx: Float,
    haptics: HapticFeedback,
    onDismiss: () -> Unit,
) {
    val menuShape = RoundedCornerShape(10.dp)
    val menuW = 132.dp

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
    val menuWpx = with(density) { menuW.toPx() }
    val menuHpx = with(density) { (items.size * 33 + 10).dp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val x = (menu.anchorX - menuWpx / 2f)
        .coerceIn(0f, (canvasWpx - menuWpx).coerceAtLeast(0f))
    val aboveY = menu.anchorY - menuHpx - padPx
    val y = if (aboveY < 0f) menu.anchorY + rowPx + padPx else aboveY

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x.roundToInt(), y.roundToInt()),
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            modifier = Modifier
                .shadow(12.dp, menuShape, spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(menuShape)
                .background(Morandi.panelHi.copy(alpha = 0.97f))
                .glassBorder(menuShape)
                .width(menuW)
                .padding(vertical = 5.dp),
        ) {
            items.forEach { (label, action) ->
                FrameMenuItem(label) {
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
    rowPx: Float,
    onDismiss: () -> Unit,
) {
    val menuShape = RoundedCornerShape(10.dp)
    val menuW = 126.dp
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
    val menuWpx = with(density) { menuW.toPx() }
    val menuHpx = with(density) { (items.size * 33 + 10).dp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val x = (menu.anchorX - menuWpx / 2f).coerceIn(4f, (canvasWpx - menuWpx).coerceAtLeast(4f))
    val aboveY = menu.anchorY - menuHpx - padPx
    val y = if (aboveY < 0f) menu.anchorY + rowPx + padPx else aboveY

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x.roundToInt(), y.roundToInt()),
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            modifier = Modifier
                .shadow(12.dp, menuShape, spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(menuShape)
                .background(Morandi.panelHi.copy(alpha = 0.97f))
                .glassBorder(menuShape)
                .width(menuW)
                .padding(vertical = 5.dp),
        ) {
            items.forEach { (label, action) ->
                val isDelete = label == "删除图层"
                FrameMenuItem(
                    text = label,
                    textColor = if (isDelete) Color(0xFFE57373) else Morandi.text,
                ) {
                    onDismiss()
                    action()
                }
            }
        }
    }
}
