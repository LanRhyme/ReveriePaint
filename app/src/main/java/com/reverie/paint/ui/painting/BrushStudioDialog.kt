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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

enum class StudioTab(val title: String, val subtitle: String, val iconRes: Int) {
    TIP("笔尖形状", "Tip & Mask", R.drawable.ic_pencil),
    STROKE("笔画动态", "Dynamics", R.drawable.ic_line),
    COLOR("色彩涂抹", "Color & Smudge", R.drawable.ic_palette),
    GEOMETRY("几何罗盘", "Geometry", R.drawable.ic_rotate_cw),
    TEXTURE("材质纹理", "Texture", R.drawable.ic_grid),
    PRESSURE("压感手感", "Pressure", R.drawable.ic_hand),
    ENGINE("引擎属性", "Engine & Limits", R.drawable.ic_settings),
}

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)

/**
 * ReveriePaint 专属双栏工作台 笔刷工作室 (Master-Detail Brush Studio)
 * 左侧模块化功能导轨 + 右侧交互试画台与深度 Krita 参数调校
 */
@Composable
fun BrushStudioDialog(
    vm: PaintViewModel,
    presetIndex: Int,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    val preset = vm.brushPresets.getOrNull(presetIndex)
    var selectedTab by remember { mutableStateOf(StudioTab.TIP) }
    var showMenu by remember { mutableStateOf(false) }

    // Scratchpad interactive test strokes
    val scratchStrokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    var currentScratchStroke by remember { mutableStateOf<List<ScratchPoint>>(emptyList()) }

    var shapeInvert by remember { mutableStateOf(false) }
    var shapeColorInvert by remember { mutableStateOf(false) }
    var shapeRgbAffectsAlpha by remember { mutableStateOf(true) }
    var roundnessDirection by remember { mutableStateOf(1) } // 0: 水平, 1: 垂直

    // Predefined tip assets from Krita bundled brushes
    val bundledTips = remember {
        listOf(
            "" to "预设贴图",
            "oil_bristle.png" to "油画毛刷",
            "chalk.png" to "粉笔颗粒",
            "smoke.png" to "烟雾纹理",
            "sparkle.png" to "星芒颗粒",
            "leaves.png" to "树叶散布",
            "hair.png" to "发丝束",
            "chisel_soft.png" to "柔和斜切",
            "plain_rake.png" to "排刷",
            "scratches_rough.png" to "粗糙刮痕",
            "splat_dots.png" to "喷溅墨滴",
            "chalk_round_hard.png" to "硬圆粉笔",
            "texture-a.png" to "糙面纹理",
            "gradient.png" to "柔光渐变",
        )
    }

    val panelShape = RoundedCornerShape(20.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Default)
                    .widthIn(min = 380.dp, max = 680.dp)
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.94f)
                    .clip(panelShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Morandi.panel.copy(alpha = 0.94f),
                                    tint = HazeTint(Morandi.panel.copy(alpha = 0.94f)),
                                    blurRadius = 28.dp,
                                    noiseFactor = 0.05f,
                                ),
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = 0.98f))
                        },
                    )
                    .border(1.dp, Morandi.border.copy(alpha = 0.85f), panelShape)
                    .clickable(enabled = false) {},
            ) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    // ---- Top Header Bar ----
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
                                .background(Morandi.panelHi)
                                .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .noRippleClickable(onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_x),
                                contentDescription = "关闭",
                                tint = Morandi.icon,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "笔刷工作台",
                                color = Morandi.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Morandi.accent.copy(alpha = 0.15f))
                                    .border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    preset?.name ?: "自定义笔刷",
                                    color = Morandi.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Overflow Menu
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .noRippleClickable { showMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_dots_vertical),
                                    contentDescription = "菜单",
                                    tint = Morandi.icon,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(Morandi.panelHi),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重置所有参数为默认值", color = Morandi.text, fontSize = 13.sp) },
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

                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.6f)))

                    // ---- Master-Detail Split Workspace (双栏工作台) ----
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Left Navigation Rail (左侧功能侧栏)
                        Column(
                            modifier = Modifier
                                .width(112.dp)
                                .fillMaxHeight()
                                .background(Morandi.panelHi.copy(alpha = 0.35f))
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            StudioTab.values().forEach { tab ->
                                val sel = tab == selectedTab
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                                        .border(
                                            width = if (sel) 1.dp else 0.dp,
                                            color = if (sel) Morandi.accent.copy(alpha = 0.55f) else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .clickable { selectedTab = tab }
                                        .padding(vertical = 9.dp, horizontal = 8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(
                                                painterResource(tab.iconRes),
                                                contentDescription = null,
                                                tint = if (sel) Morandi.accent else Morandi.icon,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                tab.title,
                                                color = if (sel) Morandi.accent else Morandi.text,
                                                fontSize = 12.sp,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                            )
                                        }
                                        Text(
                                            tab.subtitle,
                                            color = if (sel) Morandi.accent.copy(alpha = 0.8f) else Morandi.subText.copy(alpha = 0.6f),
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Morandi.border.copy(alpha = 0.6f)))

                        // Right Main Workspace (右侧工作区: 试画板 + 参数列表)
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            // Top Floating Scratchpad (交互测试台)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(118.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.panelHi.copy(alpha = 0.6f))
                                    .border(1.dp, Morandi.border, RoundedCornerShape(12.dp)),
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

                                // Scratchpad floating top badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Morandi.panel.copy(alpha = 0.85f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("试画台", color = Morandi.subText, fontSize = 10.sp)
                                }

                                // Clear button
                                if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Morandi.panel.copy(alpha = 0.9f))
                                            .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                scratchStrokes.clear()
                                                currentScratchStroke = emptyList()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(12.dp))
                                        Text("清空", color = Morandi.accent, fontSize = 11.sp)
                                    }
                                }
                            }

                            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))

                            // Parameter Cards Stack
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
                                                bundledTips = bundledTips,
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
                                            StudioTab.COLOR -> ColorTabContent(vm = vm)
                                            StudioTab.GEOMETRY -> GeometryTabContent(
                                                vm = vm,
                                                roundnessDirection = roundnessDirection,
                                                onRoundnessDirection = { roundnessDirection = it },
                                            )
                                            StudioTab.TEXTURE -> TextureTabContent(vm = vm)
                                            StudioTab.PRESSURE -> PressureTabContent(vm = vm)
                                            StudioTab.ENGINE -> EngineTabContent(vm = vm, preset = preset)
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
}

// ==========================================
// Tab 0: 笔尖形状 (Tip & Mask)
// ==========================================
@Composable
private fun TipTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    bundledTips: List<Pair<String, String>>,
    shapeInvert: Boolean,
    onShapeInvert: (Boolean) -> Unit,
    shapeColorInvert: Boolean,
    onShapeColorInvert: (Boolean) -> Unit,
    shapeRgbAffectsAlpha: Boolean,
    onShapeRgbAffectsAlpha: (Boolean) -> Unit,
    roundnessDirection: Int,
    onRoundnessDirection: (Int) -> Unit,
) {
    SectionHeader("当前笔尖贴图")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
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
            MorandiOptionClickRow("反转贴图 Alpha", checked = shapeInvert) { onShapeInvert(!shapeInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            MorandiOptionClickRow("贴图色彩反转", checked = shapeColorInvert) { onShapeColorInvert(!shapeColorInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            MorandiOptionClickRow("RGB 灰度转透明度", checked = shapeRgbAffectsAlpha) { onShapeRgbAffectsAlpha(!shapeRgbAffectsAlpha) }
        }
    }

    SectionHeader("Krita 预设笔尖库选择")
    val tipScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(tipScroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        bundledTips.forEach { (asset, name) ->
            val sel = vm.brushTipAsset == asset
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) Morandi.accent.copy(alpha = 0.2f) else Morandi.panelHi)
                    .border(1.dp, if (sel) Morandi.accent else Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { vm.updateBrushTipAsset(asset) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    color = if (sel) Morandi.accent else Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }

    SectionHeader("生成式笔尖 (Auto Brush)")
    val tipTypes = listOf("圆形笔尖 (Round)", "方形笔尖 (Square)")
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            }
        }
    }

    MorandiSliderRow("星角数 (Spikes)", vm.brushSpikes.toDouble(), 2.0, 16.0, unit = "角") { vm.updateBrushSpikes(it.toInt()) }
    MorandiSliderRow("边缘硬度 (Softness)", vm.brushSoftness, 0.0, 1.0, isPercent = true) { vm.updateBrushSoftness(it) }

    SectionHeader("抗锯齿与翻转")
    val aaList = listOf("无抗锯齿", "标准抗锯齿", "强化抗锯齿", "分级采样")
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            }
        }
    }

    MorandiSwitchLine("水平随机翻转 (Flip X)", vm.brushRandomFlipX) { vm.updateBrushRandomFlipX(it) }
    MorandiSwitchLine("垂直随机翻转 (Flip Y)", vm.brushRandomFlipY) { vm.updateBrushRandomFlipY(it) }
}

// ==========================================
// Tab 1: 笔画动态 (Dynamics)
// ==========================================
@Composable
private fun StrokeTabContent(vm: PaintViewModel) {
    SectionHeader("间距与散布")
    MorandiSliderRow("间距 (Spacing)", vm.brushSpacing, 0.01, 2.5, isPercent = true) { vm.updateBrushSpacing(it) }
    MorandiSliderRow("散布抖动 (Scatter)", vm.brushScatter, 0.0, 1.0, isPercent = true) { vm.updateBrushScatter(it) }
    MorandiSliderRow("防抖平滑度 (Streamline)", vm.brushStreamline, 0.0, 1.0, isPercent = true) { vm.updateBrushStreamline(it) }
    MorandiSliderRow("渐隐淡出 (Fade)", vm.brushFade, 0.0, 1.0, isPercent = true) { vm.updateBrushFade(it) }
    MorandiSliderRow("笔尾收尖 (Taper)", vm.brushTaper, 0.0, 1.0, isPercent = true) { vm.updateBrushTaper(it) }

    SectionHeader("喷枪持续流速 (Airbrush)")
    MorandiSwitchLine("启用喷枪时间流速模式", vm.brushAirbrush) { vm.updateBrushAirbrush(it) }
    if (vm.brushAirbrush) {
        MorandiSliderRow("喷涂流速 (Rate)", vm.brushAirbrushRate, 0.01, 1.0, isPercent = true) { vm.updateBrushAirbrushRate(it) }
    }
}

// ==========================================
// Tab 2: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
private fun ColorTabContent(vm: PaintViewModel) {
    SectionHeader("色彩随机抖动")
    MorandiSliderRow("色相抖动 (Hue Jitter)", vm.brushHueJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushHueJitter(it) }
    MorandiSliderRow("饱和度抖动 (Saturation)", vm.brushSatJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushSatJitter(it) }
    MorandiSliderRow("明度抖动 (Brightness)", vm.brushValJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushValJitter(it) }
    MorandiSliderRow("次要颜色混合比", vm.brushSecondaryMix, 0.0, 1.0, isPercent = true) { vm.updateBrushSecondaryMix(it) }
    MorandiSwitchLine("压感驱动色彩混合", vm.brushPressureColorMix) { vm.updateBrushPressureColorMix(it) }

    SectionHeader("Krita 混色涂抹动态 (Color Smudge)")
    MorandiSliderRow("混色比率 (Smudge Rate)", vm.brushSmudgeRate, 0.0, 1.0, isPercent = true) { vm.updateBrushSmudgeRate(it) }
    MorandiSliderRow("涂抹延伸长度 (Length)", vm.brushSmudgeLength, 0.0, 1.0, isPercent = true) { vm.updateBrushSmudgeLength(it) }
}

// ==========================================
// Tab 3: 几何与罗盘 (Geometry & Angle)
// ==========================================
@Composable
private fun GeometryTabContent(
    vm: PaintViewModel,
    roundnessDirection: Int,
    onRoundnessDirection: (Int) -> Unit,
) {
    SectionHeader("圆度与罗盘旋转")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
        ) {
            MorandiCardRadioRow("水平压缩", selected = roundnessDirection == 0) { onRoundnessDirection(0) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            MorandiCardRadioRow("垂直压缩", selected = roundnessDirection == 1) { onRoundnessDirection(1) }
        }

        MorandiAngleDial(
            angle = vm.brushAngle.toFloat(),
            ratio = vm.brushRatio.toFloat(),
            onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
            modifier = Modifier.size(86.dp),
        )
    }

    MorandiSliderRow("圆度比例 (Aspect Ratio)", vm.brushRatio, 0.05, 1.0, isPercent = true) { vm.updateBrushRatio(it) }
    MorandiSliderRow("笔尖基础角度", vm.brushAngle, 0.0, 360.0, unit = "°") { vm.updateBrushAngle(it) }
    MorandiSliderRow("附加旋转偏角", vm.brushRotation, 0.0, 360.0, unit = "°") { vm.updateBrushRotation(it) }
    MorandiSliderRow("角度随机抖动 (Jitter)", vm.brushJitterAngle, 0.0, 1.0, isPercent = true) { vm.updateBrushJitterAngle(it) }
    MorandiSwitchLine("沿笔画运动方向自动旋转", vm.brushFollowDirection) { vm.updateBrushFollowDirection(it) }
}

// ==========================================
// Tab 4: 材质与纹理 (Texture & Pattern)
// ==========================================
@Composable
private fun TextureTabContent(vm: PaintViewModel) {
    MorandiSwitchLine("启用纹理材质叠加", vm.brushTextureEnabled) { vm.updateBrushTextureEnabled(it) }

    if (vm.brushTextureEnabled) {
        SectionHeader("纹理混合模式")
        val texModes = listOf("multiply" to "正片叠底", "overlay" to "叠加", "screen" to "滤色", "dodge" to "颜色减淡")
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
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
                }
            }
        }

        MorandiSliderRow("纹理缩放比 (Scale)", vm.brushTextureScale, 0.2, 4.0, isPercent = true) { vm.updateBrushTextureScale(it) }
        MorandiSliderRow("纹理凹凸强度 (Strength)", vm.brushTextureStrength, 0.0, 1.0, isPercent = true) { vm.updateBrushTextureStrength(it) }
    }
}

// ==========================================
// Tab 5: 压感与手感 (Pressure & Stylus)
// ==========================================
@Composable
private fun PressureTabContent(vm: PaintViewModel) {
    MorandiSwitchLine("启用压力感应响应", vm.brushPressureEnabled) { vm.updateBrushPressureEnabled(it) }

    if (vm.brushPressureEnabled) {
        SectionHeader("动态压感感应")
        MorandiSliderRow("压力影响大小 (Size)", vm.brushPressureSize, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureSize(it) }
        MorandiSliderRow("压力影响不透明度 (Opacity)", vm.brushPressureOpacity, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureOpacity(it) }
        MorandiSliderRow("压力影响流量 (Flow)", vm.brushPressureFlow, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureFlow(it) }
        MorandiSliderRow("速度感应响应 (Speed)", vm.brushSpeedSize, 0.0, 1.0, isPercent = true) { vm.updateBrushSpeedSize(it) }

        SectionHeader("压感响应曲线")
        val curves = listOf("线性响应 (Linear)", "柔和曲线 (Soft)", "硬朗曲线 (Hard)", "S型精细响应 (S-Curve)")
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
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
                }
            }
        }
    }
}

// ==========================================
// Tab 6: 引擎与属性 (Engine & Limits)
// ==========================================
@Composable
private fun EngineTabContent(vm: PaintViewModel, preset: BrushPresetInfo?) {
    SectionHeader("Krita 笔刷引擎 (PaintOp Engine)")
    val engines = listOf(
        "defaultpaintop" to "像素引擎 (Pixel)",
        "colorsmudge" to "混色涂抹 (Smudge)",
        "spray" to "喷雾粒子 (Spray)",
        "sketch" to "素描线条 (Sketch)",
        "hairy" to "毛发鬃毛 (Hairy)",
        "roundmarker" to "圆马克笔 (Marker)",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp)),
    ) {
        engines.forEachIndexed { idx, (id, name) ->
            val sel = vm.brushPaintOpId == id
            MorandiCardRadioRow(name = name, selected = sel) { vm.updateBrushPaintOpId(id) }
            if (idx < engines.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.4f)))
            }
        }
    }

    SectionHeader("混合模式 (Composite Mode)")
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
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel)
                            .border(1.dp, if (sel) Morandi.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (sel) Morandi.accent else Morandi.subText,
                            fontSize = 11.sp,
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

    MorandiSliderRow("不透明度 (Opacity)", vm.brushOpacity, 0.01, 1.0, isPercent = true) { vm.updateBrushOpacity(it) }
    MorandiSliderRow("流量 (Flow)", vm.brushFlow, 0.01, 1.0, isPercent = true) { vm.updateBrushFlow(it) }
    MorandiSliderRow("边缘锐度 (Sharpness)", vm.brushSharpness, 0.0, 1.0, isPercent = true) { vm.updateBrushSharpness(it) }

    SectionHeader("尺寸上下限")
    MorandiSliderRow("最小尺寸限制", vm.brushMinSizeLimit, 1.0, 50.0, unit = "px") { vm.updateBrushMinSizeLimit(it) }
    MorandiSliderRow("最大尺寸限制", vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px") { vm.updateBrushMaxSizeLimit(it) }

    Spacer(Modifier.height(4.dp))

    Button(
        onClick = { vm.resetBrushParams() },
        modifier = Modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Morandi.panelHi),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(15.dp))
            Text("重置为初始预设默认值", color = Morandi.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.text, fontSize = 13.sp)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (checked) {
            Icon(painterResource(R.drawable.ic_circle_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MorandiCardRadioRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) Morandi.accent else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_circle_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
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
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Morandi.text, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isPercent) "${(value * 100).toInt()}%" else "${value.toInt()}$unit",
                color = Morandi.subText,
                fontSize = 11.sp,
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
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
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
                radius = 3.dp.toPx(),
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
        val checkSize = 12.dp.toPx()
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
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(8f, 56f) / 2f)
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
                            topLeft = Offset(curPos.x - rad, curPos.y - rad * ratio),
                            size = Size(rad * 2f, rad * 2f * ratio),
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
