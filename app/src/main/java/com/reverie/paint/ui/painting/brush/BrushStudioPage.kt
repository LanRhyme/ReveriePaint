/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import com.reverie.paint.ui.painting.TextInputGuard
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class StudioTab(val titleRes: Int, val subtitle: String, val iconRes: Int) {
    TIP(R.string.brush_studio_tab_tip, "Tip & Shape", R.drawable.ic_pencil),
    MASKING(R.string.brush_studio_tab_masking, "Masking Brush", R.drawable.ic_layers),
    STROKE(R.string.brush_studio_tab_stroke, "Dynamics", R.drawable.ic_line),
    COLOR(R.string.brush_studio_tab_color, "Color & Smudge", R.drawable.ic_palette),
    GEOMETRY(R.string.brush_studio_tab_geometry, "Geometry", R.drawable.ic_rotate_cw),
    TEXTURE(R.string.brush_studio_tab_texture, "Texture", R.drawable.ic_grid),
    PRESSURE(R.string.brush_studio_tab_pressure, "Pressure", R.drawable.ic_hand),
    ENGINE(R.string.brush_studio_tab_engine, "Engine & Ops", R.drawable.ic_settings),
    INFO(R.string.brush_studio_tab_info, "Properties", R.drawable.ic_info_circle),
}

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)
data class BrushTipItem(val filename: String, val name: String, val isCustom: Boolean, val bitmap: Bitmap?)

/**
 * GBR / PNG / GIH / JPG 笔尖贴图解码工具
 */
internal object BrushTipDecoder {
    private val cache = mutableMapOf<String, Bitmap?>()

    fun loadTip(context: Context, filename: String): Bitmap? {
        if (filename.isBlank()) return null
        if (cache.containsKey(filename)) return cache[filename]
        val bmp = runCatching {
            val internalFile = File(File(context.filesDir, "brushes"), filename)
            val stream = if (internalFile.exists()) {
                internalFile.inputStream()
            } else {
                context.assets.open("brushes/$filename")
            }
            stream.use { s ->
                if (filename.endsWith(".png", true) || filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true)) {
                    BitmapFactory.decodeStream(s)
                } else if (filename.endsWith(".gbr", true)) {
                    decodeGbr(s.readBytes())
                } else if (filename.endsWith(".gih", true)) {
                    decodeGih(s.readBytes())
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
 * 极简高级莫兰迪灰调设计，内置笔尖浮窗选择与自定义笔尖导入
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
    val preset = vm.brushPresets.firstOrNull { it.index == presetIndex }
    var selectedTab by remember { mutableStateOf(StudioTab.TIP) }
    var showMenu by remember { mutableStateOf(false) }
    var showNewBrushDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTipPickerModal by remember { mutableStateOf(false) }
    var showMaskingTipPickerModal by remember { mutableStateOf(false) }

    // SAF Import Launcher for brush preset (.kpp, .bundle, .gbr, .png)
    val importBrushLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importBrushFromUri(uri)
        }
    }

    // SAF Import Launcher for Custom Brush Tip Image (.png, .jpg, .gbr, .gih)
    val importTipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val res = vm.importCustomBrushTip(uri)
            if (res != null) {
                Toast.makeText(context, context.getString(R.string.brush_studio_toast_tip_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.brush_studio_toast_tip_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Scratchpad interactive test strokes
    val scratchStrokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    var currentScratchStroke by remember { mutableStateOf<List<ScratchPoint>>(emptyList()) }

    var shapeInvert by remember { mutableStateOf(false) }
    var shapeColorInvert by remember { mutableStateOf(false) }
    var shapeRgbAffectsAlpha by remember { mutableStateOf(true) }

    // Load full list of Krita bundled and custom brush tips
    val allTipItems = remember(context, vm.brushTipAsset) {
        val list = mutableListOf<BrushTipItem>()
        // 1. Preset Default Tip
        list.add(
            BrushTipItem(
                filename = "",
                name = context.getString(R.string.brush_studio_tip_preset_default),
                isCustom = false,
                bitmap = preset?.thumbBytes?.let { BrushThumbCache.get(preset.name, it) },
            )
        )
        // 2. Custom User Tips in filesDir/brushes
        val customDir = File(context.filesDir, "brushes")
        if (customDir.exists()) {
            customDir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".gbr", true) || name.endsWith(".gih", true)) {
                    val bmp = BrushTipDecoder.loadTip(context, name)
                    if (bmp != null) {
                        list.add(BrushTipItem(filename = name, name = context.getString(R.string.brush_studio_tip_custom_prefix, name.substringBeforeLast(".")), isCustom = true, bitmap = bmp))
                    }
                }
            }
        }
        // 3. Bundled Tips in assets/brushes
        val files = runCatching { context.assets.list("brushes")?.toList() }.getOrNull() ?: emptyList()
        files.sorted().forEach { f ->
            if (f.endsWith(".png", true) || f.endsWith(".gbr", true) || f.endsWith(".gih", true)) {
                if (list.none { it.filename == f }) {
                    val cleanName = f.substringBeforeLast(".")
                        .replace("A_", "")
                        .replace("Z_", "")
                        .replace("P_", "")
                        .replace("M_", "")
                        .replace("_", " ")
                    val bmp = BrushTipDecoder.loadTip(context, f)
                    if (bmp != null) {
                        list.add(BrushTipItem(filename = f, name = cleanName, isCustom = false, bitmap = bmp))
                    }
                }
            }
        }
        list
    }

    val pageBg = Morandi.bg
    val panelBg = Morandi.panel
    val cardBg = Morandi.panelHi
    val borderCol = Morandi.border.copy(alpha = 0.2f)
    val dividerCol = Morandi.border.copy(alpha = 0.2f)
    val textMain = Morandi.text
    val textSub = Morandi.subText

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .systemHoverIcon(context),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- Top Header Bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(panelBg)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReIconButton(R.drawable.ic_arrow_left, stringResource(R.string.brush_studio_back_canvas), onBack, size = 34.dp, tint = textMain)

                Text(
                    stringResource(R.string.brush_studio_workbench),
                    color = textMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        preset?.name ?: stringResource(R.string.brush_studio_custom_brush),
                        color = textSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.weight(1f))

                // New Brush action
                ReIconButton(R.drawable.ic_plus, stringResource(R.string.brush_studio_new_brush), { showNewBrushDialog = true }, tint = textSub, iconSize = 17.dp)

                // Duplicate action
                ReIconButton(R.drawable.ic_copy, stringResource(R.string.brush_studio_duplicate_brush), {
                    if (vm.brushPresets.any { it.index == presetIndex }) {
                        vm.duplicateBrushPreset(presetIndex)
                    }
                }, tint = textSub, iconSize = 17.dp)

                // Import action
                ReIconButton(R.drawable.ic_export_tab, stringResource(R.string.brush_studio_import_brush), { importBrushLauncher.launch(arrayOf("*/*")) }, tint = textSub, iconSize = 17.dp)

                // Overflow Menu
                Box {
                    ReIconButton(R.drawable.ic_dots_vertical, stringResource(R.string.brush_studio_more_ops), { showMenu = true }, tint = textSub, iconSize = 17.dp)

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(panelBg),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_studio_rename_brush), color = textMain, fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_share_action), color = textMain, fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                preset?.let { vm.shareBrushPreset(context, it.name) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_export_group_action), color = textMain, fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                preset?.group?.let { vm.exportBrushGroup(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.brush_studio_reset_params), color = textMain, fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                vm.resetBrushParams()
                            },
                        )
                        if (preset?.isBuiltIn != true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.brush_studio_delete_brush), color = Color(0xFFC86464), fontSize = 13.sp) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirmDialog = true
                                },
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.08f)))

            // ---- Master-Detail Split Workspace ----
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Left Navigation Rail (左侧功能导轨)
                Column(
                    modifier = Modifier
                        .width(116.dp)
                        .fillMaxHeight()
                        .background(panelBg)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    StudioTab.values().forEach { tab ->
                        val sel = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Morandi.accent.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 9.dp, horizontal = 10.dp),
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
                                        tint = if (sel) Morandi.accent else textSub,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        stringResource(tab.titleRes),
                                        color = if (sel) Morandi.accent else textMain,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                                Text(
                                    tab.subtitle,
                                    color = if (sel) Morandi.accent.copy(alpha = 0.7f) else textSub.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.width(0.6.dp).fillMaxHeight().background(Morandi.border.copy(alpha = 0.08f)))

                // Right Main Workspace (右侧工作区)
                Column(modifier = Modifier.weight(1f).fillMaxHeight().background(pageBg)) {
                    // Top Interactive Scratchpad (试画台)
                    var scratchpadExpanded by remember { mutableStateOf(false) }
                    var scratchpadSolidBg by remember { mutableStateOf(false) }
                    val scratchpadHeight by androidx.compose.animation.core.animateDpAsState(
                        targetValue = if (scratchpadExpanded) 210.dp else 126.dp,
                        label = "scratchpadHeight"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scratchpadHeight)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                            .padding(4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (scratchpadSolidBg) Morandi.panelHi else Morandi.panel),
                    ) {
                        if (!scratchpadSolidBg) {
                            CheckerboardBackground(modifier = Modifier.fillMaxSize())
                        }

                        ScratchpadCanvas(
                            vm = vm,
                            strokes = scratchStrokes,
                            currentStroke = currentScratchStroke,
                            onStrokeStart = { p -> currentScratchStroke = listOf(p) },
                            onStrokeAddPoints = { pts -> currentScratchStroke = currentScratchStroke + pts },
                            onStrokeEnd = {
                                if (currentScratchStroke.isNotEmpty()) {
                                    scratchStrokes.add(currentScratchStroke)
                                    currentScratchStroke = emptyList()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Top Controls Overlay (Background switch, Expand toggle)
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Background switcher
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel.copy(alpha = 0.85f))
                                    .clickable { scratchpadSolidBg = !scratchpadSolidBg },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(if (scratchpadSolidBg) R.drawable.ic_layers else R.drawable.ic_circle),
                                    contentDescription = stringResource(if (scratchpadSolidBg) R.string.scratchpad_bg_checker else R.string.scratchpad_bg_solid),
                                    tint = textSub,
                                    modifier = Modifier.size(14.dp),
                                )
                            }

                            // Height expand toggle
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel.copy(alpha = 0.85f))
                                    .clickable { scratchpadExpanded = !scratchpadExpanded },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_chevron),
                                    contentDescription = stringResource(if (scratchpadExpanded) R.string.scratchpad_collapse else R.string.scratchpad_expand),
                                    tint = if (scratchpadExpanded) Morandi.accent else textSub,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .rotate(if (scratchpadExpanded) -90f else 90f),
                                )
                            }
                        }

                        // Bottom Actions: Set Thumbnail & Clear button & Draw Hint
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panel.copy(alpha = 0.9f))
                                .clickable {
                                    val allStrokes = if (currentScratchStroke.isNotEmpty()) {
                                        scratchStrokes + listOf(currentScratchStroke)
                                    } else {
                                        scratchStrokes.toList()
                                    }
                                    captureScratchpadAsThumbnail(context, vm, allStrokes)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_pencil), contentDescription = null, tint = textSub, modifier = Modifier.size(13.dp))
                            Text(stringResource(R.string.brush_studio_scratchpad_set_icon), color = textSub, fontSize = 11.sp)
                        }

                        if (scratchStrokes.isNotEmpty() || currentScratchStroke.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel.copy(alpha = 0.9f))
                                    .clickable {
                                        scratchStrokes.clear()
                                        currentScratchStroke = emptyList()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = textSub, modifier = Modifier.size(13.dp))
                                Text(stringResource(R.string.brush_studio_scratchpad_clear), color = textSub, fontSize = 11.sp)
                            }
                        } else {
                            Text(
                                stringResource(R.string.brush_studio_scratchpad_hint),
                                color = textSub.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.08f)))

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
                                        onOpenTipPicker = { showTipPickerModal = true },
                                        onImportCustomTip = { importTipLauncher.launch(arrayOf("*/*")) },
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                    )
                                    StudioTab.MASKING -> MaskingTabContent(
                                        vm = vm,
                                        preset = preset,
                                        allTips = allTipItems,
                                        onOpenMaskingTipPicker = { showMaskingTipPickerModal = true },
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                    )
                                    StudioTab.STROKE -> StrokeTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.COLOR -> ColorTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.GEOMETRY -> GeometryTabContent(
                                        vm = vm,
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                    )
                                    StudioTab.TEXTURE -> TextureTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.PRESSURE -> PressureTabContent(vm = vm, cardBg = cardBg, borderCol = borderCol, textMain = textMain, textSub = textSub)
                                    StudioTab.ENGINE -> EngineTabContent(
                                        vm = vm,
                                        presetIndex = presetIndex,
                                        preset = preset,
                                        cardBg = cardBg,
                                        borderCol = borderCol,
                                        textMain = textMain,
                                        textSub = textSub,
                                        onDuplicate = {
                                            if (vm.brushPresets.any { it.index == presetIndex }) {
                                                vm.duplicateBrushPreset(presetIndex)
                                            }
                                        },
                                        onRename = { showRenameDialog = true },
                                        onDelete = { showDeleteConfirmDialog = true },
                                    )
                                    StudioTab.INFO -> InfoTabContent(
                                         vm = vm,
                                         preset = preset,
                                         cardBg = cardBg,
                                         borderCol = borderCol,
                                         textMain = textMain,
                                         textSub = textSub,
                                     )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Modal: Brush Tip Library Picker (浮窗笔尖选择器)
        if (showTipPickerModal) {
            BrushTipPickerModal(
                allTips = allTipItems,
                currentAsset = vm.brushTipAsset,
                onSelectTip = { asset ->
                    vm.updateBrushTipAsset(asset)
                    showTipPickerModal = false
                },
                onImportTip = {
                    importTipLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showTipPickerModal = false },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
            )
        }

        if (showMaskingTipPickerModal) {
            BrushTipPickerModal(
                allTips = allTipItems,
                currentAsset = vm.brushMaskingTipAsset,
                onSelectTip = { asset ->
                    vm.updateBrushMaskingTipAsset(asset)
                    showMaskingTipPickerModal = false
                },
                onImportTip = {
                    importTipLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showMaskingTipPickerModal = false },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
            )
        }

        // Dialogs
        if (showNewBrushDialog) {
            StudioNewBrushDialog(
                onDismiss = { showNewBrushDialog = false },
                onCreate = { name, group ->
                    vm.createNewBrushPreset(name = name, group = group)
                    showNewBrushDialog = false
                    Toast.makeText(context, context.getString(R.string.brush_studio_toast_created), Toast.LENGTH_SHORT).show()
                },
                cardBg = cardBg,
                textMain = textMain,
                textSub = textSub,
                borderCol = borderCol,
            )
        }

        if (showRenameDialog && preset != null) {
            TextInputGuard(vm)
            StudioRenameDialog(
                initialName = preset.name,
                onDismiss = { showRenameDialog = false },
                onRename = { newName ->
                    vm.renameBrushPreset(presetIndex, newName)
                    showRenameDialog = false
                },
                cardBg = cardBg,
                textMain = textMain,
                textSub = textSub,
                borderCol = borderCol,
            )
        }

        if (showDeleteConfirmDialog && preset != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text(stringResource(R.string.brush_studio_delete_dialog_title), color = textMain, fontSize = 15.sp) },
                text = { Text(stringResource(R.string.brush_studio_delete_dialog_msg, preset.name), color = textSub, fontSize = 13.sp) },
                confirmButton = {
                    ReTextButton(
                        stringResource(R.string.common_delete),
                        onClick = {
                            vm.deleteBrushPreset(presetIndex)
                            showDeleteConfirmDialog = false
                            Toast.makeText(context, context.getString(R.string.brush_studio_toast_deleted), Toast.LENGTH_SHORT).show()
                        },
                        textColor = Color(0xFFC86464),
                    )
                },
                dismissButton = {
                    ReTextButton(stringResource(R.string.common_cancel), { showDeleteConfirmDialog = false }, textColor = textSub)
                },
                containerColor = cardBg,
            )
        }
    }
}

// ==========================================
// Tab 0: 笔尖形状 (Tip & Mask) - 纯净精简卡片
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
    onOpenTipPicker: () -> Unit,
    onImportCustomTip: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val curTipItem = remember(vm.brushTipAsset, allTips) {
        allTips.firstOrNull { it.filename == vm.brushTipAsset } ?: allTips.firstOrNull()
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tip_section_title)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .clickable { onOpenTipPicker() }
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                if (curTipItem?.bitmap != null) {
                    Image(
                        bitmap = curTipItem.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                    )
                } else {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    curTipItem?.name ?: stringResource(R.string.brush_studio_tip_default),
                    color = textMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (curTipItem?.isCustom == true) stringResource(R.string.brush_studio_tip_custom_tag) else stringResource(R.string.brush_studio_tip_builtin_tag),
                    color = textSub,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(2.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel.copy(alpha = 0.8f))
                            .clickable { onOpenTipPicker() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(stringResource(R.string.brush_studio_tip_browse), color = textMain, fontSize = 11.sp)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel.copy(alpha = 0.8f))
                            .clickable { onImportCustomTip() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(stringResource(R.string.brush_studio_tip_import_custom), color = textMain, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tip_auto_brush)) {
        val tipTypes = listOf(stringResource(R.string.brush_studio_tip_round), stringResource(R.string.brush_studio_tip_square))
        tipTypes.forEachIndexed { idx, name ->
            val sel = vm.brushTipShape == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTipShape(idx) }
        }
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_tip_spikes), vm.brushSpikes.toDouble(), 2.0, 16.0, unit = stringResource(R.string.brush_studio_unit_spikes), textMain = textMain, textSub = textSub) { vm.updateBrushSpikes(it.toInt()) }
        StudioSliderItem(stringResource(R.string.brush_studio_tip_feather_hardness), vm.brushSoftness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSoftness(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tip_antialias)) {
        val aaList = listOf(
            stringResource(R.string.brush_studio_tip_aa_none),
            stringResource(R.string.brush_studio_tip_aa_standard),
            stringResource(R.string.brush_studio_tip_aa_high),
            stringResource(R.string.brush_studio_tip_aa_stepped),
        )
        aaList.forEachIndexed { idx, name ->
            val sel = vm.brushAntiAliasing == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushAntiAliasing(idx) }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_geo_title)) {
        StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_x), vm.brushRandomFlipX, textMain = textMain) { vm.updateBrushRandomFlipX(it) }
        StudioInnerDivider()
        StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_y), vm.brushRandomFlipY, textMain = textMain) { vm.updateBrushRandomFlipY(it) }
    }
}

// ==========================================
// Floating Modal: Brush Tip Library Picker (内置笔尖浮窗选择器)
// ==========================================
@Composable
private fun BrushTipPickerModal(
    allTips: List<BrushTipItem>,
    currentAsset: String,
    onSelectTip: (String) -> Unit,
    onImportTip: () -> Unit,
    onDismiss: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    var filterCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = listOf(
        R.string.brush_studio_tip_filter_all,
        R.string.brush_studio_tip_filter_builtin,
        R.string.brush_studio_tip_filter_custom,
    )

    val displayedTips = remember(filterCategoryIndex, allTips) {
        when (filterCategoryIndex) {
            1 -> allTips.filter { !it.isCustom }
            2 -> allTips.filter { it.isCustom }
            else -> allTips
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 560.dp)
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.78f)
                    .shadow(16.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {},
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.brush_studio_tip_library_title),
                            color = textMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(Modifier.width(10.dp))

                        // Category Pills
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            categories.forEachIndexed { idx, catRes ->
                                val sel = filterCategoryIndex == idx
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (sel) Morandi.accent.copy(alpha = 0.18f) else cardBg)
                                        .clickable { filterCategoryIndex = idx }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        stringResource(catRes),
                                        color = if (sel) Morandi.accent else textSub,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Import Custom Tip button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardBg)
                                .clickable { onImportTip() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = textMain, modifier = Modifier.size(13.dp))
                            Text(stringResource(R.string.brush_studio_tip_import_btn), color = textMain, fontSize = 11.sp)
                        }

                        Spacer(Modifier.width(6.dp))

                        ReIconButton(R.drawable.ic_x, stringResource(R.string.common_close), onDismiss, size = 28.dp, tint = textSub, iconSize = 16.dp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.2f)))
                    Spacer(Modifier.height(10.dp))

                    // Grid
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 64.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(displayedTips) { item ->
                            val isSelected = currentAsset == item.filename
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                                    .clickable { onSelectTip(item.filename) }
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
            }
        }
    }
}

private fun captureScratchpadAsThumbnail(
    context: Context,
    vm: PaintViewModel,
    strokes: List<List<ScratchPoint>>,
) {
    val size = 200
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(240, 239, 238))

    val brushColorInt = runCatching { android.graphics.Color.parseColor(vm.brushColor) }.getOrDefault(android.graphics.Color.DKGRAY)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColorInt
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    if (strokes.isNotEmpty()) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        strokes.forEach { stroke ->
            stroke.forEach { p ->
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        val strokeW = (maxX - minX).coerceAtLeast(10f)
        val strokeH = (maxY - minY).coerceAtLeast(10f)
        val padding = 28f
        val targetBox = size - padding * 2f
        val scale = minOf(targetBox / strokeW, targetBox / strokeH).coerceIn(0.1f, 5.0f)
        val offsetX = padding + (targetBox - strokeW * scale) / 2f - minX * scale
        val offsetY = padding + (targetBox - strokeH * scale) / 2f - minY * scale

        val baseWidth = (vm.brushSize.toFloat() * scale).coerceIn(4f, 48f)

        strokes.forEach { stroke ->
            if (stroke.size >= 2) {
                val path = android.graphics.Path()
                for (i in stroke.indices) {
                    val p = stroke[i]
                    val sx = p.x * scale + offsetX
                    val sy = p.y * scale + offsetY
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                paint.strokeWidth = baseWidth
                paint.alpha = (vm.brushOpacity * 255).toInt().coerceIn(10, 255)
                canvas.drawPath(path, paint)
            } else if (stroke.size == 1) {
                val p = stroke[0]
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(p.x * scale + offsetX, p.y * scale + offsetY, baseWidth / 2f, paint)
                paint.style = android.graphics.Paint.Style.STROKE
            }
        }
    } else {
        val path = android.graphics.Path()
        path.moveTo(35f, 165f)
        path.cubicTo(65f, 120f, 135f, 80f, 165f, 35f)
        paint.strokeWidth = (vm.brushSize.toFloat()).coerceIn(12f, 38f)
        paint.alpha = (vm.brushOpacity * 255).toInt().coerceIn(10, 255)
        canvas.drawPath(path, paint)
    }

    vm.capturePresetThumbnail(bitmap)
}

@Composable
private fun StudioSensorChips(
    title: String,
    selectedSensor: String,
    sensors: List<Pair<String, Int>>,
    onSelectSensor: (String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = textSub, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sensors.forEach { (sensorId, nameRes) ->
                val sel = (selectedSensor == sensorId) || (sensorId == "pressure" && selectedSensor.isBlank())
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                        .clickable { onSelectSensor(sensorId) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(nameRes),
                        color = if (sel) Morandi.accent else textMain,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioCurvePreview(
    curveType: Int,
    cardBg: Color,
    borderCol: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panel.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        val w = size.width
        val h = size.height
        val gridLines = 4
        for (i in 1 until gridLines) {
            val x = w * (i.toFloat() / gridLines)
            val y = h * (i.toFloat() / gridLines)
            drawLine(
                color = borderCol.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1f,
            )
            drawLine(
                color = borderCol.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }

        val path = androidx.compose.ui.graphics.Path()
        val steps = 50
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            val v = when (curveType) {
                1 -> t.toDouble().pow(0.5).toFloat()
                2 -> t.toDouble().pow(2.0).toFloat()
                3 -> t * t * (3f - 2f * t)
                else -> t
            }
            val px = t * w
            val py = h - v * h
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = Morandi.accent,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ==========================================
// Tab: 蒙版画笔 (Masking Brush / Dual Brush)
// ==========================================
@Composable
private fun MaskingTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    allTips: List<BrushTipItem>,
    onOpenMaskingTipPicker: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_masking_enable), vm.brushMaskingEnabled, textMain = textMain) { vm.updateBrushMaskingEnabled(it) }
    }

    if (vm.brushMaskingEnabled) {
        val maskTip = allTips.firstOrNull { it.filename == vm.brushMaskingTipAsset }
        StudioGroupCard(stringResource(R.string.brush_studio_masking_tip)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenMaskingTipPicker() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                    if (maskTip?.bitmap != null) {
                        Image(
                            bitmap = maskTip.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    } else {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = maskTip?.name ?: stringResource(R.string.brush_studio_masking_tip_default),
                        color = textMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = if (vm.brushMaskingTipAsset.isNotBlank()) vm.brushMaskingTipAsset else stringResource(R.string.brush_studio_tip_preset_default),
                        color = textSub,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.8f))
                        .clickable { onOpenMaskingTipPicker() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(stringResource(R.string.brush_studio_masking_tip_choose), color = textMain, fontSize = 11.sp)
                }
            }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_masking_mode)) {
            val maskingModes = listOf(
                "multiply" to R.string.brush_studio_blend_multiply,
                "screen" to R.string.brush_studio_blend_screen,
                "overlay" to R.string.brush_studio_blend_overlay,
                "darken" to R.string.brush_studio_blend_darken,
                "lighten" to R.string.brush_studio_blend_lighten,
                "dodge" to R.string.brush_studio_blend_dodge,
                "burn" to R.string.brush_studio_blend_burn,
                "addition" to R.string.brush_studio_blend_hard_light,
            )
            maskingModes.chunked(4).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (opId, nameRes) ->
                        val sel = vm.brushMaskingCompositeOp == opId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                                .clickable { vm.updateBrushMaskingCompositeOp(opId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(nameRes),
                                color = if (sel) Morandi.accent else textSub,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_masking_title)) {
            StudioSliderItem(stringResource(R.string.brush_studio_masking_ratio), vm.brushMaskingSizeRatio, 0.1, 3.0, unit = "x", textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSizeRatio(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_masking_spacing), vm.brushMaskingSpacing, 0.02, 1.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSpacing(it) }

            if (vm.brushMaskingTipAsset.isBlank()) {
                StudioSliderItem(stringResource(R.string.brush_studio_masking_fade), vm.brushMaskingFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingFade(it) }
                StudioSliderItem(stringResource(R.string.brush_studio_masking_softness), vm.brushMaskingSoftness, 0.1, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSoftness(it) }
            }
        }
    }
}

// ==========================================
// Tab 1: 笔画动态 (Dynamics)
// ==========================================
@Composable
private fun StrokeTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val scatterSensors = listOf(
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_spacing_scatter)) {
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_spacing), vm.brushSpacing, 0.01, 2.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpacing(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_scatter), vm.brushScatter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushScatter(it) }
        StudioSensorChips(stringResource(R.string.brush_studio_scatter_sensor), vm.brushScatterSensor, scatterSensors, { vm.updateBrushScatterSensor(it) }, cardBg, textMain, textSub)
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_fade), vm.brushFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFade(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_airbrush_mode)) {
        StudioSwitchItem(stringResource(R.string.brush_studio_dynamics_airbrush_enable), vm.brushAirbrush, textMain = textMain) { vm.updateBrushAirbrush(it) }
        if (vm.brushAirbrush) {
            StudioInnerDivider()
            StudioSliderItem(stringResource(R.string.brush_studio_dynamics_airbrush_rate), vm.brushAirbrushRate, 10.0, 120.0, unit = stringResource(R.string.brush_studio_unit_dabs_per_sec), textMain = textMain, textSub = textSub) { vm.updateBrushAirbrushRate(it) }
        }
    }
}

// ==========================================
// Tab 2: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
private fun ColorTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val isSmudgeEngine = vm.brushPaintOpId == "colorsmudge" || vm.brushSmudgeRate > 0.0 || vm.brushSmudgeLength > 0.0

    StudioGroupCard(stringResource(R.string.brush_studio_color_jitter_title)) {
        StudioSliderItem(stringResource(R.string.brush_studio_color_hue_jitter), vm.brushHueJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushHueJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_sat_jitter), vm.brushSatJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSatJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_val_jitter), vm.brushValJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushValJitter(it) }
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_color_secondary_mix), vm.brushSecondaryMix, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSecondaryMix(it) }
        StudioSwitchItem(stringResource(R.string.brush_studio_color_pressure_mix), vm.brushPressureColorMix, textMain = textMain) { vm.updateBrushPressureColorMix(it) }
    }

    if (isSmudgeEngine) {
        StudioGroupCard(stringResource(R.string.brush_studio_color_smudge_title)) {
            StudioSliderItem(stringResource(R.string.brush_studio_color_smudge_rate), vm.brushSmudgeRate, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeRate(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_color_smudge_length), vm.brushSmudgeLength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeLength(it) }
        }
    }
}

// ==========================================
// Tab 3: 几何与罗盘 (Geometry & Angle)
// ==========================================
@Composable
private fun GeometryTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val rotationSensors = listOf(
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

    StudioGroupCard(stringResource(R.string.brush_studio_geo_title)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            StudioAngleDial(
                angle = vm.brushAngle.toFloat(),
                ratio = vm.brushRatio.toFloat(),
                onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
                cardBg = cardBg,
                borderCol = borderCol,
                modifier = Modifier.size(96.dp),
            )
        }

        StudioSliderItem(stringResource(R.string.brush_studio_geo_aspect_ratio), vm.brushRatio, 0.05, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushRatio(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_base_angle), vm.brushAngle, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushAngle(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_offset_angle), vm.brushRotation, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushRotation(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_angle_jitter), vm.brushJitterAngle, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushJitterAngle(it) }
        StudioInnerDivider()
        StudioSwitchItem(stringResource(R.string.brush_studio_geo_auto_rotate), vm.brushFollowDirection, textMain = textMain) { vm.updateBrushFollowDirection(it) }
        StudioSensorChips(stringResource(R.string.brush_studio_rotation_sensor), vm.brushRotationSensor, rotationSensors, { vm.updateBrushRotationSensor(it) }, cardBg, textMain, textSub)
    }
}

// ==========================================
// Tab 4: 材质与纹理 (Texture & Pattern)
// ==========================================
@Composable
private fun TextureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_tex_enable), vm.brushTextureEnabled, textMain = textMain) { vm.updateBrushTextureEnabled(it) }
    }

    if (vm.brushTextureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_tex_blend_mode)) {
            val texModes = listOf(
                "multiply" to R.string.brush_studio_blend_multiply,
                "overlay" to R.string.brush_studio_blend_overlay,
                "screen" to R.string.brush_studio_blend_screen,
                "dodge" to R.string.brush_studio_blend_dodge_color,
            )
            texModes.forEachIndexed { idx, (id, nameRes) ->
                val sel = vm.brushTextureMode == id
                StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTextureMode(id) }
            }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_tab_texture)) {
            StudioSliderItem(stringResource(R.string.brush_studio_tex_scale), vm.brushTextureScale, 0.2, 4.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureScale(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_tex_strength), vm.brushTextureStrength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureStrength(it) }
        }
    }
}

// ==========================================
// Tab 5: 压感与手感 (Pressure & Stylus)
// ==========================================
@Composable
private fun PressureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val standardSensors = listOf(
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "fade" to R.string.brush_studio_sensor_fade,
    )

    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_press_enable), vm.brushPressureEnabled, textMain = textMain) { vm.updateBrushPressureEnabled(it) }
    }

    if (vm.brushPressureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_press_dynamics)) {
            StudioSensorChips(stringResource(R.string.brush_studio_size_sensor), vm.brushSizeSensor, standardSensors, { vm.updateBrushSizeSensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_size), vm.brushPressureSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureSize(it) }
            StudioInnerDivider()

            StudioSensorChips(stringResource(R.string.brush_studio_opacity_sensor), vm.brushOpacitySensor, standardSensors, { vm.updateBrushOpacitySensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_opacity), vm.brushPressureOpacity, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureOpacity(it) }
            StudioInnerDivider()

            StudioSensorChips(stringResource(R.string.brush_studio_flow_sensor), vm.brushFlowSensor, standardSensors, { vm.updateBrushFlowSensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_flow), vm.brushPressureFlow, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureFlow(it) }
            StudioInnerDivider()

            StudioSliderItem(stringResource(R.string.brush_studio_press_speed), vm.brushSpeedSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpeedSize(it) }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_press_curve)) {
            StudioCurvePreview(vm.brushPressureCurve, cardBg, borderCol)

            val curves = listOf(
                R.string.brush_studio_press_linear,
                R.string.brush_studio_press_soft,
                R.string.brush_studio_press_hard,
                R.string.brush_studio_press_scurve,
            )
            curves.forEachIndexed { idx, curveRes ->
                val sel = vm.brushPressureCurve == idx
                StudioRadioRow(name = stringResource(curveRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPressureCurve(idx) }
            }
        }
    }
}

// ==========================================
// Tab 6: 引擎与属性 (Engine & Limits)
// ==========================================
@Composable
private fun EngineTabContent(
    vm: PaintViewModel,
    presetIndex: Int,
    preset: BrushPresetInfo?,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    StudioGroupCard(stringResource(R.string.brush_studio_engine_title)) {
        val engines = listOf(
            "paintbrush" to R.string.brush_studio_engine_pixel,
            "colorsmudge" to R.string.brush_studio_engine_smudge,
            "spray" to R.string.brush_studio_engine_spray,
            "sketch" to R.string.brush_studio_engine_sketch,
            "hairy" to R.string.brush_studio_engine_hairy,
            "roundmarker" to R.string.brush_studio_engine_marker,
        )
        engines.forEachIndexed { idx, (id, nameRes) ->
            val sel = (vm.brushPaintOpId == id) || (id == "paintbrush" && vm.brushPaintOpId == "defaultpaintop")
            StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPaintOpId(id) }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_engine_blend_modes)) {
        val blendModeList = listOf(
            "normal" to R.string.brush_studio_blend_normal,
            "multiply" to R.string.brush_studio_blend_multiply,
            "screen" to R.string.brush_studio_blend_screen,
            "overlay" to R.string.brush_studio_blend_overlay,
            "darken" to R.string.brush_studio_blend_darken,
            "lighten" to R.string.brush_studio_blend_lighten,
            "dodge" to R.string.brush_studio_blend_dodge,
            "burn" to R.string.brush_studio_blend_burn,
            "hard_light" to R.string.brush_studio_blend_hard_light,
            "soft_light" to R.string.brush_studio_blend_soft_light,
            "difference" to R.string.brush_studio_blend_difference,
            "exclusion" to R.string.brush_studio_blend_exclusion,
        )
        blendModeList.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (opId, nameRes) ->
                    val sel = vm.brushCompositeOp == opId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(nameRes),
                            color = if (sel) Morandi.accent else textSub,
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

    StudioGroupCard(stringResource(R.string.brush_studio_tab_engine)) {
        StudioSliderItem(stringResource(R.string.brush_studio_engine_opacity), vm.brushOpacity, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushOpacity(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_engine_flow), vm.brushFlow, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFlow(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_engine_sharpness), vm.brushSharpness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSharpness(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_engine_limits)) {
        StudioSliderItem(stringResource(R.string.brush_studio_engine_min_size), vm.brushMinSizeLimit, 1.0, 50.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMinSizeLimit(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_engine_max_size), vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMaxSizeLimit(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_prop_ops)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReTextButton(
                stringResource(R.string.brush_studio_prop_copy),
                onDuplicate,
                modifier = Modifier.weight(1f),
                textColor = textMain,
                fontSize = 12.sp,
            )
            if (preset?.isBuiltIn == true) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_prop_builtin_locked), color = textSub.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            } else {
                ReTextButton(
                    stringResource(R.string.brush_studio_prop_rename),
                    onRename,
                    modifier = Modifier.weight(1f),
                    textColor = textMain,
                    fontSize = 12.sp,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReTextButton(
                stringResource(R.string.brush_studio_prop_reset),
                { vm.resetBrushParams() },
                modifier = Modifier.weight(1f),
                textColor = textMain,
                fontSize = 12.sp,
            )
            if (preset?.isBuiltIn == true) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_prop_builtin_cannot_delete), color = textSub.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            } else {
                ReTextButton(
                    stringResource(R.string.brush_studio_prop_delete),
                    onDelete,
                    modifier = Modifier.weight(1f),
                    containerColor = Color(0xFF2C1E1E),
                    contentColor = Color(0xFFC86464),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ==========================================
// Tab 7: 笔刷属性 (Metadata & Properties)
// ==========================================
@Composable
private fun InfoTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
    onRenamePreset: () -> Unit = {},
) {
    val isBuiltIn = preset?.isBuiltIn == true

    StudioGroupCard(stringResource(R.string.brush_studio_prop_info_title)) {
        // Preset Name
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.brush_studio_prop_name), color = textSub, fontSize = 11.sp)
            if (isBuiltIn) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_builtin_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }

        if (isBuiltIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text(preset?.name ?: stringResource(R.string.brush_studio_builtin_brush), color = textSub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .clickable { onRenamePreset() }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preset?.name ?: stringResource(R.string.brush_studio_custom_brush),
                    color = textMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_pencil),
                    contentDescription = stringResource(R.string.brush_studio_prop_rename_cd),
                    tint = textSub,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        StudioInnerDivider()

        // Author Field
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.brush_studio_prop_author), color = textSub, fontSize = 11.sp)
            if (isBuiltIn) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_builtin_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            } else if (vm.brushIsAuthorLocked) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_shared_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }

        if (isBuiltIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text("Krita", color = textMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        } else if (vm.brushIsAuthorLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text(vm.brushAuthor.ifEmpty { stringResource(R.string.brush_studio_prop_external_author) }, color = textSub, fontSize = 13.sp)
            }
        } else {
            androidx.compose.foundation.text.BasicTextField(
                value = vm.brushAuthor,
                onValueChange = { vm.updateBrushAuthor(it) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            )
        }

        StudioInnerDivider()

        // Version & Category
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.brush_studio_prop_version), color = textSub, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = vm.brushVersion,
                    onValueChange = { vm.updateBrushVersion(it) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(8.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.brush_studio_prop_group), color = textSub, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(8.dp),
                ) {
                    val grpName = preset?.group?.let { brushCategoryDisplayName(it) } ?: stringResource(R.string.brush_studio_prop_default_group)
                    Text(grpName, color = textMain, fontSize = 13.sp)
                }
            }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_prop_desc)) {
        androidx.compose.foundation.text.BasicTextField(
            value = vm.brushDescription,
            onValueChange = { vm.updateBrushDescription(it) },
            textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel)
                .padding(10.dp),
        )
    }

    StudioGroupCard(stringResource(R.string.brush_studio_prop_tech_specs)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_draw_engine), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushPaintOpId, color = textMain, fontSize = 12.sp)
        }
        StudioInnerDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_tip_mask), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushTipAsset.ifEmpty { stringResource(R.string.brush_studio_prop_auto_vector) }, color = textMain, fontSize = 12.sp)
        }
        StudioInnerDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_smudge_mode), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushCompositeOp, color = textMain, fontSize = 12.sp)
        }
    }
}

// ==========================================
// Minimalist UI Helper Components (Morandi Style)
// ==========================================

@Composable
private fun StudioGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            trailing?.invoke()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun StudioCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun StudioInnerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(Morandi.border.copy(alpha = 0.1f)),
    )
}

@Composable
private fun StudioSectionHeader(title: String, textSub: Color) {
    Text(
        text = title,
        color = textSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun StudioSwitchItem(label: String, checked: Boolean, textMain: Color, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = textMain, fontSize = 13.sp)
        ReSwitch(
            checked = checked,
            onChecked = onCheckedChange,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
private fun StudioRadioRow(name: String, selected: Boolean, textMain: Color, textSub: Color, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) Morandi.accent else textMain,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(15.dp))
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
    val paramUnit = when {
        isPercent -> ParamUnit.PERCENT
        unit == "°" -> ParamUnit.DEGREE
        unit == "px" -> ParamUnit.PIXEL
        else -> ParamUnit.RAW
    }
    ModernParamSlider(
        label = label,
        value = value,
        min = min,
        max = max,
        unit = paramUnit,
        onChange = onChange,
    )
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
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panel.copy(alpha = 0.6f))
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
                color = Morandi.border.copy(alpha = 0.25f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            val rad = angle * (Math.PI.toFloat() / 180f)
            val needleX = center.x + cos(rad) * (radius - 4.dp.toPx())
            val needleY = center.y + sin(rad) * (radius - 4.dp.toPx())

            drawLine(
                color = Morandi.accent.copy(alpha = 0.8f),
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
    val color1 = Morandi.panel
    val color2 = Morandi.panelHi
    Canvas(modifier = modifier) {
        val checkSize = 12.dp.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
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
    onStrokeAddPoints: (List<ScratchPoint>) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val brushColor = remember(vm.brushColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.White)
    }
    val secColor = remember(vm.brushSecondaryColor) {
        runCatching { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) }.getOrDefault(Color.White)
    }
    val opacity = vm.brushOpacity.toFloat().coerceIn(0.05f, 1f)
    val flow = vm.brushFlow.toFloat().coerceIn(0.05f, 1f)
    // 与引擎口径一致：笔尖羽化来自 Fade（MaskGenerator 的 hfade/vfade），
    // 柔度 Softness 是在其之上叠加的乘数（1.0 = 不改动），两者相乘才是最终边缘柔和程度。
    val softness = (vm.brushFade.toFloat().coerceIn(0f, 1f) * vm.brushSoftness.toFloat().coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
    val ratio = vm.brushRatio.toFloat().coerceIn(0.05f, 1f)
    val baseRadius = (vm.brushSize.toFloat().coerceIn(2f, 80f) / 2f)
    val isSquare = vm.brushTipShape == 1
    val angle = (vm.brushAngle + vm.brushRotation).toFloat()
    val tipBitmap = remember(context, vm.brushTipAsset) {
        if (vm.brushTipAsset.isNotBlank()) {
            BrushTipDecoder.loadTip(context, vm.brushTipAsset)
        } else null
    }
    val tipImageBitmap = remember(tipBitmap) { tipBitmap?.asImageBitmap() }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val initP = if (down.pressure > 0f) down.pressure.coerceIn(0.1f, 1.0f) else 1.0f
                onStrokeStart(ScratchPoint(down.position.x, down.position.y, initP))

                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        onStrokeEnd()
                        break
                    }
                    if (change.position != change.previousPosition) {
                        change.consume()
                        val p = if (change.pressure > 0f) change.pressure.coerceIn(0.1f, 1.0f) else initP
                        val historical = change.historical
                        if (historical.isNotEmpty()) {
                            val batch = ArrayList<ScratchPoint>(historical.size + 1)
                            for (h in historical) {
                                batch.add(ScratchPoint(h.position.x, h.position.y, p))
                            }
                            batch.add(ScratchPoint(change.position.x, change.position.y, p))
                            onStrokeAddPoints(batch)
                        } else {
                            onStrokeAddPoints(listOf(ScratchPoint(change.position.x, change.position.y, p)))
                        }
                    }
                }
            }
        },
    ) {
        fun DrawScope.drawDab(curPos: Offset, pressure: Float) {
            val rad = baseRadius * (if (vm.brushPressureEnabled) (0.2f + 0.8f * pressure * vm.brushPressureSize.toFloat()) else 1f)
            val baseAlpha = (opacity * flow * (if (vm.brushPressureEnabled) (0.25f + 0.75f * pressure * vm.brushPressureOpacity.toFloat()) else 1f)).coerceIn(0.02f, 1f)
            val texMod = if (vm.brushTextureEnabled) {
                0.8f + 0.4f * (((curPos.x.toInt() * 73 + curPos.y.toInt() * 37) and 0xFF) / 255f) * vm.brushTextureStrength.toFloat()
            } else 1f
            val dabAlpha = (baseAlpha * texMod).coerceIn(0.01f, 1f)

            // Secondary color mix preview
            val effectiveColor = if (vm.brushSecondaryMix > 0.001) {
                val mixFactor = vm.brushSecondaryMix.toFloat().coerceIn(0f, 1f)
                Color(
                    red = brushColor.red * (1f - mixFactor) + secColor.red * mixFactor,
                    green = brushColor.green * (1f - mixFactor) + secColor.green * mixFactor,
                    blue = brushColor.blue * (1f - mixFactor) + secColor.blue * mixFactor,
                    alpha = brushColor.alpha,
                )
            } else brushColor

            if (tipImageBitmap != null) {
                val dabW = (rad * 2f).coerceAtLeast(2f)
                val dabH = (rad * 2f * ratio).coerceAtLeast(2f)
                if (angle != 0f) {
                    withTransform({
                        rotate(angle, curPos)
                    }) {
                        drawImage(
                            image = tipImageBitmap,
                            dstOffset = IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                            dstSize = IntSize(dabW.toInt(), dabH.toInt()),
                            alpha = dabAlpha,
                            colorFilter = ColorFilter.tint(effectiveColor, BlendMode.SrcIn),
                        )
                    }
                } else {
                    drawImage(
                        image = tipImageBitmap,
                        dstOffset = IntOffset((curPos.x - dabW / 2f).toInt(), (curPos.y - dabH / 2f).toInt()),
                        dstSize = IntSize(dabW.toInt(), dabH.toInt()),
                        alpha = dabAlpha,
                        colorFilter = ColorFilter.tint(effectiveColor, BlendMode.SrcIn),
                    )
                }
            } else if (isSquare) {
                if (angle != 0f) {
                    withTransform({
                        rotate(angle, curPos)
                    }) {
                        drawRect(
                            color = effectiveColor.copy(alpha = dabAlpha),
                            topLeft = Offset(curPos.x - rad, curPos.y - rad * ratio),
                            size = Size(rad * 2f, rad * 2f * ratio),
                        )
                    }
                } else {
                    drawRect(
                        color = effectiveColor.copy(alpha = dabAlpha),
                        topLeft = Offset(curPos.x - rad, curPos.y - rad * ratio),
                        size = Size(rad * 2f, rad * 2f * ratio),
                    )
                }
            } else {
                // Circle tip: when softness is close to 0, render razor sharp solid disc (no feathering)
                if (ratio < 0.99f || angle != 0f) {
                    withTransform({
                        if (angle != 0f) rotate(angle, curPos)
                        scale(scaleX = 1f, scaleY = ratio, pivot = curPos)
                    }) {
                        if (softness <= 0.02f) {
                            drawCircle(
                                color = effectiveColor.copy(alpha = dabAlpha),
                                radius = rad.coerceAtLeast(1.5f),
                                center = curPos,
                            )
                        } else {
                            val solidStop = (1f - softness).coerceIn(0f, 0.98f)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to effectiveColor.copy(alpha = dabAlpha),
                                        solidStop to effectiveColor.copy(alpha = dabAlpha),
                                        1f to effectiveColor.copy(alpha = 0f),
                                    ),
                                    center = curPos,
                                    radius = rad.coerceAtLeast(1.5f),
                                ),
                                radius = rad.coerceAtLeast(1.5f),
                                center = curPos,
                            )
                        }
                    }
                } else {
                    if (softness <= 0.02f) {
                        drawCircle(
                            color = effectiveColor.copy(alpha = dabAlpha),
                            radius = rad.coerceAtLeast(1.5f),
                            center = curPos,
                        )
                    } else {
                        val solidStop = (1f - softness).coerceIn(0f, 0.98f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to effectiveColor.copy(alpha = dabAlpha),
                                    solidStop to effectiveColor.copy(alpha = dabAlpha),
                                    1f to effectiveColor.copy(alpha = 0f),
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

        fun DrawScope.drawScratch(pts: List<ScratchPoint>) {
            if (pts.isEmpty()) return
            val spacing = (vm.brushSpacing.toFloat().coerceIn(0.01f, 2.5f) * (baseRadius * 2f)).coerceAtLeast(1.0f)

            if (pts.size == 1) {
                drawDab(Offset(pts[0].x, pts[0].y), pts[0].pressure)
                return
            }

            drawDab(Offset(pts[0].x, pts[0].y), pts[0].pressure)
            var distToNextDab = spacing

            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                val segDist = kotlin.math.hypot(dx, dy)
                if (segDist <= 0.0001f) continue

                var traveled = 0f
                while (traveled + distToNextDab <= segDist) {
                    traveled += distToNextDab
                    val t = traveled / segDist
                    val cx = p0.x + dx * t
                    val cy = p0.y + dy * t
                    val cp = p0.pressure + (p1.pressure - p0.pressure) * t
                    drawDab(Offset(cx, cy), cp)
                    distToNextDab = spacing
                }
                distToNextDab -= (segDist - traveled)
            }
        }

        strokes.forEach { drawScratch(it) }
        drawScratch(currentStroke)
    }
}

// ==========================================
// Dialogs
// ==========================================

@Composable
private fun StudioNewBrushDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    val defaultGroup = stringResource(R.string.brush_preset_custom_tag)
    var name by remember { mutableStateOf("") }
    var group by remember(defaultGroup) { mutableStateOf(defaultGroup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_new_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_new_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_create),
                onClick = { onCreate(name.trim(), group) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}

@Composable
private fun StudioRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_rename_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_rename_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_save),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}
