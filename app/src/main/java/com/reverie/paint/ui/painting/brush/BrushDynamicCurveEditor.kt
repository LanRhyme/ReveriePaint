/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.model.BrushSensor
import com.reverie.paint.model.CurvePoint
import com.reverie.paint.model.CurvePreset
import com.reverie.paint.model.DynamicOptionConfig
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * 独立参数的高阶贝塞尔动态响应曲线编辑器 (BrushDynamicCurveEditor)
 *
 * 特性：
 * 1. 任意多控制点贝塞尔曲线绘制与插值，支持增删控制点、防越界保护
 * 2. 全量 Krita 传感器切换（压力、速度、运笔角、俯仰倾角、方位倾角、旋转、切向压感、渐隐、距离、时间、随机噪点）
 * 3. 常用曲线预设快速切换（线性/软/硬/S形/阶梯/拱形）与水平/垂直反转
 * 4. 选定控制点坐标数值步进微调面板
 * 5. 试画板实时光点追踪游标与输入/输出可视化标尺
 */
@Composable
fun BrushDynamicCurveEditor(
    config: DynamicOptionConfig,
    onConfigChange: (DynamicOptionConfig) -> Unit,
    modifier: Modifier = Modifier,
    liveInput: Float = -1f,
    cardBg: Color = Morandi.panel,
    borderCol: Color = Morandi.border,
    textMain: Color = Morandi.text,
    textSub: Color = Morandi.subText,
) {
    val haptic = LocalHapticFeedback.current
    var selectedPointIndex by remember { mutableIntStateOf(-1) }
    var showSensorPicker by remember { mutableStateOf(false) }

    val currentSensor = remember(config.sensorId) {
        BrushSensor.fromId(config.sensorId)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg.copy(alpha = 0.5f))
            .border(1.dp, borderCol.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 1. 顶栏：开关 + 传感器徽标 + 重置 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReSwitch(
                    checked = config.enabled,
                    onChecked = { checked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfigChange(config.copy(enabled = checked))
                    },
                )
                Text(
                    text = stringResource(R.string.brush_dynamics_expand_title),
                    color = if (config.enabled) textMain else textSub,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (config.enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 当前传感器选择胶囊
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.accent.copy(alpha = 0.15f))
                            .clickable { showSensorPicker = !showSensorPicker }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                painter = painterResource(currentSensor.iconRes),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = stringResource(currentSensor.titleRes),
                                color = Morandi.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.accent.copy(alpha = 0.7f),
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }

                    // 重置曲线按钮
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onConfigChange(
                                    config.copy(
                                        points = CurvePreset.LINEAR.createPoints(),
                                        strength = 1.0f,
                                    )
                                )
                                selectedPointIndex = -1
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.brush_dynamics_reset),
                            tint = textSub,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }

        if (config.enabled) {
            // ---- 2. 传感器展开选择器 (全量 Krita 传感器矩阵) ----
            AnimatedVisibility(
                visible = showSensorPicker,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.75f))
                        .border(1.dp, borderCol.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.brush_studio_sensor_title),
                        color = textSub,
                        fontSize = 10.5.sp,
                    )
                    // 流式展示全部传感器
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BrushSensor.entries.forEach { sensor ->
                            val isSelected = sensor.id == config.sensorId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) Morandi.accent else Morandi.panel.copy(alpha = 0.8f)
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onConfigChange(config.copy(sensorId = sensor.id))
                                        showSensorPicker = false
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(sensor.iconRes),
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else textSub,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = stringResource(sensor.titleRes),
                                        color = if (isSelected) Color.White else textMain,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 3. 可交互曲线网格画布 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141619))
                    .border(1.dp, borderCol.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .pointerInput(config.points) {
                        detectTapGestures { offset ->
                            val pad = 16f
                            val w = size.width - pad * 2
                            val h = size.height - pad * 2
                            if (w <= 0 || h <= 0) return@detectTapGestures

                            val clickNormX = ((offset.x - pad) / w).coerceIn(0f, 1f)
                            val clickNormY = (1f - (offset.y - pad) / h).coerceIn(0f, 1f)

                            // 检查是否点中了现有控制点
                            var hitIndex = -1
                            val hitRadiusPx = 28f
                            config.points.forEachIndexed { idx, pt ->
                                val ptPxX = pad + pt.x * w
                                val ptPxY = pad + (1f - pt.y) * h
                                val dist = kotlin.math.hypot(offset.x - ptPxX, offset.y - ptPxY)
                                if (dist <= hitRadiusPx) {
                                    hitIndex = idx
                                }
                            }

                            if (hitIndex >= 0) {
                                selectedPointIndex = hitIndex
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else {
                                // 点击空白网格处新增控制点
                                val newPoints = (config.points + CurvePoint.of(clickNormX, clickNormY))
                                    .sortedBy { it.x }
                                selectedPointIndex = newPoints.indexOfFirst { it.x == clickNormX }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onConfigChange(config.copy(points = newPoints))
                            }
                        }
                    }
                    .pointerInput(config.points, selectedPointIndex) {
                        // 拖动期间以"按下时的点"为基准做绝对换算, 而不是逐帧累加 dragAmount:
                        // 累加依赖父级回传的 config.points, 而 onConfigChange 要绕一圈
                        // runCore 才回来, 状态滞后会让拖动丢增量/抖动。
                        var grabbedIdx = -1
                        var startNormX = 0f
                        var startNormY = 0f
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val pad = 16f
                                val w = size.width - pad * 2
                                val h = size.height - pad * 2
                                val hitRadiusPx = 32f
                                grabbedIdx = -1
                                config.points.forEachIndexed { idx, pt ->
                                    val ptPxX = pad + pt.x * w
                                    val ptPxY = pad + (1f - pt.y) * h
                                    val dist = kotlin.math.hypot(startOffset.x - ptPxX, startOffset.y - ptPxY)
                                    // 取最近的命中点, 而不是最后命中的那个
                                    if (dist <= hitRadiusPx) {
                                        val cur = if (grabbedIdx < 0) Float.MAX_VALUE
                                        else kotlin.math.hypot(
                                            startOffset.x - (pad + config.points[grabbedIdx].x * w),
                                            startOffset.y - (pad + (1f - config.points[grabbedIdx].y) * h),
                                        )
                                        if (dist < cur) {
                                            grabbedIdx = idx
                                            startNormX = pt.x
                                            startNormY = pt.y
                                        }
                                    }
                                }
                                // 命中空白处就放弃本次拖动: 否则会顺移上一次选中的点
                                if (grabbedIdx >= 0) selectedPointIndex = grabbedIdx
                            },
                        ) { change, _ ->
                            val idx = grabbedIdx
                            if (idx in config.points.indices) {
                                change.consume()
                                val pad = 16f
                                val w = size.width - pad * 2
                                val h = size.height - pad * 2
                                if (w > 0 && h > 0) {
                                    val curPos = change.position
                                    val newNormY = (1f - (curPos.y - pad) / h).coerceIn(0f, 1f)
                                    // 首尾端点的 X 严格锁定在 0 与 1
                                    val newNormX = when (idx) {
                                        0 -> 0f
                                        config.points.lastIndex -> 1f
                                        else -> ((curPos.x - pad) / w).coerceIn(0.01f, 0.99f)
                                    }

                                    val mutable = config.points.toMutableList()
                                    mutable[idx] = CurvePoint.of(newNormX, newNormY)
                                    // 仅对中间点重新保持有序
                                    val sorted = mutable.sortedBy { it.x }
                                    // 用"被移动的点在排序后的下标"回填, 不用 indexOf(值):
                                    // 拖到与邻点数值重合时 indexOf 会返回邻点的下标, 之后
                                    // 手柄会突然跳到另一个点上。sortedBy 是稳定排序, 因此
                                    // 被移动点若与邻点 x 相同, 保持相对次序即为它自身。
                                    selectedPointIndex = sorted.indexOfFirst { it === mutable[idx] }
                                        .takeIf { it >= 0 } ?: sorted.indexOf(mutable[idx])
                                    onConfigChange(config.copy(points = sorted))
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pad = 16.dp.toPx()
                    val w = size.width - pad * 2
                    val h = size.height - pad * 2
                    if (w <= 0 || h <= 0) return@Canvas

                    // 1. 绘制 4x4 网格辅助线
                    val gridSteps = 4
                    for (i in 0..gridSteps) {
                        val gx = pad + (w / gridSteps) * i
                        val gy = pad + (h / gridSteps) * i
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(gx, pad),
                            end = Offset(gx, pad + h),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(pad, gy),
                            end = Offset(pad + w, gy),
                            strokeWidth = 1f,
                        )
                    }

                    // 2. 对角参考虚线
                    drawLine(
                        color = Color.White.copy(alpha = 0.12f),
                        start = Offset(pad, pad + h),
                        end = Offset(pad + w, pad),
                        strokeWidth = 1f,
                    )

                    // 3. 拟合计算样条曲线路径
                    val sorted = config.points.sortedBy { it.x }
                    if (sorted.isNotEmpty()) {
                        val curvePath = Path()
                        val fillPath = Path()

                        val sampleSteps = 100
                        val firstX = pad + sorted.first().x * w
                        val firstY = pad + (1f - sorted.first().y) * h

                        curvePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, pad + h)
                        fillPath.lineTo(firstX, firstY)

                        for (s in 1..sampleSteps) {
                            val normX = s.toFloat() / sampleSteps
                            val normY = config.evaluate(normX)
                            val px = pad + normX * w
                            val py = pad + (1f - normY) * h
                            curvePath.lineTo(px, py)
                            fillPath.lineTo(px, py)
                        }

                        val lastX = pad + sorted.last().x * w
                        fillPath.lineTo(lastX, pad + h)
                        fillPath.close()

                        // 填充渐变下投影
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(Morandi.accent.copy(alpha = 0.28f), Color.Transparent),
                                startY = pad,
                                endY = pad + h,
                            ),
                        )

                        // 描画主曲线轮廓
                        drawPath(
                            path = curvePath,
                            color = Morandi.accent,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // 4. 绘制控制点节点
                    sorted.forEachIndexed { index, pt ->
                        val px = pad + pt.x * w
                        val py = pad + (1f - pt.y) * h
                        val isSel = index == selectedPointIndex

                        // 选中光晕
                        if (isSel) {
                            drawCircle(
                                color = Morandi.accent.copy(alpha = 0.35f),
                                radius = 10.dp.toPx(),
                                center = Offset(px, py),
                            )
                        }

                        // 节点主体
                        drawCircle(
                            color = if (isSel) Color.White else Morandi.accent,
                            radius = if (isSel) 5.dp.toPx() else 4.dp.toPx(),
                            center = Offset(px, py),
                        )
                        drawCircle(
                            color = Color(0xFF141619),
                            radius = if (isSel) 2.5.dp.toPx() else 2.dp.toPx(),
                            center = Offset(px, py),
                        )
                    }

                    // 5. 试画板实时动态追踪光点 (Live Cursor)
                    if (liveInput in 0f..1f) {
                        val liveOutput = config.evaluate(liveInput)
                        val lpx = pad + liveInput * w
                        val lpy = pad + (1f - liveOutput) * h

                        // 外圈脉冲光环
                        drawCircle(
                            color = Color(0xFF50E3C2).copy(alpha = 0.45f),
                            radius = 9.dp.toPx(),
                            center = Offset(lpx, lpy),
                        )
                        // 内圈高亮核心
                        drawCircle(
                            color = Color(0xFF50E3C2),
                            radius = 4.5.dp.toPx(),
                            center = Offset(lpx, lpy),
                        )
                    }
                }

                // 左下角微提示
                Text(
                    text = stringResource(R.string.brush_dynamics_add_point_hint),
                    color = textSub.copy(alpha = 0.5f),
                    fontSize = 9.5.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 6.dp),
                )

                // 右上角实时响应标尺 HUD
                if (liveInput in 0f..1f) {
                    val liveOutput = config.evaluate(liveInput)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp),
                    ) {
                        Text(
                            text = "In: ${(liveInput * 100).toInt()}% → Out: ${(liveOutput * 100).toInt()}%",
                            color = Color(0xFF50E3C2),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ---- 4. 曲线工具条：快捷预设 + 反转/增删 + 选定点微调 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 预设模式选项卡
                CurvePreset.entries.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.6f))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onConfigChange(config.copy(points = preset.createPoints()))
                                selectedPointIndex = -1
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(preset.titleRes),
                            color = textSub,
                            fontSize = 10.sp,
                        )
                    }
                }

                // 水平反转
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.6f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val inverted = config.points.map { CurvePoint.of(1f - it.x, it.y) }.sortedBy { it.x }
                            onConfigChange(config.copy(points = inverted))
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.brush_dynamics_invert_h),
                        color = textSub,
                        fontSize = 10.sp,
                    )
                }

                // 垂直反转
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.6f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val inverted = config.points.map { CurvePoint.of(it.x, 1f - it.y) }
                            onConfigChange(config.copy(points = inverted))
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.brush_dynamics_invert_v),
                        color = textSub,
                        fontSize = 10.sp,
                    )
                }

                // 删除选定节点 (若选中的是非首尾节点)
                if (selectedPointIndex > 0 && selectedPointIndex < config.points.lastIndex) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFC86464).copy(alpha = 0.15f))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val mutable = config.points.toMutableList()
                                mutable.removeAt(selectedPointIndex)
                                selectedPointIndex = -1
                                onConfigChange(config.copy(points = mutable))
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "删除点",
                            color = Color(0xFFC86464),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ---- 5. 选定点坐标数值精调 (Steppers) ----
            if (selectedPointIndex in config.points.indices) {
                val currentPt = config.points[selectedPointIndex]
                val isEndpoint = selectedPointIndex == 0 || selectedPointIndex == config.points.lastIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "点 #${selectedPointIndex + 1}",
                        color = textSub,
                        fontSize = 10.5.sp,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // X 步进调节 (首尾锁定)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "X: ${(currentPt.x * 100).roundToInt()}%",
                                color = textMain,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (!isEndpoint) {
                                PointStepper(
                                    onStep = { delta ->
                                        val newX = (currentPt.x + delta).coerceIn(0.01f, 0.99f)
                                        val mutable = config.points.toMutableList()
                                        mutable[selectedPointIndex] = CurvePoint.of(newX, currentPt.y)
                                        val sorted = mutable.sortedBy { it.x }
                                        selectedPointIndex = sorted.indexOf(mutable[selectedPointIndex])
                                        onConfigChange(config.copy(points = sorted))
                                    }
                                )
                            }
                        }

                        // Y 步进调节
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Y: ${(currentPt.y * 100).roundToInt()}%",
                                color = textMain,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            PointStepper(
                                onStep = { delta ->
                                    val newY = (currentPt.y + delta).coerceIn(0f, 1f)
                                    val mutable = config.points.toMutableList()
                                    mutable[selectedPointIndex] = CurvePoint.of(currentPt.x, newY)
                                    onConfigChange(config.copy(points = mutable))
                                }
                            )
                        }
                    }
                }
            }

            // ---- 6. 动态影响强度滑块 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.brush_dynamics_strength),
                    color = textSub,
                    fontSize = 11.sp,
                    modifier = Modifier.width(76.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    ReSlider(
                        value = config.strength,
                        onValue = { onConfigChange(config.copy(strength = it)) },
                    )
                }
                Text(
                    text = "${(config.strength * 100).roundToInt()}%",
                    color = textMain,
                    fontSize = 11.sp,
                    modifier = Modifier.width(36.dp),
                )
            }
        }
    }
}

@Composable
private fun PointStepper(onStep: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Morandi.panelHi)
                .clickable { onStep(-0.02f) },
            contentAlignment = Alignment.Center,
        ) {
            Text("-", color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Morandi.panelHi)
                .clickable { onStep(0.02f) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
