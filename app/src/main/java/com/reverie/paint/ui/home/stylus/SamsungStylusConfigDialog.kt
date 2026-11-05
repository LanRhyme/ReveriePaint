/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

/** 按 actionId 查选项文案, 未命中时回退到 fallbackIdx 对应文案 */
@Composable
private fun actionTitle(
    options: List<Pair<String, Int>>,
    labels: List<String>,
    actionId: String,
    fallbackIdx: Int,
): String = options.indexOfFirst { it.first == actionId }
    .takeIf { it >= 0 }
    ?.let { labels[it] }
    ?: labels[fallbackIdx]

@Composable
internal fun SamsungStylusConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val options = stylusActionOptions()
    val optionLabels = options.map { stringResource(it.second) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                StylusDialogHeader(
                    badgeText = "S Pen",
                    title = stringResource(R.string.stylus_samsung_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                // 侧键行为
                StylusDialogSectionTitle(stringResource(R.string.stylus_samsung_side_key))
                StylusDialogCard {
                    StylusDialogSwitchItem(
                        title = stringResource(R.string.stylus_samsung_hold_eraser),
                        summary = stringResource(R.string.stylus_samsung_hold_eraser_desc),
                        checked = vm.samsungSideButtonErase,
                        onCheckedChange = { vm.updateSamsungSideButtonErase(it) },
                    )
                }

                // 侧键动作映射
                StylusDialogSectionTitle(stringResource(R.string.stylus_samsung_key_mapping))
                Text(
                    text = stringResource(R.string.spen_air_button_note),
                    color = colors.subText,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                StylusDialogCard {
                    val singleClickTitle = actionTitle(options, optionLabels, vm.samsungSingleClickAction, 0)
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_samsung_click_action),
                        currentText = singleClickTitle,
                        options = optionLabels,
                        onSelect = { idx ->
                            vm.updateSamsungSingleClickAction(options[idx].first)
                        },
                    )
                    StylusDialogDivider()
                    val doubleClickTitle = actionTitle(options, optionLabels, vm.samsungDoubleClickAction, 1)
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_samsung_double_click_action),
                        currentText = doubleClickTitle,
                        options = optionLabels,
                        onSelect = { idx ->
                            vm.updateSamsungDoubleClickAction(options[idx].first)
                        },
                    )
                    StylusDialogDivider()
                    val longPressTitle = actionTitle(options, optionLabels, vm.samsungLongPressAction, 3)
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_samsung_long_press_action),
                        currentText = longPressTitle,
                        options = optionLabels,
                        onSelect = { idx ->
                            vm.updateSamsungLongPressAction(options[idx].first)
                        },
                    )
                }

                // 隔空挥动手势映射区
                SpenAirGestureSection(vm)

                Spacer(Modifier.height(14.dp))

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
                        text = stringResource(R.string.stylus_samsung_hint),
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
