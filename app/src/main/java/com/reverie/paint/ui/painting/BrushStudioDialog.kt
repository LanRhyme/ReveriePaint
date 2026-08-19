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
import androidx.compose.foundation.horizontalScroll
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

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)

/**
 * 笔刷工作室 (Brush Studio)
 * ReveriePaint 莫兰迪风格交互面板，包含互动测试画板与全维度参数精细调校
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
    var showMenu by remember { mutableStateOf(false) }

    // Scratchpad interactive strokes
    val scratchStrokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    var currentScratchStroke by remember { mutableStateOf<List<ScratchPoint>>(emptyList()) }

    var shapeInvert by remember { mutableStateOf(false) }
    var shapeColorInvert by remember { mutableStateOf(false) }
    var shapeRgbAffectsAlpha by remember { mutableStateOf(true) }
    var roundnessDirection by remember { mutableStateOf(1) } // 0: 水平, 1: 垂直

    val panelShape = RoundedCornerShape(16.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                ) {
                    // ---- Top Header Bar ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 10.dp),
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

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "笔刷工作室",
                                color = Morandi.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                preset?.name ?: "自定义笔刷",
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Clear scratchpad action button
                        if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        scratchStrokes.clear()
                                        currentScratchStroke = emptyList()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("清空画板", color = Morandi.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(6.dp))
                        }

                        // Overflow Menu
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
                                    contentDescription = "菜单",
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
                                    text = { Text("重置为初始预设默认值", color = Morandi.text, fontSize = 13.sp) },
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

                    // ---- Interactive Scratchpad Canvas (测试画板) ----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.6f))
                            .border(1.dp, Morandi.border, RoundedCornerShape(10.dp)),
                    ) {
                        CheckerboardBackground(modifier = Modifier.fillMaxSize())

                        ScratchpadCanvas(
                            vm = vm,
                            strokes = scratchStrokes,
                            currentStroke = currentScratchStroke,
                            onStrokeStart = { p -> currentScratchStroke = listOf(p) },
                            onStrokeMove = { p -> currentScratchStroke = currentScratchStroke + p },
                            onStrokeEnd = {
                                if (currentScratchStroke.isNotEmpty()) {
                                    scratchStrokes.add(currentScratchStroke)
                                    currentScratchStroke = emptyList()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (scratchStrokes.isEmpty() && currentScratchStroke.isEmpty()) {
                            Text(
                                "在此随意画线测试笔触与手感",
                                color = Morandi.subText.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ---- Tab Row (Morandi 胶囊标签栏) ----
                    val tabScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(tabScrollState)
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudioTab.values().forEach { tab ->
                            val sel = tab == selectedTab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                                    .border(
                                        width = if (sel) 1.dp else 0.dp,
                                        color = if (sel) Morandi.accent.copy(alpha = 0.4f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { selectedTab = tab }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = tab.title,
                                    color = if (sel) Morandi.accent else Morandi.subText,
                                    fontSize = 13.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp).background(Morandi.border))

                    // ---- Tab Content Area ----
                    val contentScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(contentScrollState)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "studioTabAnim",
                        ) { tab ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                when (tab) {
                                    StudioTab.TIP -> TipTabContent(
                                        vm = vm,
                                        preset = preset,
                                        shapeInvert = shapeInvert,
                                        onShapeInvert = { shapeInvert = it },
                                        shapeColorInvert = shapeColorInvert,
                                        onShapeColorInvert = { shapeColorInvert = it },
                                        shapeRgbAffectsAlpha = shapeRgbAffectsAlpha,
                                        onShapeRgbAffectsAlpha = { shapeRgbAffectsAlpha = it },
                                        roundnessDirection = roundnessDirection,
                                        onRoundnessDirection = { roundnessDirection = it },
                                    )
                                    StudioTab.STROKE -> StrokeTabContent(vm = vm)
                                    StudioTab.TEXTURE -> TextureTabContent(vm = vm)
                                    StudioTab.RENDERING -> RenderingTabContent(vm = vm)
                                    StudioTab.COLOR -> ColorTabContent(vm = vm)
                                    StudioTab.PRESSURE -> PressureTabContent(vm = vm)
                                    StudioTab.PROPERTIES -> PropertiesTabContent(vm = vm, preset = preset)
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
private fun TipTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    shapeInvert: Boolean,
    onShapeInvert: (Boolean) -> Unit,
    shapeColorInvert: Boolean,
    onShapeColorInvert: (Boolean) -> Unit,
    shapeRgbAffectsAlpha: Boolean,
    onShapeRgbAffectsAlpha: (Boolean) -> Unit,
    roundnessDirection: Int,
    onRoundnessDirection: (Int) -> Unit,
) {
    SectionHeader("形状与笔尖")

    // Shape box with thumbnail & stacked options
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CheckerboardBackground(modifier = Modifier.fillMaxSize())
            val thumbBmp = remember(preset?.thumbBytes) {
                preset?.thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (thumbBmp != null) {
                Image(
                    bitmap = thumbBmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(Morandi.accent))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
        ) {
            MorandiOptionClickRow("反转笔尖", checked = shapeInvert) { onShapeInvert(!shapeInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
            MorandiOptionClickRow("颜色反转", checked = shapeColorInvert) { onShapeColorInvert(!shapeColorInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
            MorandiOptionClickRow("RGB 影响透明度", checked = shapeRgbAffectsAlpha) { onShapeRgbAffectsAlpha(!shapeRgbAffectsAlpha) }
        }
    }

    SectionHeader("抗锯齿等级")
    val aaList = listOf("无", "正常", "强化", "分级")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
    ) {
        aaList.forEachIndexed { idx, name ->
            val sel = vm.brushAntiAliasing == idx
            MorandiCardRadioRow(name = name, selected = sel) { vm.updateBrushAntiAliasing(idx) }
            if (idx < aaList.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
            }
        }
    }

    MorandiSwitchLine("水平翻转随机", vm.brushRandomFlipX) { vm.updateBrushRandomFlipX(it) }
    MorandiSwitchLine("垂直翻转随机", vm.brushRandomFlipY) { vm.updateBrushRandomFlipY(it) }
    MorandiSwitchLine("沿笔画方向旋转", vm.brushFollowDirection) { vm.updateBrushFollowDirection(it) }

    SectionHeader("笔尖类型")
    val tipTypes = listOf("圆形笔触", "方形笔触")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
    ) {
        tipTypes.forEachIndexed { idx, name ->
            val sel = vm.brushTipShape == idx
            MorandiCardRadioRow(name = name, selected = sel) { vm.updateBrushTipShape(idx) }
            if (idx < tipTypes.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
            }
        }
    }

    MorandiSliderRow("硬度", vm.brushSoftness, 0.0, 1.0, isPercent = true) { vm.updateBrushSoftness(it) }

    SectionHeader("圆度与角度")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
        ) {
            MorandiCardRadioRow("水平方向", selected = roundnessDirection == 0) { onRoundnessDirection(0) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
            MorandiCardRadioRow("垂直方向", selected = roundnessDirection == 1) { onRoundnessDirection(1) }
        }

        MorandiAngleDial(
            angle = vm.brushAngle.toFloat(),
            ratio = vm.brushRatio.toFloat(),
            onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
            modifier = Modifier.size(86.dp),
        )
    }

    MorandiSliderRow("角度", vm.brushAngle, 0.0, 360.0, unit = "°") { vm.updateBrushAngle(it) }
    MorandiSliderRow("旋转", vm.brushRotation, 0.0, 360.0, unit = "°") { vm.updateBrushRotation(it) }
}

// ==========================================
// Tab 1: 线条 (Stroke)
// ==========================================
@Composable
private fun StrokeTabContent(vm: PaintViewModel) {
    SectionHeader("间距与抖动")
    MorandiSliderRow("间距", vm.brushSpacing, 0.01, 2.5, isPercent = true) { vm.updateBrushSpacing(it) }
    MorandiSliderRow("散布 / 抖动", vm.brushScatter, 0.0, 1.0, isPercent = true) { vm.updateBrushScatter(it) }
    MorandiSliderRow("流畅度 / 防抖 (Streamline)", vm.brushStreamline, 0.0, 1.0, isPercent = true) { vm.updateBrushStreamline(it) }
    MorandiSliderRow("渐隐", vm.brushFade, 0.0, 1.0, isPercent = true) { vm.updateBrushFade(it) }

    SectionHeader("收尖 (Taper)")
    MorandiSliderRow("笔尾收尖强度", vm.brushTaper, 0.0, 1.0, isPercent = true) { vm.updateBrushTaper(it) }
}

// ==========================================
// Tab 2: 纹理 (Texture)
// ==========================================
@Composable
private fun TextureTabContent(vm: PaintViewModel) {
    MorandiSwitchLine("启用纹理材质叠加", vm.brushTextureEnabled) { vm.updateBrushTextureEnabled(it) }

    if (vm.brushTextureEnabled) {
        SectionHeader("纹理混合模式")
        val texModes = listOf("multiply" to "正片叠底", "overlay" to "叠加", "screen" to "滤色", "dodge" to "减淡")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
        ) {
            texModes.forEachIndexed { idx, (id, name) ->
                val sel = vm.brushTextureMode == id
                MorandiCardRadioRow(name = name, selected = sel) { vm.updateBrushTextureMode(id) }
                if (idx < texModes.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
                }
            }
        }

        MorandiSliderRow("纹理缩放", vm.brushTextureScale, 0.2, 4.0, isPercent = true) { vm.updateBrushTextureScale(it) }
        MorandiSliderRow("纹理强度", vm.brushTextureStrength, 0.0, 1.0, isPercent = true) { vm.updateBrushTextureStrength(it) }
    }
}

// ==========================================
// Tab 3: 渲染 (Rendering)
// ==========================================
@Composable
private fun RenderingTabContent(vm: PaintViewModel) {
    SectionHeader("混合模式")

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        blendModeList.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (opId, name) ->
                    val sel = vm.brushCompositeOp == opId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                            .border(1.dp, if (sel) Morandi.accent else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (sel) Morandi.accent else Morandi.subText,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (row.size < 3) {
                    Spacer(Modifier.weight((3 - row.size).toFloat()))
                }
            }
        }
    }

    MorandiSliderRow("不透明度", vm.brushOpacity, 0.01, 1.0, isPercent = true) { vm.updateBrushOpacity(it) }
    MorandiSliderRow("流量 (Flow)", vm.brushFlow, 0.01, 1.0, isPercent = true) { vm.updateBrushFlow(it) }
    MorandiSliderRow("边缘锐度 (Sharpness)", vm.brushSharpness, 0.0, 1.0, isPercent = true) { vm.updateBrushSharpness(it) }
}

// ==========================================
// Tab 4: 颜色 (Color)
// ==========================================
@Composable
private fun ColorTabContent(vm: PaintViewModel) {
    SectionHeader("色彩动态抖动")
    MorandiSliderRow("色相抖动 (Hue)", vm.brushHueJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushHueJitter(it) }
    MorandiSliderRow("饱和度抖动 (Saturation)", vm.brushSatJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushSatJitter(it) }
    MorandiSliderRow("明度抖动 (Brightness)", vm.brushValJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushValJitter(it) }
    MorandiSliderRow("前/背景色混合比", vm.brushSecondaryMix, 0.0, 1.0, isPercent = true) { vm.updateBrushSecondaryMix(it) }
    MorandiSwitchLine("压感驱动色彩混合", vm.brushPressureColorMix) { vm.updateBrushPressureColorMix(it) }
}

// ==========================================
// Tab 5: 压力 (Pressure)
// ==========================================
@Composable
private fun PressureTabContent(vm: PaintViewModel) {
    MorandiSwitchLine("启用压力感应", vm.brushPressureEnabled) { vm.updateBrushPressureEnabled(it) }

    if (vm.brushPressureEnabled) {
        SectionHeader("动态感应")
        MorandiSliderRow("压力对大小影响", vm.brushPressureSize, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureSize(it) }
        MorandiSliderRow("压力对不透明度影响", vm.brushPressureOpacity, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureOpacity(it) }
        MorandiSliderRow("压力对流量影响", vm.brushPressureFlow, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureFlow(it) }
        MorandiSliderRow("速度感应 (Speed Dynamics)", vm.brushSpeedSize, 0.0, 1.0, isPercent = true) { vm.updateBrushSpeedSize(it) }

        SectionHeader("压感响应曲线")
        val curves = listOf("线性", "柔和 (易出重压)", "硬朗 (需用力)", "S型曲线")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
        ) {
            curves.forEachIndexed { idx, name ->
                val sel = vm.brushPressureCurve == idx
                MorandiCardRadioRow(name = name, selected = sel) { vm.updateBrushPressureCurve(idx) }
                if (idx < curves.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

// ==========================================
// Tab 6: 属性 (Properties)
// ==========================================
@Composable
private fun PropertiesTabContent(vm: PaintViewModel, preset: BrushPresetInfo?) {
    SectionHeader("笔刷信息")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("笔刷名称", color = Morandi.subText, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(preset?.name ?: "未命名", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("分组归属", color = Morandi.subText, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(preset?.group?.ifBlank { "全部" } ?: "全部", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    SectionHeader("尺寸限制")
    MorandiSliderRow("最小尺寸限制", vm.brushMinSizeLimit, 1.0, 50.0, unit = "px") { vm.updateBrushMinSizeLimit(it) }
    MorandiSliderRow("最大尺寸限制", vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px") { vm.updateBrushMaxSizeLimit(it) }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { vm.resetBrushParams() },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Morandi.panelHi),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
            Text("重置为初始预设默认值", color = Morandi.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ==========================================
// Morandi UI Helper Components
// ==========================================

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Morandi.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun MorandiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

@Composable
private fun MorandiSwitchLine(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.text, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        MorandiSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.35f)))
}

@Composable
private fun MorandiOptionClickRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (checked) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MorandiCardRadioRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) Morandi.accent else Morandi.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MorandiSliderRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    isPercent: Boolean = false,
    unit: String = "",
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun MorandiAngleDial(
    angle: Float,
    ratio: Float,
    onAngleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    var deg = (atan2(dy, dx) * 180f / Math.PI.toFloat())
                    if (deg < 0) deg += 360f
                    onAngleChange(deg)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            drawCircle(
                color = Morandi.border,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            val rad = angle * (Math.PI.toFloat() / 180f)
            val needleX = center.x + cos(rad) * (radius - 4.dp.toPx())
            val needleY = center.y + sin(rad) * (radius - 4.dp.toPx())

            drawLine(
                color = Morandi.accent,
                start = center,
                end = Offset(needleX, needleY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            drawCircle(
                color = Morandi.accent,
                radius = 3.5.dp.toPx(),
                center = Offset(needleX, needleY),
            )
        }
    }
}

// ==========================================
// Canvas & Scratchpad
// ==========================================

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val checkSize = 14.dp.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
        val color1 = Color(0xFF202024)
        val color2 = Color(0xFF26262B)
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val c = if ((i + j) % 2 == 0) color1 else color2
                drawRect(
                    color = c,
                    topLeft = Offset(i * checkSize, j * checkSize),
                    size = Size(checkSize, checkSize),
                )
            }
        }
    }
}

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
    val hardness = vm.brushSoftness.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(8f, 64f) / 2f)
    val isSquare = vm.brushTipShape == 1

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset -> onStrokeStart(ScratchPoint(offset.x, offset.y, 1.0f)) },
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
        fun drawScratch(pts: List<ScratchPoint>) {
            if (pts.isEmpty()) return
            var lastDab = Offset(-999f, -999f)
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.03f, 1.5f) * baseRadius).coerceAtLeast(1.5f)

            pts.forEach { pt ->
                val curPos = Offset(pt.x, pt.y)
                val dist = (curPos - lastDab).getDistance()
                if (dist >= spacing || lastDab.x < 0) {
                    lastDab = curPos
                    val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pt.pressure * vm.brushPressureSize.toFloat()) else 1f)
                    val dabAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.25f + 0.75f * pt.pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)

                    if (isSquare) {
                        drawRect(
                            color = brushColor.copy(alpha = dabAlpha),
                            topLeft = Offset(curPos.x - rad, curPos.y - rad),
                            size = Size(rad * 2f, rad * 2f),
                        )
                    } else {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    brushColor.copy(alpha = dabAlpha),
                                    brushColor.copy(alpha = dabAlpha * hardness),
                                    brushColor.copy(alpha = 0f),
                                ),
                                center = curPos,
                                radius = rad.coerceAtLeast(1.5f),
                            ),
                            radius = rad.coerceAtLeast(1.5f),
                            center = curPos,
                        )
                    }
                }
            }
        }

        strokes.forEach { drawScratch(it) }
        drawScratch(currentStroke)
    }
}
