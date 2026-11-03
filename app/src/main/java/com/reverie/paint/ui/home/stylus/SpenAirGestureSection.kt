/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.openSpenAirSystemSettings
import com.reverie.paint.core.resetSpenAirBindings
import com.reverie.paint.core.spenAirBindingFor
import com.reverie.paint.core.updateSpenAirBinding
import com.reverie.paint.model.SpenAirGesture
import com.reverie.paint.ui.theme.Theme

/** 全量动作选项 (18 项): actionId -> 文案资源, 供笔键动作与隔空挥动手势下拉共用 */
internal fun stylusActionOptions(): List<Pair<String, Int>> = listOf(
    "toggle_eraser" to R.string.stylus_action_switch_brush_eraser,
    "eraser_mode" to R.string.stylus_action_eraser_mode,
    "undo" to R.string.stylus_action_undo,
    "redo" to R.string.stylus_action_redo,
    "tool_picker" to R.string.stylus_action_eyedropper,
    "toggle_last_tool" to R.string.stylus_action_prev_tool,
    "tool_color" to R.string.stylus_action_quick_palette,
    "brush_size_inc" to R.string.stylus_action_brush_size_inc,
    "brush_size_dec" to R.string.stylus_action_brush_size_dec,
    "brush_opacity_inc" to R.string.stylus_action_brush_opacity_inc,
    "brush_opacity_dec" to R.string.stylus_action_brush_opacity_dec,
    "zoom_in" to R.string.stylus_action_zoom_in,
    "zoom_out" to R.string.stylus_action_zoom_out,
    "flip_canvas" to R.string.stylus_action_flip,
    "layer_prev" to R.string.stylus_action_layer_prev,
    "layer_next" to R.string.stylus_action_layer_next,
    "toggle_quick_menu" to R.string.stylus_action_quick_menu,
    "toggle_ref_window" to R.string.stylus_action_ref_window,
    "none" to R.string.stylus_action_none,
)

/** 隔空挥动手势标签 (仅 6 个 motion 手势, 笔键类 CLICK/DOUBLE_CLICK 不在此区展示) */
private fun gestureLabelRes(gesture: SpenAirGesture): Int = when (gesture) {
    SpenAirGesture.SWIPE_LEFT -> R.string.spen_gesture_swipe_left
    SpenAirGesture.SWIPE_RIGHT -> R.string.spen_gesture_swipe_right
    SpenAirGesture.SWIPE_UP -> R.string.spen_gesture_swipe_up
    SpenAirGesture.SWIPE_DOWN -> R.string.spen_gesture_swipe_down
    SpenAirGesture.CIRCLE_CW -> R.string.spen_gesture_circle_cw
    SpenAirGesture.CIRCLE_CCW -> R.string.spen_gesture_circle_ccw
    else -> R.string.spen_gesture_click
}

/** 三星弹窗内的隔空挥动手势映射区 (系统设置入口 + 提示 + 6 个手势下拉 + 恢复默认) */
@Composable
internal fun SpenAirGestureSection(vm: PaintViewModel) {
    val colors = Theme.current
    val context = LocalContext.current
    val options = stylusActionOptions()
    val optionLabels = options.map { stringResource(it.second) }
    val gestures = listOf(
        SpenAirGesture.SWIPE_LEFT,
        SpenAirGesture.SWIPE_RIGHT,
        SpenAirGesture.SWIPE_UP,
        SpenAirGesture.SWIPE_DOWN,
        SpenAirGesture.CIRCLE_CW,
        SpenAirGesture.CIRCLE_CCW,
    )

    StylusDialogSectionTitle(stringResource(R.string.spen_air_motion_title))

    // 系统设置入口
    StylusDialogCard {
        StylusDialogNavItem(
            iconRes = R.drawable.ic_settings,
            title = stringResource(R.string.spen_air_open_system_settings),
            onClick = { openSpenAirSystemSettings(context) },
        )
    }

    Spacer(Modifier.height(10.dp))

    // 提示卡片
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_help_circle),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.spen_air_hint),
            color = colors.subText,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }

    Spacer(Modifier.height(6.dp))

    // 动作映射 (6 个挥动手势)
    StylusDialogCard {
        gestures.forEachIndexed { index, gesture ->
            if (index > 0) {
                StylusDialogDivider()
            }
            val currentActionId = vm.spenAirBindingFor(gesture)
            val currentTitle = stringResource(
                options.find { it.first == currentActionId }?.second ?: R.string.stylus_action_none,
            )
            StylusDialogDropdownItem(
                title = stringResource(gestureLabelRes(gesture)),
                currentText = currentTitle,
                options = optionLabels,
                onSelect = { idx ->
                    vm.updateSpenAirBinding(gesture, options[idx].first)
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // 恢复默认映射
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panelHi)
            .clickable { vm.resetSpenAirBindings() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.spen_air_reset_default),
            color = colors.subText,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
