/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class StudioTab(val titleRes: Int, val subtitle: String, val iconRes: Int) {
    TIP(R.string.brush_studio_tab_tip, "Tip & Shape", R.drawable.ic_pencil),
    DYNAMICS(R.string.brush_studio_tab_stroke, "Dynamics", R.drawable.ic_line),
    COLOR(R.string.brush_studio_tab_color, "Color & Smudge", R.drawable.ic_palette),
    TEXTURE(R.string.brush_studio_tab_texture, "Texture & Mask", R.drawable.ic_grid),
    PRESET(R.string.brush_studio_tab_engine, "Preset & Engine", R.drawable.ic_settings),
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
    val tipImportedToast = stringResource(R.string.brush_studio_toast_tip_imported)
    val tipImportFailedToast = stringResource(R.string.brush_studio_toast_tip_import_failed)
    val brushCreatedToast = stringResource(R.string.brush_studio_toast_created)
    val brushDeletedToast = stringResource(R.string.brush_studio_toast_deleted)
    val revertToast = stringResource(R.string.brush_studio_revert_toast)
    val preset = vm.brushPresets.firstOrNull { it.index == presetIndex }
    var selectedTab by remember { mutableStateOf(StudioTab.TIP) }
    var showMenu by remember { mutableStateOf(false) }
    var showNewBrushDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRevertConfirmDialog by remember { mutableStateOf(false) }
    var showTipPickerModal by remember { mutableStateOf(false) }
    var showMaskingTipPickerModal by remember { mutableStateOf(false) }
    var showPatternPickerModal by remember { mutableStateOf(false) }

    // 进入工作台时捕获初始参数快照
    LaunchedEffect(presetIndex) {
        vm.captureBrushStudioSnapshot()
    }

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
                Toast.makeText(context, tipImportedToast, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, tipImportFailedToast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    var allTipItems by remember { mutableStateOf<List<BrushTipItem>>(emptyList()) }
    LaunchedEffect(context) {
        allTipItems = withContext(Dispatchers.IO) { buildTipItems(context) }
    }

    var allPatternItems by remember { mutableStateOf<List<PatternItem>>(emptyList()) }
    LaunchedEffect(context) {
        allPatternItems = withContext(Dispatchers.IO) { buildPatternItems(context) }
    }

    // SAF Import Launcher for Custom Texture Pattern (.pat, .png, .jpg)
    val importPatternLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val res = vm.importCustomPattern(uri)
            if (res != null) {
                allPatternItems = buildPatternItems(context)
                Toast.makeText(context, context.getString(R.string.brush_studio_pattern_toast_imported, res), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.brush_studio_pattern_toast_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 手机端折叠状态
    var scratchpadVisible by remember { mutableStateOf(true) }
    var scratchpadExpanded by remember { mutableStateOf(false) }

    var shapeInvert by remember { mutableStateOf(false) }
    var shapeColorInvert by remember { mutableStateOf(false) }
    var shapeRgbAffectsAlpha by remember { mutableStateOf(true) }

    val pageBg = Morandi.bg
    val panelBg = Morandi.panel
    val cardBg = Morandi.panelHi
    val borderCol = Morandi.border.copy(alpha = 0.2f)
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

                // 还原修改动作
                val hasChanges = vm.hasBrushStudioChanges()
                ReIconButton(
                    R.drawable.ic_refresh,
                    stringResource(R.string.brush_studio_revert_changes),
                    onTap = {
                        if (hasChanges) {
                            showRevertConfirmDialog = true
                        }
                    },
                    tint = if (hasChanges) Morandi.accent else textSub.copy(alpha = 0.45f),
                    iconSize = 17.dp,
                )

                // 试画台折叠切换 (手机端有效)
                ReIconButton(
                    R.drawable.ic_pencil,
                    stringResource(if (scratchpadVisible) R.string.scratchpad_collapse else R.string.scratchpad_expand),
                    onTap = { scratchpadVisible = !scratchpadVisible },
                    tint = if (scratchpadVisible) Morandi.accent else textSub,
                    iconSize = 17.dp,
                )

                // 新建笔刷
                ReIconButton(R.drawable.ic_plus, stringResource(R.string.brush_studio_new_brush), { showNewBrushDialog = true }, tint = textSub, iconSize = 17.dp)

                // 复制笔刷
                ReIconButton(R.drawable.ic_copy, stringResource(R.string.brush_studio_duplicate_brush), {
                    if (vm.brushPresets.any { it.index == presetIndex }) {
                        vm.duplicateBrushPreset(presetIndex)
                    }
                }, tint = textSub, iconSize = 17.dp)

                // 导入笔刷
                ReIconButton(R.drawable.ic_export_tab, stringResource(R.string.brush_studio_import_brush), { importBrushLauncher.launch(arrayOf("*/*")) }, tint = textSub, iconSize = 17.dp)

                // 溢出菜单
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

            // ---- 参数面板卡片内容 ----
            @Composable
            fun ParameterCards(modifier: Modifier = Modifier) {
                Column(
                    modifier = modifier
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
                                StudioTab.DYNAMICS -> DynamicsTabContent(
                                    vm = vm,
                                    cardBg = cardBg,
                                    borderCol = borderCol,
                                    textMain = textMain,
                                    textSub = textSub,
                                )
                                StudioTab.COLOR -> ColorTabContent(
                                    vm = vm,
                                    cardBg = cardBg,
                                    borderCol = borderCol,
                                    textMain = textMain,
                                    textSub = textSub,
                                )
                                StudioTab.TEXTURE -> TextureTabContent(
                                    vm = vm,
                                    preset = preset,
                                    allTips = allTipItems,
                                    allPatterns = allPatternItems,
                                    onOpenPatternPicker = { showPatternPickerModal = true },
                                    onOpenMaskingTipPicker = { showMaskingTipPickerModal = true },
                                    cardBg = cardBg,
                                    borderCol = borderCol,
                                    textMain = textMain,
                                    textSub = textSub,
                                )
                                StudioTab.PRESET -> PresetTabContent(
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
                            }
                        }
                    }
                }
            }

            // 折叠状态下的快速呼出栏
            @Composable
            fun CollapsedScratchpadBar() {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg.copy(alpha = 0.5f))
                        .clickable { scratchpadVisible = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_pencil), contentDescription = null, tint = textSub, modifier = Modifier.size(13.dp))
                        Text(stringResource(R.string.brush_studio_scratchpad_toggle), color = textSub, fontSize = 11.sp)
                    }
                    Text(stringResource(R.string.scratchpad_expand), color = Morandi.accent, fontSize = 11.sp)
                }
            }

            // ---- 自适应工作区 (宽屏/平板左右分栏，窄屏/手机顶部Tab胶囊) ----
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val isWideScreen = maxWidth >= 600.dp

                if (isWideScreen) {
                    // 宽屏模式：左侧参数区（约 55% 宽度，内含 Tab 导轨 + 参数卡片）+ 右侧全高试画板（约 45% 宽度）
                    Row(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                            // 116dp 导轨
                            Column(
                                modifier = Modifier
                                    .width(116.dp)
                                    .fillMaxHeight()
                                    .background(panelBg)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                StudioTab.values().forEach { tab ->
                                    val sel = tab == selectedTab
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (sel) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent)
                                            .clickable { selectedTab = tab }
                                            .padding(vertical = 10.dp, horizontal = 10.dp),
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
                                                    modifier = Modifier.size(16.dp),
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

                            Box(modifier = Modifier.width(0.6.dp).fillMaxHeight().background(Morandi.border.copy(alpha = 0.15f)))

                            ParameterCards(modifier = Modifier.weight(1f).fillMaxHeight().background(pageBg))
                        }

                        Box(modifier = Modifier.width(0.6.dp).fillMaxHeight().background(Morandi.border.copy(alpha = 0.15f)))

                        // 右侧独立全高试画板
                        BrushStudioScratchpadPanel(
                            vm = vm,
                            modifier = Modifier
                                .weight(0.95f)
                                .fillMaxHeight()
                                .padding(8.dp),
                            isCollapsible = false,
                        )
                    }
                } else {
                    // 窄屏/手机模式：顶部水平滚动 Tab 胶囊栏 + 折叠式试画卡片 + 参数区
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(panelBg)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StudioTab.values().forEach { tab ->
                                val sel = tab == selectedTab
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Morandi.accent.copy(alpha = 0.16f) else cardBg.copy(alpha = 0.5f))
                                    .border(if (sel) 1.dp else 0.5.dp, if (sel) Morandi.accent.copy(alpha = 0.4f) else borderCol, RoundedCornerShape(8.dp))
                                        .clickable { selectedTab = tab }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            painterResource(tab.iconRes),
                                            contentDescription = null,
                                            tint = if (sel) Morandi.accent else textSub,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            stringResource(tab.titleRes),
                                            color = if (sel) Morandi.accent else textMain,
                                            fontSize = 11.sp,
                                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }

                        Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.08f)))

                        if (scratchpadVisible) {
                            val scratchpadHeight by animateDpAsState(
                                targetValue = if (scratchpadExpanded) 220.dp else 140.dp,
                                label = "phoneScratchpadH",
                            )
                            BrushStudioScratchpadPanel(
                                vm = vm,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(scratchpadHeight)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                isCollapsible = true,
                                isExpanded = scratchpadExpanded,
                                onToggleExpanded = { scratchpadExpanded = !scratchpadExpanded },
                                onClose = { scratchpadVisible = false },
                            )
                        } else {
                            CollapsedScratchpadBar()
                        }

                        Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.08f)))
                        ParameterCards(modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }

        // 浮窗笔尖选择器
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

        if (showPatternPickerModal) {
            BrushPatternPickerModal(
                allPatterns = allPatternItems,
                currentPattern = vm.brushTexturePattern,
                onSelectPattern = { pat ->
                    vm.updateBrushTexturePattern(pat)
                    showPatternPickerModal = false
                },
                onImportPattern = {
                    importPatternLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showPatternPickerModal = false },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
            )
        }

        // 对话框
        if (showNewBrushDialog) {
            StudioNewBrushDialog(
                onDismiss = { showNewBrushDialog = false },
                onCreate = { name, group ->
                    vm.createNewBrushPreset(name = name, group = group)
                    showNewBrushDialog = false
                    Toast.makeText(context, brushCreatedToast, Toast.LENGTH_SHORT).show()
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

        // 还原修改确认弹窗
        if (showRevertConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRevertConfirmDialog = false },
                title = { Text(stringResource(R.string.brush_studio_revert_changes), color = textMain, fontSize = 15.sp) },
                text = { Text(stringResource(R.string.brush_studio_revert_confirm), color = textSub, fontSize = 13.sp) },
                confirmButton = {
                    ReTextButton(
                        stringResource(R.string.common_confirm),
                        onClick = {
                            vm.revertBrushStudioSnapshot()
                            showRevertConfirmDialog = false
                            Toast.makeText(context, revertToast, Toast.LENGTH_SHORT).show()
                        },
                        textColor = Morandi.accent,
                        fontWeight = FontWeight.Bold,
                    )
                },
                dismissButton = {
                    ReTextButton(stringResource(R.string.common_cancel), { showRevertConfirmDialog = false }, textColor = textSub)
                },
                containerColor = cardBg,
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
                            Toast.makeText(context, brushDeletedToast, Toast.LENGTH_SHORT).show()
                        },
                        textColor = Morandi.error,
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
