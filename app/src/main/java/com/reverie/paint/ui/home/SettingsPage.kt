package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

enum class SettingsSubPage {
    MAIN,
    THEME,
    STYLUS
}

@Composable
fun SettingsPageContent(vm: PaintViewModel) {
    var subPage by remember { mutableStateOf(SettingsSubPage.MAIN) }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            if (targetState != SettingsSubPage.MAIN) {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)))
            }
        },
        label = "SettingsSubPageTransition"
    ) { page ->
        when (page) {
            SettingsSubPage.MAIN -> SettingsMainPage(
                onNavigate = { subPage = it }
            )
            SettingsSubPage.THEME -> ThemeSettingsSubPage(
                vm = vm,
                onBack = { subPage = SettingsSubPage.MAIN }
            )
            SettingsSubPage.STYLUS -> StylusSettingsSubPage(
                vm = vm,
                onBack = { subPage = SettingsSubPage.MAIN }
            )
        }
    }
}

@Composable
private fun SettingsMainPage(
    onNavigate: (SettingsSubPage) -> Unit
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "设置",
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // Native Android settings row: 主题设置
        SettingNavRow(
            icon = Icons.Rounded.Palette,
            title = "主题设置",
            summary = "主色调、面板透明度与全屏沉浸模式",
            onClick = { onNavigate(SettingsSubPage.THEME) }
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(8.dp))

        // Native Android settings row: 手写笔设置
        SettingNavRow(
            icon = Icons.Rounded.Draw,
            title = "手写笔设置",
            summary = "笔模式、光标显示、驻停成形与全局压力曲线",
            onClick = { onNavigate(SettingsSubPage.STYLUS) }
        )
    }
}

@Composable
private fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showCustomColorDialog by remember { mutableStateOf(false) }

    val presetSwatches = listOf(
        "#5E8BA8", "#C9ADA7", "#8D9E8F", "#B4552D",
        "#5A6E8A", "#7C8F9E", "#9A8F7B", "#3E6B89"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // Native back bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "主题设置",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Section: 主色调
        SettingCategoryHeader("外观")
        Text(
            text = "主色调",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = "应用于按钮、滑块及高亮强调色",
            color = colors.subText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        // Swatch list + Custom "+" button
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            items(presetSwatches) { hex ->
                val swatchColor = parseColor(hex)
                val isSelected = vm.accentColorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else colors.border,
                            shape = CircleShape
                        )
                        .clickable {
                            vm.updateAccentColor(hex)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Custom color button
            item {
                val isCustomSelected = presetSwatches.none { it.equals(vm.accentColorHex, ignoreCase = true) }
                val currentCustomColor = if (isCustomSelected) parseColor(vm.accentColorHex) else colors.panel
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(currentCustomColor)
                        .border(
                            width = if (isCustomSelected) 3.dp else 1.dp,
                            color = if (isCustomSelected) Color.White else colors.border,
                            shape = CircleShape
                        )
                        .clickable {
                            showCustomColorDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCustomSelected) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = "自定义颜色",
                        tint = if (isCustomSelected) Color.White else colors.icon,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // Section: 界面不透明度
        SettingCategoryHeader("界面不透明度")

        SettingSliderRow(
            title = "主界面面板",
            summary = "工具栏与顶部栏不透明度",
            value = vm.uiOpacity,
            onValueChange = { vm.updateUiOpacity(it) }
        )

        Spacer(Modifier.height(16.dp))

        SettingSliderRow(
            title = "浮动面板",
            summary = "图层、笔刷、颜色等弹窗不透明度",
            value = vm.popupPanelOpacity,
            onValueChange = { vm.updatePopupPanelOpacity(it) }
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // Section: 显示与沉浸
        SettingCategoryHeader("显示")

        SettingSwitchRow(
            title = "沉浸模式",
            summary = "隐藏系统状态栏与导航栏，并将画布延展至刘海挖孔区域",
            checked = vm.immersiveMode,
            onCheckedChange = {
                vm.updateExtendToCutout(true)
                vm.updateImmersiveMode(it)
            }
        )
    }

    if (showCustomColorDialog) {
        CustomColorDialog(
            initialHex = vm.accentColorHex,
            onConfirm = { hex ->
                vm.updateAccentColor(hex)
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false }
        )
    }
}

@Composable
private fun StylusSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showHelpDialog by remember { mutableStateOf(false) }

    val cursorModeOptions = listOf("不显示", "绘画时显示", "悬空显示", "绘画和悬空显示")
    val cursorStyleOptions = listOf("圆形", "十字准星", "点", "无")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // Back Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "手写笔设置",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. 笔模式
        SettingSwitchRow(
            title = "笔模式",
            summary = "开启后禁止手指绘制，手指仅可进行缩放与旋转画布",
            checked = vm.penOnlyMode,
            onCheckedChange = { vm.updatePenOnlyMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 2. 画笔光标
        SettingDropdownRow(
            title = "画笔光标",
            currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { "不显示" },
            options = cursorModeOptions,
            onSelect = { vm.updateBrushCursorMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 3. 橡皮光标
        SettingDropdownRow(
            title = "橡皮光标",
            currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { "绘画和悬空显示" },
            options = cursorModeOptions,
            onSelect = { vm.updateEraserCursorMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 4. 光标样式
        SettingDropdownRow(
            title = "光标样式",
            currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { "圆形" },
            options = cursorStyleOptions,
            onSelect = { vm.updateCursorStyleMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 5. 驻停线条成形
        SettingSwitchRow(
            title = "驻停线条成形",
            summary = "绘制完成笔尖停顿时自动成形为几何线条或圆弧",
            checked = vm.quickShapeEnabled,
            onCheckedChange = { vm.updateQuickShapeEnabled(it) }
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // 6. 全局压力曲线
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "全局压力曲线",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "部分华为设备压力不灵敏，可尝试第二个曲线",
                color = colors.subText,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        // Interactive 4x4 Grid Curve Canvas
        PressureCurveEditor(
            points = vm.pressureControlPoints,
            onPointsChanged = { newPoints ->
                vm.pressureControlPoints = newPoints
                vm.pressureCurvePreset = 4 // custom
            }
        )

        Spacer(Modifier.height(12.dp))

        // Bottom action bar (重置 + 5 预设图标 + 帮助 ?)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panel)
                    .clickable { vm.updatePressureCurvePreset(0) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "重置",
                    color = colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Preset 0: 线性
                CurvePresetIcon(
                    type = 0,
                    selected = vm.pressureCurvePreset == 0,
                    onClick = { vm.updatePressureCurvePreset(0) }
                )
                // Preset 1: 轻压灵敏 (凸)
                CurvePresetIcon(
                    type = 1,
                    selected = vm.pressureCurvePreset == 1,
                    onClick = { vm.updatePressureCurvePreset(1) }
                )
                // Preset 2: 重压偏硬 (凹)
                CurvePresetIcon(
                    type = 2,
                    selected = vm.pressureCurvePreset == 2,
                    onClick = { vm.updatePressureCurvePreset(2) }
                )
                // Preset 3: S型
                CurvePresetIcon(
                    type = 3,
                    selected = vm.pressureCurvePreset == 3,
                    onClick = { vm.updatePressureCurvePreset(3) }
                )
                // Preset 4: 极限
                CurvePresetIcon(
                    type = 4,
                    selected = vm.pressureCurvePreset == 4,
                    onClick = { vm.updatePressureCurvePreset(4) }
                )

                // Help ?
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { showHelpDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "帮助",
                        tint = colors.subText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panelHi)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "压力曲线说明",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "压力曲线用于调整手写笔从轻压到重压的感应输出。\n\n• 曲线向上凸起：轻握笔时即可输出较大粗细与浓度，适合手劲轻或压力较硬的手写笔。\n• 曲线向下凹陷：需要较用力按压才会达到最大粗细，手感更扎实。\n• S型曲线：两端平缓中间灵敏，层次更分明。",
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showHelpDialog = false }) {
                            Text("我知道了", color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PressureCurveEditor(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit
) {
    val colors = Theme.current
    var draggingPointIdx by remember { mutableStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E2022))
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { touchOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        // Find closest control point
                        var minD = Float.MAX_VALUE
                        var foundIdx = -1
                        points.forEachIndexed { idx, pt ->
                            val px = pt.x * w
                            val py = (1f - pt.y) * h
                            val d = (touchOffset.x - px) * (touchOffset.x - px) + (touchOffset.y - py) * (touchOffset.y - py)
                            if (d < minD && d < 40f * 40f) {
                                minD = d
                                foundIdx = idx
                            }
                        }
                        draggingPointIdx = foundIdx
                    },
                    onDragEnd = { draggingPointIdx = -1 },
                    onDragCancel = { draggingPointIdx = -1 },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val idx = draggingPointIdx
                        if (idx in points.indices) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cur = points[idx]
                            val newX = if (idx == 0) 0f else if (idx == points.size - 1) 1f else (cur.x + dragAmount.x / w).coerceIn(0f, 1f)
                            val newY = (cur.y - dragAmount.y / h).coerceIn(0f, 1f)
                            val updated = points.toMutableList()
                            updated[idx] = Offset(newX, newY)
                            onPointsChanged(updated)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Draw 4x4 Grid
            val gridColor = Color(0xFF2E3135)
            for (i in 1..3) {
                val gx = w * (i / 4f)
                val gy = h * (i / 4f)
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
            }

            // Draw Curve Line
            if (points.size >= 4) {
                val path = Path()
                val p0 = Offset(points[0].x * w, (1f - points[0].y) * h)
                val p1 = Offset(points[1].x * w, (1f - points[1].y) * h)
                val p2 = Offset(points[2].x * w, (1f - points[2].y) * h)
                val p3 = Offset(points[3].x * w, (1f - points[3].y) * h)

                path.moveTo(p0.x, p0.y)
                path.cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)

                drawPath(
                    path = path,
                    color = Color(0xFFC0C4CC),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Control points handles
                points.forEachIndexed { idx, pt ->
                    val cx = pt.x * w
                    val cy = (1f - pt.y) * h
                    drawCircle(
                        color = Color(0xFF388AF6),
                        radius = if (idx == 0 || idx == points.size - 1) 4.dp.toPx() else 3.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

@Composable
private fun CurvePresetIcon(
    type: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.25f) else colors.panel)
            .border(
                1.dp,
                if (selected) colors.accent else colors.border.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val strokeColor = if (selected) colors.accent else colors.subText
            val path = Path()

            when (type) {
                0 -> { // Linear /
                    drawLine(strokeColor, Offset(2f, h - 2f), Offset(w - 2f, 2f), strokeWidth = 1.5.dp.toPx())
                }
                1 -> { // Soft / Convex ⌒
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.2f, h * 0.3f, w * 0.5f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                2 -> { // Hard / Concave ‿
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.5f, h - 2f, w * 0.8f, h * 0.7f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                3 -> { // S-Curve ~
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.4f, h - 2f, w * 0.6f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                4 -> { // Extreme
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.1f, 2f, w * 0.9f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun SettingDropdownRow(
    title: String,
    currentText: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    val colors = Theme.current
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        )

        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panel)
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentText,
                    color = colors.text,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(12.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colors.panelHi)
            ) {
                options.forEachIndexed { idx, opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt,
                                color = if (opt == currentText) colors.accent else colors.text,
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            onSelect(idx)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingCategoryHeader(title: String) {
    val colors = Theme.current
    Text(
        text = title,
        color = colors.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun SettingNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.icon,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = colors.subText,
                fontSize = 12.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.subText.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.panelHi,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.subText,
                uncheckedTrackColor = colors.panel
            )
        )
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val colors = Theme.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = summary,
                    color = colors.subText,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "${(value * 100).toInt()}%",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.2f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentHi,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.panel
            )
        )
    }
}

@Composable
private fun CustomColorDialog(
    initialHex: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Theme.current
    var hexInput by remember { mutableStateOf(initialHex.removePrefix("#")) }
    val parsedPreview = remember(hexInput) {
        try {
            parseColor("#$hexInput")
        } catch (_: Exception) {
            colors.accent
        }
    }

    val extraColors = listOf(
        "#E06C75", "#E5C07B", "#98C379", "#56B6C2",
        "#61AFEF", "#C678DD", "#FF6B6B", "#4ECDC4",
        "#45B7D1", "#F7B731", "#5F27CD", "#00D2D3"
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelHi)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "自定义主色调",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                // Color preview & HEX input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(parsedPreview)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    )
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexInput = filtered
                        },
                        prefix = { Text("#", color = colors.subText) },
                        singleLine = true,
                        placeholder = { Text("5E8BA8", color = colors.subText.copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor = colors.accent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (hexInput.length == 6) {
                                onConfirm("#$hexInput")
                            }
                        }),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("快速选取色盘", color = colors.subText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))

                // Quick extra colors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    extraColors.take(6).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseColor(hex))
                                .clickable {
                                    hexInput = hex.removePrefix("#")
                                }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    extraColors.takeLast(6).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseColor(hex))
                                .clickable {
                                    hexInput = hex.removePrefix("#")
                                }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.subText)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val hex = if (hexInput.length == 6) "#$hexInput" else initialHex
                            onConfirm(hex)
                        }
                    ) {
                        Text("确定", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
