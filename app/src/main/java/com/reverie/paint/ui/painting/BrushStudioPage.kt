package com.reverie.paint.ui.painting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
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
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class StudioTab(val title: String, val subtitle: String, val iconRes: Int) {
    TIP("笔尖库", "Tip Library", R.drawable.ic_pencil),
    STROKE("笔画动态", "Dynamics", R.drawable.ic_line),
    COLOR("色彩涂抹", "Color & Smudge", R.drawable.ic_palette),
    GEOMETRY("几何罗盘", "Geometry", R.drawable.ic_rotate_cw),
    TEXTURE("材质纹理", "Texture", R.drawable.ic_grid),
    PRESSURE("压感手感", "Pressure", R.drawable.ic_hand),
    ENGINE("引擎属性", "Engine & Limits", R.drawable.ic_settings),
}

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)
data class BrushTipItem(val filename: String, val name: String, val bitmap: Bitmap?)

/**
 * GBR / PNG 笔尖贴图解码工具
 */
private object BrushTipDecoder {
    private val cache = mutableMapOf<String, Bitmap?>()

    fun loadTip(context: Context, filename: String): Bitmap? {
        if (filename.isBlank()) return null
        if (cache.containsKey(filename)) return cache[filename]
        val bmp = runCatching {
            context.assets.open("brushes/$filename").use { stream ->
                if (filename.endsWith(".png", ignoreCase = true)) {
                    BitmapFactory.decodeStream(stream)
                } else if (filename.endsWith(".gbr", ignoreCase = true)) {
                    decodeGbr(stream.readBytes())
                } else if (filename.endsWith(".gih", ignoreCase = true)) {
                    decodeGih(stream.readBytes())
                } else {
                    null
                }
            }
        }.getOrNull()
        cache[filename] = bmp
        return bmp
    }

    private fun decodeGbr(bytes: ByteArray): Bitmap? {
        if (bytes.size < 28) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val headerSize = buf.int
        val version = buf.int
        val width = buf.int
        val height = buf.int
        val bpp = buf.int
        if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null
        val offset = headerSize.coerceAtLeast(28)
        if (bytes.size < offset + width * height * bpp) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        if (bpp == 1) {
            for (i in 0 until width * height) {
                val a = 255 - (bytes[offset + i].toInt() and 0xFF)
                pixels[i] = (a shl 24) or 0x00FFFFFF
            }
        } else if (bpp == 4) {
            for (i in 0 until width * height) {
                val r = bytes[offset + i * 4].toInt() and 0xFF
                val g = bytes[offset + i * 4 + 1].toInt() and 0xFF
                val b = bytes[offset + i * 4 + 2].toInt() and 0xFF
                val a = bytes[offset + i * 4 + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun decodeGih(bytes: ByteArray): Bitmap? {
        // Find embedded GBR header inside GIH file
        if (bytes.size < 64) return null
        for (i in 0 until bytes.size - 28) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && (bytes[i + 6] == 0.toByte() && bytes[i + 7] == 2.toByte())) {
                val sub = bytes.copyOfRange(i, bytes.size)
                val bmp = decodeGbr(sub)
                if (bmp != null) return bmp
            }
        }
        return null
    }
}

/**
 * 笔刷工作室独立全屏页面 (Dedicated Full-Screen Brush Studio Page)
 * 极简高级莫兰迪灰调设计，纯交互式试画板与 Krita 全量图形化笔尖库
 */
@Composable
fun BrushStudioPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
    hazeState: HazeState? = null,
) {
    BackHandler { onBack() }

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

    // Load full list of Krita bundled brush tips with decoded graphical thumbnails
    val allTipItems = remember(context) {
        val list = mutableListOf<BrushTipItem>()
        list.add(
            BrushTipItem(
                filename = "",
                name = "当前预设笔尖",
                bitmap = preset?.thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
            )
        )
        val files = runCatching { context.assets.list("brushes")?.toList() }.getOrNull() ?: emptyList()
        files.sorted().forEach { f ->
            if (f.endsWith(".png", true) || f.endsWith(".gbr", true) || f.endsWith(".gih", true)) {
                val cleanName = f.substringBeforeLast(".")
                    .replace("A_", "")
                    .replace("Z_", "")
                    .replace("P_", "")
                    .replace("M_", "")
                    .replace("_", " ")
                val bmp = BrushTipDecoder.loadTip(context, f)
                if (bmp != null) {
                    list.add(BrushTipItem(filename = f, name = cleanName, bitmap = bmp))
                }
            }
        }
        list
    }

    val pageBg = Color(0xFF141416)
    val panelBg = Color(0xFF1A1A1E)
    val cardBg = Color(0xFF222227)
    val borderCol = Color(0xFF2A2A30)
    val textMain = Color(0xFFE6E6EB)
    val textSub = Color(0xFF8C8C94)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .pointerHoverIcon(PointerIcon.Default),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- Top Header Bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(panelBg)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_left),
                        contentDescription = "返回画布",
                        tint = textMain,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Text(
                    "笔刷工作台",
                    color = textMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(cardBg)
                        .border(1.dp, borderCol, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        preset?.name ?: "自定义笔刷",
                        color = textSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.weight(1f))

                // Reset action
                IconButton(onClick = { vm.resetBrushParams() }) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = "重置参数",
                        tint = textSub,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol))

            // ---- Master-Detail Split Workspace ----
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Left Navigation Rail (左侧功能导轨)
                Column(
                    modifier = Modifier
                        .width(108.dp)
                        .fillMaxHeight()
                        .background(panelBg)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    StudioTab.values().forEach { tab ->
                        val sel = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) cardBg else Color.Transparent)
                                .border(
                                    width = if (sel) 1.dp else 0.dp,
                                    color = if (sel) borderCol.copy(alpha = 0.8f) else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .clickable { selectedTab = tab }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        painterResource(tab.iconRes),
                                        contentDescription = null,
                                        tint = if (sel) textMain else textSub,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        tab.title,
                                        color = if (sel) textMain else textSub,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                                Text(
                                    tab.subtitle,
                                    color = textSub.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(borderCol))

                // Right Main Workspace (右侧工作区)
                Column(modifier = Modifier.weight(1f).fillMaxHeight().background(pageBg)) {
                    // Top Interactive Scratchpad (试画台)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(126.dp)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cardBg)
                            .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
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

                        // Clear button
                        if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(panelBg.copy(alpha = 0.9f))
                                    .border(1.dp, borderCol, RoundedCornerShape(4.dp))
                                    .clickable {
                                        scratchStrokes.clear()
                                        currentScratchStroke = emptyList()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = textSub, modifier = Modifier.size(12.dp))
                                Text("清空", color = textSub, fontSize = 11.sp)
                            }
                        } else {
                            Text(
                                "在此随意画线测试手感",
                                color = textSub.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol))

                    // Parameter Cards Stack
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                        allTips = allTipItems,
                                        shapeInvert = shapeInvert,
                                        onShapeInvert = { shapeInvert = it },
                                        shapeColorInvert = shapeColorInvert,
                                        onShapeColorInvert = { shapeColorInvert = it },
                                        shapeRgbAffectsAlpha = shapeRgbAffectsAlpha,
                                        onShapeRgbAffectsAlpha = { shapeRgbAffectsAlpha = it },
                                        roundnessDirection = roundnessDirection,
                                        onRoundnessDirection = { roundnessDirection = it },
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                    )
                                    StudioTab.STROKE -> StrokeTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.COLOR -> ColorTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.GEOMETRY -> GeometryTabContent(
                                        vm = vm,
                                        roundnessDirection = roundnessDirection,
                                        onRoundnessDirection = { roundnessDirection = it },
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                    )
                                    StudioTab.TEXTURE -> TextureTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.PRESSURE -> PressureTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.ENGINE -> EngineTabContent(vm = vm, preset = preset, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
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
// Tab 0: 笔尖库 (Tip Library & Mask)
// ==========================================
@Composable
private fun TipTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    allTips: List<BrushTipItem>,
    shapeInvert: Boolean,
    onShapeInvert: (Boolean) -> Unit,
    shapeColorInvert: Boolean,
    onShapeColorInvert: (Boolean) -> Unit,
    shapeRgbAffectsAlpha: Boolean,
    onShapeRgbAffectsAlpha: (Boolean) -> Unit,
    roundnessDirection: Int,
    onRoundnessDirection: (Int) -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    StudioSectionHeader("Krita 笔尖图形库 (点击即换笔尖)", textSub)

    // Visual Graphical Grid of Krita Brush Tips
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 64.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(allTips) { item ->
                val isSelected = vm.brushTipAsset == item.filename
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color(0xFF32323A) else Color(0xFF1B1B1F))
                        .border(1.dp, if (isSelected) Color(0xFF888899) else Color(0xFF26262C), RoundedCornerShape(6.dp))
                        .clickable { vm.updateBrushTipAsset(item.filename) }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                    if (item.bitmap != null) {
                        Image(
                            bitmap = item.bitmap.asImageBitmap(),
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    } else {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
    }

    StudioSectionHeader("笔尖形态与生成 (Auto Brush)", textSub)
    val tipTypes = listOf("圆形笔尖 (Round)", "方形笔尖 (Square)")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
    ) {
        tipTypes.forEachIndexed { idx, name ->
            val sel = vm.brushTipShape == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTipShape(idx) }
            if (idx < tipTypes.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
            }
        }
    }

    StudioSliderItem("星角数 (Spikes)", vm.brushSpikes.toDouble(), 2.0, 16.0, unit = "角", textMain = textMain, textSub = textSub) { vm.updateBrushSpikes(it.toInt()) }
    StudioSliderItem("边缘羽化硬度", vm.brushSoftness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSoftness(it) }

    StudioSectionHeader("抗锯齿与翻转", textSub)
    val aaList = listOf("无抗锯齿", "标准抗锯齿", "强化抗锯齿", "分级采样")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
    ) {
        aaList.forEachIndexed { idx, name ->
            val sel = vm.brushAntiAliasing == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushAntiAliasing(idx) }
            if (idx < aaList.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
            }
        }
    }

    StudioSwitchItem("水平随机翻转 (Flip X)", vm.brushRandomFlipX, textMain = textMain) { vm.updateBrushRandomFlipX(it) }
    StudioSwitchItem("垂直随机翻转 (Flip Y)", vm.brushRandomFlipY, textMain = textMain) { vm.updateBrushRandomFlipY(it) }
}

// ==========================================
// Tab 1: 笔画动态 (Dynamics)
// ==========================================
@Composable
private fun StrokeTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioSectionHeader("间距与散布", textSub)
    StudioSliderItem("间距 (Spacing)", vm.brushSpacing, 0.01, 2.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpacing(it) }
    StudioSliderItem("散布抖动 (Scatter)", vm.brushScatter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushScatter(it) }
    StudioSliderItem("防抖平滑度 (Streamline)", vm.brushStreamline, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushStreamline(it) }
    StudioSliderItem("渐隐淡出 (Fade)", vm.brushFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFade(it) }
    StudioSliderItem("笔尾收尖 (Taper)", vm.brushTaper, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTaper(it) }

    StudioSectionHeader("喷枪流速模式 (Airbrush)", textSub)
    StudioSwitchItem("启用喷枪时间流速持续喷涂", vm.brushAirbrush, textMain = textMain) { vm.updateBrushAirbrush(it) }
    if (vm.brushAirbrush) {
        StudioSliderItem("喷涂流速 (Rate)", vm.brushAirbrushRate, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushAirbrushRate(it) }
    }
}

// ==========================================
// Tab 2: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
private fun ColorTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioSectionHeader("色彩随机抖动", textSub)
    StudioSliderItem("色相抖动 (Hue Jitter)", vm.brushHueJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushHueJitter(it) }
    StudioSliderItem("饱和度抖动 (Saturation)", vm.brushSatJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSatJitter(it) }
    StudioSliderItem("明度抖动 (Brightness)", vm.brushValJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushValJitter(it) }
    StudioSliderItem("次要颜色混合比", vm.brushSecondaryMix, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSecondaryMix(it) }
    StudioSwitchItem("压感驱动色彩混合", vm.brushPressureColorMix, textMain = textMain) { vm.updateBrushPressureColorMix(it) }

    StudioSectionHeader("Krita 混色涂抹动态 (Color Smudge)", textSub)
    StudioSliderItem("混色比率 (Smudge Rate)", vm.brushSmudgeRate, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeRate(it) }
    StudioSliderItem("涂抹延伸长度 (Length)", vm.brushSmudgeLength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeLength(it) }
}

// ==========================================
// Tab 3: 几何与罗盘 (Geometry & Angle)
// ==========================================
@Composable
private fun GeometryTabContent(
    vm: PaintViewModel,
    roundnessDirection: Int,
    onRoundnessDirection: (Int) -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    StudioSectionHeader("圆度与罗盘旋转", textSub)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(cardBg)
                .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
        ) {
            StudioRadioRow("水平压缩", selected = roundnessDirection == 0, textMain = textMain, textSub = textSub) { onRoundnessDirection(0) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
            StudioRadioRow("垂直压缩", selected = roundnessDirection == 1, textMain = textMain, textSub = textSub) { onRoundnessDirection(1) }
        }

        StudioAngleDial(
            angle = vm.brushAngle.toFloat(),
            ratio = vm.brushRatio.toFloat(),
            onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
            cardBg = cardBg,
            borderCol = borderCol,
            modifier = Modifier.size(86.dp),
        )
    }

    StudioSliderItem("圆度比例 (Aspect Ratio)", vm.brushRatio, 0.05, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushRatio(it) }
    StudioSliderItem("笔尖基础角度", vm.brushAngle, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushAngle(it) }
    StudioSliderItem("附加旋转偏角", vm.brushRotation, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushRotation(it) }
    StudioSliderItem("角度随机抖动 (Jitter)", vm.brushJitterAngle, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushJitterAngle(it) }
    StudioSwitchItem("沿笔画运动方向自动旋转", vm.brushFollowDirection, textMain = textMain) { vm.updateBrushFollowDirection(it) }
}

// ==========================================
// Tab 4: 材质与纹理 (Texture & Pattern)
// ==========================================
@Composable
private fun TextureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioSwitchItem("启用纹理材质叠加", vm.brushTextureEnabled, textMain = textMain) { vm.updateBrushTextureEnabled(it) }

    if (vm.brushTextureEnabled) {
        StudioSectionHeader("纹理混合模式", textSub)
        val texModes = listOf("multiply" to "正片叠底", "overlay" to "叠加", "screen" to "滤色", "dodge" to "颜色减淡")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(cardBg)
                .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
        ) {
            texModes.forEachIndexed { idx, (id, name) ->
                val sel = vm.brushTextureMode == id
                StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTextureMode(id) }
                if (idx < texModes.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
                }
            }
        }

        StudioSliderItem("纹理缩放比 (Scale)", vm.brushTextureScale, 0.2, 4.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureScale(it) }
        StudioSliderItem("纹理凹凸强度 (Strength)", vm.brushTextureStrength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureStrength(it) }
    }
}

// ==========================================
// Tab 5: 压感与手感 (Pressure & Stylus)
// ==========================================
@Composable
private fun PressureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioSwitchItem("启用压力感应响应", vm.brushPressureEnabled, textMain = textMain) { vm.updateBrushPressureEnabled(it) }

    if (vm.brushPressureEnabled) {
        StudioSectionHeader("动态压感感应", textSub)
        StudioSliderItem("压力影响大小 (Size)", vm.brushPressureSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureSize(it) }
        StudioSliderItem("压力影响不透明度 (Opacity)", vm.brushPressureOpacity, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureOpacity(it) }
        StudioSliderItem("压力影响流量 (Flow)", vm.brushPressureFlow, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureFlow(it) }
        StudioSliderItem("速度感应响应 (Speed)", vm.brushSpeedSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpeedSize(it) }

        StudioSectionHeader("压感响应曲线", textSub)
        val curves = listOf("线性响应 (Linear)", "柔和曲线 (Soft)", "硬朗曲线 (Hard)", "S型精细响应 (S-Curve)")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(cardBg)
                .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
        ) {
            curves.forEachIndexed { idx, name ->
                val sel = vm.brushPressureCurve == idx
                StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPressureCurve(idx) }
                if (idx < curves.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

// ==========================================
// Tab 6: 引擎与属性 (Engine & Limits)
// ==========================================
@Composable
private fun EngineTabContent(vm: PaintViewModel, preset: BrushPresetInfo?, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioSectionHeader("Krita 笔刷引擎 (PaintOp Engine)", textSub)
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
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp)),
    ) {
        engines.forEachIndexed { idx, (id, name) ->
            val sel = vm.brushPaintOpId == id
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPaintOpId(id) }
            if (idx < engines.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderCol.copy(alpha = 0.5f)))
            }
        }
    }

    StudioSectionHeader("混合模式 (Composite Mode)", textSub)
    val blendModeList = listOf(
        "normal" to "正常",
        "multiply" to "正片叠底",
        "screen" to "滤色",
        "overlay" to "叠加",
        "darken" to "变暗",
        "lighten" to "变亮",
        "dodge" to "减淡",
        "burn" to "加深",
        "hard_light" to "强光",
        "soft_light" to "柔光",
        "difference" to "差值",
        "exclusion" to "排除",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
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
                            .height(30.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel) Color(0xFF34343C) else Color(0xFF1E1E23))
                            .border(1.dp, if (sel) Color(0xFF70707C) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            color = if (sel) textMain else textSub,
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

    StudioSliderItem("不透明度 (Opacity)", vm.brushOpacity, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushOpacity(it) }
    StudioSliderItem("流量 (Flow)", vm.brushFlow, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFlow(it) }
    StudioSliderItem("边缘锐度 (Sharpness)", vm.brushSharpness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSharpness(it) }

    StudioSectionHeader("尺寸上下限限制", textSub)
    StudioSliderItem("最小尺寸限制", vm.brushMinSizeLimit, 1.0, 50.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMinSizeLimit(it) }
    StudioSliderItem("最大尺寸限制", vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMaxSizeLimit(it) }
}

// ==========================================
// Minimalist UI Helper Components
// ==========================================

@Composable
private fun StudioSectionHeader(title: String, textSub: Color) {
    Text(
        text = title,
        color = textSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun StudioSwitchItem(label: String, checked: Boolean, textMain: Color, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = textMain, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF555560),
                uncheckedThumbColor = Color(0xFF888890),
                uncheckedTrackColor = Color(0xFF25252A),
            ),
        )
    }
}

@Composable
private fun StudioRadioRow(name: String, selected: Boolean, textMain: Color, textSub: Color, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) textMain else textSub,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = textMain, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StudioSliderItem(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    isPercent: Boolean = false,
    unit: String = "",
    textMain: Color,
    textSub: Color,
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = textMain, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isPercent) "${(value * 100).toInt()}%" else "${value.toInt()}$unit",
                color = textSub,
                fontSize = 11.sp,
            )
        }
        ReSlider(
            value = fraction,
            onValue = { f -> onChange(f * (max - min) + min) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StudioAngleDial(
    angle: Float,
    ratio: Float,
    onAngleChange: (Float) -> Unit,
    cardBg: Color,
    borderCol: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
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
                color = borderCol,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            val rad = angle * (Math.PI.toFloat() / 180f)
            val needleX = center.x + cos(rad) * (radius - 4.dp.toPx())
            val needleY = center.y + sin(rad) * (radius - 4.dp.toPx())

            drawLine(
                color = Color(0xFFAAAAAA),
                start = center,
                end = Offset(needleX, needleY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            drawCircle(
                color = Color.White,
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
        val color1 = Color(0xFF1B1B1E)
        val color2 = Color(0xFF222226)
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
