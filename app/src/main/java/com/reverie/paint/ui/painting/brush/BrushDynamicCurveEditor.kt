/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.model.BrushSensor
import com.reverie.paint.model.CurvePoint
import com.reverie.paint.model.CurvePreset
import com.reverie.paint.model.DynamicOptionConfig
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

/**
 * 笔刷参数动力学曲线组件
 *
 * 规范采用「紧凑微缩胶囊预览 + 全功能大尺寸弹窗调校」架构：
 * 1. 卡片内仅展示紧凑微缩预览与传感器徽标，消除纵向过度堆叠
 * 2. 点击唤起专属大尺寸调校弹窗，采用单手势 awaitEachGesture 内核，彻底解决坐标系错位与拖拽手势冲突
 * 3. 弹窗内集成硬件压感试笔条与预设切换，支持所见即所得调校
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
    var showDialog by remember { mutableStateOf(false) }

    val currentSensor = remember(config.sensorId) {
        BrushSensor.fromId(config.sensorId)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (config.enabled) Morandi.panel.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 顶栏：左侧标题与传感器药丸，右侧重置按钮与开关 (完全对齐 Studio 统一交互与尺度)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.brush_dynamics_expand_title),
                    color = textMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
                if (config.enabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Morandi.accent.copy(alpha = 0.16f))
                            .clickable { showDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                painter = painterResource(currentSensor.iconRes),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = stringResource(currentSensor.titleRes),
                                color = Morandi.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (config.enabled) {
                    ReIconButton(
                        icon = R.drawable.ic_refresh,
                        desc = stringResource(R.string.brush_dynamics_reset),
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onConfigChange(
                                config.copy(
                                    points = CurvePreset.LINEAR.createPoints(),
                                    strength = 1.0f,
                                )
                            )
                        },
                        size = 28.dp,
                        iconSize = 13.dp,
                        tint = textSub,
                    )
                }
                ReSwitch(
                    checked = config.enabled,
                    onChecked = { checked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfigChange(config.copy(enabled = checked))
                    },
                    modifier = Modifier.scale(0.85f),
                )
            }
        }

        // 启用状态下：微缩曲线条目，去除所有突兀边框，内敛深色卡片
        if (config.enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi.copy(alpha = 0.6f))
                    .clickable { showDialog = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 微缩高清曲线预览 (无多余白边)
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF141619)),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        val w = size.width
                        val h = size.height

                        val gridCol = Color.White.copy(alpha = 0.08f)
                        drawLine(gridCol, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), 0.8f)
                        drawLine(gridCol, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), 0.8f)

                        val path = Path()
                        val step = 40
                        for (s in 0..step) {
                            val xVal = s / step.toFloat()
                            val yVal = config.evaluate(xVal)
                            val sx = xVal * w
                            val sy = (1f - yVal) * h
                            if (s == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                        }
                        drawPath(path, Morandi.accent, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))

                        config.points.forEach { pt ->
                            drawCircle(Morandi.accent, 2.dp.toPx(), Offset(pt.x * w, (1f - pt.y) * h))
                        }

                        if (liveInput in 0f..1f) {
                            val liveX = liveInput * w
                            val liveY = (1f - config.evaluate(liveInput)) * h
                            drawCircle(Color.White, 3.dp.toPx(), Offset(liveX, liveY))
                        }
                    }
                }

                // 中间信息
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(currentSensor.titleRes),
                        color = textMain,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${(config.strength * 100).toInt()}% · ${stringResource(R.string.brush_studio_curve_edit)}",
                        color = textSub,
                        fontSize = 10.sp,
                    )
                }

                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = textSub.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp).rotate(-90f),
                )
            }
        }
    }

    if (showDialog) {
        BrushDynamicCurveDialog(
            config = config,
            onConfigChange = onConfigChange,
            onDismiss = { showDialog = false },
            cardBg = cardBg,
            textMain = textMain,
            textSub = textSub,
            borderCol = borderCol,
        )
    }
}

/**
 * 动力学曲线大尺寸精调弹窗
 */
@Composable
internal fun BrushDynamicCurveDialog(
    config: DynamicOptionConfig,
    onConfigChange: (DynamicOptionConfig) -> Unit,
    onDismiss: () -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    val haptic = LocalHapticFeedback.current
    var showSensorDropdown by remember { mutableStateOf(false) }

    val currentSensor = remember(config.sensorId) {
        BrushSensor.fromId(config.sensorId)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 580.dp)
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 1. 顶栏：标题 + 传感器切换胶囊 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.brush_dynamics_expand_title),
                                color = textMain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.brush_dynamics_add_point_hint),
                                color = textSub,
                                fontSize = 11.sp,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 传感器下拉选择
                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.accent.copy(alpha = 0.15f))
                                        .clickable { showSensorDropdown = true }
                                        .padding(horizontal = 9.dp, vertical = 5.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                                            fontSize = 12.sp,
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

                                DropdownMenu(
                                    expanded = showSensorDropdown,
                                    onDismissRequest = { showSensorDropdown = false },
                                    modifier = Modifier.background(Morandi.panelHi),
                                ) {
                                    BrushSensor.entries.forEach { sensor ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(sensor.iconRes),
                                                        contentDescription = null,
                                                        tint = if (sensor == currentSensor) Morandi.accent else textSub,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                    Text(
                                                        text = stringResource(sensor.titleRes),
                                                        color = if (sensor == currentSensor) Morandi.accent else textMain,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (sensor == currentSensor) FontWeight.SemiBold else FontWeight.Normal,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showSensorDropdown = false
                                                onConfigChange(config.copy(sensorId = sensor.id))
                                            },
                                        )
                                    }
                                }
                            }

                            // 关闭按钮
                            ReIconButton(R.drawable.ic_x, stringResource(R.string.common_close), onDismiss, size = 30.dp, tint = textSub)
                        }
                    }

                    // 2. 快捷预设切换栏 (水平可滑动)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CurvePreset.entries.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi.copy(alpha = 0.8f))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onConfigChange(config.copy(points = preset.createPoints()))
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = stringResource(preset.titleRes),
                                    color = textMain,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        // 水平反转
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi.copy(alpha = 0.8f))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val inverted = config.points.map { CurvePoint.of(1f - it.x, it.y) }.sortedBy { it.x }
                                    onConfigChange(config.copy(points = inverted))
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(stringResource(R.string.brush_dynamics_invert_h), color = textSub, fontSize = 11.sp)
                        }

                        // 垂直反转
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi.copy(alpha = 0.8f))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val inverted = config.points.map { CurvePoint.of(it.x, 1f - it.y) }
                                    onConfigChange(config.copy(points = inverted))
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(stringResource(R.string.brush_dynamics_invert_v), color = textSub, fontSize = 11.sp)
                        }
                    }

                    // 3. 核心大尺寸曲线交互网格 (单手势引擎内核)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi),
                    ) {
                        DynamicCurveEditorCanvas(
                            config = config,
                            onPointsChanged = { newPts ->
                                onConfigChange(config.copy(points = newPts))
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // 4. 实时硬件压感试笔条
                    DynamicTestStrokeCanvas(
                        config = config,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )

                    // 5. 影响强度调节滑块
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
                        ReSlider(
                            value = config.strength,
                            onValue = { onConfigChange(config.copy(strength = it.coerceIn(0.05f, 1f))) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(config.strength * 100).toInt()}%",
                            color = textMain,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(36.dp),
                        )
                    }

                    // 底部操作栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ReTextButton(
                            stringResource(R.string.common_confirm),
                            onClick = onDismiss,
                            textColor = Morandi.accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 曲线网格编辑器交互画布 (单手势 awaitEachGesture 内核，坐标系严格统一)
 */
@Composable
private fun DynamicCurveEditorCanvas(
    config: DynamicOptionConfig,
    onPointsChanged: (List<CurvePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentPoints by rememberUpdatedState(config.points)
    val currentOnPointsChanged by rememberUpdatedState(onPointsChanged)
    var selectedIdx by remember { mutableIntStateOf(-1) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapIndex by remember { mutableIntStateOf(-1) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val density = this.density
                val pad = 20f * density
                val w = size.width.toFloat() - pad * 2f
                val h = size.height.toFloat() - pad * 2f
                if (w <= 0f || h <= 0f) return@awaitEachGesture

                val touchOffset = down.position
                val pts = currentPoints
                val touchRadiusPx = 36f * density

                // 1. 命中测试
                var foundIdx = -1
                for (i in pts.indices) {
                    val pt = pts[i]
                    val screenX = pad + pt.x * w
                    val screenY = pad + (1f - pt.y) * h
                    val dx = touchOffset.x - screenX
                    val dy = touchOffset.y - screenY
                    if (dx * dx + dy * dy <= touchRadiusPx * touchRadiusPx) {
                        foundIdx = i
                        break
                    }
                }

                // 双击内部控制点直接删除
                val now = System.currentTimeMillis()
                if (foundIdx > 0 && foundIdx < pts.size - 1) {
                    if (foundIdx == lastTapIndex && now - lastTapTime < 350L) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val curList = pts.toMutableList()
                        curList.removeAt(foundIdx)
                        currentOnPointsChanged(curList)
                        lastTapTime = 0L
                        lastTapIndex = -1
                        selectedIdx = -1
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }
                    lastTapTime = now
                    lastTapIndex = foundIdx
                } else {
                    lastTapTime = 0L
                    lastTapIndex = -1
                }

                var activeIdx = foundIdx
                if (activeIdx == -1) {
                    // 空白处添加新点 (上限 6 个)
                    if (pts.size < 6) {
                        val newPt = CurvePoint.of(
                            ((touchOffset.x - pad) / w).coerceIn(0.02f, 0.98f),
                            (1f - (touchOffset.y - pad) / h).coerceIn(0f, 1f),
                        )
                        val updated = (pts + newPt).sortedBy { it.x }
                        activeIdx = updated.indexOf(newPt)
                        selectedIdx = activeIdx
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnPointsChanged(updated)
                    } else {
                        selectedIdx = -1
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }
                } else {
                    selectedIdx = activeIdx
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }

                var isDraggedOutOfCanvas = false

                // 拖拽跟踪
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()

                    val curList = currentPoints.toMutableList()
                    if (activeIdx in curList.indices) {
                        val isInterior = activeIdx > 0 && activeIdx < curList.size - 1
                        if (isInterior) {
                            val outThresh = 30f * density
                            val p = change.position
                            isDraggedOutOfCanvas = p.y < -outThresh || p.y > size.height + outThresh ||
                                    p.x < -outThresh || p.x > size.width + outThresh
                        }

                        // X 轴按邻点约束，杜绝交叉
                        val minX = if (activeIdx == 0) 0f else (curList[activeIdx - 1].x + 0.02f).coerceAtMost(1f)
                        val maxX = if (activeIdx == curList.size - 1) 1f else (curList[activeIdx + 1].x - 0.02f).coerceAtLeast(0f)
                        val curX = if (activeIdx == 0) 0f else if (activeIdx == curList.size - 1) 1f else ((change.position.x - pad) / w).coerceIn(minX, maxX)
                        val curY = (1f - (change.position.y - pad) / h).coerceIn(0f, 1f)
                        curList[activeIdx] = CurvePoint.of(curX, curY)
                        currentOnPointsChanged(curList)
                    }
                }

                // 拖出边界释放删除内部点
                if (isDraggedOutOfCanvas && activeIdx > 0 && activeIdx < currentPoints.size - 1) {
                    val curList = currentPoints.toMutableList()
                    if (activeIdx in curList.indices) {
                        curList.removeAt(activeIdx)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnPointsChanged(curList)
                    }
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = 20.dp.toPx()
            val w = size.width - pad * 2f
            val h = size.height - pad * 2f
            if (w <= 0f || h <= 0f) return@Canvas

            // 1. 4x4 网格辅助线
            val gridSteps = 4
            val gridCol = Color.White.copy(alpha = 0.08f)
            for (i in 0..gridSteps) {
                val gx = pad + (w / gridSteps) * i
                val gy = pad + (h / gridSteps) * i
                drawLine(gridCol, Offset(gx, pad), Offset(gx, pad + h), 1f)
                drawLine(gridCol, Offset(pad, gy), Offset(pad + w, gy), 1f)
            }

            // 2. 绘制连续光滑响应曲线
            val path = Path()
            val step = 100
            for (s in 0..step) {
                val xVal = s / step.toFloat()
                val yVal = config.evaluate(xVal)
                val sx = pad + xVal * w
                val sy = pad + (1f - yVal) * h
                if (s == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            drawPath(path, Morandi.accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

            // 3. 绘制控制点
            config.points.forEachIndexed { idx, pt ->
                val cx = pad + pt.x * w
                val cy = pad + (1f - pt.y) * h
                val isSel = idx == selectedIdx

                if (isSel) {
                    drawCircle(Morandi.accent.copy(alpha = 0.3f), 10.dp.toPx(), Offset(cx, cy))
                }
                drawCircle(Color.White, 5.5.dp.toPx(), Offset(cx, cy))
                drawCircle(Morandi.accent, 4.dp.toPx(), Offset(cx, cy))
            }
        }
    }
}

/**
 * 实时硬件压感试笔条：用笔轻重划线，实时体验当前曲线映射过渡
 */
private data class TestStrokePoint(val x: Float, val y: Float, val pressure: Float)

@Composable
private fun DynamicTestStrokeCanvas(
    config: DynamicOptionConfig,
    modifier: Modifier = Modifier,
) {
    var strokes by remember { mutableStateOf(listOf<List<TestStrokePoint>>()) }
    var activeStroke by remember { mutableStateOf<List<TestStrokePoint>?>(null) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .glassBorder(RoundedCornerShape(10.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(config) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val initP = if (down.pressure > 0f) down.pressure else 0.5f
                        val active = mutableListOf(TestStrokePoint(down.position.x, down.position.y, initP))
                        activeStroke = active.toList()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            val p = if (change.pressure > 0f) change.pressure else 0.5f
                            active.add(TestStrokePoint(change.position.x, change.position.y, p))
                            activeStroke = active.toList()
                        }

                        strokes = strokes + listOf(active.toList())
                        activeStroke = null
                    }
                },
        ) {
            val all = strokes + (activeStroke?.let { listOf(it) } ?: emptyList())
            for (stroke in all) {
                if (stroke.size == 1) {
                    val p = stroke[0]
                    val mappedP = config.evaluate(p.pressure)
                    val r = 2.dp.toPx() + mappedP * 8.dp.toPx()
                    drawCircle(Morandi.text, radius = r, center = Offset(p.x, p.y))
                } else {
                    for (i in 0 until stroke.size - 1) {
                        val p0 = stroke[i]
                        val p1 = stroke[i + 1]
                        val mappedP = config.evaluate((p0.pressure + p1.pressure) * 0.5f)
                        val strokeW = 1.5.dp.toPx() + mappedP * 14.dp.toPx()
                        drawLine(
                            color = Morandi.text,
                            start = Offset(p0.x, p0.y),
                            end = Offset(p1.x, p1.y),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }

        // 提示文案与清空按钮
        if (strokes.isEmpty() && activeStroke == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.brush_studio_curve_test_hint),
                    color = Morandi.subText.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Morandi.panel.copy(alpha = 0.8f))
                    .clickable { strokes = emptyList() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = null,
                    tint = Morandi.subText,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
