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
 * 完全对齐画世界 Pro 交互视觉规范
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

    // Scratchpad drawing points
    val scratchStrokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    var currentScratchStroke by remember { mutableStateOf<List<ScratchPoint>>(emptyList()) }

    // Tip shape options state
    var shapeInvert by remember { mutableStateOf(false) }
    var shapeColorInvert by remember { mutableStateOf(false) }
    var shapeRgbAffectsAlpha by remember { mutableStateOf(true) }
    var roundnessDirection by remember { mutableStateOf(1) } // 0: 水平, 1: 垂直

    val bgDark = Color(0xFF141416)
    val cardDark = Color(0xFF1E1E22)
    val itemBg = Color(0xFF28282E)
    val checkAccent = Morandi.accent

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
                .pointerHoverIcon(PointerIcon.Default),
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
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .noRippleClickable(onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Mode Switcher (笔触预览 / 涂鸦测试板)
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(cardDark)
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .padding(horizontal = 14.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (previewMode == StudioPreviewMode.STROKE) Color(0xFF383840) else Color.Transparent)
                                .clickable { previewMode = StudioPreviewMode.STROKE },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    painterResource(R.drawable.ic_brush),
                                    contentDescription = null,
                                    tint = if (previewMode == StudioPreviewMode.STROKE) Color.White else Color(0xFF8E8E93),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    "笔触",
                                    color = if (previewMode == StudioPreviewMode.STROKE) Color.White else Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .padding(horizontal = 14.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (previewMode == StudioPreviewMode.SCRATCHPAD) Color(0xFF383840) else Color.Transparent)
                                .clickable { previewMode = StudioPreviewMode.SCRATCHPAD },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    painterResource(R.drawable.ic_sliders),
                                    contentDescription = null,
                                    tint = if (previewMode == StudioPreviewMode.SCRATCHPAD) Color.White else Color(0xFF8E8E93),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    "画板",
                                    color = if (previewMode == StudioPreviewMode.SCRATCHPAD) Color.White else Color(0xFF8E8E93),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Overflow Menu
                    Box {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .noRippleClickable { showMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_dots_vertical),
                                contentDescription = "菜单",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(cardDark),
                        ) {
                            DropdownMenuItem(
                                text = { Text("重置当前笔刷数值", color = Color.White, fontSize = 13.sp) },
                                onClick = {
                                    vm.resetBrushParams()
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = checkAccent, modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }

                // ---- Top Stroke Preview Banner ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardDark)
                        .border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(8.dp)),
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize())

                    when (previewMode) {
                        StudioPreviewMode.STROKE -> {
                            RealisticLiveStrokePreview(
                                vm = vm,
                                preset = preset,
                                shapeInvert = shapeInvert,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        StudioPreviewMode.SCRATCHPAD -> {
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

                            if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF222226).copy(alpha = 0.9f))
                                        .border(1.dp, Color(0xFF33333A), RoundedCornerShape(12.dp))
                                        .clickable {
                                            scratchStrokes.clear()
                                            currentScratchStroke = emptyList()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("清空", color = checkAccent, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ---- Tab Bar (画世界 Pro 风格 胶囊标签栏) ----
                val tabScrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(tabScrollState)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StudioTab.values().forEach { tab ->
                        val sel = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF2C2C32) else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.title,
                                color = if (sel) Color.White else Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ---- Tab Content Area ----
                val contentScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(contentScrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "studioTabAnim",
                    ) { tab ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
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
    // 形状 header
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("形状", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        StudioSwitch(checked = vm.brushTipShape == 0, onCheckedChange = { vm.updateBrushTipShape(if (it) 0 else 1) })
    }

    // Shape box with thumbnail & stacked options
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail square with checkerboard
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E22))
                .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
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
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.White),
                )
            }
        }

        // 3 option rows on the right
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E22))
                .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
        ) {
            StudioOptionClickRow("反转", checked = shapeInvert) { onShapeInvert(!shapeInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
            StudioOptionClickRow("颜色", checked = shapeColorInvert) { onShapeColorInvert(!shapeColorInvert) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
            StudioOptionClickRow("形状RGB值影响透明度", checked = shapeRgbAffectsAlpha) { onShapeRgbAffectsAlpha(!shapeRgbAffectsAlpha) }
        }
    }

    // 抗锯齿 header & card
    Text("抗锯齿", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val aaList = listOf("无", "正常", "强化", "分级")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
    ) {
        aaList.forEachIndexed { idx, name ->
            val sel = vm.brushAntiAliasing == idx
            StudioCardRadioRow(name = name, selected = sel) { vm.updateBrushAntiAliasing(idx) }
            if (idx < aaList.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
            }
        }
    }

    // Switches
    StudioSwitchLine("水平翻转随机", vm.brushRandomFlipX) { vm.updateBrushRandomFlipX(it) }
    StudioSwitchLine("垂直翻转随机", vm.brushRandomFlipY) { vm.updateBrushRandomFlipY(it) }
    StudioSwitchLine("沿笔画方向旋转", vm.brushFollowDirection) { vm.updateBrushFollowDirection(it) }

    // 笔尖类型
    Text("笔尖", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val tipTypes = listOf("圆形笔触", "方形笔触")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
    ) {
        tipTypes.forEachIndexed { idx, name ->
            val sel = vm.brushTipShape == idx
            StudioCardRadioRow(name = name, selected = sel) { vm.updateBrushTipShape(idx) }
            if (idx < tipTypes.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
            }
        }
    }

    // 硬度
    StudioSliderRow("硬度", vm.brushSoftness, 0.0, 1.0, isPercent = true) { vm.updateBrushSoftness(it) }

    // 圆度 & 旋转轮盘
    Text("圆度", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E22))
                .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
        ) {
            StudioCardRadioRow("水平方向", selected = roundnessDirection == 0) { onRoundnessDirection(0) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
            StudioCardRadioRow("垂直方向", selected = roundnessDirection == 1) { onRoundnessDirection(1) }
        }

        // Circular Angle / Roundness Dial Widget
        StudioAngleDial(
            angle = vm.brushAngle.toFloat(),
            ratio = vm.brushRatio.toFloat(),
            onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
            onRatioChange = { vm.updateBrushRatio(it.toDouble()) },
            modifier = Modifier.size(86.dp),
        )
    }

    StudioSliderRow("角度", vm.brushAngle, 0.0, 360.0, unit = "°") { vm.updateBrushAngle(it) }
    StudioSliderRow("旋转", vm.brushRotation, 0.0, 360.0, unit = "°") { vm.updateBrushRotation(it) }
}

// ==========================================
// Tab 1: 线条 (Stroke)
// ==========================================
@Composable
private fun StrokeTabContent(vm: PaintViewModel) {
    Text("间距与抖动", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    StudioSliderRow("间距", vm.brushSpacing, 0.01, 2.5, isPercent = true) { vm.updateBrushSpacing(it) }
    StudioSliderRow("散布 / 抖动", vm.brushScatter, 0.0, 1.0, isPercent = true) { vm.updateBrushScatter(it) }
    StudioSliderRow("流畅度 / 防抖 (Streamline)", vm.brushStreamline, 0.0, 1.0, isPercent = true) { vm.updateBrushStreamline(it) }
    StudioSliderRow("渐隐", vm.brushFade, 0.0, 1.0, isPercent = true) { vm.updateBrushFade(it) }

    Spacer(Modifier.height(4.dp))
    Text("笔尾收尖 (Taper)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    StudioSliderRow("收尖强度", vm.brushTaper, 0.0, 1.0, isPercent = true) { vm.updateBrushTaper(it) }
}

// ==========================================
// Tab 2: 纹理 (Texture)
// ==========================================
@Composable
private fun TextureTabContent(vm: PaintViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("纹理材质", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        StudioSwitch(checked = vm.brushTextureEnabled, onCheckedChange = { vm.updateBrushTextureEnabled(it) })
    }

    if (vm.brushTextureEnabled) {
        val texModes = listOf("multiply" to "正片叠底", "overlay" to "叠加", "screen" to "滤色", "dodge" to "减淡")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E22))
                .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
        ) {
            texModes.forEachIndexed { idx, (id, name) ->
                val sel = vm.brushTextureMode == id
                StudioCardRadioRow(name = name, selected = sel) { vm.updateBrushTextureMode(id) }
                if (idx < texModes.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
                }
            }
        }

        StudioSliderRow("纹理缩放", vm.brushTextureScale, 0.2, 4.0, isPercent = true) { vm.updateBrushTextureScale(it) }
        StudioSliderRow("纹理强度", vm.brushTextureStrength, 0.0, 1.0, isPercent = true) { vm.updateBrushTextureStrength(it) }
    }
}

// ==========================================
// Tab 3: 渲染 (Rendering)
// ==========================================
@Composable
private fun RenderingTabContent(vm: PaintViewModel) {
    Text("混合模式", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

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
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp))
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
                            .background(if (sel) Color(0xFF383842) else Color(0xFF26262C))
                            .border(1.dp, if (sel) Morandi.accent else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (sel) Color.White else Color(0xFF8E8E93),
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

    StudioSliderRow("不透明度", vm.brushOpacity, 0.01, 1.0, isPercent = true) { vm.updateBrushOpacity(it) }
    StudioSliderRow("流量 (Flow)", vm.brushFlow, 0.01, 1.0, isPercent = true) { vm.updateBrushFlow(it) }
    StudioSliderRow("边缘锐度 (Sharpness)", vm.brushSharpness, 0.0, 1.0, isPercent = true) { vm.updateBrushSharpness(it) }
}

// ==========================================
// Tab 4: 颜色 (Color)
// ==========================================
@Composable
private fun ColorTabContent(vm: PaintViewModel) {
    Text("颜色动态", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    StudioSliderRow("色相抖动 (Hue)", vm.brushHueJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushHueJitter(it) }
    StudioSliderRow("饱和度抖动 (Saturation)", vm.brushSatJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushSatJitter(it) }
    StudioSliderRow("明度抖动 (Brightness)", vm.brushValJitter, 0.0, 1.0, isPercent = true) { vm.updateBrushValJitter(it) }
    StudioSliderRow("前/背景色混合比", vm.brushSecondaryMix, 0.0, 1.0, isPercent = true) { vm.updateBrushSecondaryMix(it) }
    StudioSwitchLine("压感驱动色彩混合", vm.brushPressureColorMix) { vm.updateBrushPressureColorMix(it) }
}

// ==========================================
// Tab 5: 压力 (Pressure)
// ==========================================
@Composable
private fun PressureTabContent(vm: PaintViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("压感感应", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        StudioSwitch(checked = vm.brushPressureEnabled, onCheckedChange = { vm.updateBrushPressureEnabled(it) })
    }

    if (vm.brushPressureEnabled) {
        StudioSliderRow("压力对大小影响", vm.brushPressureSize, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureSize(it) }
        StudioSliderRow("压力对不透明度影响", vm.brushPressureOpacity, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureOpacity(it) }
        StudioSliderRow("压力对流量影响", vm.brushPressureFlow, 0.0, 1.0, isPercent = true) { vm.updateBrushPressureFlow(it) }
        StudioSliderRow("速度感应 (Speed Dynamics)", vm.brushSpeedSize, 0.0, 1.0, isPercent = true) { vm.updateBrushSpeedSize(it) }

        Spacer(Modifier.height(4.dp))
        Text("压感响应曲线", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val curves = listOf("线性", "柔和 (易重压)", "硬朗 (需用力)", "S型曲线")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E22))
                .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
        ) {
            curves.forEachIndexed { idx, name ->
                val sel = vm.brushPressureCurve == idx
                StudioCardRadioRow(name = name, selected = sel) { vm.updateBrushPressureCurve(idx) }
                if (idx < curves.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
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
    Text("笔刷属性", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("笔刷名称", color = Color(0xFF8E8E93), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(preset?.name ?: "未命名", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28282E)))
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("分组归属", color = Color(0xFF8E8E93), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(preset?.group?.ifBlank { "全部" } ?: "全部", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    StudioSliderRow("最小尺寸限制", vm.brushMinSizeLimit, 1.0, 50.0, unit = "px") { vm.updateBrushMinSizeLimit(it) }
    StudioSliderRow("最大尺寸限制", vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px") { vm.updateBrushMaxSizeLimit(it) }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { vm.resetBrushParams() },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282830)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
            Text("重置为初始预设默认值", color = Morandi.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ==========================================
// Reusable UI Components
// ==========================================

@Composable
private fun StudioSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Morandi.accent,
            uncheckedThumbColor = Color(0xFF8E8E93),
            uncheckedTrackColor = Color(0xFF2C2C32),
        ),
    )
}

@Composable
private fun StudioSwitchLine(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        StudioSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StudioOptionClickRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (checked) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StudioCardRadioRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) Color.White else Color(0xFFB0B0B8),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StudioSliderRow(
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
            Text(label, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isPercent) "${(value * 100).toInt()}%" else "${value.toInt()}$unit",
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
            )
        }
        ReSlider(
            value = fraction,
            onValue = { f -> onChange(f * (max - min) + min) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 圆度与角度轮盘组件 (Circular Dial)
 */
@Composable
private fun StudioAngleDial(
    angle: Float,
    ratio: Float,
    onAngleChange: (Float) -> Unit,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2C2C32), RoundedCornerShape(8.dp))
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

            // Outer ring
            drawCircle(
                color = Color(0xFF383842),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // Needle indicating angle
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
// High-Fidelity Stroke Renderer
// ==========================================

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val checkSize = 14.dp.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
        val color1 = Color(0xFF222226)
        val color2 = Color(0xFF2A2A2E)
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

/**
 * 真实有机笔触曲线渲染 (高质量 Spline + 笔尖压感羽化)
 */
@Composable
private fun RealisticLiveStrokePreview(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    shapeInvert: Boolean,
    modifier: Modifier = Modifier,
) {
    val brushColor = remember(vm.brushColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.White)
    }
    val thumbBmp = remember(preset?.thumbBytes) {
        preset?.thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    val hardness = vm.brushSoftness.toFloat().coerceIn(0.05f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val scatter = vm.brushScatter.toFloat()
    val angleDeg = vm.brushAngle.toFloat() + vm.brushRotation.toFloat()
    val isSquare = vm.brushTipShape == 1

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val margin = 28.dp.toPx()
        val startX = margin
        val endX = w - margin
        val midY = h / 2f

        val totalSteps = 240
        val samplePoints = mutableListOf<Triple<Offset, Float, Float>>() // pos, pressure, tangentAngle

        var prevX = startX
        var prevY = midY

        for (i in 0..totalSteps) {
            val t = i.toFloat() / totalSteps.toFloat()
            val x = startX + (endX - startX) * t
            // Natural S-shape wave
            val wave = sin(t * Math.PI.toFloat() * 2f) * 14.dp.toPx()
            val y = midY + wave

            val dx = x - prevX
            val dy = y - prevY
            val tangent = atan2(dy, dx) * 180f / Math.PI.toFloat()
            prevX = x
            prevY = y

            // Smooth bell taper profile: 0.05 -> 1.0 -> 0.05
            val taperProfile = sin(t * Math.PI.toFloat()).coerceIn(0.02f, 1.0f)
            val curvedP = when (vm.brushPressureCurve) {
                1 -> taperProfile.pow(0.6f)
                2 -> taperProfile.pow(1.8f)
                3 -> taperProfile * taperProfile * (3f - 2f * taperProfile)
                else -> taperProfile
            }
            val effP = if (vm.brushPressureEnabled) curvedP else 1.0f
            samplePoints.add(Triple(Offset(x, y), effP, tangent))
        }

        val baseRadius = (vm.brushSize.toFloat().coerceIn(16f, 52f) / 2f)
        val spacingPx = (vm.brushSpacing.toFloat().coerceIn(0.03f, 2.0f) * baseRadius * 0.7f).coerceAtLeast(1.5f)

        var lastDabPos = Offset(-999f, -999f)

        samplePoints.forEach { (pos, pressure, tangent) ->
            val dist = (pos - lastDabPos).getDistance()
            if (dist >= spacingPx || lastDabPos.x < 0) {
                lastDabPos = pos

                val pFactor = if (vm.brushPressureEnabled) (0.15f + 0.85f * pressure * vm.brushPressureSize.toFloat()) else 1f
                val dabRadius = (baseRadius * pFactor).coerceAtLeast(1.5f)

                val opFactor = if (vm.brushPressureEnabled) (0.25f + 0.75f * pressure * vm.brushPressureOpacity.toFloat()) else 1f
                val dabAlpha = (opacity * flow * opFactor).coerceIn(0.02f, 1.0f)

                val scatterOffset = if (scatter > 0f) {
                    val sAngle = (sin(pos.x * 17.1f) * Math.PI).toFloat()
                    val sDist = abs(cos(pos.y * 23.3f)) * dabRadius * scatter * 1.6f
                    Offset(cos(sAngle) * sDist, sin(sAngle) * sDist)
                } else Offset.Zero

                val dabCenter = pos + scatterOffset
                val finalAngle = if (vm.brushFollowDirection) tangent + angleDeg else angleDeg

                rotate(degrees = finalAngle, pivot = dabCenter) {
                    if (isSquare) {
                        drawRect(
                            color = brushColor.copy(alpha = dabAlpha),
                            topLeft = Offset(dabCenter.x - dabRadius, dabCenter.y - dabRadius * ratio),
                            size = Size(dabRadius * 2f, dabRadius * 2f * ratio),
                        )
                    } else if (thumbBmp != null && vm.brushTipShape == 0 && !shapeInvert) {
                        // High quality dab texture from Krita preset
                        drawImage(
                            image = thumbBmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                (dabCenter.x - dabRadius).roundToInt(),
                                (dabCenter.y - dabRadius * ratio).roundToInt(),
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (dabRadius * 2f).roundToInt().coerceAtLeast(2),
                                (dabRadius * 2f * ratio).roundToInt().coerceAtLeast(2),
                            ),
                            colorFilter = ColorFilter.tint(brushColor.copy(alpha = dabAlpha), BlendMode.SrcIn),
                        )
                    } else {
                        // Smooth radial gradient dab
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    brushColor.copy(alpha = dabAlpha),
                                    brushColor.copy(alpha = dabAlpha * hardness),
                                    brushColor.copy(alpha = 0f),
                                ),
                                center = dabCenter,
                                radius = dabRadius,
                            ),
                            radius = dabRadius,
                            center = dabCenter,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 涂鸦测试板
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
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.05f, 1.5f) * baseRadius).coerceAtLeast(2f)

            pts.forEach { pt ->
                val curPos = Offset(pt.x, pt.y)
                val dist = (curPos - lastDab).getDistance()
                if (dist >= spacing || lastDab.x < 0) {
                    lastDab = curPos
                    val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pt.pressure * vm.brushPressureSize.toFloat()) else 1f)
                    val dabAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.3f + 0.7f * pt.pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)

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
                                radius = rad.coerceAtLeast(1f),
                            ),
                            radius = rad.coerceAtLeast(1f),
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
