package com.reverie.paint.ui.painting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.*

enum class StudioTab(val title: String) {
    TIP("笔尖"),
    STROKE("线条"),
    TEXTURE("纹理"),
    RENDERING("渲染"),
    COLOR("颜色"),
    PRESSURE("压力"),
    PROPERTIES("属性"),
}

enum class StudioPreviewMode {
    STROKE,
    SCRATCHPAD,
}

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)

/**
 * 画世界 Pro 风格 笔刷工作室 (Brush Studio Dialog)
 * 包含实时笔迹渲染预览、互动涂鸦测试板、以及 7 大分类参数精细调节
 */
@Composable
fun BrushStudioDialog(
    vm: PaintViewModel,
    presetIndex: Int,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null,
) {
    val preset = vm.brushPresets.getOrNull(presetIndex)
    var selectedTab by remember { mutableStateOf(StudioTab.TIP) }
    var previewMode by remember { mutableStateOf(StudioPreviewMode.STROKE) }
    var showMenu by remember { mutableStateOf(false) }

    // Scratchpad touch paths
    val scratchStrokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    var currentScratchStroke by remember { mutableStateOf<List<ScratchPoint>>(emptyList()) }

    val panelShape = RoundedCornerShape(16.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Default)
                    .widthIn(min = 360.dp, max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.92f)
                    .clip(panelShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Morandi.panel.copy(alpha = 0.96f),
                                    tint = HazeTint(Morandi.panel.copy(alpha = 0.96f)),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.05f,
                                ),
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = 0.98f))
                        },
                    )
                    .border(1.dp, Morandi.border, panelShape)
                    .clickable(enabled = false) {},
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- Top Header ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .noRippleClickable(onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_x),
                                contentDescription = "关闭",
                                tint = Morandi.icon,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Preview Mode Switcher (笔触预览 / 涂鸦测试板)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(Morandi.panelHi)
                                .padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (previewMode == StudioPreviewMode.STROKE) Morandi.accent else Color.Transparent)
                                    .clickable { previewMode = StudioPreviewMode.STROKE },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_brush),
                                        contentDescription = null,
                                        tint = if (previewMode == StudioPreviewMode.STROKE) Color.White else Morandi.subText,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        "笔触预览",
                                        color = if (previewMode == StudioPreviewMode.STROKE) Color.White else Morandi.subText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (previewMode == StudioPreviewMode.SCRATCHPAD) Morandi.accent else Color.Transparent)
                                    .clickable { previewMode = StudioPreviewMode.SCRATCHPAD },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_sliders),
                                        contentDescription = null,
                                        tint = if (previewMode == StudioPreviewMode.SCRATCHPAD) Color.White else Morandi.subText,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        "涂鸦测试板",
                                        color = if (previewMode == StudioPreviewMode.SCRATCHPAD) Color.White else Morandi.subText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // More Menu
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .noRippleClickable { showMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_dots_vertical),
                                    contentDescription = "更多",
                                    tint = Morandi.icon,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(Morandi.panelHi),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重置为预设默认值", color = Morandi.text, fontSize = 13.sp) },
                                    onClick = {
                                        vm.resetBrushParams()
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }

                    // ---- Live Stroke Preview / Scratchpad Area ----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(136.dp)
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Morandi.border, RoundedCornerShape(12.dp)),
                    ) {
                        // Checkerboard background
                        CheckerboardBackground(modifier = Modifier.fillMaxSize())

                        when (previewMode) {
                            StudioPreviewMode.STROKE -> {
                                LiveStrokePreviewCanvas(
                                    vm = vm,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            StudioPreviewMode.SCRATCHPAD -> {
                                ScratchpadCanvas(
                                    vm = vm,
                                    strokes = scratchStrokes,
                                    currentStroke = currentScratchStroke,
                                    onStrokeStart = { p ->
                                        currentScratchStroke = listOf(p)
                                    },
                                    onStrokeMove = { p ->
                                        currentScratchStroke = currentScratchStroke + p
                                    },
                                    onStrokeEnd = {
                                        if (currentScratchStroke.isNotEmpty()) {
                                            scratchStrokes.add(currentScratchStroke)
                                            currentScratchStroke = emptyList()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )

                                // Floating clear button
                                if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Morandi.panel.copy(alpha = 0.85f))
                                            .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                                            .clickable {
                                                scratchStrokes.clear()
                                                currentScratchStroke = emptyList()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text("清空画板", color = Morandi.accent, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // ---- Category Tab Row ----
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        edgePadding = 12.dp,
                        containerColor = Color.Transparent,
                        contentColor = Morandi.accent,
                        divider = {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
                        },
                        indicator = { tabPositions ->
                            if (selectedTab.ordinal < tabPositions.size) {
                                Box(
                                    Modifier
                                        .tabIndicatorOffset(tabPositions[selectedTab.ordinal])
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(Morandi.accent),
                                )
                            }
                        },
                    ) {
                        StudioTab.values().forEach { tab ->
                            val sel = tab == selectedTab
                            Tab(
                                selected = sel,
                                onClick = { selectedTab = tab },
                                text = {
                                    Text(
                                        text = tab.title,
                                        color = if (sel) Morandi.accent else Morandi.subText,
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                            )
                        }
                    }

                    // ---- Tab Settings Content ----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "tabContent",
                            ) { tab ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    when (tab) {
                                        StudioTab.TIP -> TipSettingsTab(vm = vm, preset = preset)
                                        StudioTab.STROKE -> StrokeSettingsTab(vm = vm)
                                        StudioTab.TEXTURE -> TextureSettingsTab(vm = vm)
                                        StudioTab.RENDERING -> RenderingSettingsTab(vm = vm)
                                        StudioTab.COLOR -> ColorSettingsTab(vm = vm)
                                        StudioTab.PRESSURE -> PressureSettingsTab(vm = vm)
                                        StudioTab.PROPERTIES -> PropertiesSettingsTab(vm = vm, preset = preset)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 0: 笔尖 (Tip / Shape)
// ==========================================
@Composable
private fun TipSettingsTab(vm: PaintViewModel, preset: BrushPresetInfo?) {
    SectionHeader("形状与笔尖")

    // Shape card
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi.copy(alpha = 0.6f))
            .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbBmp = remember(preset?.thumbBytes) {
            preset?.thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        if (thumbBmp != null) {
            Image(
                bitmap = thumbBmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_brush), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(preset?.name ?: "自定义笔尖", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Krita 内置笔尖预设 · 随压感与物理引擎交互", color = Morandi.subText, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(14.dp))

    // Anti-Aliasing options
    StudioSegmentedRow(
        label = "抗锯齿",
        options = listOf("无", "正常", "强化", "分级"),
        selectedIndex = vm.brushAntiAliasing.coerceIn(0, 3),
        onSelect = { vm.updateBrushAntiAliasing(it) },
    )

    // Tip shape selection
    StudioSegmentedRow(
        label = "笔尖类型",
        options = listOf("圆形笔触", "方形笔触"),
        selectedIndex = vm.brushTipShape.coerceIn(0, 1),
        onSelect = { vm.updateBrushTipShape(it) },
    )

    // Flip random switches
    StudioSwitchRow("水平翻转随机", vm.brushRandomFlipX) { vm.updateBrushRandomFlipX(it) }
    StudioSwitchRow("垂直翻转随机", vm.brushRandomFlipY) { vm.updateBrushRandomFlipY(it) }
    StudioSwitchRow("沿笔画方向旋转", vm.brushFollowDirection) { vm.updateBrushFollowDirection(it) }

    // Sliders
    StudioSliderItem("硬度", vm.brushSoftness, 0.0, 1.0, isPercent = true) { vm.updateBrushSoftness(it) }
    StudioSliderItem("圆度 / 比例", vm.brushRatio, 0.05, 1.0, isPercent = true) { vm.updateBrushRatio(it) }
    StudioSliderItem("角度", vm.brushAngle, 0.0, 360.0, unit = "°") { vm.updateBrushAngle(it) }
    StudioSliderItem("旋转角度", vm.brushRotation, 0.0, 360.0, unit = "°") { vm.updateBrushRotation(it) }
}

// ==========================================
// Tab 1: 线条 (Stroke)
// ==========================================
@Composable
private fun StrokeSettingsTab(vm: PaintViewModel) {
    SectionHeader("线条与动态")

    StudioSliderItem("间距", vm.brushSpacing, 0.01, 2.0, isPercent = true) { vm.updateBrushSpacing(it) }
    StudioSliderItem("散布 / 抖动", vm.brushScatter, 0.0, 1.0, isPercent = true) { vm.updateBrushScatter(it) }
    StudioSliderItem("渐隐", vm.brushFade, 0.0, 1.0, isPercent = true) { vm.updateBrushFade(it) }
    StudioSliderItem("流畅度 / 防抖 (Streamline)", vm.brushStreamline, 0.0, 1.0, isPercent = true) { vm.updateBrushStreamline(it) }
    StudioSliderItem("笔尾收尖 (Taper)", vm.brushTaper, 0.0, 1.0, isPercent = true) { vm.updateBrushTaper(it) }
}

// ==========================================
// Tab 2: 纹理 (Texture)
// ==========================================
@Composable
private fun TextureSettingsTab(vm: PaintViewModel) {
    SectionHeader("材质与纹理")

    StudioSwitchRow("启用纹理叠加", vm.brushTextureEnabled) { vm.updateBrushTextureEnabled(it) }

    if (vm.brushTextureEnabled) {
        val modes = listOf("multiply" to "正片叠底", "overlay" to "叠加", "screen" to "滤色", "dodge" to "减淡")
        StudioSegmentedRow(
            label = "纹理模式",
            options = modes.map { it.second },
            selectedIndex = modes.indexOfFirst { it.first == vm.brushTextureMode }.coerceAtLeast(0),
            onSelect = { vm.updateBrushTextureMode(modes[it].first) },
        )

        StudioSliderItem("纹理缩放比例", vm.brushTextureScale, 0.2, 3.0, isPercent = true) { vm.updateBrushTextureScale(it) }
        StudioSliderItem("纹理强度", vm.brushTextureStrength, 0.0, 1.0, isPercent = true) { vm.updateBrushTextureStrength(it) }
    }
}

// ==========================================
// Tab 3: 渲染 (Rendering)
// ==========================================
@Composable
private fun RenderingSettingsTab(vm: PaintViewModel) {
    SectionHeader("渲染模式与透明度")

    val blendModeList = listOf(
        "normal" to "正常",
        "multiply" to "正片叠底",
        "screen" to "滤色",
        "overlay" to "叠加",
        "darken" to "变暗",
        "lighten" to "变亮",
        "dodge" to "颜色减淡",
        "burn" to "颜色加深",
        "hard_light" to "强光",
        "soft_light" to "柔光",
        "difference" to "差值",
        "exclusion" to "排除",
    )

    var showBlendGrid by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showBlendGrid = !showBlendGrid }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("混合模式", color = Morandi.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            blendModeList.firstOrNull { it.first == vm.brushCompositeOp }?.second ?: "正常",
            color = Morandi.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(16.dp).rotate(if (showBlendGrid) 90f else 0f),
        )
    }

    if (showBlendGrid) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            blendModeList.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (opId, name) ->
                        val sel = vm.brushCompositeOp == opId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Morandi.accent else Morandi.panel)
                                .clickable {
                                    vm.updateBrushCompositeOp(opId)
                                    showBlendGrid = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, color = if (sel) Color.White else Morandi.text, fontSize = 11.sp)
                        }
                    }
                    if (row.size < 3) {
                        Spacer(Modifier.weight((3 - row.size).toFloat()))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    StudioSliderItem("不透明度", vm.brushOpacity, 0.01, 1.0, isPercent = true) { vm.updateBrushOpacity(it) }
    StudioSliderItem("流量 (Flow)", vm.brushFlow, 0.01, 1.0, isPercent = true) { vm.updateBrushFlow(it) }
    StudioSliderItem("边缘锐度 (Sharpness)", vm.brushSharpness, 0.0, 1.0, isPercent = true) { vm.updateBrushSharpness(it) }
}

// ==========================================
// Tab 4: 颜色 (Color)
// ==========================================
@Composable
private fun ColorSettingsTab(vm: PaintViewModel) {
    SectionHeader("色彩动态与混色")

    StudioSliderItem("色相随机抖动 (Hue Jitter)", vm.brushHueJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushHueJitter(it) }
    StudioSliderItem("饱和度抖动 (Saturation)", vm.brushSatJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushSatJitter(it) }
    StudioSliderItem("明度抖动 (Brightness)", vm.brushValJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushValJitter(it) }
    StudioSliderItem("前/背景色混合比", vm.brushSecondaryMix, 0.0, 1.0, isPercent = true) { vm.updateBrushSecondaryMix(it) }
    StudioSwitchRow("压感驱动前背景混色", vm.brushPressureColorMix) { vm.updateBrushPressureColorMix(it) }
}

// ==========================================
// Tab 5: 压力 (Pressure)
// ==========================================
@Composable
private fun PressureSettingsTab(vm: PaintViewModel) {
    SectionHeader("压感与速度动态")

    StudioSwitchRow("启用压力感应", vm.brushPressureEnabled) { vm.updateBrushPressureEnabled(it) }

    if (vm.brushPressureEnabled) {
        StudioSliderItem("压力对大小影响", vm.brushPressureSize, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureSize(it) }
        StudioSliderItem("压力对不透明度影响", vm.brushPressureOpacity, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureOpacity(it) }
        StudioSliderItem("压力对流量影响", vm.brushPressureFlow, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureFlow(it) }
        StudioSliderItem("速度感应 (Speed Dynamics)", vm.brushSpeedSize, 0.0, 1.0, isPercent = true) { vm.updateBrushSpeedSize(it) }

        val curves = listOf("线性", "柔和", "硬朗", "S型")
        StudioSegmentedRow(
            label = "压感响应曲线",
            options = curves,
            selectedIndex = vm.brushPressureCurve.coerceIn(0, 3),
            onSelect = { vm.updateBrushPressureCurve(it) },
        )
    }
}

// ==========================================
// Tab 6: 属性 (Properties)
// ==========================================
@Composable
private fun PropertiesSettingsTab(vm: PaintViewModel, preset: BrushPresetInfo?) {
    SectionHeader("笔刷预设与尺寸范围")

    StudioInfoRow("笔刷名称", preset?.name ?: "未命名笔刷")
    StudioInfoRow("分类", preset?.group?.ifBlank { "全部" } ?: "全部")

    StudioSliderItem("最小尺寸限制", vm.brushMinSizeLimit, 1.0, 50.0, unit = "px") { vm.updateBrushMinSizeLimit(it) }
    StudioSliderItem("最大尺寸限制", vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px") { vm.updateBrushMaxSizeLimit(it) }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = { vm.resetBrushParams() },
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Morandi.panelHi),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
            Text("重置为预设默认值", color = Morandi.accent, fontSize = 13.sp)
        }
    }
}

// ==========================================
// UI Helper Components
// ==========================================

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Morandi.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun StudioInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.35f)))
}

@Composable
private fun StudioSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.text, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Morandi.accent,
                uncheckedThumbColor = Morandi.subText,
                uncheckedTrackColor = Morandi.panelHi,
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.35f)))
}

@Composable
private fun StudioSegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Morandi.text, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { idx, opt ->
                val sel = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Morandi.accent else Color.Transparent)
                        .clickable { onSelect(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        color = if (sel) Color.White else Morandi.subText,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.35f)))
}

@Composable
private fun StudioSliderItem(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    isPercent: Boolean = false,
    unit: String = "",
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Morandi.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isPercent) "${(value * 100).toInt()}%" else "${value.toInt()}$unit",
                color = Morandi.subText,
                fontSize = 12.sp,
            )
        }
        ReSlider(
            value = fraction,
            onValue = { f -> onChange(f * (max - min) + min) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.35f)))
}

// ==========================================
// Interactive Canvas & Live Renderers
// ==========================================

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val checkSize = 16.dp.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
        val lightColor = Color(0xFF28282B)
        val darkColor = Color(0xFF1E1E20)
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val c = if ((i + j) % 2 == 0) lightColor else darkColor
                drawRect(
                    color = c,
                    topLeft = Offset(i * checkSize, j * checkSize),
                    size = Size(checkSize, checkSize),
                )
            }
        }
    }
}

/**
 * 实时标准笔触曲线预览
 */
@Composable
private fun LiveStrokePreviewCanvas(vm: PaintViewModel, modifier: Modifier = Modifier) {
    val brushColor = remember(vm.brushColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.White)
    }
    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    val hardness = vm.brushSoftness.toFloat().coerceIn(0.1f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.1f, 1f)
    val scatter = vm.brushScatter.toFloat()
    val isSquare = vm.brushTipShape == 1

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val margin = 24.dp.toPx()
        val startX = margin
        val endX = w - margin
        val midY = h / 2f

        val steps = 180
        val points = mutableListOf<Triple<Float, Float, Float>>() // x, y, pressure

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val x = startX + (endX - startX) * t
            val wave = sin(t * Math.PI.toFloat() * 2f) * 18.dp.toPx()
            val y = midY + wave

            // Standard taper pressure profile: 0.1 -> 1.0 -> 0.1
            val basePressure = sin(t * Math.PI.toFloat()).coerceIn(0.05f, 1.0f)
            val curvedPressure = when (vm.brushPressureCurve) {
                1 -> basePressure.pow(0.6f)
                2 -> basePressure.pow(1.8f)
                3 -> basePressure * basePressure * (3f - 2f * basePressure)
                else -> basePressure
            }
            val effPressure = if (vm.brushPressureEnabled) curvedPressure else 1.0f
            points.add(Triple(x, y, effPressure))
        }

        // Draw dabs along the path
        val baseRadius = (vm.brushSize.toFloat().coerceIn(12f, 48f) / 2f)
        val spacing = (vm.brushSpacing.toFloat().coerceIn(0.05f, 1.5f) * baseRadius).coerceAtLeast(2f)

        var lastDabX = -999f
        var lastDabY = -999f

        points.forEach { (px, py, p) ->
            val dist = hypot(px - lastDabX, py - lastDabY)
            if (dist >= spacing || lastDabX < 0) {
                lastDabX = px
                lastDabY = py

                val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * p * vm.brushPressureSize.toFloat()) else 1f)
                val dabOpacity = (opacity * flow * (if (vm.brushPressureEnabled) (0.3f + 0.7f * p * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)

                val scatterOffset = if (scatter > 0f) {
                    val angle = (sin(px * 13.7f) * Math.PI).toFloat()
                    val r = abs(cos(py * 17.3f)) * rad * scatter * 1.5f
                    Offset(cos(angle) * r, sin(angle) * r)
                } else Offset.Zero

                val center = Offset(px + scatterOffset.x, py + scatterOffset.y)

                if (isSquare) {
                    drawRect(
                        color = brushColor.copy(alpha = dabOpacity),
                        topLeft = Offset(center.x - rad, center.y - rad * ratio),
                        size = Size(rad * 2f, rad * 2f * ratio),
                    )
                } else {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                brushColor.copy(alpha = dabOpacity),
                                brushColor.copy(alpha = dabOpacity * hardness),
                                brushColor.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = rad.coerceAtLeast(1f),
                        ),
                        radius = rad.coerceAtLeast(1f),
                        center = center,
                    )
                }
            }
        }
    }
}

/**
 * 互动涂鸦测试板
 */
@Composable
private fun ScratchpadCanvas(
    vm: PaintViewModel,
    strokes: List<List<ScratchPoint>>,
    currentStroke: List<ScratchPoint>,
    onStrokeStart: (ScratchPoint) -> Unit,
    onStrokeMove: (ScratchPoint) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brushColor = remember(vm.brushColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.White)
    }
    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    val hardness = vm.brushSoftness.toFloat().coerceIn(0.1f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(8f, 64f) / 2f)
    val isSquare = vm.brushTipShape == 1

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    onStrokeStart(ScratchPoint(offset.x, offset.y, 1.0f))
                },
                onDrag = { change, _ ->
                    change.consume()
                    val p = change.pressure.coerceIn(0.1f, 1.0f)
                    onStrokeMove(ScratchPoint(change.position.x, change.position.y, p))
                },
                onDragEnd = { onStrokeEnd() },
                onDragCancel = { onStrokeEnd() },
            )
        },
    ) {
        fun drawScratchStroke(pts: List<ScratchPoint>) {
            if (pts.isEmpty()) return
            var lastDabX = -999f
            var lastDabY = -999f
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.05f, 1.5f) * baseRadius).coerceAtLeast(2f)

            pts.forEach { pt ->
                val dist = hypot(pt.x - lastDabX, pt.y - lastDabY)
                if (dist >= spacing || lastDabX < 0) {
                    lastDabX = pt.x
                    lastDabY = pt.y

                    val p = pt.pressure
                    val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * p * vm.brushPressureSize.toFloat()) else 1f)
                    val dabOpacity = (opacity * flow * (if (vm.brushPressureEnabled) (0.3f + 0.7f * p * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)
                    val center = Offset(pt.x, pt.y)

                    if (isSquare) {
                        drawRect(
                            color = brushColor.copy(alpha = dabOpacity),
                            topLeft = Offset(center.x - rad, center.y - rad),
                            size = Size(rad * 2f, rad * 2f),
                        )
                    } else {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    brushColor.copy(alpha = dabOpacity),
                                    brushColor.copy(alpha = dabOpacity * hardness),
                                    brushColor.copy(alpha = 0f),
                                ),
                                center = center,
                                radius = rad.coerceAtLeast(1f),
                            ),
                            radius = rad.coerceAtLeast(1f),
                            center = center,
                        )
                    }
                }
            }
        }

        strokes.forEach { drawScratchStroke(it) }
        drawScratchStroke(currentStroke)
    }
}
