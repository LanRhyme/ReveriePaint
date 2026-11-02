/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.push3DUndoState
import com.reverie.paint.core.update3DState
import com.reverie.paint.model.JointOffsetSpec
import com.reverie.paint.perf.JointOffsetExport
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * 关节位置微调面板。
 *
 * 自动绑定 (UniRig) 的关节点位置在网格上不一定落在真实关节处, 离线几轮调整仍有残余偏差。
 * 这里把"往哪里挪"的决定权交给用户: 选骨 → 拖竖直滑杆 → 网格实时形变 → 导出给开发侧固化。
 *
 * 平移走 [com.reverie.paint.model.PosePresets.buildTranslations] 已有的 `<骨名>_ty` 通道,
 * 因此模型层无需改动; 只改 Y (竖直) 是因为实机反馈的问题集中在高度方向。
 *
 * 导出只在 debug 包可用 (见 [JointOffsetExport]), 正式包里按钮不出现。
 */
@Composable
fun JointOffsetPanel(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val state = vm.currentLayer3DState ?: run {
        onDismiss()
        return
    }
    val context = LocalContext.current
    var selected by remember { mutableStateOf("scapula_L") }
    var axis by remember { mutableStateOf(1) }   // 0 = X (左右), 1 = Y (上下)
    var exportedPath by remember { mutableStateOf<String?>(null) }

    val offsets = state.jointRestOffsets
    val current = JointOffsetSpec.offsetOf(offsets, selected, axis)

    fun apply(value: Float) {
        vm.push3DUndoState(state)
        vm.update3DState(
            state.copy(jointRestOffsets = JointOffsetSpec.withOffset(state.jointRestOffsets, selected, value, axis)),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "关节位置微调",
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "竖直方向 · 单位 1/100 模型",
                    color = Morandi.subText,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "关闭",
                    color = Morandi.accent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 骨骼选择: 按部位分组的横向滚动
            val hScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                JointOffsetSpec.CAT_BONES.forEach { group ->
                    Column {
                        Text(
                            text = group.label,
                            color = Morandi.subText,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            group.bones.forEach { bone ->
                                val active = bone == selected
                                val modified = JointOffsetSpec.offsetOf(offsets, bone, 0) != 0f ||
                                    JointOffsetSpec.offsetOf(offsets, bone, 1) != 0f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) Morandi.accent.copy(alpha = 0.22f) else Morandi.panelHi)
                                        .border(
                                            1.dp,
                                            if (modified) Morandi.accent else Color.Transparent,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .clickable { selected = bone }
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = bone,
                                        color = if (active) Morandi.text else Morandi.subText,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 当前骨: 数值 + 滑杆
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selected,
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    text = "%s = %+.1f".format(if (axis == 0) "x" else "y", current),
                    color = if (current == 0f) Morandi.subText else Morandi.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.width(96.dp),
                )
                com.reverie.paint.ui.components.ReSlider(
                    value = current.coerceIn(-JointOffsetSpec.RANGE, JointOffsetSpec.RANGE),
                    onValue = { apply((it * 2f).roundToInt() / 2f) },
                    valueRange = -JointOffsetSpec.RANGE..JointOffsetSpec.RANGE,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(0 to "左右 X", 1 to "上下 Y").forEach { (a, label) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (axis == a) Morandi.accent.copy(alpha = 0.28f) else Morandi.panelHi)
                            .clickable { axis = a }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(text = label, color = Morandi.text, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-5f to "-5", -1f to "-1", 1f to "+1", 5f to "+5").forEach { (delta, label) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi)
                            .clickable {
                                apply((current + delta).coerceIn(-JointOffsetSpec.RANGE, JointOffsetSpec.RANGE))
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = label, color = Morandi.text, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable { apply(0f) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(text = "归零当前", color = Morandi.subText, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable {
                            vm.push3DUndoState(state)
                            vm.update3DState(
                                state.copy(jointRestOffsets = JointOffsetSpec.clearOffsets(state.jointRestOffsets)),
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(text = "全部归零", color = Morandi.subText, fontSize = 11.sp)
                }
                if (JointOffsetExport.available) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.accent.copy(alpha = 0.22f))
                            .clickable {
                                exportedPath = JointOffsetExport.write(
                                    context,
                                    state.modelType,
                                    state.posePreset,
                                    state.jointRestOffsets,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = "导出", color = Morandi.text, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = exportedPath?.let { "已导出: $it" }
                    ?: "改的是骨骼绑定位置 (网格不动), 只影响之后旋转该关节的轴心 · 导出仅 debug 包",
                color = Morandi.subText,
                fontSize = 9.sp,
            )
        }
    }
}
