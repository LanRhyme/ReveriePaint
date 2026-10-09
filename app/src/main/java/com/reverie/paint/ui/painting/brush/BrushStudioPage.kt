/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * 笔尖库清单。**只列名字, 不解码位图**。
     *
     * 原实现在 `remember` 里遍历 filesDir/brushes + assets/brushes 并对每个文件
     * 同步 readBytes + 逐像素解码, 而 remember 是在组合阶段跑主线程的: 打开工作台
     * 要扫六百多个文件, 其中还有 19MB 的动画 .gih, 卡顿与内存峰值都来自这里。
     * 解码改由网格项按可见范围异步触发 (见 TipThumb)。
     */
    var allTipItems by remember { mutableStateOf<List<BrushTipItem>>(emptyList()) }
    LaunchedEffect(context) {
        allTipItems = withContext(Dispatchers.IO) { buildTipItems(context) }
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

                    ReDropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        ReDropdownMenuItem(
                            text = stringResource(R.string.brush_studio_rename_brush),
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.brush_share_action),
                            onClick = {
                                showMenu = false
                                preset?.let { vm.shareBrushPreset(context, it.name) }
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.brush_export_group_action),
                            onClick = {
                                showMenu = false
                                preset?.group?.let { vm.exportBrushGroup(context, it) }
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.brush_studio_reset_params),
                            onClick = {
                                showMenu = false
                                vm.resetBrushParams()
                            },
                        )
                        if (preset?.isBuiltIn != true) {
                            ReDropdownMenuItem(
                                text = stringResource(R.string.brush_studio_delete_brush),
                                isDestructive = true,
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
