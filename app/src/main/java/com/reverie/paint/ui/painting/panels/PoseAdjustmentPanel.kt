/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Model3DState
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class JointCategory(val titleRes: Int) {
    ALL(R.string.layer_3d_part_all),
    HEAD(R.string.layer_3d_part_head),
    TORSO(R.string.layer_3d_part_body),
    FRONT_LEGS(R.string.layer_3d_part_front_legs),
    HIND_LEGS(R.string.layer_3d_part_hind_legs),
    ARMS(R.string.layer_3d_part_arms),
    LEGS(R.string.layer_3d_part_legs),
    TAIL(R.string.layer_3d_part_tail),
}

private data class JointDef(
    val key: String,
    val titleRes: Int,
    val min: Float,
    val max: Float,
    val category: JointCategory,
)

/**
 * 画布骨骼模式点选的任意骨骼, 三轴角度键 (与 [com.reverie.paint.model.PosePresets] 的兜底键约定一致)
 */
private const val AXIS_PITCH_SUFFIX = "_p"
private const val AXIS_YAW_SUFFIX = "_y"
private const val AXIS_ROLL_SUFFIX = "_r"

/**
 * 3D 人偶与模型专属骨骼关节姿态调节浮动面板 (非模态浮窗，不遮挡画布手势与实时视角旋转)
 */
@Composable
fun PoseAdjustmentPanel(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = vm.currentLayer3DState ?: run {
        onDismiss()
        return
    }
    var showJointOffset by remember { mutableStateOf(false) }
    if (showJointOffset) {
        JointOffsetPanel(vm = vm, onDismiss = { showJointOffset = false })
    }

    val config = LocalConfiguration.current
    val isPhone = config.screenWidthDp < 600 || config.screenWidthDp < config.screenHeightDp
    val isCat = state.modelType.equals("cat", ignoreCase = true)

    val presets = remember(isCat) {
        if (isCat) {
            listOf(
                "stand" to R.string.layer_3d_cat_stand,
                "sit" to R.string.layer_3d_cat_sit,
                "loaf" to R.string.layer_3d_cat_loaf,
                "stretch" to R.string.layer_3d_cat_stretch,
                "walk" to R.string.layer_3d_cat_walk,
                "curl" to R.string.layer_3d_cat_curl,
                "pounce" to R.string.layer_3d_cat_pounce,
                "rear" to R.string.layer_3d_cat_rear,
                "look" to R.string.layer_3d_cat_look,
            )
        } else {
            listOf(
                "stand" to R.string.layer_3d_pose_stand,
                "sit" to R.string.layer_3d_pose_sit,
                "action" to R.string.layer_3d_pose_action,
                "crossed" to R.string.layer_3d_pose_crossed,
                "hips" to R.string.layer_3d_pose_hips,
                "kneel" to R.string.layer_3d_pose_kneel,
            )
        }
    }

    val categories = remember(isCat) {
        if (isCat) {
            listOf(
                JointCategory.ALL,
                JointCategory.HEAD,
                JointCategory.TORSO,
                JointCategory.FRONT_LEGS,
                JointCategory.HIND_LEGS,
                JointCategory.TAIL,
            )
        } else {
            listOf(
                JointCategory.ALL,
                JointCategory.HEAD,
                JointCategory.TORSO,
                JointCategory.ARMS,
                JointCategory.LEGS,
            )
        }
    }

    var selectedCategory by remember { mutableStateOf(JointCategory.ALL) }

    val jointDefs = remember(isCat) {
        if (isCat) {
            listOf(
                JointDef("cat_head_pitch", R.string.layer_3d_joint_cat_head_pitch, -30f, 30f, JointCategory.HEAD),
                JointDef("cat_head_yaw", R.string.layer_3d_joint_cat_head_yaw, -40f, 40f, JointCategory.HEAD),
                JointDef("cat_spine_arch", R.string.layer_3d_joint_cat_spine, -25f, 30f, JointCategory.TORSO),
                JointDef("cat_fl_leg", R.string.layer_3d_joint_cat_fl_leg, -45f, 45f, JointCategory.FRONT_LEGS),
                JointDef("cat_fr_leg", R.string.layer_3d_joint_cat_fr_leg, -45f, 45f, JointCategory.FRONT_LEGS),
                JointDef("cat_hl_leg", R.string.layer_3d_joint_cat_hl_leg, -50f, 45f, JointCategory.HIND_LEGS),
                JointDef("cat_hr_leg", R.string.layer_3d_joint_cat_hr_leg, -50f, 45f, JointCategory.HIND_LEGS),
                JointDef("cat_tail_pitch", R.string.layer_3d_joint_cat_tail_pitch, -50f, 50f, JointCategory.TAIL),
                JointDef("cat_tail_yaw", R.string.layer_3d_joint_cat_tail_yaw, -45f, 45f, JointCategory.TAIL),
            )
        } else {
            listOf(
                JointDef("head_pitch", R.string.layer_3d_joint_head_pitch, -40f, 40f, JointCategory.HEAD),
                JointDef("head_yaw", R.string.layer_3d_joint_head_yaw, -55f, 55f, JointCategory.HEAD),
                JointDef("spine_pitch", R.string.layer_3d_joint_spine_pitch, -25f, 35f, JointCategory.TORSO),
                JointDef("spine_yaw", R.string.layer_3d_joint_spine_yaw, -40f, 40f, JointCategory.TORSO),
                JointDef("l_arm_pitch", R.string.layer_3d_joint_l_arm_pitch, -90f, 90f, JointCategory.ARMS),
                JointDef("l_arm_yaw", R.string.layer_3d_joint_l_arm_yaw, -80f, 80f, JointCategory.ARMS),
                JointDef("l_elbow", R.string.layer_3d_joint_l_elbow, 0f, 135f, JointCategory.ARMS),
                JointDef("r_arm_pitch", R.string.layer_3d_joint_r_arm_pitch, -90f, 90f, JointCategory.ARMS),
                JointDef("r_arm_yaw", R.string.layer_3d_joint_r_arm_yaw, -80f, 80f, JointCategory.ARMS),
                JointDef("r_elbow", R.string.layer_3d_joint_r_elbow, 0f, 135f, JointCategory.ARMS),
                JointDef("l_thigh_pitch", R.string.layer_3d_joint_l_thigh_pitch, -80f, 50f, JointCategory.LEGS),
                JointDef("l_thigh_yaw", R.string.layer_3d_joint_l_thigh_yaw, -35f, 35f, JointCategory.LEGS),
                JointDef("l_knee", R.string.layer_3d_joint_l_knee, 0f, 135f, JointCategory.LEGS),
                JointDef("r_thigh_pitch", R.string.layer_3d_joint_r_thigh_pitch, -80f, 50f, JointCategory.LEGS),
                JointDef("r_thigh_yaw", R.string.layer_3d_joint_r_thigh_yaw, -35f, 35f, JointCategory.LEGS),
                JointDef("r_knee", R.string.layer_3d_joint_r_knee, 0f, 135f, JointCategory.LEGS),
            )
        }
    }

    val displayJoints = remember(jointDefs, selectedCategory) {
        if (selectedCategory == JointCategory.ALL) {
            jointDefs
        } else {
            jointDefs.filter { it.category == selectedCategory }
        }
    }

    // 追踪滑块交互前快照，松手时推入撤销栈
    var activeSliderStartState by remember { mutableStateOf<Model3DState?>(null) }

    fun commitSliderIfNeeded() {
        val start = activeSliderStartState
        val cur = vm.currentLayer3DState
        if (start != null && cur != null && start != cur) {
            vm.push3DUndoState(start)
        }
        activeSliderStartState = null
    }

    val panelShape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .widthIn(max = if (isPhone) 480.dp else 360.dp)
            .shadow(16.dp, panelShape, ambientColor = Color.Black.copy(alpha = 0.28f))
            .clip(panelShape)
            .background(Morandi.panel.copy(alpha = 0.96f))
            .border(1.dp, Morandi.border.copy(alpha = 0.45f), panelShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 1. 顶部控制栏: 标题 + 撤销/重做 + 全部重置 + 关闭
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.layer_3d_pose_rigging),
                color = Morandi.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 3D 专属撤销
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (vm.canUndo3D) Morandi.panelHi else Morandi.panelHi.copy(alpha = 0.35f))
                        .clickable(enabled = vm.canUndo3D) { vm.undo3D() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_undo),
                        contentDescription = stringResource(R.string.layer_3d_pose_undo),
                        tint = if (vm.canUndo3D) Morandi.icon else Morandi.icon.copy(alpha = 0.35f),
                        modifier = Modifier.size(15.dp),
                    )
                }

                // 3D 专属重做
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (vm.canRedo3D) Morandi.panelHi else Morandi.panelHi.copy(alpha = 0.35f))
                        .clickable(enabled = vm.canRedo3D) { vm.redo3D() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_redo),
                        contentDescription = stringResource(R.string.layer_3d_pose_redo),
                        tint = if (vm.canRedo3D) Morandi.icon else Morandi.icon.copy(alpha = 0.35f),
                        modifier = Modifier.size(15.dp),
                    )
                }

                // 重置所有关节微调
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .clickable {
                            // 可视化微调: 打开后直接在画布上点关节拖动即可改位置
                            val next = !vm.jointPositionEditMode
                            vm.jointPositionEditMode = next
                            if (next) {
                                vm.active3DInteractionMode = com.reverie.paint.model.Interaction3DMode.BONE
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (vm.jointPositionEditMode) "微调中 · 点此退出" else "关节位置",
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 数值微调 (备用入口: 需要精确到 0.5 时用)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .clickable { showJointOffset = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "数值",
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 重置所有关节角度
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .clickable { vm.reset3DJointAngles() }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = stringResource(R.string.layer_3d_pose_reset_joints),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 关闭面板
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Morandi.panelHi.copy(alpha = 0.5f))
                        .clickable(onClick = onDismiss),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.common_close),
                        tint = Morandi.subText,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 姿态预设胶囊栏 (Presets)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(presets) { (presetKey, titleRes) ->
                val isSelected = state.posePreset.equals(presetKey, ignoreCase = true)
                val bg = if (isSelected) Morandi.accent else Morandi.panelHi
                val txtColor = if (isSelected) Morandi.onAccent else Morandi.text
                val borderMod = if (isSelected) {
                    Modifier
                } else {
                    Modifier.border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .then(borderMod)
                        .clickable { vm.set3DPosePreset(presetKey) }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(titleRes),
                        color = txtColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. 画布点选的任意骨骼: 三轴精确调节 (任意 GLB / VRM / PMX 骨骼名都能用)
        val selectedBone = vm.active3DSelectedJointName
        if (selectedBone != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.accent.copy(alpha = 0.10f))
                    .border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.layer_3d_pose_selected_bone),
                        color = Morandi.subText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = selectedBone,
                            color = Morandi.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(Morandi.panelHi)
                                .clickable {
                                    val s = vm.currentLayer3DState
                                    if (s != null) {
                                        vm.push3DUndoState(s)
                                        val keys = listOf(
                                            selectedBone + AXIS_PITCH_SUFFIX,
                                            selectedBone + AXIS_YAW_SUFFIX,
                                            selectedBone + AXIS_ROLL_SUFFIX,
                                        )
                                        val kept = s.jointAngles.filterKeys { it !in keys }
                                        vm.update3DState(s.copy(jointAngles = kept))
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.layer_3d_pose_reset_bone),
                                color = Morandi.subText,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }

                val axisDefs = listOf(
                    Triple(AXIS_PITCH_SUFFIX, R.string.layer_3d_axis_pitch, -150f to 150f),
                    Triple(AXIS_YAW_SUFFIX, R.string.layer_3d_axis_yaw, -180f to 180f),
                    Triple(AXIS_ROLL_SUFFIX, R.string.layer_3d_axis_roll, -180f to 180f),
                )
                for ((suffix, labelRes, range) in axisDefs) {
                    val key = selectedBone + suffix
                    val angle = state.jointAngles[key] ?: 0f
                    PoseAngleRow(
                        label = stringResource(labelRes),
                        angle = angle,
                        min = range.first,
                        max = range.second,
                        onValue = { newAngle ->
                            if (activeSliderStartState == null) {
                                activeSliderStartState = vm.currentLayer3DState
                            }
                            vm.set3DJointAngle(key, newAngle)
                        },
                        onRelease = { commitSliderIfNeeded() },
                        onReset = {
                            if (abs(angle) >= 0.05f) {
                                val cur = vm.currentLayer3DState
                                if (cur != null) vm.push3DUndoState(cur)
                                vm.set3DJointAngle(key, 0f)
                            }
                        },
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.layer_3d_pose_no_selection),
                color = Morandi.subText.copy(alpha = 0.8f),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 身体部位分类标签导航 (Filter Chips)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                val chipBg = if (isSelected) Morandi.accent.copy(alpha = 0.22f) else Morandi.panelHi.copy(alpha = 0.5f)
                val chipTextColor = if (isSelected) Morandi.accent else Morandi.subText
                val chipBorder = if (isSelected) {
                    Modifier.border(1.dp, Morandi.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(chipBg)
                        .then(chipBorder)
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(cat.titleRes),
                        color = chipTextColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 骨骼关节点微调滑块列表 (带独立零位重置与吸附)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isPhone) 200.dp else 300.dp),
        ) {
            items(displayJoints, key = { it.key }) { joint ->
                val angle = state.jointAngles[joint.key] ?: 0f
                PoseAngleRow(
                    label = stringResource(joint.titleRes),
                    angle = angle,
                    min = joint.min,
                    max = joint.max,
                    onValue = { newAngle ->
                        if (activeSliderStartState == null) {
                            activeSliderStartState = vm.currentLayer3DState
                        }
                        vm.set3DJointAngle(joint.key, newAngle)
                    },
                    onRelease = { commitSliderIfNeeded() },
                    onReset = {
                        if (abs(angle) >= 0.05f) {
                            vm.push3DUndoState(state)
                            vm.set3DJointAngle(joint.key, 0f)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 单个关节角度滑块: 标题 + 角度角标 (点按归零) + 滑块 (零位吸附 + 松手入撤销栈)
 */
@Composable
private fun PoseAngleRow(
    label: String,
    angle: Float,
    min: Float,
    max: Float,
    onValue: (Float) -> Unit,
    onRelease: () -> Unit,
    onReset: () -> Unit,
) {
    val angleFrac = ((angle - min) / (max - min)).coerceIn(0f, 1f)
    val sign = if (angle > 0) "+" else ""
    val isNonZero = abs(angle) >= 0.05f

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Morandi.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )

            // 独立角度角标 (点击立刻复位该关节到 0°)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isNonZero) Morandi.accent.copy(alpha = 0.14f) else Morandi.panelHi)
                    .clickable { onReset() }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$sign${angle.roundToInt()}°",
                    color = if (isNonZero) Morandi.accent else Morandi.subText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isNonZero) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.layer_3d_pose_reset_single),
                        tint = Morandi.accent,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        ReSlider(
            value = angleFrac,
            onValue = { frac ->
                var newAngle = min + frac * (max - min)
                // 零位吸附：在 [-2.5°, +2.5°] 范围内自动吸附回 0°
                if (abs(newAngle) <= 2.5f) {
                    newAngle = 0f
                }
                onValue(newAngle)
            },
            onRelease = onRelease,
            height = 16,
        )
    }
}
